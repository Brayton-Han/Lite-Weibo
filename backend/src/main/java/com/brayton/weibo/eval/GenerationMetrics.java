package com.brayton.weibo.eval;

import com.brayton.weibo.generation.QueryIntent;

import java.util.Collection;

/**
 * 生成层评测指标，口径见 docs/EVAL.md「Day 15 · 生成层指标定义」。
 * <p>
 * 这里只做计数与求比例，不判断对错：每条引用、每个句子"是否被支持"由 judge（或人工）给出，
 * 拒答与意图的对错由标注集给出。分母为空时直接抛异常，不返回 0 或 NaN——和 {@link RetrievalMetrics} 一样，
 * 没有样本的指标不能悄悄混进报表。
 */
public final class GenerationMetrics {

    private GenerationMetrics() {
    }

    /** judge 对回答里一个句子的判定 */
    public enum ClaimVerdict {
        /** 句中的信息都能在送进模型的帖子里找到 */
        SUPPORTED,
        /** 至少有一处信息在送进模型的帖子里找不到，或与帖子矛盾 */
        UNSUPPORTED,
        /** 不含可核对的信息（过渡句、礼貌语），不进分母 */
        NO_CLAIM
    }

    /** 一条 query 在拒答口径下的结果：是否应答（标注）与是否给出了生成回答（系统） */
    public record RefusalOutcome(boolean answerable, boolean answered) {}

    /** 拒答的四格计数，三个比例都从这里算 */
    public record RefusalCounts(int answerable, int falseRefusals, int noAnswer, int missedRefusals) {

        /** 误拒率 = 可答 query 里没给出回答的比例 */
        public double falseRefusalRate() {
            return ratio(falseRefusals, answerable, "falseRefusalRate needs at least one answerable query");
        }

        /** 漏拒率 = 无答案 query 里仍给出回答的比例 */
        public double missedRefusalRate() {
            return ratio(missedRefusals, noAnswer, "missedRefusalRate needs at least one no-answer query");
        }

        /**
         * 拒答正确率 = 两类各自正确率的平均（balanced accuracy）。
         * 不用总体正确率：可答与无答案约 23 : 5，"永远不拒"也能拿到 82%。
         */
        public double accuracy() {
            return ((1 - falseRefusalRate()) + (1 - missedRefusalRate())) / 2;
        }
    }

    /** 一条 query 的意图：标注值与分类器输出，分类失败时 {@code predicted} 为 null */
    public record IntentOutcome(QueryIntent gold, QueryIntent predicted) {}

    /** 引用正确率 = 被判支持的引用数 / 引用总数，跨 query 按引用条数微平均 */
    public static double citationAccuracy(Collection<Boolean> citationSupported) {
        long supported = citationSupported.stream().filter(Boolean::booleanValue).count();
        return ratio(supported, citationSupported.size(), "citationAccuracy needs at least one citation");
    }

    /** 幻觉率 = UNSUPPORTED 句数 / 含信息的句数（NO_CLAIM 不计），跨 query 按句数微平均 */
    public static double hallucinationRate(Collection<ClaimVerdict> verdicts) {
        long unsupported = verdicts.stream().filter(v -> v == ClaimVerdict.UNSUPPORTED).count();
        long claims = verdicts.stream().filter(v -> v != ClaimVerdict.NO_CLAIM).count();
        return ratio(unsupported, claims, "hallucinationRate needs at least one sentence with a claim");
    }

    public static RefusalCounts refusalCounts(Collection<RefusalOutcome> outcomes) {
        int answerable = 0, falseRefusals = 0, noAnswer = 0, missedRefusals = 0;
        for (RefusalOutcome o : outcomes) {
            if (o.answerable()) {
                answerable++;
                if (!o.answered()) falseRefusals++;
            } else {
                noAnswer++;
                if (o.answered()) missedRefusals++;
            }
        }
        return new RefusalCounts(answerable, falseRefusals, noAnswer, missedRefusals);
    }

    /** 意图路由准确率 = 分类正确的 query / 全部 query；分类失败算错 */
    public static double intentAccuracy(Collection<IntentOutcome> outcomes) {
        long correct = outcomes.stream().filter(o -> o.gold() == o.predicted()).count();
        return ratio(correct, outcomes.size(), "intentAccuracy needs at least one query");
    }

    /** 风险类召回 = 标注为 RISK 且被分到 RISK 的 / 标注为 RISK 的；分类失败算漏判 */
    public static double riskRecall(Collection<IntentOutcome> outcomes) {
        long risk = outcomes.stream().filter(o -> o.gold() == QueryIntent.RISK).count();
        long caught = outcomes.stream()
                .filter(o -> o.gold() == QueryIntent.RISK && o.predicted() == QueryIntent.RISK)
                .count();
        return ratio(caught, risk, "riskRecall needs at least one RISK query");
    }

    /** 答案有用性 = 1–5 分的均值，只对给出了回答的可答 query 打分 */
    public static double usefulness(Collection<Integer> scores) {
        for (int s : scores) {
            if (s < 1 || s > 5) {
                throw new IllegalArgumentException("usefulness score must be in 1..5, got " + s);
            }
        }
        long sum = scores.stream().mapToLong(Integer::longValue).sum();
        return ratio(sum, scores.size(), "usefulness needs at least one scored answer");
    }

    private static double ratio(long numerator, long denominator, String emptyMessage) {
        if (denominator == 0) {
            throw new IllegalArgumentException(emptyMessage);
        }
        return (double) numerator / denominator;
    }
}
