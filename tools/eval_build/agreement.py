# -*- coding: utf-8 -*-
"""人机标注一致性（RAG_ROADMAP Day 8 验收）。

    python agreement.py        # 读人工表与 Claude 标注，输出一致率与 Cohen's kappa → eval/AGREEMENT.md

验收线：
- 总体一致率 ≥ 80%（三级分数完全相同才算一致）
- 共鸣类 Cohen's kappa ≥ 0.6——低于它就回去改 eval/LABELING_GUIDE.md 再标一轮

为什么一致率之外还要 kappa：大多数候选是 0 分，两个标注者全标 0 也能拿到很高的一致率。
kappa 扣掉了「按各自的分数分布瞎猜也能对上」的那部分，才反映指南本身是否清晰。
"""
from __future__ import annotations

import sys
from collections import Counter

from common import EVAL, QUOTAS, ROOT, read_jsonl, read_tsv
from label import CLAUDE_LABELS, SHEET

OUT = EVAL / "AGREEMENT.md"
GRADES = (0, 1, 2)
MIN_RESONANCE_PAIRS = 30
AGREEMENT_BAR, KAPPA_BAR = 0.80, 0.60


def cohen_kappa(pairs: list[tuple[int, int]], weighted: bool = False) -> float:
    """Cohen's kappa。weighted=True 为线性加权：差 1 级算半个不一致，差 2 级算一个。"""
    n = len(pairs)
    a, b = Counter(x for x, _ in pairs), Counter(y for _, y in pairs)

    def disagree(x: int, y: int) -> float:
        return abs(x - y) / (len(GRADES) - 1) if weighted else float(x != y)

    observed = sum(disagree(x, y) for x, y in pairs) / n
    expected = sum(a[x] * b[y] * disagree(x, y) for x in GRADES for y in GRADES) / (n * n)
    if expected == 0:
        return float("nan")      # 两边都只用了同一个分数：kappa 无定义，不硬报 1.0
    return 1 - observed / expected


def summarize(pairs: list[tuple[int, int]]) -> dict:
    n = len(pairs)
    return {
        "n": n,
        "exact": sum(x == y for x, y in pairs) / n,
        "binary": sum((x > 0) == (y > 0) for x, y in pairs) / n,
        "kappa": cohen_kappa(pairs),
        "wkappa": cohen_kappa(pairs, weighted=True),
    }


def main():
    human = [r for r in read_tsv(SHEET) if r["grade"].strip()]
    bad = [r for r in human if r["grade"].strip() not in {"0", "1", "2"}]
    if bad:
        sys.exit(f"人工表有 {len(bad)} 行 grade 不是 0/1/2，例如 qid={bad[0]['qid']} post_id={bad[0]['post_id']}")
    claude = {(l["qid"], int(l["post_id"])): l["grade"] for l in read_jsonl(CLAUDE_LABELS)}

    by_type: dict[str, list[tuple[int, int]]] = {t: [] for t in QUOTAS}
    unmatched = 0
    for r in human:
        key = (r["qid"], int(r["post_id"]))
        if key not in claude:
            unmatched += 1
            continue
        by_type[r["type"]].append((int(r["grade"]), claude[key]))
    if unmatched:
        print(f"⚠️ {unmatched} 行人工标注在 Claude 标注里找不到对应（候选池重建过？）")
    allp = [p for ps in by_type.values() for p in ps]
    if not allp:
        sys.exit("没有可比对的标注：人工表的 grade 列还是空的？")

    lines = ["# 人机标注一致性（Day 8）", "",
             "> 由 `tools/eval_build/agreement.py` 生成。人工与 Claude 双盲独立标注，"
             "标注指南见 `eval/LABELING_GUIDE.md`。", "",
             "| 类型 | 对数 | 完全一致率 | 二值一致率（相关/不相关） | Cohen's κ | 线性加权 κ |",
             "|---|---|---|---|---|---|"]
    for name, ps in [*by_type.items(), ("**合计**", allp)]:
        if not ps:
            continue
        s = summarize(ps)
        lines.append(f"| {name} | {s['n']} | {s['exact']:.1%} | {s['binary']:.1%} | {s['kappa']:.3f} | {s['wkappa']:.3f} |")

    # 混淆矩阵：行 = 人工，列 = Claude；看分歧集中在哪一档（通常是 1 分的边界）
    cm = Counter(allp)
    lines += ["", "混淆矩阵（行 = 人工，列 = Claude）：", "", "| 人工 \\ Claude | 0 | 1 | 2 |", "|---|---|---|---|"]
    lines += [f"| {x} | " + " | ".join(str(cm[(x, y)]) for y in GRADES) + " |" for x in GRADES]

    total, res = summarize(allp), by_type["resonance"]
    checks = [(f"总体完全一致率 ≥ {AGREEMENT_BAR:.0%}", total["exact"] >= AGREEMENT_BAR, f"{total['exact']:.1%}")]
    if len(res) < MIN_RESONANCE_PAIRS:
        checks.append((f"共鸣类 κ ≥ {KAPPA_BAR}", False, f"样本只有 {len(res)} 对，不足 {MIN_RESONANCE_PAIRS}"))
    else:
        k = cohen_kappa(res)
        checks.append((f"共鸣类 κ ≥ {KAPPA_BAR}", k >= KAPPA_BAR, f"{k:.3f}（{len(res)} 对）"))
    lines += ["", "## 验收", ""] + [f"- {'✅' if ok else '❌'} {name}：{val}" for name, ok, val in checks]

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n写入 {OUT.relative_to(ROOT)}")
    if not all(ok for _, ok, _ in checks):
        print("\n未达标：先看混淆矩阵与 note 列找分歧集中的情形，改 LABELING_GUIDE.md，"
              "删掉 labels_claude.jsonl 后重标（人工也要按新指南重看分歧行）")
        sys.exit(1)
    print("\n下一步：python split.py")


if __name__ == "__main__":
    main()
