package com.brayton.weibo.retrieval;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * 混合检索（RAG Day 11）：词面、向量两路并行各取 top-N，按 RRF（Reciprocal Rank Fusion）融合后取 top-k。
 * <pre>
 *   RRF(d) = Σ_i 1 / (rrfK + rank_i(d))      rank 从 1 开始，某一路没召回 d 则该路贡献 0
 * </pre>
 * 只用排名不用分数：两路分数量纲不同（word_similarity / ts_rank_cd / 余弦，见 {@link ScoredPost}），
 * 直接加权要先归一化，归一化参数又会随语料漂移。
 *
 * <h3>加权</h3>
 * 可选给向量一路乘权重 w（词面一路固定为 1）：{@code RRF(d) = 1/(rrfK + r_lex) + w/(rrfK + r_vec)}。
 * Day 11 扫参发现两路几乎不重叠时等权 RRF 退化成按名次交替合并，词面的低精度拉低了结果；
 * w 越大，词面独有的帖子越难挤进前面，词面一路越接近只给向量结果「加分」。w = 1 即经典 RRF。
 *
 * <h3>并行</h3>
 * 两路在注入的 {@code executor} 上各跑一个任务，调用线程等两者都返回。两个 repository 方法各自 {@code @Transactional}，
 * 在哪个线程上跑都各开各的事务，SET LOCAL 的参数不会串。executor 的拒绝策略不能是丢弃——丢掉的任务对应的 future
 * 永远不完成，调用线程会一直挂着；评测接的是 feed 线程池（CallerRuns，满了就在调用线程上串行跑）。
 *
 * <h3>单路深度</h3>
 * 每路取 {@code max(n, k)}：k 超过 N 时（评测剔除非语料帖后的加深重取）跟着加深，否则融合结果凑不满 k 条。
 * 返回条数少于请求的 k，当且仅当两路都已没有更多结果——与评测里「不够就加深」的停止条件一致。
 *
 * <h3>同分</h3>
 * 只出现在一路、且名次相同的两条帖子 RRF 分完全相等（比如两路各自的第 1 名都只出现在本路）。
 * 按 id 升序打破平局，保证同一输入的输出确定，扫参前后可比。
 *
 * score 字段是 RRF 分。
 */
public class HybridRrfStrategy implements RetrievalStrategy {

    private final RetrievalStrategy lexical;
    private final RetrievalStrategy vector;
    private final Executor executor;
    private final int n;
    private final int rrfK;
    private final double vectorWeight;

    /** 等权 RRF。 */
    public HybridRrfStrategy(RetrievalStrategy lexical, RetrievalStrategy vector, Executor executor, int n, int rrfK) {
        this(lexical, vector, executor, n, rrfK, 1.0);
    }

    /**
     * @param n    每路召回条数（top-N）；Day 11 选定 20（N ≥ 50 时向量路内层 LIMIT 过大，规划器放弃 HNSW 改走全表扫描）
     * @param rrfK RRF 的平滑常数，经典取 60：越大越看重「出现在几路里」，越小越看重「单路里排多靠前」
     * @param vectorWeight 向量一路的权重，词面一路固定为 1；Day 11 选定 k = 10、w = 10，见 docs/EVAL.md
     */
    public HybridRrfStrategy(RetrievalStrategy lexical, RetrievalStrategy vector, Executor executor,
                             int n, int rrfK, double vectorWeight) {
        if (n <= 0 || rrfK < 0 || !(vectorWeight > 0)) {
            throw new IllegalArgumentException("n and vectorWeight must be positive and rrfK non-negative, got n="
                    + n + ", rrfK=" + rrfK + ", vectorWeight=" + vectorWeight);
        }
        this.lexical = lexical;
        this.vector = vector;
        this.executor = executor;
        this.n = n;
        this.rrfK = rrfK;
        this.vectorWeight = vectorWeight;
    }

    @Override
    public String name() {
        return "rrf";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        int depth = Math.max(n, k);
        CompletableFuture<List<ScoredPost>> lexicalHits =
                CompletableFuture.supplyAsync(() -> lexical.retrieve(query, depth, requesterId), executor);
        CompletableFuture<List<ScoredPost>> vectorHits =
                CompletableFuture.supplyAsync(() -> vector.retrieve(query, depth, requesterId), executor);
        try {
            return fuse(List.of(lexicalHits.join(), vectorHits.join()), List.of(1.0, vectorWeight), rrfK, k);
        } catch (CompletionException e) {
            // 把某一路的原始异常抛给调用方，而不是包一层 CompletionException
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** 等权 RRF 融合。 */
    static List<ScoredPost> fuse(List<List<ScoredPost>> rankings, int rrfK, int k) {
        return fuse(rankings, rankings.stream().map(r -> 1.0).toList(), rrfK, k);
    }

    /** 加权 RRF 融合，weights 与 rankings 一一对应；每路内同一帖子只按第一次出现的名次计。检索详情面板也用它，保证展示的就是这套算法 */
    public static List<ScoredPost> fuse(List<List<ScoredPost>> rankings, List<Double> weights, int rrfK, int k) {
        if (weights.size() != rankings.size()) {
            throw new IllegalArgumentException("weights and rankings must have the same size");
        }
        Map<Long, Double> scores = new HashMap<>();
        for (int i = 0; i < rankings.size(); i++) {
            List<ScoredPost> ranking = rankings.get(i);
            double w = weights.get(i);
            Set<Long> seen = new HashSet<>();
            int rank = 0;
            for (ScoredPost p : ranking) {
                if (seen.add(p.postId())) {
                    rank++;
                    scores.merge(p.postId(), w / (rrfK + rank), Double::sum);
                }
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                .limit(k)
                .map(e -> new ScoredPost(e.getKey(), e.getValue()))
                .toList();
    }
}
