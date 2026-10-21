# 生成层 judge 校准：改进引用判定后另抽的引用（Day 15）

> 由 `tools/eval_build/gen_calib.py agreement cit2` 生成。claude-opus-5-5盲标（未看 judge 结果）、judge（`claude-haiku-4-5`）独立判定，回答来自 train 集（`GenerationEvalRunner`），样本 `gen_calib_sample_cit2_train.tsv`。口径见 `docs/EVAL.md`「Day 15 · 生成层指标定义」。

> ⚠️ **标注者是 claude-opus-5-5，不是人工。** 它与 judge 同属 Claude，可能共享同样的偏差，一致率只能说明两个模型判得像，不能替代人工校准；第三条的验收（人工一致率 ≥ 80%）仍未完成。

| 判定 | 对数 | 验收口径 | 一致率 | 其他 |
|---|---|---|---|---|
| 引用是否支持 | 30 | 二值一致 | 96.7% | κ = 0.000（一边几乎全标「支持」时 κ 没有意义，看混淆矩阵） |

引用（行 = 人工，列 = judge）：

| 人工 \ judge | 1 | 0 |
|---|---|---|
| 1 | 29 | 1 |
| 0 | 0 | 0 |

## 验收

- ✅ 引用二值一致率 ≥ 80%：96.7%

以上「验收」是 claude-opus-5-5 与 judge 的一致率，**不算第三条完成**。

样本小：30 对的一致率 80%，95% 置信区间大约是 ±14 个点。未达标的那项，judge 给出的数字不能用。
