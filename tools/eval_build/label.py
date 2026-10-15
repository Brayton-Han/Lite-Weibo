# -*- coding: utf-8 -*-
"""候选池相关性标注（RAG_ROADMAP Day 8 第 5 步）。

    python label.py export              # 每条 query 导出一个盲标任务 → eval/work/label_tasks/
    #   → Claude Code 子代理按 eval/LABELING_GUIDE.md 标注，结果写 eval/work/label_out/{qid}.jsonl
    python label.py import              # 校验完整性 → eval/work/labels_claude.jsonl
    python label.py sheet               # 导出人工标注表 eval/work/human_sheet.tsv

    python label.py claude              # 备选：走 API 标注（需要 ANTHROPIC_API_KEY），可断点续跑

两个标注者必须**独立**：
- Claude 看不到来源、排名、种子帖与族——只看 query 和帖子正文，顺序打乱
- 人工表同样去掉这些信息，也不含 Claude 的分数。一致率与 kappa 只有在双盲下才有意义
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter

import anthropic

from common import (DEFAULT_MODEL, GUIDE, QUOTAS, ROOT, WORK, call_json, estimate_cost,
                    read_jsonl, run_concurrent, write_jsonl, write_tsv)

POOL = WORK / "pool.jsonl"
CLAUDE_LABELS = WORK / "labels_claude.jsonl"
SHEET = WORK / "human_sheet.tsv"
TASKS = WORK / "label_tasks"
OUTS = WORK / "label_out"
SEED = 20261014

SYSTEM_TAIL = """

---

你是这份指南的标注者。每次收到一条 query 和它的全部候选帖子，逐条按指南打分。
每条候选都必须给出结果，id 原样填写。各条之间独立判断：不要因为前面给了很多 2 分就压低后面的分。"""

SCHEMA = {
    "type": "object",
    "properties": {
        "labels": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "grade": {"type": "integer", "enum": [0, 1, 2]},
                    "reason": {"type": "string"},
                },
                "required": ["id", "grade", "reason"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["labels"],
    "additionalProperties": False,
}


def shuffled(cands: list[dict], qid: str) -> list[dict]:
    """按 qid 定种子打乱：重跑顺序一致，且池子里的来源顺序（like 在前、family 在后）不会泄露给标注者。"""
    out = list(cands)
    random.Random(f"{SEED}-{qid}").shuffle(out)
    return out


def build_user(row: dict) -> tuple[str, dict[str, int]]:
    cands = shuffled(row["candidates"], row["qid"])
    ids = {f"c{i:03d}": c["post_id"] for i, c in enumerate(cands, 1)}
    lines = [f"query：{row['query']}", f"类型：{row['type']}", f"候选 {len(cands)} 条：", ""]
    for cid, c in zip(ids, cands):
        lines.append(f"[{cid}] {c['content']}")
    return "\n".join(lines), ids


def label_one(client, model: str, system: str, row: dict) -> list[dict]:
    user, ids = build_user(row)
    res = call_json(client, model, system, user, SCHEMA, max_tokens=32000)
    got = {l["id"]: l for l in res["labels"] if l["id"] in ids}
    missing = set(ids) - set(got)
    if missing:
        raise ValueError(f"漏标 {len(missing)} 条：{sorted(missing)[:5]}")
    return [{"qid": row["qid"], "post_id": ids[cid], "grade": l["grade"], "reason": l["reason"]}
            for cid, l in sorted(got.items())]


def cmd_claude(args) -> None:
    pool = read_jsonl(POOL)
    system = GUIDE.read_text(encoding="utf-8") + SYSTEM_TAIL
    done = read_jsonl(CLAUDE_LABELS) if CLAUDE_LABELS.exists() else []
    done_q = {l["qid"] for l in done}
    todo = [r for r in pool if r["qid"] not in done_q]

    pairs = sum(len(r["candidates"]) for r in todo)
    print(f"候选池 {len(pool)} 条 query，已标 {len(done_q)}，本次 {len(todo)} 条 / {pairs} 个 (query, 帖子) 对")
    chars = sum(len(system) + len(build_user(r)[0]) for r in todo)
    print(f"成本估算（{args.model}）：{estimate_cost(args.model, chars, pairs * 40 + len(todo) * 3000)}")
    if args.dry_run:
        if todo:
            print("\n--- dry-run：首个请求的 user prompt（前 1500 字）---")
            print(build_user(todo[0])[0][:1500])
        return
    if not todo:
        print("全部已标完。要重标先删掉 " + str(CLAUDE_LABELS.relative_to(ROOT)))
        return

    client = anthropic.Anthropic()
    jobs = {r["qid"]: (lambda r=r: label_one(client, args.model, system, r)) for r in todo}
    results, failures = run_concurrent(jobs, args.workers)

    rows = done + [l for qid in sorted(results) for l in results[qid]]
    write_jsonl(CLAUDE_LABELS, rows)
    grades = Counter(l["grade"] for l in rows)
    print(f"\n写入 {CLAUDE_LABELS.relative_to(ROOT)}：{len({l['qid'] for l in rows})} 条 query，"
          f"{len(rows)} 个判定，分布 {dict(sorted(grades.items()))}")
    if failures:
        print(f"⚠️ {len(failures)} 条 query 失败（重跑本命令只会补这些）：")
        for k, why in failures:
            print(f"   {k}: {why}")
        sys.exit(1)


TASK_TAIL = """
---

逐条按 `eval/LABELING_GUIDE.md` 打分，结果写到 `eval/work/label_out/{qid}.jsonl`，每条候选一行，不要其他内容：

    {{"id": "c001", "grade": 0, "reason": "不超过 30 字的判断依据"}}

每条候选都必须有结果，id 原样填写。各条之间独立判断，不要因为前面给了很多 2 分就压低后面的分。
"""


def cmd_export(args) -> None:
    """导出的任务文件与 API 路径的 user prompt 逐字相同：两条路径给标注者看到的信息一致。"""
    pool = read_jsonl(POOL)
    TASKS.mkdir(parents=True, exist_ok=True)
    OUTS.mkdir(parents=True, exist_ok=True)
    for r in pool:
        user, _ = build_user(r)
        (TASKS / f"{r['qid']}.md").write_text(user + "\n" + TASK_TAIL.format(qid=r["qid"]), encoding="utf-8")
    pairs = sum(len(r["candidates"]) for r in pool)
    print(f"写入 {TASKS.relative_to(ROOT)}/：{len(pool)} 个任务，{pairs} 个 (query, 帖子) 对")
    print(f"下一步：让 Claude Code 子代理按任务文件标注 → {OUTS.relative_to(ROOT)}/，然后 python label.py import")


def cmd_import(args) -> None:
    pool = read_jsonl(POOL)
    rows, problems, pending = [], [], []
    for r in pool:
        out = OUTS / f"{r['qid']}.jsonl"
        if not out.exists():
            pending.append(r["qid"])
            continue
        _, ids = build_user(r)
        got = {}
        for i, line in enumerate(out.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                l = json.loads(line)
                cid, grade = l["id"], l["grade"]
            except (json.JSONDecodeError, KeyError, TypeError) as e:
                problems.append(f"{r['qid']} 第 {i} 行解析失败：{e}")
                continue
            if cid not in ids:
                problems.append(f"{r['qid']} 第 {i} 行 id {cid} 不在任务里")
            elif grade not in (0, 1, 2):
                problems.append(f"{r['qid']} {cid} 的 grade={grade!r} 不是 0/1/2")
            elif cid in got:
                problems.append(f"{r['qid']} {cid} 重复")
            else:
                got[cid] = l
        missing = set(ids) - set(got)
        if missing:
            problems.append(f"{r['qid']} 漏标 {len(missing)} 条：{sorted(missing)[:5]}")
        rows += [{"qid": r["qid"], "post_id": ids[cid], "grade": l["grade"], "reason": l.get("reason", "")}
                 for cid, l in sorted(got.items())]
    if pending:
        print(f"⏳ {len(pending)} 条 query 还没有结果：{pending}")
    if problems:
        sys.exit("校验未通过（改对应的 label_out 文件或重标该 query）：\n  " + "\n  ".join(problems))
    if pending:
        sys.exit(1)
    write_jsonl(CLAUDE_LABELS, rows)
    grades = Counter(l["grade"] for l in rows)
    print(f"写入 {CLAUDE_LABELS.relative_to(ROOT)}：{len(pool)} 条 query，{len(rows)} 个判定，"
          f"分布 {dict(sorted(grades.items()))}")
    print("下一步：人工标完 human_sheet.tsv 后 python agreement.py（人工表已导出过就不要重导，会覆盖你的标注）")


def pick_full(pool: list[dict], n: int) -> list[str]:
    """按配额比例在各类型里抽「整池亲手标」的 query；共鸣类至少 6 条，保证 kappa 有足够样本。"""
    rng = random.Random(SEED)
    total = sum(QUOTAS.values())
    want = {t: max(1, round(n * q / total)) for t, q in QUOTAS.items()}
    want["resonance"] = max(want["resonance"], 6)
    # 四舍五入后总数可能偏离 n：多了按下面的顺序轮流扣，少了补给配额最大的类型。
    # 先扣 no_answer（候选几乎全是 0 分，人机很容易一致，会把一致率抬虚），再扣 mixed，
    # 最后才动经验类的主力 keyword / synonym
    trim = ["no_answer", "mixed", "keyword", "synonym"]
    i = 0
    while sum(want.values()) > n and any(want[t] > 0 for t in trim):
        t = trim[i % len(trim)]
        if want[t] > 0:
            want[t] -= 1
        i += 1
    while sum(want.values()) < n:
        t = max(want, key=lambda t: QUOTAS[t] / want[t])
        want[t] += 1
    out = []
    for t, k in want.items():
        qids = sorted(r["qid"] for r in pool if r["type"] == t)
        out += rng.sample(qids, min(k, len(qids)))
    return out


def cmd_sheet(args) -> None:
    pool = read_jsonl(POOL)
    if SHEET.exists() and not args.overwrite:
        sys.exit(f"{SHEET.relative_to(ROOT)} 已存在，可能已有你的标注。确认要覆盖加 --overwrite")
    full = set(pick_full(pool, args.full))
    rng = random.Random(SEED + 1)

    rows, n_full, n_spot = [], 0, 0
    order = list(pool)
    rng.shuffle(order)
    for r in order:
        cands = shuffled(r["candidates"], r["qid"])
        if r["qid"] in full:
            # --per-query：只从池子里均匀抽 K 对。候选已按 qid 打乱，取前 K 个即是无偏的均匀样本
            picked = cands[:args.per_query] if args.per_query else cands
            n_full += len(picked)
        else:
            picked = [c for c in cands if rng.random() < args.spot]
            n_spot += len(picked)
        for c in picked:
            rows.append([r["qid"], r["type"], r["query"], c["post_id"], c["content"], "", ""])

    write_tsv(SHEET, ["qid", "type", "query", "post_id", "content", "grade", "note"], rows)
    by_type = Counter(r["type"] for r in pool if r["qid"] in full)
    print(f"写入 {SHEET.relative_to(ROOT)}：{len(rows)} 行")
    scope = f"每条抽 {args.per_query} 对" if args.per_query else "整池"
    print(f"  {scope}亲手标 {len(full)} 条 query（{dict(by_type)}）= {n_full} 对")
    print(f"  其余 query 按 {args.spot:.0%} 抽检 = {n_spot} 对")
    print(f"""
下一步（人工）：先读 {GUIDE.relative_to(ROOT)}，再在 grade 列填 0 / 1 / 2。
  - 不要先看 Claude 的标注：这张表是用来测一致率的，看过就不独立了
  - 拿不准的写在 note 列，不要留空 grade（留空的行不计入一致率）
然后：python agreement.py""")


def main():
    ap = argparse.ArgumentParser(description="候选池相关性标注")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("export", help="导出盲标任务文件，交给 Claude Code 子代理")
    sub.add_parser("import", help="校验子代理的标注结果并汇总")
    c = sub.add_parser("claude", help="备选：走 API 标注全部候选池")
    c.add_argument("--model", default=DEFAULT_MODEL)
    c.add_argument("--workers", type=int, default=4)
    c.add_argument("--dry-run", action="store_true")
    s = sub.add_parser("sheet", help="导出人工标注表")
    s.add_argument("--full", type=int, default=20, help="整池亲手标的 query 数（Roadmap：至少 20）")
    s.add_argument("--spot", type=float, default=0.3, help="其余 query 的抽检比例（Roadmap：30%%）")
    s.add_argument("--per-query", type=int, default=0,
                   help="--full 选中的 query 每条只均匀抽这么多对，而不是整池（0 = 整池）")
    s.add_argument("--overwrite", action="store_true")
    args = ap.parse_args()
    {"export": cmd_export, "import": cmd_import, "claude": cmd_claude, "sheet": cmd_sheet}[args.cmd](args)


if __name__ == "__main__":
    main()
