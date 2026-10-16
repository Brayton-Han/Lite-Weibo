package com.brayton.weibo.retrieval;

import com.brayton.weibo.repository.PostEmbeddingRepository;

import java.util.List;
import java.util.function.Function;

/**
 * 纯向量检索：与语义搜索端点同一条 {@link PostEmbeddingRepository#searchNearest}，ef_search 由构造参数指定。
 *
 * 向量化通过 {@code embedder} 注入而不是直接依赖 {@code EmbeddingClient}：评测时换成读缓存的实现，
 * 反复跑不花 API 费用，且 query 向量固定，前后两次跑的差异只来自检索本身。
 * 相应地，评测量到的延迟<b>不含</b>调用 embedding API 的那一次网络往返。
 */
public class VectorStrategy implements RetrievalStrategy {

    private final PostEmbeddingRepository repository;
    private final String modelId;
    private final Function<String, float[]> embedder;
    private final int efSearch;

    public VectorStrategy(PostEmbeddingRepository repository, String modelId,
                          Function<String, float[]> embedder, int efSearch) {
        this.repository = repository;
        this.modelId = modelId;
        this.embedder = embedder;
        this.efSearch = efSearch;
    }

    @Override
    public String name() {
        return "vector";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        return repository.searchNearest(embedder.apply(query), modelId, k, efSearch).stream()
                .map(h -> new ScoredPost(h.postId(), h.score()))
                .toList();
    }
}
