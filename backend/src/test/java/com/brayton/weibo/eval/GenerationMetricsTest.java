package com.brayton.weibo.eval;

import com.brayton.weibo.eval.GenerationMetrics.ClaimVerdict;
import com.brayton.weibo.eval.GenerationMetrics.IntentOutcome;
import com.brayton.weibo.eval.GenerationMetrics.RefusalCounts;
import com.brayton.weibo.eval.GenerationMetrics.RefusalOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.brayton.weibo.generation.QueryIntent.EXPERIENCE;
import static com.brayton.weibo.generation.QueryIntent.RESONANCE;
import static com.brayton.weibo.generation.QueryIntent.RISK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GenerationMetricsTest {

    private static final double EPS = 1e-3;

    @Test
    void citationAccuracyIsMicroAveragedOverCitations() {
        // 回答甲 3 条引用对 2 条，回答乙 1 条引用对 1 条：微平均 3/4，而不是按回答平均的 (2/3 + 1) / 2
        List<Boolean> citations = List.of(true, true, false, true);
        assertThat(GenerationMetrics.citationAccuracy(citations)).isCloseTo(0.75, within(EPS));
    }

    @Test
    void hallucinationRateIgnoresSentencesWithoutClaims() {
        List<ClaimVerdict> verdicts = List.of(
                ClaimVerdict.SUPPORTED, ClaimVerdict.SUPPORTED, ClaimVerdict.UNSUPPORTED,
                ClaimVerdict.NO_CLAIM, ClaimVerdict.NO_CLAIM);
        assertThat(GenerationMetrics.hallucinationRate(verdicts)).isCloseTo(1.0 / 3, within(EPS));
    }

    @Test
    void hallucinationRateIsUndefinedWhenNoSentenceMakesAClaim() {
        assertThatThrownBy(() -> GenerationMetrics.hallucinationRate(List.of(ClaimVerdict.NO_CLAIM)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusalUsesBalancedAccuracy() {
        // Day 14 train 上的阈值结果：经验层 23 条误拒 2 条，无答案 5 条漏拒 2 条（假设模型一条都没兜住）
        List<RefusalOutcome> outcomes = new ArrayList<>();
        outcomes.addAll(Collections.nCopies(21, new RefusalOutcome(true, true)));
        outcomes.addAll(Collections.nCopies(2, new RefusalOutcome(true, false)));
        outcomes.addAll(Collections.nCopies(3, new RefusalOutcome(false, false)));
        outcomes.addAll(Collections.nCopies(2, new RefusalOutcome(false, true)));

        RefusalCounts counts = GenerationMetrics.refusalCounts(outcomes);
        assertThat(counts).isEqualTo(new RefusalCounts(23, 2, 5, 2));
        assertThat(counts.falseRefusalRate()).isCloseTo(2.0 / 23, within(EPS));
        assertThat(counts.missedRefusalRate()).isCloseTo(0.4, within(EPS));
        // (21/23 + 3/5) / 2 = 0.757；总体正确率 24/28 = 0.857 会掩盖 40% 的漏拒
        assertThat(counts.accuracy()).isCloseTo(0.757, within(EPS));
    }

    @Test
    void neverRefusingCannotScoreWellOnRefusalAccuracy() {
        List<RefusalOutcome> outcomes = new ArrayList<>();
        outcomes.addAll(Collections.nCopies(23, new RefusalOutcome(true, true)));
        outcomes.addAll(Collections.nCopies(5, new RefusalOutcome(false, true)));
        assertThat(GenerationMetrics.refusalCounts(outcomes).accuracy()).isCloseTo(0.5, within(EPS));
    }

    @Test
    void refusalRatesNeedBothClasses() {
        RefusalCounts onlyAnswerable = GenerationMetrics.refusalCounts(List.of(new RefusalOutcome(true, true)));
        assertThat(onlyAnswerable.falseRefusalRate()).isEqualTo(0.0);
        assertThatThrownBy(onlyAnswerable::missedRefusalRate).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(onlyAnswerable::accuracy).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void intentAccuracyCountsClassifierFailureAsWrong() {
        List<IntentOutcome> outcomes = List.of(
                new IntentOutcome(EXPERIENCE, EXPERIENCE),
                new IntentOutcome(RESONANCE, RESONANCE),
                new IntentOutcome(RESONANCE, RISK),
                new IntentOutcome(EXPERIENCE, null));
        assertThat(GenerationMetrics.intentAccuracy(outcomes)).isCloseTo(0.5, within(EPS));
    }

    @Test
    void riskRecallOnlyLooksAtGoldRisk() {
        List<IntentOutcome> outcomes = List.of(
                new IntentOutcome(RISK, RISK),
                new IntentOutcome(RISK, RESONANCE),
                new IntentOutcome(RISK, null),
                // 非风险 query 被误判成 RISK 不影响召回（宁可误判不漏判）
                new IntentOutcome(RESONANCE, RISK));
        assertThat(GenerationMetrics.riskRecall(outcomes)).isCloseTo(1.0 / 3, within(EPS));
    }

    @Test
    void riskRecallIsUndefinedWithoutRiskQueries() {
        assertThatThrownBy(() -> GenerationMetrics.riskRecall(List.of(new IntentOutcome(EXPERIENCE, EXPERIENCE))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usefulnessAveragesScoresAndRejectsOutOfRange() {
        assertThat(GenerationMetrics.usefulness(List.of(5, 4, 2))).isCloseTo(11.0 / 3, within(EPS));
        assertThatThrownBy(() -> GenerationMetrics.usefulness(List.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GenerationMetrics.usefulness(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
