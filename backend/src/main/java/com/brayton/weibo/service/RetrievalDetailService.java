package com.brayton.weibo.service;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.dto.PostResponse;
import com.brayton.weibo.dto.RetrievalDetailResponse;
import com.brayton.weibo.dto.RetrievalDetailResponse.FusedHit;
import com.brayton.weibo.dto.RetrievalDetailResponse.Hit;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.EmbeddingException;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostLexicalSearchRepository;
import com.brayton.weibo.retrieval.HybridRrfStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检索详情面板（RAG Day 16）：把一次检索拆开给人看——词面路、向量路各自的 top-k，以及 RRF 融合的过程。
 *
 * <h3>和问答的关系</h3>
 * 问答（{@link AskService}）只用向量路，拒答阈值也是按向量 top-1 余弦校准的；RRF 是 Day 11 在评测里选定的混合检索配置，
 * 还没接进问答（Day 17 接 rerank 时一起上，届时阈值要重新校准）。面板把两者都摆出来，并标出向量 top-1 与阈值，
 * 正好能解释"为什么这条拒答了"。融合调的就是 {@link HybridRrfStrategy#fuse}，展示的数字与评测同一套算法。
 *
 * <h3>成本</h3>
 * 每次调用多一次 embedding（问答那次的向量没有缓存可复用）。前端只在用户展开面板时才请求，平时不花这笔钱。
 */
@Slf4j
@Service
public class RetrievalDetailService {

    static final int SNIPPET_LENGTH = 80;

    private final PostLexicalSearchRepository lexicalRepository;
    private final PostEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;
    private final PostService postService;
    private final int n;
    private final int rrfK;
    private final double vectorWeight;
    private final double refusalThreshold;
    private final int askTopK;

    public RetrievalDetailService(
            PostLexicalSearchRepository lexicalRepository, PostEmbeddingRepository embeddingRepository,
            EmbeddingClient embeddingClient, PostService postService,
            // Day 11 选定的配置，见 docs/EVAL.md；与 RagEvalRunner 的默认值一致
            @Value("${retrieval.rrf.n:20}") int n,
            @Value("${retrieval.rrf.k:10}") int rrfK,
            @Value("${retrieval.rrf.vector-weight:10}") double vectorWeight,
            @Value("${ask.refusal.min-top1-score:0.40}") double refusalThreshold,
            @Value("${ask.top-k:10}") int askTopK
    ) {
        this.lexicalRepository = lexicalRepository;
        this.embeddingRepository = embeddingRepository;
        this.embeddingClient = embeddingClient;
        this.postService = postService;
        this.n = n;
        this.rrfK = rrfK;
        this.vectorWeight = vectorWeight;
        this.refusalThreshold = refusalThreshold;
        this.askTopK = askTopK;
    }

    public RetrievalDetailResponse explain(String query, int k, Long selfId) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty() || q.length() > PostService.SEMANTIC_QUERY_MAX_LENGTH) {
            throw new WeiboException(CommonErrorCode.SEARCH_QUERY_INVALID);
        }
        k = Math.max(1, Math.min(k, n));

        boolean chinese = ChineseUtil.containsChinese(q);
        long t0 = System.nanoTime();
        List<ScoredPost> lexical = (chinese
                ? lexicalRepository.searchChinese(q, null, n)
                : lexicalRepository.searchEnglish(q, null, n))
                .stream().map(h -> new ScoredPost(h.postId(), h.score())).toList();
        long t1 = System.nanoTime();

        float[] vector;
        try {
            vector = embeddingClient.embedQuery(q);
        } catch (EmbeddingException e) {
            log.warn("检索详情查询向量化失败：{}", e.getMessage());
            throw new WeiboException(CommonErrorCode.SEMANTIC_SEARCH_UNAVAILABLE);
        }
        long t2 = System.nanoTime();
        List<ScoredPost> vectorHits = embeddingRepository.searchNearest(vector, embeddingClient.modelId(), n).stream()
                .map(h -> new ScoredPost(h.postId(), h.score())).toList();
        long t3 = System.nanoTime();

        List<ScoredPost> fused = HybridRrfStrategy.fuse(List.of(lexical, vectorHits), List.of(1.0, vectorWeight), rrfK, k);

        List<ScoredPost> lexicalTop = lexical.stream().limit(k).toList();
        List<ScoredPost> vectorTop = vectorHits.stream().limit(k).toList();
        Map<Long, PostResponse> posts = loadPosts(lexicalTop, vectorTop, fused, selfId);
        Map<Long, Integer> lexicalRanks = ranks(lexical);
        Map<Long, Integer> vectorRanks = ranks(vectorHits);

        List<FusedHit> fusedHits = new ArrayList<>(fused.size());
        for (int i = 0; i < fused.size(); i++) {
            ScoredPost p = fused.get(i);
            Integer lr = lexicalRanks.get(p.postId());
            Integer vr = vectorRanks.get(p.postId());
            PostResponse post = posts.get(p.postId());
            fusedHits.add(new FusedHit(i + 1, p.postId(), p.score(),
                    lr, lr == null ? 0 : 1.0 / (rrfK + lr),
                    vr, vr == null ? 0 : vectorWeight / (rrfK + vr),
                    author(post), snippet(post)));
        }

        return new RetrievalDetailResponse(
                chinese ? "trigram" : "ts_rank_cd",
                hits(lexicalTop, posts),
                hits(vectorTop, posts),
                fusedHits,
                vectorHits.isEmpty() ? null : vectorHits.get(0).score(),
                new RetrievalDetailResponse.Config(k, n, rrfK, vectorWeight, refusalThreshold, askTopK),
                new RetrievalDetailResponse.Timings(ms(t0, t1), ms(t1, t2), ms(t2, t3)));
    }

    /** 名次从 1 开始；同一帖子只按第一次出现计，与 fuse 的口径一致 */
    static Map<Long, Integer> ranks(List<ScoredPost> ranking) {
        Map<Long, Integer> out = new HashMap<>();
        int rank = 0;
        for (ScoredPost p : ranking) {
            if (!out.containsKey(p.postId())) {
                out.put(p.postId(), ++rank);
            }
        }
        return out;
    }

    private Map<Long, PostResponse> loadPosts(List<ScoredPost> a, List<ScoredPost> b, List<ScoredPost> c, Long selfId) {
        Set<Long> ids = new LinkedHashSet<>();
        for (List<ScoredPost> list : List.of(a, b, c)) {
            list.forEach(p -> ids.add(p.postId()));
        }
        return postService.getPublicPosts(List.copyOf(ids), selfId).stream()
                .collect(Collectors.toMap(PostResponse::getId, Function.identity()));
    }

    private static List<Hit> hits(List<ScoredPost> ranking, Map<Long, PostResponse> posts) {
        List<Hit> out = new ArrayList<>(ranking.size());
        for (int i = 0; i < ranking.size(); i++) {
            ScoredPost p = ranking.get(i);
            PostResponse post = posts.get(p.postId());
            out.add(new Hit(i + 1, p.postId(), p.score(), author(post), snippet(post)));
        }
        return out;
    }

    /** 检索与加载之间被删或改为非公开的帖子取不到，名次照样保留，只是没有作者和摘要 */
    private static String author(PostResponse post) {
        return post == null || post.getUser() == null ? null : post.getUser().getUsername();
    }

    static String snippet(PostResponse post) {
        if (post == null || post.getContent() == null) return null;
        String text = post.getContent().strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) <= SNIPPET_LENGTH) return text;
        return text.substring(0, text.offsetByCodePoints(0, SNIPPET_LENGTH)) + "…";
    }

    private static long ms(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
