package com.brayton.weibo.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RetrievalMetricsTest {

    private static final double EPS = 1e-3;

    // RAG_ROADMAP.md Day 7 手算练习
    private static final Set<String> G = Set.of("A", "B", "C");
    private static final List<String> SYSTEM_JIA = List.of("A", "X", "B", "Y", "C");
    private static final List<String> SYSTEM_YI = List.of("X", "Y", "A", "B", "C");

    @Test
    void handCalculatedExampleForSystemJia() {
        assertThat(RetrievalMetrics.recallAtK(SYSTEM_JIA, G, 3)).isCloseTo(2.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.precisionAtK(SYSTEM_JIA, G, 3)).isCloseTo(2.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.reciprocalRank(SYSTEM_JIA, G)).isCloseTo(1.0, within(EPS));
        // DCG = 1 + 0.5 = 1.5，IDCG = 1 + 0.631 + 0.5 = 2.131
        assertThat(RetrievalMetrics.ndcgAtK(SYSTEM_JIA, G, 3)).isCloseTo(0.704, within(EPS));
    }

    @Test
    void handCalculatedExampleForSystemYi() {
        assertThat(RetrievalMetrics.recallAtK(SYSTEM_YI, G, 3)).isCloseTo(1.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.precisionAtK(SYSTEM_YI, G, 3)).isCloseTo(1.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.reciprocalRank(SYSTEM_YI, G)).isCloseTo(1.0 / 3, within(EPS));
        // DCG = 0.5
        assertThat(RetrievalMetrics.ndcgAtK(SYSTEM_YI, G, 3)).isCloseTo(0.235, within(EPS));
    }

    @Test
    void mrrAveragesReciprocalRanksAcrossQueries() {
        double rrJia = RetrievalMetrics.reciprocalRank(SYSTEM_JIA, G);
        double rrYi = RetrievalMetrics.reciprocalRank(SYSTEM_YI, G);
        assertThat(RetrievalMetrics.meanReciprocalRank(List.of(rrJia, rrYi))).isCloseTo(2.0 / 3, within(EPS));
    }

    @Test
    void fullRecallAtFive() {
        assertThat(RetrievalMetrics.recallAtK(SYSTEM_JIA, G, 5)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(SYSTEM_YI, G, 5)).isEqualTo(1.0);
    }

    @Test
    void reciprocalRankIsZeroWhenNothingHits() {
        assertThat(RetrievalMetrics.reciprocalRank(List.of("X", "Y"), G)).isEqualTo(0.0);
    }

    @Test
    void precisionKeepsKAsDenominatorWhenFewerResultsReturned() {
        assertThat(RetrievalMetrics.precisionAtK(List.of("A"), G, 5)).isCloseTo(0.2, within(EPS));
    }

    @Test
    void duplicateResultsAreCountedOnce() {
        List<String> ranked = List.of("A", "A", "A");
        assertThat(RetrievalMetrics.recallAtK(ranked, G, 3)).isCloseTo(1.0 / 3, within(EPS));
        assertThat(RetrievalMetrics.precisionAtK(ranked, G, 3)).isCloseTo(1.0 / 3, within(EPS));
        // 只有第 1 名得分：DCG = 1，IDCG = 2.131
        assertThat(RetrievalMetrics.ndcgAtK(ranked, G, 3)).isCloseTo(0.469, within(EPS));
    }

    @Test
    void gradedNdcgRewardsHighlyRelevantFirst() {
        Map<String, Integer> grades = Map.of("A", 2, "B", 1, "C", 0);
        // 理想排序 [A, B]：IDCG = 2 + 1/log2(3) = 2.631
        assertThat(RetrievalMetrics.ndcgAtK(List.of("A", "B", "X"), grades, 3)).isCloseTo(1.0, within(EPS));
        // [B, A]：DCG = 1 + 2/log2(3) = 2.262
        assertThat(RetrievalMetrics.ndcgAtK(List.of("B", "A", "X"), grades, 3)).isCloseTo(0.860, within(EPS));
    }

    @Test
    void relevantSetDropsZeroGrades() {
        assertThat(RetrievalMetrics.relevantSet(Map.of(1024L, 2, 1187L, 1, 1203L, 0)))
                .containsExactlyInAnyOrder(1024L, 1187L);
    }

    @Test
    void noAnswerQueryIsRejectedForRecallAndNdcg() {
        assertThatThrownBy(() -> RetrievalMetrics.recallAtK(SYSTEM_JIA, Set.of(), 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetrievalMetrics.ndcgAtK(SYSTEM_JIA, Map.<String, Integer>of(), 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositiveKIsRejected() {
        assertThatThrownBy(() -> RetrievalMetrics.precisionAtK(SYSTEM_JIA, G, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
