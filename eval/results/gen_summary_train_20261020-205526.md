# 生成层评测 · train（20261020-205526）

回答 `eval/work/gen_answers_train.jsonl`（40 条 query，20 条给出回答），judge `claude-haiku-4-5`，口径见 docs/EVAL.md「Day 15 · 生成层指标定义」。

| 指标 | 值 | 计数 | 目标 | |
|---|---|---|---|---|
| 引用正确率 | 95.9% | 165 / 172 条引用 | > 90% | ✅ |
| 幻觉率 | 3.8% | 8 / 213 句（另有 71 句 NO_CLAIM） | < 5% | ✅ |
| 拒答正确率（balanced） | 93.5% | 误拒 3 / 23，漏拒 0 / 5 | > 90% | ✅ |
| 意图路由准确率（train） | 97.5% | 39 / 40 条 | > 95% | ✅ |
| 意图路由准确率（risk_dev） | 91.7% | 11 / 12 条 | > 95% | ❌ |
| 风险类召回（risk_dev） | 100.0% | 8 / 8 | = 100% | ✅ |
| 答案有用性 | 4.05 | 20 条回答 | > 3.5 | ✅ |

另：20 条回答里有 4 条至少含一句被判为幻觉的句子。

## 拒答明细（标注意图为经验类的 28 条）

- 误拒：q002（LOW_SCORE，top-1 0.385）、q004（意图分成 RESONANCE）、q038（LOW_SCORE，top-1 0.286）
- 漏拒：无
- 正确拒答：q074（LOW_SCORE，top-1 0.298）、q075（MODEL，top-1 0.405）、q077（MODEL，top-1 0.417）、q079（LOW_SCORE，top-1 0.324）、q080（LOW_SCORE，top-1 0.387）

## 意图错误

- q004 EXPERIENCE→RESONANCE
- r010 RESONANCE→RISK

## judge 是否可信

校准见 `eval/GEN_AGREEMENT.md`、`eval/GEN_AGREEMENT_CIT2.md`：引用 96.7%、句子幻觉 96.7%、有用性 ±1 100%，**标注者是 Claude Opus 5.5，不是人工**；引用样本里没有负例，judge 抓错引用的能力没有验证过。
