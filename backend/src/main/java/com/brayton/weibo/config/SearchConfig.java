package com.brayton.weibo.config;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostLexicalSearchRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.rerank.RerankCandidate;
import com.brayton.weibo.rerank.Reranker;
import com.brayton.weibo.retrieval.HybridRrfStrategy;
import com.brayton.weibo.retrieval.RerankStrategy;
import com.brayton.weibo.retrieval.RetrievalStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.retrieval.TimedStrategy;
import com.brayton.weibo.retrieval.TsRankStrategy;
import com.brayton.weibo.retrieval.VectorStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 线上语义搜索的检索链（RAG Day 17 接入 rerank）：
 * <pre>
 *   rerank( rrf( lexical, vector ), n = 30 )      每层都包 {@link TimedStrategy}，分层计时
 * </pre>
 * 参数都是评测里选定的：RRF 为 Day 11 的 n = 20、k = 10、w = 10（与 {@code RetrievalDetailService}、{@code RagEvalRunner} 默认一致），
 * rerank 为 Day 12 的 voyage rerank-3、候选 30。rerank 失败（2s 超时、429、5xx）退回 RRF 排序，不报错，计数 {@code rerank.fallback}。
 *
 * <h3>只接语义搜索，不接问答的经验类</h3>
 * 经验类的拒答阈值（向量 top-1 余弦 0.40）是按纯向量检索校准的，换成 rerank 分数必须重新校准，
 * 而重新校准要在 train 上重跑——test 集已经报过最终数，不能再动。所以问答的经验类仍走纯向量 top-10，
 * 共鸣类返回的"相关帖子"和 {@code /posts/search/semantic} 走这条链。
 */
@Configuration
public class SearchConfig {

    @Bean
    public RetrievalStrategy semanticSearchStrategy(
            PostLexicalSearchRepository lexicalRepository, PostEmbeddingRepository embeddingRepository,
            EmbeddingClient embeddingClient, Reranker reranker, PostRepository postRepository,
            @Qualifier(AsyncConfig.SEARCH_EXECUTOR) Executor executor, MeterRegistry meters,
            @Value("${search.trgm.threshold:0.05}") double trgmThreshold,
            @Value("${embedding.search.ef-search:100}") int efSearch,
            @Value("${retrieval.rrf.n:20}") int rrfN,
            @Value("${retrieval.rrf.k:10}") int rrfK,
            @Value("${retrieval.rrf.vector-weight:10}") double vectorWeight,
            @Value("${retrieval.rerank.n:30}") int rerankN
    ) {
        RetrievalStrategy lexical = new TimedStrategy(new TsRankStrategy(lexicalRepository, trgmThreshold), "lexical", meters);
        // 向量路的耗时包含 query 向量化（一次 Voyage 往返）
        RetrievalStrategy vector = new TimedStrategy(new VectorStrategy(embeddingRepository, embeddingClient.modelId(),
                embeddingClient::embedQuery, efSearch), "vector", meters);
        RetrievalStrategy rrf = new TimedStrategy(
                new HybridRrfStrategy(lexical, vector, executor, rrfN, rrfK, vectorWeight), "fusion", meters);
        Counter fallbacks = Counter.builder("rerank.fallback")
                .description("rerank 失败退回 RRF 排序的次数").register(meters);
        RerankStrategy rerank = new RerankStrategy(rrf, new TimedReranker(reranker, meters),
                ids -> postTexts(postRepository, ids), rerankN, true, fallbacks::increment);
        return new TimedStrategy(rerank, "total", meters);
    }

    private static Map<Long, String> postTexts(PostRepository postRepository, Iterable<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (Post p : postRepository.findAllById(ids)) {
            out.put(p.getId(), p.getContent());
        }
        return out;
    }

    /** 只量 rerank API 本身的耗时（{@code rerank.latency}），不含第一阶段 */
    static final class TimedReranker implements Reranker {
        private final Reranker delegate;
        private final Timer ok;
        private final Timer error;

        TimedReranker(Reranker delegate, MeterRegistry meters) {
            this.delegate = delegate;
            this.ok = Timer.builder("rerank.latency").tags("outcome", "ok").register(meters);
            this.error = Timer.builder("rerank.latency").tags("outcome", "error").register(meters);
        }

        @Override
        public List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k) {
            Timer.Sample sample = Timer.start();
            try {
                List<ScoredPost> result = delegate.rerank(query, candidates, k);
                sample.stop(ok);
                return result;
            } catch (RuntimeException e) {
                sample.stop(error);
                throw e;
            }
        }

        @Override
        public String modelId() {
            return delegate.modelId();
        }
    }
}
