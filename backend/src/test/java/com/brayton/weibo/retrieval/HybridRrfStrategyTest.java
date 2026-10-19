package com.brayton.weibo.retrieval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HybridRrfStrategyTest {

    private static final long A = 1, B = 2, C = 3, D = 4;

    private static List<ScoredPost> ranked(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new ScoredPost(id, 0.0)).toList();
    }

    private static RetrievalStrategy fixed(List<Long> order, List<Integer> depths) {
        return new RetrievalStrategy() {
            public String name() { return "fake"; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                depths.add(k);
                return order.stream().limit(k).map(id -> new ScoredPost(id, 0.0)).toList();
            }
        };
    }

    @Test
    void roadmapWorkedExample() {
        // 向量 [A, B, C]、词面 [B, D, A]、k = 60 → B > A > D > C
        List<ScoredPost> fused = HybridRrfStrategy.fuse(List.of(ranked(A, B, C), ranked(B, D, A)), 60, 10);

        assertThat(fused).extracting(ScoredPost::postId).containsExactly(B, A, D, C);
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 62 + 1.0 / 61, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 61 + 1.0 / 63, within(1e-12));
        assertThat(fused.get(3).score()).isCloseTo(1.0 / 63, within(1e-12));
    }

    @Test
    void vectorWeightKeepsLexicalOnlyPostsBehindVectorHits() {
        // 词面 [D, B]、向量 [A, B, C]：等权时 D（词面第 1）与 A 同分、排在 C（向量第 3）前面；
        // 向量权重 2 时 D 的 1/61 输给 C 的 2/63
        List<List<ScoredPost>> rankings = List.of(ranked(D, B), ranked(A, B, C));

        assertThat(HybridRrfStrategy.fuse(rankings, 60, 10)).extracting(ScoredPost::postId).containsExactly(B, A, D, C);
        List<ScoredPost> weighted = HybridRrfStrategy.fuse(rankings, List.of(1.0, 2.0), 60, 10);
        assertThat(weighted).extracting(ScoredPost::postId).containsExactly(B, A, C, D);
        assertThat(weighted.get(0).score()).isCloseTo(1.0 / 62 + 2.0 / 62, within(1e-12));
    }

    @Test
    void tiesBreakByIdAndResultIsTruncatedToK() {
        // 两路的第 1 名各自只出现在本路，分数相等
        List<ScoredPost> fused = HybridRrfStrategy.fuse(List.of(ranked(D, C), ranked(A, B)), 60, 3);

        assertThat(fused).extracting(ScoredPost::postId).containsExactly(A, D, B);
    }

    @Test
    void duplicateWithinOneRankingCountsOnce() {
        List<ScoredPost> fused = HybridRrfStrategy.fuse(List.of(ranked(A, A, B), ranked()), 60, 10);

        assertThat(fused).extracting(ScoredPost::postId).containsExactly(A, B);
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 61, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 62, within(1e-12));
    }

    @Test
    void eachLegFetchesMaxOfNAndK() {
        List<Integer> depths = Collections.synchronizedList(new ArrayList<>());
        RetrievalStrategy lexical = fixed(List.of(B, D, A), depths);
        RetrievalStrategy vector = fixed(List.of(A, B, C), depths);
        HybridRrfStrategy s = new HybridRrfStrategy(lexical, vector, Runnable::run, 50, 60);

        assertThat(s.retrieve("q", 10, null)).extracting(ScoredPost::postId).containsExactly(B, A, D, C);
        assertThat(depths).containsExactly(50, 50);

        depths.clear();
        s.retrieve("q", 80, null);
        assertThat(depths).containsExactly(80, 80);
    }

    @Test
    void legsRunConcurrently() throws Exception {
        // 每一路都要等另一路也开始才返回：串行执行会卡在第一路上
        CountDownLatch bothStarted = new CountDownLatch(2);
        RetrievalStrategy waiting = new RetrievalStrategy() {
            public String name() { return "waiting"; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                bothStarted.countDown();
                try {
                    if (!bothStarted.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("legs did not run in parallel");
                    }
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return ranked(A);
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            HybridRrfStrategy s = new HybridRrfStrategy(waiting, waiting, pool, 50, 60);
            assertThat(s.retrieve("q", 10, null)).extracting(ScoredPost::postId).containsExactly(A);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legFailurePropagatesUnwrapped() {
        RetrievalStrategy failing = new RetrievalStrategy() {
            public String name() { return "failing"; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                throw new IllegalStateException("db down");
            }
        };
        HybridRrfStrategy s = new HybridRrfStrategy(fixed(List.of(A), new ArrayList<>()), failing, Runnable::run, 50, 60);

        assertThatThrownBy(() -> s.retrieve("q", 10, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }
}
