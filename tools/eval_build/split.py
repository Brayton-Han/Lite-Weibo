# -*- coding: utf-8 -*-
"""合并标注并切分 train / test（RAG_ROADMAP Day 8 最后一步）。

    python split.py

产出（只含 query 与 post_id，不含帖子正文，可以进 git）：
- eval/train/queries.jsonl   40 条，调参只用它
- eval/test/queries.jsonl    40 条，最终报数用，**中途绝不看**
- eval/qrels.tsv             全部判定（含 0 分），Day 9 统计「结果里有多少条没被标过」要用。
                             带 gen_id：post_id 由导入时的数据库分配，重建库后靠它重新映射

最终分数：人工标过的以人工为准，其余用 Claude 的。
同一条种子帖派生的多条 query 总是切到同一侧（见 keep_seed_together）。
"""
from __future__ import annotations

import random
import sys
from collections import Counter, defaultdict

from common import EVAL, QUOTAS, ROOT, TYPE_LAYER, WORK, read_jsonl, read_tsv, write_jsonl, write_tsv
from label import CLAUDE_LABELS, POOL, SHEET

DRAFT = WORK / "queries_draft.jsonl"
SEED = 20261014


def keep_seed_together(split: dict[str, list[dict]], seed_of: dict[str, str | None]) -> None:
    """同一条种子帖派生的多条 query（不同类型）必须落在同一侧。

    它们问的往往是同一件事（「欧洲旅游签要准备哪些东西」与「申根visa材料清单」），
    一条进 train、一条进 test，调参时就等于提前见过测试题。
    做法：以 qid 最小的那条所在一侧为准，另一侧的同伴与本侧一条同类型、无同伴的 query 对调——
    各类型在两侧的条数不变。
    """
    groups = defaultdict(list)
    for q in (q for rows in split.values() for q in rows):
        if seed_of.get(q["qid"]):
            groups[seed_of[q["qid"]]].append(q["qid"])
    grouped = {qid for qids in groups.values() if len(qids) > 1 for qid in qids}
    side = {q["qid"]: name for name, rows in split.items() for q in rows}

    for seed, qids in sorted(groups.items()):
        if len(qids) < 2:
            continue
        anchor = side[min(qids)]
        other = "test" if anchor == "train" else "train"
        for qid in qids:
            if side[qid] == anchor:
                continue
            mover = next(q for q in split[other] if q["qid"] == qid)
            swap = next((q for q in split[anchor]
                         if q["type"] == mover["type"] and q["qid"] not in grouped), None)
            if swap is None:
                sys.exit(f"种子帖 {seed} 的 query 无法放到同一侧：{anchor} 里找不到可对调的 {mover['type']} query")
            split[other].remove(mover)
            split[anchor].remove(swap)
            split[anchor].append(mover)
            split[other].append(swap)
            side[qid], side[swap["qid"]] = anchor, other
        print(f"  种子帖 {seed} 的 {len(qids)} 条 query 同在 {anchor}：{sorted(qids)}")


def main():
    queries = read_jsonl(DRAFT)
    pool = {r["qid"]: r for r in read_jsonl(POOL)}
    grades: dict[tuple[str, int], tuple[int, str]] = {
        (l["qid"], int(l["post_id"])): (l["grade"], "claude") for l in read_jsonl(CLAUDE_LABELS)}
    if SHEET.exists():
        for r in read_tsv(SHEET):
            if r["grade"].strip():
                grades[(r["qid"], int(r["post_id"]))] = (int(r["grade"]), "human")

    problems, relevant_layers = [], Counter()
    qrels, out = [], []
    for q in queries:
        if q["qid"] not in pool:
            problems.append(f"{q['qid']} 不在候选池里")
            continue
        rel = {}
        for c in pool[q["qid"]]["candidates"]:
            g = grades.get((q["qid"], c["post_id"]))
            if g is None:
                problems.append(f"{q['qid']} / {c['post_id']} 没有标注")
                continue
            qrels.append([q["qid"], c["post_id"], c["gen_id"], g[0], g[1]])
            if g[0] > 0:
                rel[str(c["post_id"])] = g[0]
                relevant_layers[c["layer"]] += 1
        if q["type"] == "no_answer" and rel:
            problems.append(f"{q['qid']}「{q['query']}」是无答案 query，却有 {len(rel)} 条相关帖——换掉它，或改判类型")
        if q["type"] != "no_answer" and not rel:
            problems.append(f"{q['qid']}「{q['query']}」没有任何相关帖")
        out.append({"qid": q["qid"], "query": q["query"], "type": q["type"],
                    "layer": TYPE_LAYER[q["type"]], "relevant": dict(sorted(rel.items(), key=lambda kv: int(kv[0])))})
    if problems:
        sys.exit("校验未通过：\n  " + "\n  ".join(problems))

    # 按类型分层切分。奇数配额的多出来那条轮流给 train / test，保证两边各 40
    rng = random.Random(SEED)
    split, extra_to_train = defaultdict(list), True
    for t in QUOTAS:
        group = [q for q in out if q["type"] == t]
        rng.shuffle(group)
        half = len(group) // 2
        if len(group) % 2:
            half += extra_to_train
            extra_to_train = not extra_to_train
        split["train"] += group[:half]
        split["test"] += group[half:]
    keep_seed_together(split, {q["qid"]: q["seed_gen_id"] for q in queries})
    for name, rows in split.items():
        write_jsonl(EVAL / name / "queries.jsonl", sorted(rows, key=lambda q: q["qid"]))
    write_tsv(EVAL / "qrels.tsv", ["qid", "post_id", "gen_id", "grade", "annotator"], qrels)

    src = Counter(a for *_, a in qrels)
    print(f"query {len(out)} 条：train {len(split['train'])} / test {len(split['test'])}")
    for name, rows in split.items():
        print(f"  {name}: {dict(Counter(q['type'] for q in rows))}")
    print(f"判定 {len(qrels)} 个（人工 {src['human']} / Claude {src['claude']}），"
          f"分布 {dict(sorted(Counter(g for *_, g, _ in qrels).items()))}")
    print(f"相关帖按语料层：{dict(relevant_layers)}  ← noise 非 0 说明 pooling 接住了噪声层里的语义重叠帖")
    rel_n = [len(q["relevant"]) for q in out if q["type"] != "no_answer"]
    print(f"每条 query 相关帖数：min {min(rel_n)} / 中位 {sorted(rel_n)[len(rel_n) // 2]} / max {max(rel_n)}")
    print(f"写入 eval/train/、eval/test/、eval/qrels.tsv（相对 {ROOT}）")


if __name__ == "__main__":
    main()
