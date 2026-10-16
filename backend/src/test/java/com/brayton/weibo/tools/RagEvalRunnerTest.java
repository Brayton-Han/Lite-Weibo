package com.brayton.weibo.tools;

import com.brayton.weibo.retrieval.RetrievalStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.tools.RagEvalRunner.EvalQuery;
import com.brayton.weibo.tools.RagEvalRunner.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RagEvalRunnerTest {

    private static final Set<Long> CORPUS = Set.of(1L, 2L, 3L, 4L, 5L);

    private static List<ScoredPost> hits(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new ScoredPost(id, 0.0)).toList();
    }

    @Test
    void nonCorpusPostsAreDroppedBeforeTruncation() {
        // 99 是没有 corpus_meta 的真实帖子：剔除后 3 补上来，而不是让 99 占掉第 2 名
        EvalQuery q = new EvalQuery("q1", "x", "keyword", "experience", Map.of(3L, 2), Set.of());
        QueryResult r = RagEvalRunner.score(q, hits(1, 99, 3, 4), CORPUS, 2, 0);

        assertThat(r.ranked()).containsExactly(1L, 3L);
        assertThat(r.recall()).isEqualTo(1.0);
        assertThat(r.rr()).isEqualTo(0.5);
        assertThat(r.precision()).isEqualTo(0.5);
    }

    @Test
    void retrievalDeepensOnlyWhenNonCorpusPostsCrowdOut() {
        // 策略按 [1, 99, 98, 2, 3, 4, 5] 排名，99 / 98 不是语料帖
        List<Long> order = List.of(1L, 99L, 98L, 2L, 3L, 4L, 5L);
        List<Integer> depths = new java.util.ArrayList<>();
        RetrievalStrategy s = new RetrievalStrategy() {
            public String name() { return "fake"; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                depths.add(k);
                return order.stream().limit(k).map(id -> new ScoredPost(id, 0.0)).toList();
            }
        };

        // k = 1：第 1 名就是语料帖，不重取
        RagEvalRunner.retrieveCorpusTopK(s, "x", 1, CORPUS);
        assertThat(depths).containsExactly(1);

        // k = 3：深度 3 只剩 1 条，翻到 6 才凑够 3 条
        depths.clear();
        List<ScoredPost> raw = RagEvalRunner.retrieveCorpusTopK(s, "x", 3, CORPUS);
        assertThat(depths).containsExactly(3, 6);
        assertThat(RagEvalRunner.score(new EvalQuery("q1", "x", "keyword", "experience", Map.of(1L, 2), Set.of()),
                raw, CORPUS, 3, 0).ranked()).containsExactly(1L, 2L, 3L);

        // 策略结果耗尽时停止，不会死循环
        depths.clear();
        RagEvalRunner.retrieveCorpusTopK(s, "x", 6, CORPUS);
        assertThat(depths).containsExactly(6, 12);
    }

    @Test
    void gradedNdcgUsesGrades() {
        // 理想排序 [2, 1]：IDCG = 2 + 1/log2(3)；实际 [1, 2]：DCG = 1 + 2/log2(3)
        EvalQuery q = new EvalQuery("q1", "x", "synonym", "experience", Map.of(1L, 1, 2L, 2), Set.of());
        QueryResult r = RagEvalRunner.score(q, hits(1, 2), CORPUS, 10, 0);

        double log2of3 = Math.log(3) / Math.log(2);
        assertThat(r.ndcg()).isCloseTo((1 + 2 / log2of3) / (2 + 1 / log2of3), within(1e-9));
    }

    @Test
    void noAnswerQueryHasNoMetrics() {
        EvalQuery q = new EvalQuery("q9", "量子计算入门", "no_answer", null, Map.of(), Set.of());
        QueryResult r = RagEvalRunner.score(q, hits(1, 2), CORPUS, 10, 0);

        assertThat(r.ranked()).containsExactly(1L, 2L);
        assertThat(r.recall()).isNull();
        assertThat(r.ndcg()).isNull();
    }

    @Test
    void judgedRateCountsPooledResultsOnly() {
        // 1 判过（0 分也算判过），2 是池外帖子
        EvalQuery q = new EvalQuery("q1", "x", "keyword", "experience", Map.of(3L, 2), Set.of(1L, 3L));
        QueryResult r = RagEvalRunner.score(q, hits(1, 2), CORPUS, 10, 0);

        assertThat(RagEvalRunner.judgedRate(List.of(r))).isEqualTo("50.0%");
        assertThat(RagEvalRunner.judgedRate(List.of(RagEvalRunner.score(q, hits(), CORPUS, 10, 0)))).isEqualTo("—");
    }

    @Test
    void percentileIsNearestRank() {
        List<Long> v = List.of(5L, 1L, 4L, 2L, 3L, 6L, 7L, 8L, 9L, 10L);
        assertThat(RagEvalRunner.percentile(v, 50)).isEqualTo(5L);
        assertThat(RagEvalRunner.percentile(v, 95)).isEqualTo(10L);
        assertThat(RagEvalRunner.percentile(List.of(7L), 95)).isEqualTo(7L);
        assertThatThrownBy(() -> RagEvalRunner.percentile(List.of(), 95)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void summaryKeepsLayersApartAndExcludesNoAnswer() {
        EvalQuery exp = new EvalQuery("q1", "a", "keyword", "experience", Map.of(1L, 2), Set.of());
        EvalQuery res = new EvalQuery("q2", "b", "resonance", "resonance", Map.of(2L, 2), Set.of());
        EvalQuery none = new EvalQuery("q3", "c", "no_answer", null, Map.of(), Set.of());
        Map<String, List<QueryResult>> all = new LinkedHashMap<>();
        all.put("like", List.of(
                RagEvalRunner.score(exp, hits(1), CORPUS, 10, 1_000_000),
                RagEvalRunner.score(res, hits(), CORPUS, 10, 1_000_000),
                RagEvalRunner.score(none, hits(), CORPUS, 10, 1_000_000)));

        String md = RagEvalRunner.summarize(all, 10);
        assertThat(md).contains("| like | 2 | 0.500 | 0.050 | 0.500 | 0.500 | 0.0% | 1.0 | 1.0 |");

        assertThat(md).contains("| **经验层** | 1 | 1.000 |");
        assertThat(md).contains("| **共鸣层** | 1 | 0.000 |");
        assertThat(md).contains("| **全部可答** | 2 | 0.500 |");
        assertThat(md).contains("| 　· mixed | 0 | — |");
        assertThat(md).contains("| like | 1 | 1 | 0.0 |");   // 无答案：1 条，返回空 1 条
    }
}
