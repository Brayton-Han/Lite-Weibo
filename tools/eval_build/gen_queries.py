# -*- coding: utf-8 -*-
"""评测 query 的半自动构造（RAG_ROADMAP Day 8 第 1–3 步）。

不调 API：query 由 Claude Code 会话按任务文件直接撰写，脚本只负责抽样、校验与落表。

    python gen_queries.py prepare     # 分层抽 150 条种子帖 → eval/work/query_task.md（写作要求 + 种子帖）
    #   → 在 Claude Code 里：「按 eval/work/query_task.md 写 query」，产出 eval/work/query_responses.jsonl
    python gen_queries.py import      # 校验覆盖与格式 → eval/work/query_candidates.tsv
    #   → 人工在 keep 列填 1 选中，可直接改 query 文本
    python gen_queries.py finalize    # 校验配额并编号 → eval/work/queries_draft.jsonl

种子帖只用来**启发** query，不决定相关性：相关性在 pooling 之后对整个候选池统一标注。
所以 query 必须是一个有多条帖子能回答的一般性需求，而不是只指向种子帖的「找回那一条」——
写作要求里禁止使用种子帖独有的细节（数字、人名、具体经过）。
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter, defaultdict

from common import MANIFEST, QUOTAS, ROOT, WORK, bigram_jaccard, read_jsonl, read_tsv, write_jsonl, write_tsv

TASK = WORK / "query_task.md"
SEEDS = WORK / "query_seeds.jsonl"
RESPONSES = WORK / "query_responses.jsonl"
CANDIDATES = WORK / "query_candidates.tsv"
DRAFT = WORK / "queries_draft.jsonl"
SEED = 20261014

# 每种类型抽多少条种子帖：约为配额的 2 倍，留出人工筛掉低质量的余量；合计 150（Roadmap 第 1 步）
SEED_PLAN = {"keyword": 30, "synonym": 40, "mixed": 20, "resonance": 60}
NO_ANSWER_CANDIDATES = 25

SPEC = """\
你在为一个中文社交平台（类微博）的**站内搜索评测**编写搜索 query。
评测会比较 LIKE 子串匹配、字符 trigram、向量语义检索等方案，所以 query 必须像真实用户在搜索框里敲的：
短（通常 4–20 个字）、口语、可以不完整、不加标点或只带问号。

下面列出若干条「种子帖」，每条附一个**指定类型**。为每条种子帖写恰好一条该类型的 query。

## 通用要求

- query 表达的是一个**一般性的需求**，站内应当有多条帖子都能回应它，种子帖只是其中之一。
- **禁止使用种子帖独有的细节**：具体数字、金额、人名、地名、时间、事件经过。
  ✗「中介要一个月房租砍到七折」（只能找回这一条）  ✓「中介费能砍吗」
- 不要写成问卷或标题腔（✗「关于租房中介费议价的经验分享」）。

## 各类型的要求

- **keyword**（关键词直搜）：取种子帖里最有区分度的一个词或短语（专有名词、术语、产品名、固定说法），
  **必须是种子帖原文里连续出现的子串**，2–8 个字。这类 query 是 LIKE 也能做对的对照组。
- **synonym**（同义表达）：问种子帖所回答的那个问题，但**换一套说法**——不许复用种子帖的实词，
  用同义词、上位词、口语描述或具体场景代替。这是语义检索的核心考察点。
- **mixed**（中英混杂）：像真实用户那样中英夹杂地问同一类问题，至少含一个英文词或缩写
  （如 offer、HR、PPT、k8s、iPhone、ETF），英文词不必出现在种子帖里。
- **resonance**（求共鸣）：用第一人称写出和种子帖**同样的处境与感受**，像一个正在经历这件事的人在搜「有没有人跟我一样」。
  不许复用种子帖的实词；不要写成求建议的问句（✗「怎么缓解工作倦怠」）。
  安全边界：可以表达「撑不下去」这类情绪，但不写任何具体的自伤方法或计划。
"""

NO_ANSWER_SPEC = """\
另外编写 {n} 条**无答案 query**（与种子帖无关）：站内语料里没有任何相关讨论的搜索。
它们用来考察系统能不能识别「检索不到」并拒答，而不是硬凑出看似相关的结果。

站内语料只覆盖下面这些话题，外加一些天气、饮食、追剧游戏、抽奖转发、节日祝福之类的日常碎碎念：

{clusters}

要求：
- 写的 query 要像真实用户的搜索（4–20 字、口语），话题要**明确落在上面所有话题之外**。
- 避开与上述话题有语义交叉的方向（例如「二手房贷款」与租房交叉、「Python 入门」与后端技术交叉）。
- 也避开天气、美食、明星、游戏这类日常话题——语料里的日常碎碎念可能恰好提到。
- 话题彼此不同，覆盖科学、法律、农业、手工、体育规则、历史等多个领域。
"""

OUTPUT_SPEC = """\
## 输出格式

写到 `eval/work/query_responses.jsonl`，每行一个 JSON 对象，不要其他内容：

    {{"seed": "E01-0-007", "query": "中介费能砍吗"}}          ← 种子帖的 query，seed 原样填编号
    {{"seed": null, "query": "古籍修复怎么入门"}}               ← 无答案 query，seed 为 null
    {{"seed": "E04-0-001", "type": "mixed", "query": "每天commute两小时怎么熬"}}  ← 可选：给某类补候选，用别的类型复用种子帖

每条种子帖恰好一行，外加 {n} 行无答案 query。写完运行 `python gen_queries.py import` 校验。
"""


def sample_seeds(manifest: list[dict]) -> list[tuple[str, dict]]:
    """按族分层抽种子帖：先把族打乱轮转，每个族依次出一条，避免集中在少数族。"""
    rng = random.Random(SEED)
    by_family: dict[str, dict[str, list[dict]]] = {"experience": defaultdict(list), "resonance": defaultdict(list)}
    for r in manifest:
        if r["layer"] in by_family:
            by_family[r["layer"]][f'{r["cluster"]}/{r["family"]}'].append(r)

    def draw(layer: str, n: int) -> list[dict]:
        fams = sorted(by_family[layer])
        for posts in by_family[layer].values():
            rng.shuffle(posts)
        rng.shuffle(fams)
        out, i = [], 0
        while len(out) < n:
            posts = by_family[layer][fams[i % len(fams)]]
            if posts:
                out.append(posts.pop())
            i += 1
        return out

    exp_n = SEED_PLAN["keyword"] + SEED_PLAN["synonym"] + SEED_PLAN["mixed"]
    exp = draw("experience", exp_n)
    # 中英混杂优先给本就是中英混杂文体的种子帖，写出来更自然
    exp.sort(key=lambda r: r["style"] != "中英混杂")
    types = ["mixed"] * SEED_PLAN["mixed"]
    rest = ["keyword"] * SEED_PLAN["keyword"] + ["synonym"] * SEED_PLAN["synonym"]
    rng.shuffle(rest)
    seeds = list(zip(types + rest, exp))
    seeds += [("resonance", r) for r in draw("resonance", SEED_PLAN["resonance"])]
    return seeds


def cluster_list() -> str:
    sys.path.insert(0, str(ROOT / "tools" / "corpus_gen"))
    from clusters import EXPERIENCE, RESONANCE   # noqa: E402 —— 簇定义的唯一可执行副本
    return "\n".join(f"- {name}：{'、'.join(f[0] for f in fams)}" for _, name, fams in EXPERIENCE + RESONANCE)


def cmd_prepare(args) -> None:
    seeds = sample_seeds(read_jsonl(MANIFEST))
    write_jsonl(SEEDS, [{"gen_id": r["gen_id"], "type": t, "cluster": r["cluster"], "family": r["family"],
                         "content": r["content"]} for t, r in seeds])

    parts = ["# 评测 query 撰写任务（Day 8）", "", "> 由 `tools/eval_build/gen_queries.py prepare` 生成，不要手改。", "",
             SPEC, "## 无答案 query", "", NO_ANSWER_SPEC.format(n=NO_ANSWER_CANDIDATES, clusters=cluster_list()),
             OUTPUT_SPEC.format(n=NO_ANSWER_CANDIDATES), f"## 种子帖（{len(seeds)} 条）", ""]
    for t, r in seeds:
        parts.append(f"【{r['gen_id']}】类型：{t}\n{r['content']}\n")
    TASK.parent.mkdir(parents=True, exist_ok=True)
    TASK.write_text("\n".join(parts), encoding="utf-8")

    print(f"种子帖 {len(seeds)} 条：{dict(Counter(t for t, _ in seeds))}，簇覆盖 {len({r['cluster'] for _, r in seeds})} / 24")
    print(f"写入 {TASK.relative_to(ROOT)}、{SEEDS.relative_to(ROOT)}")
    print(f"\n下一步：在 Claude Code 里让它按 {TASK.relative_to(ROOT)} 写 query → {RESPONSES.relative_to(ROOT)}，"
          "然后 python gen_queries.py import")


def cmd_import(args) -> None:
    seeds = {s["gen_id"]: s for s in read_jsonl(SEEDS)}
    if not RESPONSES.exists():
        sys.exit(f"找不到 {RESPONSES.relative_to(ROOT)}，先按 {TASK.relative_to(ROOT)} 写 query")
    rows, problems, seen, n_na = [], [], set(), 0
    for i, line in enumerate(RESPONSES.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
            seed, query = obj["seed"], obj["query"].strip()
            extra_type = obj.get("type")
        except (json.JSONDecodeError, KeyError, AttributeError) as e:
            problems.append(f"第 {i} 行解析失败：{e}")
            continue
        if not query:
            problems.append(f"第 {i} 行 query 为空")
        elif seed is None:
            n_na += 1
            rows.append(["", "no_answer", query, "", "", "", "", "", ""])
        elif seed not in seeds:
            problems.append(f"第 {i} 行种子 {seed} 不在本次抽样里")
        elif (seed, extra_type or seeds[seed]["type"]) in seen:
            problems.append(f"第 {i} 行种子 {seed} 重复")
        elif extra_type and extra_type not in QUOTAS:
            problems.append(f"第 {i} 行类型 {extra_type} 未知")
        else:
            s = seeds[seed]
            t = extra_type or s["type"]
            seen.add((seed, t))
            rows.append(["", t, query, seed, s["cluster"], s["family"],
                         f"{bigram_jaccard(query, s['content']):.2f}",
                         "Y" if query in s["content"] else "", s["content"]])
    missing = sorted(g for g, s in seeds.items() if (g, s["type"]) not in seen)
    if missing:
        problems.append(f"{len(missing)} 条种子帖没有 query：{missing[:10]}")
    if n_na < NO_ANSWER_CANDIDATES:
        problems.append(f"无答案 query 只有 {n_na} 条，要求 {NO_ANSWER_CANDIDATES}")
    if problems:
        sys.exit("校验未通过：\n  " + "\n  ".join(problems))

    rows.sort(key=lambda r: (list(QUOTAS).index(r[1]), r[3]))
    header = ["keep", "type", "query", "seed_gen_id", "cluster", "family", "overlap", "in_seed", "seed_content"]
    if CANDIDATES.exists():
        # 已有筛选表时只追加新候选：人工填的 keep 与改过的 query 文本不能被重新导入冲掉。
        # 种子帖候选按 (seed, type) 认，无答案按 query 文本认
        old = read_tsv(CANDIDATES)
        known = {(r["seed_gen_id"], r["type"]) if r["seed_gen_id"] else ("", r["query"]) for r in old}
        added = [r for r in rows if ((r[3], r[1]) if r[3] else ("", r[2])) not in known]
        rows = [[r[h] for h in header] for r in old] + added
        print(f"保留已有筛选表 {len(old)} 行（含 keep 标记），追加 {len(added)} 条新候选")
    write_tsv(CANDIDATES, header, rows)
    print(f"写入 {CANDIDATES.relative_to(ROOT)}：{len(rows)} 条候选 {dict(Counter(r[1] for r in rows))}")
    print(f"""
下一步（人工）：在 keep 列填 1 选出 {sum(QUOTAS.values())} 条，配额 {QUOTAS}。筛选时看：
  - overlap 是 query 与种子帖的字符 bigram Jaccard。synonym / resonance 应当很低（≲0.05），偏高说明在复述原文
  - in_seed：keyword 必须为 Y（LIKE 能命中的前提）
  - 只能找回种子帖那一条的 query 不要（带了独有细节）
  - 簇尽量分散；可以直接改 query 文本，改完的 query 仍按原类型计
然后：python gen_queries.py finalize""")


def cmd_finalize(args) -> None:
    rows = [r for r in read_tsv(CANDIDATES) if r["keep"].strip() == "1"]
    counts = Counter(r["type"] for r in rows)
    problems = [f"{t}: 选了 {counts.get(t, 0)} 条，配额 {n}" for t, n in QUOTAS.items() if counts.get(t, 0) != n]
    dup = [q for q, c in Counter(r["query"].strip() for r in rows).items() if c > 1]
    if dup:
        problems.append(f"重复 query：{dup}")
    for r in rows:
        if r["type"] == "keyword" and r["query"].strip() not in r["seed_content"]:
            problems.append(f"keyword「{r['query']}」不是种子帖的子串，LIKE 命中不了它的种子")
    if problems and not args.force:
        sys.exit("配额 / 校验未通过（确认无误可加 --force）：\n  " + "\n  ".join(problems))

    out, n = [], 0
    for t in QUOTAS:
        for r in sorted((r for r in rows if r["type"] == t), key=lambda r: r["seed_gen_id"]):
            n += 1
            out.append({"qid": f"q{n:03d}", "query": r["query"].strip(), "type": t,
                        "seed_gen_id": r["seed_gen_id"] or None,
                        "cluster": r["cluster"] or None, "family": r["family"] or None})
    write_jsonl(DRAFT, out)
    clusters = Counter(r["cluster"] for r in out if r["cluster"])
    print(f"写入 {DRAFT.relative_to(ROOT)}：{len(out)} 条 {dict(Counter(r['type'] for r in out))}")
    print(f"簇覆盖 {len(clusters)} / 24：{dict(sorted(clusters.items()))}")
    print("""
下一步：构造候选池（需要 PostgreSQL 与 VOYAGE_API_KEY）
  cd backend && EVAL_POOL=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools""")


def main():
    ap = argparse.ArgumentParser(description="评测 query 构造")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("prepare", help="抽种子帖，导出撰写任务文件")
    sub.add_parser("import", help="校验 Claude Code 写好的 query，生成人工筛选表")
    f = sub.add_parser("finalize", help="读人工筛选结果，校验配额并编号")
    f.add_argument("--force", action="store_true", help="校验不过也写出（自行确认后使用）")
    args = ap.parse_args()
    {"prepare": cmd_prepare, "import": cmd_import, "finalize": cmd_finalize}[args.cmd](args)


if __name__ == "__main__":
    main()
