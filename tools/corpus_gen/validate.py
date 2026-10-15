# -*- coding: utf-8 -*-
"""靶区语料校验（docs/CORPUS_DESIGN.md §2 + Day 3 验收标准）。

核心是**字符 bigram 的 Jaccard 重叠度**。为什么用字符 bigram 而不是 jieba 分词：
本项目的词面基线是 `LIKE '%q%'` 和 tsvector，对中文而言它们匹配的就是字符子串，
字符 n-gram 比分词更忠实于「词面检索到底能看到什么」。

用法：
    python validate.py                 # 全量报告
    python validate.py --cluster R01   # 只看一个簇
    python validate.py --show-pairs    # 打印零重叠样本对，人工抽检用
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from itertools import combinations
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "corpus" / "manifest.jsonl"

MIN_LEN, MAX_LEN = 30, 200   # 见 docs/CORPUS_DESIGN.md §1「长度口径」
ZERO_OVERLAP = 0.05     # Jaccard 低于此视为「词面零重叠」
NEAR_DUPE = 0.50        # 高于此视为近重复，必须重生成
MIN_ZERO_PAIRS = 3      # 每簇至少要有这么多组零重叠对（Day 3 验收标准）

_KEEP = re.compile(r"[一-鿿0-9a-zA-Z]+")


def bigrams(text: str) -> set[str]:
    """归一化后取字符 bigram。剥掉标点、emoji、空白——它们不是检索信号。"""
    s = "".join(_KEEP.findall(text.lower()))
    return {s[i:i + 2] for i in range(len(s) - 1)} if len(s) > 1 else set()


def jaccard(a: set[str], b: set[str]) -> float:
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def visible_len(text: str) -> int:
    """按可见字符计长（emoji/标点不计入长度门槛）。"""
    return len("".join(_KEEP.findall(text)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cluster", help="只校验指定簇")
    ap.add_argument("--show-pairs", action="store_true", help="打印零重叠样本对")
    ap.add_argument("--manifest", type=Path, default=MANIFEST, help="manifest 路径（测试用）")
    args = ap.parse_args()

    if not args.manifest.exists():
        sys.exit(f"找不到 {args.manifest}，先跑 generate.py")

    rows = [json.loads(l) for l in args.manifest.read_text(encoding="utf-8").splitlines() if l.strip()]
    if args.cluster:
        rows = [r for r in rows if r["cluster"] == args.cluster]
    if not rows:
        sys.exit("没有数据")

    by_family = defaultdict(list)
    for r in rows:
        by_family[(r["cluster"], r["family"])].append(r)
    for r in rows:
        r["_bg"] = bigrams(r["content"])

    n_clusters = len({r["cluster"] for r in rows})
    print("=== 总览 ===")
    print(f"条数 {len(rows)} | 簇 {n_clusters} | 族 {len(by_family)}")

    # ---- 长度 ----
    bad_len = [r for r in rows if not (MIN_LEN <= visible_len(r["content"]) <= MAX_LEN)]
    lens = sorted(visible_len(r["content"]) for r in rows)
    print(f"\n=== 长度（要求 {MIN_LEN}–{MAX_LEN}）===")
    print(f"min {lens[0]} | p50 {lens[len(lens)//2]} | max {lens[-1]} | 越界 {len(bad_len)}")
    for r in bad_len[:8]:
        print(f"  ✗ {r['gen_id']} 长度 {visible_len(r['content'])}: {r['content'][:40]}…")

    # ---- 族内重叠 ----
    print(f"\n=== 族内词面重叠（Jaccard on 字符 bigram）===")
    cluster_zero = defaultdict(int)
    dupes, family_rows = [], []
    for (cid, fam), items in sorted(by_family.items()):
        zero = worst = 0
        worst_val = 0.0
        for a, b in combinations(items, 2):
            j = jaccard(a["_bg"], b["_bg"])
            if j < ZERO_OVERLAP:
                zero += 1
            if j > worst_val:
                worst_val, worst = j, (a, b)
            if j > NEAR_DUPE:
                dupes.append((j, a, b))
        cluster_zero[cid] += zero
        total_pairs = len(items) * (len(items) - 1) // 2
        family_rows.append((cid, fam, len(items), zero, total_pairs, worst_val))

    for cid, fam, n, zero, total, worst_val in family_rows:
        ratio = zero / total if total else 0
        mark = "✓" if ratio >= 0.5 else ("~" if ratio >= 0.2 else "✗")
        print(f"  {mark} {cid} {fam[:14]:<16} n={n:<3} 零重叠对 {zero}/{total} "
              f"({ratio:.0%})  最高相似 {worst_val:.2f}")

    # ---- 簇级验收 ----
    print(f"\n=== 簇级验收（每簇需 ≥{MIN_ZERO_PAIRS} 组零重叠对）===")
    failed = [c for c, n in sorted(cluster_zero.items()) if n < MIN_ZERO_PAIRS]
    for c, n in sorted(cluster_zero.items()):
        print(f"  {'✓' if n >= MIN_ZERO_PAIRS else '✗'} {c}: {n} 组")

    # ---- 近重复 ----
    print(f"\n=== 近重复（Jaccard > {NEAR_DUPE}，需重生成）===")
    if not dupes:
        print("  无")
    for j, a, b in sorted(dupes, reverse=True, key=lambda x: x[0])[:10]:
        print(f"  ✗ {j:.2f}  {a['gen_id']} ←→ {b['gen_id']}")
        print(f"      {a['content'][:48]}…")
        print(f"      {b['content'][:48]}…")

    # ---- 抽检样本 ----
    if args.show_pairs:
        print(f"\n=== 零重叠样本对抽检 ===")
        for (cid, fam), items in sorted(by_family.items()):
            pair = next(((a, b) for a, b in combinations(items, 2)
                         if jaccard(a["_bg"], b["_bg"]) < ZERO_OVERLAP), None)
            if pair:
                print(f"\n  [{cid} · {fam}]")
                print(f"    A: {pair[0]['content']}")
                print(f"    B: {pair[1]['content']}")

    # ---- 结论 ----
    print(f"\n=== 结论 ===")
    problems = []
    if bad_len:
        problems.append(f"{len(bad_len)} 条长度越界")
    if failed:
        problems.append(f"{len(failed)} 个簇零重叠对不足: {', '.join(failed)}")
    if dupes:
        problems.append(f"{len(dupes)} 对近重复")
    if problems:
        print("✗ " + "；".join(problems))
        print("  重生成建议：python generate.py submit --only " +
              ",".join(sorted({*failed, *(a['cluster'] for _, a, _ in dupes)})[:10]))
        sys.exit(1)
    print("✓ 全部通过，可进入 Step 4 导入")


if __name__ == "__main__":
    main()
