package com.brayton.weibo.dto;

import java.util.List;

/**
 * 检索详情面板（RAG Day 16）：同一个 query 下词面、向量两路各自的排序，以及 RRF 怎么把它们融合起来。
 * 只用来演示和排查，不参与问答；分数的量纲各路不同（见 {@code ScoredPost}），只能在同一列里比。
 *
 * @param lexical    词面路 top-k，score 为 word_similarity（中文）或 ts_rank_cd（英文）
 * @param vector     向量路 top-k，score 为余弦相似度
 * @param fused      RRF 融合后的 top-k，带每一路的名次与贡献
 * @param vectorTop1 向量 top-1 余弦，问答的拒答阈值就看它；没有结果时为 null
 */
public record RetrievalDetailResponse(
        String lexicalMode,
        List<Hit> lexical,
        List<Hit> vector,
        List<FusedHit> fused,
        Double vectorTop1,
        Config config,
        Timings timings
) {

    /** @param rank 从 1 开始 */
    public record Hit(int rank, long postId, double score, String author, String snippet) {}

    /**
     * @param lexicalRank 在词面路（深度 n）里的名次，没召回为 null；contribution 同理为 0
     * @param vectorRank  在向量路（深度 n）里的名次
     */
    public record FusedHit(int rank, long postId, double score,
                           Integer lexicalRank, double lexicalContribution,
                           Integer vectorRank, double vectorContribution,
                           String author, String snippet) {}

    /** @param n 融合时每路的召回深度；面板只展示每路 top-k，但融合用的是 top-n */
    public record Config(int k, int n, int rrfK, double vectorWeight, double refusalThreshold, int askTopK) {}

    /** embedMs 是调 embedding API 的网络往返，不算在 vectorMs 里 */
    public record Timings(long lexicalMs, long embedMs, long vectorMs) {}
}
