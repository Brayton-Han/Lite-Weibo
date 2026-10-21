package com.brayton.weibo.service;

import com.brayton.weibo.dto.ScoredPostResponse;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.retrieval.RetrievalStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 语义搜索（Day 6 起是纯向量临时端点，Day 17 换成线上检索链 rerank(rrf)，见 {@code SearchConfig}）。
 *
 * 只返回 top-k、不分页：重排后的结果没有稳定的游标。分数是 rerank 的相关性分（降级时是 RRF 分），
 * 不同 query 之间可比，但和 Day 6–16 返回的余弦不是一回事。
 */
@Slf4j
@Service
public class SemanticSearchService {

    public static final int MAX_K = 50;

    private final RetrievalStrategy strategy;
    private final PostService postService;

    public SemanticSearchService(@Qualifier("semanticSearchStrategy") RetrievalStrategy strategy, PostService postService) {
        this.strategy = strategy;
        this.postService = postService;
    }

    public List<ScoredPostResponse> search(String query, int k, Long selfId) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > PostService.SEMANTIC_QUERY_MAX_LENGTH) {
            throw new WeiboException(CommonErrorCode.SEARCH_QUERY_INVALID);
        }
        k = Math.max(1, Math.min(k, MAX_K));

        List<ScoredPost> hits;
        try {
            hits = strategy.retrieve(q, k, selfId);
        } catch (EmbeddingException e) {
            log.warn("语义搜索查询向量化失败：{}", e.getMessage());
            throw new WeiboException(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }
        return postService.toScoredResponses(hits, selfId);
    }
}
