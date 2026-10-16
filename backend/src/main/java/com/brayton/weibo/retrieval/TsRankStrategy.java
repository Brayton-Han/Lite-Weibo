package com.brayton.weibo.retrieval;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 词面检索 + 相关性排序：在 {@link LikeStrategy} 的基础上只改「怎么排」，不引入任何语义能力。
 * 分支条件与线上搜索相同（含汉字走中文路）：
 * <ul>
 *   <li>中文：pg_trgm {@code word_similarity(q, content)}——query 与帖子中最相似的那一段的 trigram 相似度。
 *       不用 {@code similarity()}：它拿短 query 与整条长帖比，帖子越长分数越低，排序会偏向短帖。
 *       与 Day 8 候选池的 trgm 一路同一口径</li>
 *   <li>英文：{@code ts_rank(search_vector, plainto_tsquery('simple', q))}，命中条件与 {@code searchPosts} 相同</li>
 * </ul>
 *
 * 中文路目前是全表算分、不设阈值，每条可见帖子都有分数——3000 条语料下延迟可接受。
 * Day 10 再改成 {@code %} 操作符走 GIN 索引并调 similarity_threshold。
 */
@RequiredArgsConstructor
public class TsRankStrategy implements RetrievalStrategy {

    private static final String TRGM = """
            SELECT p.id, word_similarity(?, p.content) AS score
            FROM posts p
            WHERE p.deleted = false AND p.visibility = ?
            ORDER BY score DESC, p.id
            LIMIT ?
            """;

    private static final String TS_RANK = """
            SELECT p.id, ts_rank(p.search_vector, plainto_tsquery('simple', ?)) AS score
            FROM posts p
            WHERE p.deleted = false AND p.visibility = ?
              AND p.search_vector @@ plainto_tsquery('simple', ?)
            ORDER BY score DESC, p.id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    @Override
    public String name() {
        return "tsrank";
    }

    @Override
    public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
        int pub = PostEmbeddingRepository.VISIBILITY_PUBLIC;
        return ChineseUtil.containsChinese(query)
                ? jdbc.query(TRGM, (rs, i) -> new ScoredPost(rs.getLong("id"), rs.getDouble("score")), query, pub, k)
                : jdbc.query(TS_RANK, (rs, i) -> new ScoredPost(rs.getLong("id"), rs.getDouble("score")),
                        query, pub, query, k);
    }
}
