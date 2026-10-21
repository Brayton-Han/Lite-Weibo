# -*- coding: utf-8 -*-
"""生成层 judge 校准（RAG_ROADMAP Day 15 第三条）。不调用任何 API。

    python gen_calib.py sample       # 从全量校准表抽样 → eval/work/gen_calib_sample_train.tsv，交给人工标
    python gen_calib.py agreement    # 人工标完后：人工 vs judge 一致率 → eval/GEN_AGREEMENT.md
    python gen_calib.py sample-cit2       # 改了引用判定后：从第一轮没抽到的引用里另抽 30 条 → gen_calib_sample_cit2_train.tsv
    python gen_calib.py agreement cit2    # 新样本的一致率 → eval/GEN_AGREEMENT_CIT2.md

全量校准表与 judge 判定由 GenerationEvalRunner 生成（GEN_EVAL=answers / judge），都在 eval/work/。
抽样表不含 judge 的判定：人工必须盲标，看过 judge 结论再标，一致率就是虚高的。

抽样：20 条回答的有用性全标；引用、句子各随机抽 30 条（固定种子，重跑抽到的一样）。
标签：citation 填 1（支持）/ 0（不支持）；sentence 填 S / U / N（SUPPORTED / UNSUPPORTED / NO_CLAIM）；
usefulness 填 1–5。口径见 docs/EVAL.md「Day 15 · 生成层指标定义」。

验收线（定在看到任何人工标签之前）：
- 引用：二值一致率 ≥ 80%
- 句子：「是不是幻觉」（U vs 非 U）二值一致率 ≥ 80%，这是幻觉率真正用到的划分；三分类一致率与 κ 一并报
- 有用性：相差 ≤ 1 分的比例 ≥ 80%。5 档打分完全一致过于严苛，人与人之间也很少做到；完全一致率、加权 κ、
  均分偏差（judge − 人工）一并报——judge 系统性偏高时，即使 ±1 达标也要在报数里注明
"""
from __future__ import annotations

import csv
import json
import random
import sys
from collections import Counter
from pathlib import Path

# 不 import common：它会 import anthropic，而这个脚本不调 API，不该依赖它
ROOT = Path(__file__).resolve().parents[2]
EVAL = ROOT / "eval"
WORK = EVAL / "work"


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        sys.exit(f"找不到 {path.relative_to(ROOT)}")
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def read_tsv(path: Path) -> list[dict]:
    if not path.exists():
        sys.exit(f"找不到 {path.relative_to(ROOT)}")
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter="\t", quoting=csv.QUOTE_NONE, quotechar=None))


def write_tsv(path: Path, header: list[str], rows: list[list]) -> None:
    with path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh, delimiter="\t", quoting=csv.QUOTE_NONE, quotechar=None)
        w.writerow(header)
        w.writerows(rows)


SPLIT = "train"
FULL = WORK / f"gen_calib_sheet_{SPLIT}.tsv"
SAMPLE = WORK / f"gen_calib_sample_{SPLIT}.tsv"
SAMPLE_CIT2 = WORK / f"gen_calib_sample_cit2_{SPLIT}.tsv"
JUDGE = WORK / f"gen_judge_{SPLIT}.jsonl"
OUT = EVAL / "GEN_AGREEMENT.md"

SEED = 15
PER_KIND = {"citation": 30, "sentence": 30}
VALID = {"citation": {"0", "1"}, "sentence": {"S", "U", "N"}, "usefulness": {"1", "2", "3", "4", "5"}}
BAR = 0.80


def sample():
    if SAMPLE.exists():
        sys.exit(f"{SAMPLE.relative_to(ROOT)} 已存在，可能有标了一半的人工标签；确实要重抽就先手动删掉")
    rows = read_tsv(FULL)
    rng = random.Random(SEED)
    picked = []
    for kind in ("citation", "sentence", "usefulness"):
        pool = [r for r in rows if r["kind"] == kind]
        n = PER_KIND.get(kind)
        picked += pool if n is None else rng.sample(pool, min(n, len(pool)))
    picked.sort(key=lambda r: (r["qid"], r["unit_id"]))   # 按回答聚在一起，标的时候少来回翻
    header = list(rows[0].keys())
    write_tsv(SAMPLE, header, [[r[h] for h in header] for r in picked])
    print(f"抽样 {len(picked)} 条：{dict(Counter(r['kind'] for r in picked))} → {SAMPLE.relative_to(ROOT)}")
    print(f"对照阅读：{(WORK / f'gen_calib_context_{SPLIT}.md').relative_to(ROOT)}")


def sample_cit2():
    """第一轮校准的分歧被拿来改了 judge，再用同一批样本验就是虚高，所以另抽一批没见过的引用"""
    if SAMPLE_CIT2.exists():
        sys.exit(f"{SAMPLE_CIT2.relative_to(ROOT)} 已存在，可能有标了一半的标签；确实要重抽就先手动删掉")
    seen = {r["unit_id"] for r in read_tsv(SAMPLE)}
    pool = [r for r in read_tsv(FULL) if r["kind"] == "citation" and r["unit_id"] not in seen]
    picked = sorted(random.Random(SEED + 1).sample(pool, min(30, len(pool))), key=lambda r: (r["qid"], r["unit_id"]))
    header = list(picked[0].keys())
    write_tsv(SAMPLE_CIT2, header, [[r[h] for h in header] for r in picked])
    print(f"另抽引用 {len(picked)} 条（候选 {len(pool)} 条，排除第一轮的 {len(seen)} 个单元）→ {SAMPLE_CIT2.relative_to(ROOT)}")


def kappa(pairs, cats, weight=None):
    """Cohen's κ；weight(x, y) 给出不一致程度（默认 0/1），线性加权时传 |x − y| / (k − 1)"""
    d = weight or (lambda x, y: float(x != y))
    n = len(pairs)
    a, b = Counter(x for x, _ in pairs), Counter(y for _, y in pairs)
    observed = sum(d(x, y) for x, y in pairs) / n
    expected = sum(a[x] * b[y] * d(x, y) for x in cats for y in cats) / (n * n)
    return float("nan") if expected == 0 else 1 - observed / expected


def confusion(pairs, cats, title):
    cm = Counter(pairs)
    lines = ["", f"{title}（行 = 人工，列 = judge）：", "",
             "| 人工 \\ judge | " + " | ".join(cats) + " |", "|---" * (len(cats) + 1) + "|"]
    lines += [f"| {x} | " + " | ".join(str(cm[(x, y)]) for y in cats) + " |" for x in cats]
    return lines


def agreement(which: str = ""):
    sample_file, out = (SAMPLE_CIT2, EVAL / "GEN_AGREEMENT_CIT2.md") if which == "cit2" else (SAMPLE, OUT)
    human = read_tsv(sample_file)
    empty = [r["unit_id"] for r in human if not r["label"].strip()]
    if empty:
        sys.exit(f"还有 {len(empty)} 条没标，例如 {empty[0]}")
    bad = [r["unit_id"] for r in human if r["label"].strip().upper() not in VALID[r["kind"]]]
    if bad:
        sys.exit(f"{len(bad)} 条标签不合法，例如 {bad[0]}（合法值见本文件开头）")
    judge = {j["unit_id"]: j["label"] for j in read_jsonl(JUDGE)}
    missing = [r["unit_id"] for r in human if r["unit_id"] not in judge]
    if missing:
        sys.exit(f"{len(missing)} 条在 judge 结果里找不到，例如 {missing[0]}（回答重新生成过？）")

    pairs = {k: [(r["label"].strip().upper(), judge[r["unit_id"]]) for r in human if r["kind"] == k] for k in VALID}
    cit, sent, use = pairs["citation"], pairs["sentence"], pairs["usefulness"]

    # note 列以 annotator=xxx 开头时按它报标注者，否则视为人工；Claude 代标不能当人工校准报
    annotators = {r["note"].split("；")[0].removeprefix("annotator=") for r in human if r["note"].startswith("annotator=")}
    who = "、".join(sorted(annotators)) if annotators else "人工"
    caveat = ([] if not annotators else
              ["", f"> ⚠️ **标注者是 {who}，不是人工。** 它与 judge 同属 Claude，可能共享同样的偏差，"
                   "一致率只能说明两个模型判得像，不能替代人工校准；第三条的验收（人工一致率 ≥ 80%）仍未完成。"])
    title = "生成层 judge 校准：改进引用判定后另抽的引用（Day 15）" if which == "cit2" else "生成层 judge 校准（Day 15）"
    lines = [f"# {title}", "",
             f"> 由 `{("tools/eval_build/gen_calib.py agreement " + which).strip()}` 生成。{who}盲标（未看 judge 结果）、judge（`claude-haiku-4-5`）独立判定，"
             f"回答来自 train 集（`GenerationEvalRunner`），样本 `{sample_file.name}`。口径见 `docs/EVAL.md`「Day 15 · 生成层指标定义」。", *caveat, "",
             "| 判定 | 对数 | 验收口径 | 一致率 | 其他 |", "|---|---|---|---|---|"]
    checks, details = [], []
    if cit:
        cit_agree = sum(x == y for x, y in cit) / len(cit)
        lines.append(f"| 引用是否支持 | {len(cit)} | 二值一致 | {cit_agree:.1%} | κ = {kappa(cit, ['1', '0']):.3f}"
                     "（一边几乎全标「支持」时 κ 没有意义，看混淆矩阵） |")
        details += confusion(cit, ["1", "0"], "引用")
        checks.append(("引用二值一致率", cit_agree))
    if sent:
        sent_exact = sum(x == y for x, y in sent) / len(sent)
        sent_bin = sum((x == "U") == (y == "U") for x, y in sent) / len(sent)
        lines.append(f"| 句子是否幻觉 | {len(sent)} | U vs 非 U | {sent_bin:.1%} | 三分类一致 {sent_exact:.1%}，"
                     f"κ = {kappa(sent, ['S', 'U', 'N']):.3f} |")
        details += confusion(sent, ["S", "U", "N"], "句子")
        checks.append(("句子幻觉二值一致率", sent_bin))
    if use:
        uh = [(int(x), int(y)) for x, y in use]
        use_exact = sum(x == y for x, y in uh) / len(uh)
        use_near = sum(abs(x - y) <= 1 for x, y in uh) / len(uh)
        use_bias = sum(y - x for x, y in uh) / len(uh)
        use_wk = kappa(uh, range(1, 6), lambda x, y: abs(x - y) / 4)
        lines.append(f"| 有用性 | {len(uh)} | 相差 ≤ 1 分 | {use_near:.1%} | 完全一致 {use_exact:.1%}，线性加权 κ = {use_wk:.3f}，"
                     f"均分偏差（judge − 人工）{use_bias:+.2f} |")
        details += confusion([(str(x), str(y)) for x, y in uh], [str(i) for i in range(1, 6)], "有用性")
        checks.append(("有用性 ±1 一致率", use_near))
    lines += details

    lines += ["", "## 验收", ""] + [f"- {'✅' if v >= BAR else '❌'} {name} ≥ {BAR:.0%}：{v:.1%}" for name, v in checks]
    if annotators:
        lines += ["", f"以上「验收」是 {who} 与 judge 的一致率，**不算第三条完成**。"]
    lines += ["", f"样本小：30 对的一致率 80%，95% 置信区间大约是 ±14 个点。未达标的那项，judge 给出的数字不能用。"]

    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"\n写入 {out.relative_to(ROOT)}")
    if any(v < BAR for _, v in checks):
        sys.exit(1)


if __name__ == "__main__":
    cmd, arg = (sys.argv[1:] + ["", ""])[:2]
    {"sample": sample, "sample-cit2": sample_cit2, "agreement": lambda: agreement(arg)}.get(cmd, lambda: sys.exit(__doc__))()
