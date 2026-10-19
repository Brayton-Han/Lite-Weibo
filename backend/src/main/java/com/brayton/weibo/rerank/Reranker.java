package com.brayton.weibo.rerank;

import com.brayton.weibo.retrieval.ScoredPost;

import java.util.List;

/**
 * 第二阶段精排（RAG Day 12）：对第一阶段召回的候选重新打分排序。
 * 抽象成接口与 {@code EmbeddingClient} 同理——上层（{@code RerankStrategy}、评测）只依赖它，
 * 以后想加自部署的 cross-encoder 做对照，不动调用方。
 */
public interface Reranker {

    /**
     * @param candidates 第一阶段的排序，下标即名次；同分时实现应按下标（第一阶段名次）打破平局
     * @return 至多 k 条，按相关性从高到低；score 是该 reranker 自己的分数，与第一阶段的分数不可比
     * @throws RerankException 调用失败（网络、限流、响应不合预期）
     */
    List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k);

    /** 出现在评测汇总里，如 {@code voyage/rerank-3}。 */
    String modelId();
}
