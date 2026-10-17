package com.brayton.weibo.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 词面搜索按相关性排序，替代原来的「命中即可、按 created_at DESC 排」（RAG Day 10）。
 * 线上 {@code /posts/search} 与评测的 {@code TsRankStrategy} 共用这里，评测量到的就是线上的排序。
 * 两路都只检索 PUBLIC、未删除的帖子。
 *
 * <h3>中文路：word_similarity 而不是 similarity</h3>
 * {@code similarity()} 拿短 query 与整条帖子比，帖子越长分数越低，排序偏向短帖；
 * {@code word_similarity(q, content)} 取 query 与帖子中最相似的那一段。命中条件用对应的 {@code q <% content}，
 * 同样能走 {@code idx_posts_content_trgm}（gin_trgm_ops）。
 *
 * <h3>中文路：阈值不能用默认值</h3>
 * {@code pg_trgm.word_similarity_threshold} 默认 0.6，对中文基本等于不返回结果：中文词之间没有空格，
 * query 首尾那几个带词边界填充的 trigram 在正文中间永远匹配不上——原样出现在帖子里的「考核期」也只有 0.5 分。
 * 阈值是硬截断：低于它的帖子不会被返回，调高换索引选择性、调低换召回。
 * 在事务内 {@code set_config(..., true)}（即 SET LOCAL），理由同 {@link PostEmbeddingRepository#searchNearest}。
 *
 * <h3>英文路：ts_rank_cd 而不是 ts_rank</h3>
 * 命中条件不变（{@code search_vector @@ plainto_tsquery('simple', q)}，走 {@code idx_post_search}）。
 * {@code ts_rank_cd}（cover density）在词频之外还看 query 各词在帖子里挨得多近：多词 query 的词聚在一起的帖子排前面。
 * 不做长度归一化（normalization = 0），微博正文都短，长度差异不大。
 *
 * <h3>翻页</h3>
 * 按 (score DESC, id ASC) 做 keyset。游标仍是上一页最后一条的 id（前端不用改）：同一 query 下分数可由 id 重算，
 * 两个打分函数都是确定性的，重算值与上一页完全相等。游标帖子在两页之间被硬删除时下一页为空（软删除不受影响）。
 */
@Repository
@RequiredArgsConstructor
public class PostLexicalSearchRepository {

    private static final String TRGM_SCORE = "word_similarity(?, %s)";
    private static final String TRGM_MATCH = "? <% p.content";

    private static final String TS_SCORE = "ts_rank_cd(%s, plainto_tsquery('simple', ?))";
    private static final String TS_MATCH = "p.search_vector @@ plainto_tsquery('simple', ?)";

    private static final String CHINESE = first(TRGM_SCORE.formatted("p.content"), TRGM_MATCH);
    private static final String CHINESE_AFTER = after(TRGM_SCORE, "content", TRGM_MATCH);
    private static final String ENGLISH = first(TS_SCORE.formatted("p.search_vector"), TS_MATCH);
    private static final String ENGLISH_AFTER = after(TS_SCORE, "search_vector", TS_MATCH);

    private static final RowMapper<Hit> HIT = (rs, i) -> new Hit(rs.getLong("id"), rs.getDouble("score"));

    private final JdbcTemplate jdbc;

    /**
     * 中文路的 word_similarity 下限。0.05 来自 Day 10 在 train 上的扫参（docs/EVAL.md）：
     * 16 个 trigram 以内的 query 最小非零分 ≥ 1/16，0.05 实际等于「至少共享一个 trigram」，
     * 指标与不设阈值的全表算分完全相同，而候选中位数只剩约 1.5%；再往上每一档都以召回为代价。
     */
    @Value("${search.trgm.threshold:0.05}")
    private double threshold;

    /** 线上路径，阈值取配置值。 */
    @Transactional(readOnly = true)
    public List<Hit> searchChinese(String query, Long lastId, int limit) {
        return searchChinese(query, lastId, limit, threshold);
    }

    /**
     * 指定阈值的版本，供评测扫参。
     *
     * @param lastId 上一页最后一条的 id；null 表示第一页
     */
    @Transactional(readOnly = true)
    public List<Hit> searchChinese(String query, Long lastId, int limit, double threshold) {
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold 应在 [0, 1]，实际 " + threshold);
        }
        jdbc.queryForObject("SELECT set_config('pg_trgm.word_similarity_threshold', ?, true)",
                String.class, String.valueOf(threshold));
        return search(CHINESE, CHINESE_AFTER, query, lastId, limit);
    }

    /** @param lastId 上一页最后一条的 id；null 表示第一页 */
    public List<Hit> searchEnglish(String query, Long lastId, int limit) {
        return search(ENGLISH, ENGLISH_AFTER, query, lastId, limit);
    }

    /** 两组 SQL 的占位符顺序相同，见 {@link #first} / {@link #after}。 */
    private List<Hit> search(String first, String after, String query, Long lastId, int limit) {
        int pub = PostEmbeddingRepository.VISIBILITY_PUBLIC;
        return lastId == null
                ? jdbc.query(first, HIT, query, pub, query, limit)
                : jdbc.query(after, HIT, query, query, lastId, pub, query, query, query, lastId, limit);
    }

    /** 占位符：score 的 q、visibility、match 的 q、limit。 */
    private static String first(String score, String match) {
        return """
                SELECT p.id, %s AS score
                FROM posts p
                WHERE p.deleted = false AND p.visibility = ?
                  AND %s
                ORDER BY score DESC, p.id
                LIMIT ?
                """.formatted(score, match);
    }

    /** 占位符：score 的 q、游标分数的 q、lastId、visibility、match 的 q、两处比较的 q、lastId、limit。 */
    private static String after(String scoreTemplate, String column, String match) {
        String score = scoreTemplate.formatted("p." + column);
        return """
                SELECT p.id, %s AS score
                FROM posts p, (SELECT %s AS s FROM posts WHERE id = ?) c
                WHERE p.deleted = false AND p.visibility = ?
                  AND %s
                  AND (%s < c.s OR (%s = c.s AND p.id > ?))
                ORDER BY score DESC, p.id
                LIMIT ?
                """.formatted(score, scoreTemplate.formatted(column), match, score, score);
    }

    /** score 为该路自己的分数：中文 word_similarity ∈ [0, 1]，英文 ts_rank_cd 无上界，两者不可比。 */
    public record Hit(long postId, double score) {}
}
