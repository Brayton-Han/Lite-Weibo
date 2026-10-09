# -*- coding: utf-8 -*-
"""从候选池抽定额噪声语料，可按靶区的长度分布做分层匹配。

两个设计取舍：

1. **不做 LLM 语义复筛。** 关键词过滤后仍含约 17% 与共鸣类/经验类语义重叠的内容。
   接受它的理由是 Day 8 的 pooling 会把真正相关的噪声帖标成相关，不构成假阴性。
   ⚠️ 这把正确性押在「pooling 覆盖所有待测系统」上，见 CORPUS_DESIGN.md §5。

2. **按靶区长度分布分层抽样（`--match-lengths`）。** 若两层的长度分布不一致，
   「长度」就会成为与语料层相关的特征，任何偏好长文档的检索方法都会虚高。
   仅对齐上下界不够——要对齐**实际分布**。

用法：
    python sample_noise.py                                   # 随机抽 900
    python sample_noise.py --match-lengths ../../corpus/manifest.jsonl
    python sample_noise.py --audit 40                        # 打印样本供人工估算污染率
"""
from __future__ import annotations

import argparse
import json
import random
import re
import statistics as stats
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
IN = ROOT / "corpus" / "noise_candidates.jsonl"
OUT = ROOT / "corpus" / "noise.jsonl"
KEEP = re.compile(r"[一-鿿0-9a-zA-Z]+")
BUCKET = 10


def vlen(t: str) -> int:
    return len("".join(KEEP.findall(t)))


def bucket(n: int) -> int:
    return n // BUCKET * BUCKET


def describe(lens: list[int], label: str) -> None:
    lens = sorted(lens)
    print(f"  {label:<10} n={len(lens):<5} min {lens[0]:<4} p25 {lens[len(lens)//4]:<4} "
          f"p50 {lens[len(lens)//2]:<4} p75 {lens[3*len(lens)//4]:<4} max {lens[-1]}")


def stratified(pool: list[dict], target_lens: list[int], n: int,
               rng: random.Random) -> list[dict]:
    """按 target_lens 的分桶比例，从 pool 中抽 n 条。

    某个桶供给不足时，缺口按剩余桶的比例补回，并打印警告——
    供给不足意味着该长度段匹配不上，是要知道的事实，不能静默吞掉。
    """
    want = Counter()
    hist = Counter(bucket(x) for x in target_lens)
    total = sum(hist.values())
    for b, c in hist.items():
        want[b] = round(c / total * n)

    by_bucket: dict[int, list[dict]] = defaultdict(list)
    for it in pool:
        by_bucket[bucket(vlen(it["content"]))].append(it)
    for v in by_bucket.values():
        rng.shuffle(v)

    picked, shortfall = [], 0
    for b, k in sorted(want.items()):
        avail = by_bucket.get(b, [])
        take = min(k, len(avail))
        picked.extend(avail[:take])
        by_bucket[b] = avail[take:]
        if take < k:
            shortfall += k - take
            print(f"  ⚠️ {b}–{b+BUCKET-1} 字段供给不足：需 {k} 只有 {len(avail)}")

    if shortfall:
        rest = [it for v in by_bucket.values() for it in v]
        rng.shuffle(rest)
        picked.extend(rest[:shortfall])
        print(f"  ⚠️ 共 {shortfall} 条缺口由其余长度段补足，分布匹配不完全")

    rng.shuffle(picked)
    return picked[:n]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", type=int, default=900)
    ap.add_argument("--seed", type=int, default=20260918)
    ap.add_argument("--audit", type=int, default=0, help="打印 N 条供人工估算污染率")
    ap.add_argument("--match-lengths", metavar="MANIFEST", type=Path,
                    help="按该 manifest 的长度分布做分层抽样（通常是靶区 manifest.jsonl）")
    args = ap.parse_args()

    rows = [json.loads(l) for l in IN.read_text(encoding="utf-8").splitlines() if l.strip()]
    rng = random.Random(args.seed)
    print(f"候选池 {len(rows):,} 条")
    describe([vlen(r["content"]) for r in rows], "候选池")

    if args.match_lengths:
        tgt = [json.loads(l) for l in
               args.match_lengths.read_text(encoding="utf-8").splitlines() if l.strip()]
        tgt_lens = [vlen(r["content"]) for r in tgt]
        describe(tgt_lens, "靶区")
        print(f"  按靶区分布分层抽样（桶宽 {BUCKET} 字）…")
        final = stratified(rows, tgt_lens, args.target, rng)
    else:
        rng.shuffle(rows)
        final = rows[: args.target]

    for i, r in enumerate(final):
        r["gen_id"] = f"N-{i:04d}"
        r["style"] = ""
    with OUT.open("w", encoding="utf-8") as fh:
        for r in final:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    out_lens = [vlen(r["content"]) for r in final]
    describe(out_lens, "抽出")
    if args.match_lengths:
        d = abs(stats.median(out_lens) - stats.median(tgt_lens))
        print(f"  中位数差 {d:.0f} 字 {'✓' if d <= 5 else '⚠️ 偏差偏大'}")
    print(f"写入 {OUT.relative_to(ROOT)}：{len(final)} 条")

    if args.audit:
        print(f"\n=== 人工估算污染率样本（{args.audit} 条）===")
        for r in rng.sample(final, min(args.audit, len(final))):
            print(f"  [{vlen(r['content']):>3}] {r['content'][:68]}")


if __name__ == "__main__":
    main()
