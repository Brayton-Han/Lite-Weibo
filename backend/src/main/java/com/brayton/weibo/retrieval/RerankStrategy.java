package com.brayton.weibo.retrieval;

import com.brayton.weibo.rerank.RerankCandidate;
import com.brayton.weibo.rerank.RerankException;
import com.brayton.weibo.rerank.Reranker;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 两阶段检索（RAG Day 12）：base 策略召回 top-N 候选，{@link Reranker} 精排后取 top-k。
 *
 * <h3>候选深度</h3>
 * 送进 reranker 的是 base 的前 {@code max(n, k)} 条：k 超过 N 时（评测剔除非语料帖后的加深重取）跟着加深，
 * 与 {@link HybridRrfStrategy} 同一约定。重排只能调整候选内部的顺序，base 的 Recall@N 就是重排后 Recall@k 的上限。
 * 注意深度一变候选集就变，重排后的前几名也可能跟着变——这一点比 RRF 明显，评测按深度 k 计时与打分，加深只是为了凑满 k 条语料帖。
 *
 * <h3>正文</h3>
 * {@code texts} 按 id 批量取正文（一次查询，不要 N+1）。取不到的帖子（召回之后被删）直接丢掉；
 * 正文为空的帖子（纯图片、纯转发）没法让 cross-encoder 打分，不送进去，按 base 名次排在重排结果之后。
 *
 * <h3>降级</h3>
 * {@code fallbackOnError = true} 时 reranker 抛 {@link RerankException} 就返回 base 的前 k 条并打 warn——
 * 重排是锦上添花，外部 API 挂了不能把搜索整个打挂。评测传 false：静默降级会让指标混进 base 的结果，必须失败得明明白白。
 *
 * score 字段是 reranker 的分数（降级时是 base 的分数），与其他策略不可比，见 {@link ScoredPost}。
 */
@Slf4j
public class RerankStrategy implements RetrievalStrategy {

    private final RetrievalStrategy base;
    private final Reranker reranker;
    private final Function<Collection<Long>, Map<Long, String>> texts;
    private final int n;
    private final boolean fallbackOnError;

    /**
     * @param texts 按 id 批量取正文；返回的 map 里缺的 id 视为已删除
     * @param n     候选深度（top-N），Day 12 默认 30
     */
    public RerankStrategy(RetrievalStrategy base, Reranker reranker,
                          Function<Collection<Long>, Map<Long, String>> texts, int n, boolean fallbackOnError) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive, got " + n);
        }
        this.base = base;
        this.reranker = reranker;
        this.texts = texts;
        this.n = n;
        this.fallbackOnError = fallbackOnError;
    }

    @Override
    public String name() {
        return "rerank";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        List<ScoredPost> first = base.retrieve(query, Math.max(n, k), requesterId);
        if (first.isEmpty() || k <= 0) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        first.forEach(p -> ids.add(p.postId()));
        Map<Long, String> byId = texts.apply(ids);

        List<RerankCandidate> candidates = new ArrayList<>(ids.size());
        List<ScoredPost> blank = new ArrayList<>();
        List<ScoredPost> kept = new ArrayList<>(ids.size());
        Set<Long> seen = new LinkedHashSet<>();
        for (ScoredPost p : first) {
            String text = byId.get(p.postId());
            if (text == null || !seen.add(p.postId())) {
                continue;
            }
            kept.add(p);
            if (text.isBlank()) {
                blank.add(p);
            } else {
                candidates.add(new RerankCandidate(p.postId(), text));
            }
        }

        List<ScoredPost> reranked;
        try {
            reranked = reranker.rerank(query, candidates, k);
        } catch (RerankException e) {
            if (!fallbackOnError) {
                throw e;
            }
            log.warn("rerank 失败，降级为第一阶段排序：{}", e.getMessage());
            return kept.stream().limit(k).toList();
        }
        if (reranked.size() >= k || blank.isEmpty()) {
            return reranked;
        }
        List<ScoredPost> out = new ArrayList<>(reranked);
        blank.stream().limit(k - reranked.size()).forEach(out::add);
        return out;
    }
}
