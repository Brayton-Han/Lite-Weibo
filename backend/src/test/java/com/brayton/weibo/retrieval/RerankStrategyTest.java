package com.brayton.weibo.retrieval;

import com.brayton.weibo.rerank.RerankCandidate;
import com.brayton.weibo.rerank.RerankException;
import com.brayton.weibo.rerank.Reranker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankStrategyTest {

    /** base 按给定顺序返回前 k 条，并记下每次请求的深度。 */
    private static RetrievalStrategy base(List<Long> order, List<Integer> depths) {
        return new RetrievalStrategy() {
            public String name() { return "base"; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                depths.add(k);
                return order.stream().limit(k).map(id -> new ScoredPost(id, 1.0 / id)).toList();
            }
        };
    }

    /** 按 postId 降序打分的假 reranker，记下收到的候选。 */
    private static final class ReverseReranker implements Reranker {
        final List<List<RerankCandidate>> calls = new ArrayList<>();

        public List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k) {
            calls.add(candidates);
            return candidates.stream()
                    .sorted(Comparator.comparingLong(RerankCandidate::postId).reversed())
                    .limit(k)
                    .map(c -> new ScoredPost(c.postId(), c.postId()))
                    .toList();
        }

        public String modelId() { return "fake"; }
    }

    private static final Reranker FAILING = new Reranker() {
        public List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k) {
            throw new RerankException("boom");
        }
        public String modelId() { return "failing"; }
    };

    private static Function<Collection<Long>, Map<Long, String>> texts(Map<Long, String> overrides) {
        return ids -> {
            Map<Long, String> out = new HashMap<>();
            ids.forEach(id -> out.put(id, "post-" + id));
            out.putAll(overrides);
            out.values().removeIf(v -> v.equals("<deleted>"));
            return out;
        };
    }

    @Test
    void reranksTopNCandidatesInFirstStageOrder() {
        List<Integer> depths = new ArrayList<>();
        ReverseReranker reranker = new ReverseReranker();
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L, 4L, 5L), depths), reranker, texts(Map.of()), 4, false);

        List<ScoredPost> out = s.retrieve("q", 2, null);

        assertThat(depths).containsExactly(4);
        assertThat(reranker.calls.get(0)).extracting(RerankCandidate::postId).containsExactly(1L, 2L, 3L, 4L);
        assertThat(reranker.calls.get(0).get(0).text()).isEqualTo("post-1");
        assertThat(out).extracting(ScoredPost::postId).containsExactly(4L, 3L);
    }

    @Test
    void deepensCandidatesWhenKExceedsN() {
        List<Integer> depths = new ArrayList<>();
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L, 4L, 5L), depths), new ReverseReranker(), texts(Map.of()), 2, false);

        assertThat(s.retrieve("q", 4, null)).hasSize(4);
        assertThat(depths).containsExactly(4);
    }

    @Test
    void fallsBackToFirstStageWhenEnabled() {
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L), new ArrayList<>()), FAILING, texts(Map.of()), 3, true);

        assertThat(s.retrieve("q", 2, null)).extracting(ScoredPost::postId).containsExactly(1L, 2L);
    }

    @Test
    void propagatesFailureWhenFallbackDisabled() {
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L), new ArrayList<>()), FAILING, texts(Map.of()), 3, false);

        assertThatThrownBy(() -> s.retrieve("q", 2, null)).isInstanceOf(RerankException.class);
    }

    @Test
    void blankPostsSkipRerankerAndFillTheTail() {
        ReverseReranker reranker = new ReverseReranker();
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L), new ArrayList<>()), reranker,
                texts(Map.of(2L, "  ")), 3, false);

        List<ScoredPost> out = s.retrieve("q", 3, null);

        assertThat(reranker.calls.get(0)).extracting(RerankCandidate::postId).containsExactly(1L, 3L);
        assertThat(out).extracting(ScoredPost::postId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void deletedPostsAreDropped() {
        ReverseReranker reranker = new ReverseReranker();
        RerankStrategy s = new RerankStrategy(base(List.of(1L, 2L, 3L), new ArrayList<>()), reranker,
                texts(Map.of(3L, "<deleted>")), 3, true);

        assertThat(s.retrieve("q", 3, null)).extracting(ScoredPost::postId).containsExactly(2L, 1L);
    }

    @Test
    void emptyFirstStageSkipsReranker() {
        ReverseReranker reranker = new ReverseReranker();
        RerankStrategy s = new RerankStrategy(base(List.of(), new ArrayList<>()), reranker, texts(Map.of()), 3, false);

        assertThat(s.retrieve("q", 3, null)).isEmpty();
        assertThat(reranker.calls).isEmpty();
    }

    @Test
    void rejectsNonPositiveN() {
        assertThatThrownBy(() -> new RerankStrategy(base(List.of(), new ArrayList<>()), new ReverseReranker(), texts(Map.of()), 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
