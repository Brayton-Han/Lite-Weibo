package com.brayton.weibo.retrieval;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.repository.PostLexicalSearchRepository;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 词面检索 + 相关性排序：在 {@link LikeStrategy} 的基础上只改「怎么排」，不引入任何语义能力。
 * 与线上 {@code /posts/search} 调同一个 {@link PostLexicalSearchRepository}，分支条件也相同（含汉字走中文路）：
 * <ul>
 *   <li>中文：pg_trgm {@code word_similarity} 排序、{@code <%} 过滤走 GIN 索引，阈值由构造参数指定</li>
 *   <li>英文：{@code ts_rank_cd(search_vector, plainto_tsquery('simple', q))} 排序，命中条件与原来的 {@code searchPosts} 相同</li>
 * </ul>
 */
@RequiredArgsConstructor
public class TsRankStrategy implements RetrievalStrategy {

    private final PostLexicalSearchRepository lexicalRepository;
    private final double trgmThreshold;

    @Override
    public String name() {
        return "tsrank";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        List<PostLexicalSearchRepository.Hit> hits = ChineseUtil.containsChinese(query)
                ? lexicalRepository.searchChinese(query, null, k, trgmThreshold)
                : lexicalRepository.searchEnglish(query, null, k);
        return hits.stream().map(h -> new ScoredPost(h.postId(), h.score())).toList();
    }
}
