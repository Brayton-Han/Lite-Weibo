package com.brayton.weibo.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 点赞计数的批量读写，供 {@code LikeCountService} 重算 Redis 计数、异步写回 posts.like_count。
 * likes 表的行是点赞数的唯一真相，posts.like_count 只是它的异步副本。
 */
@Repository
@RequiredArgsConstructor
public class LikeCountRepository {

    private final JdbcTemplate jdbc;

    /** 按 likes 表精确计数（走 idx_likes_post）；没有点赞的帖子返回 0 */
    public Map<Long, Long> countByPostIds(Collection<Long> postIds) {
        Map<Long, Long> counts = new HashMap<>();
        if (postIds.isEmpty()) return counts;
        for (Long id : postIds) counts.put(id, 0L);

        jdbc.query(
                "SELECT post_id, count(*) FROM likes WHERE post_id = ANY(?) GROUP BY post_id",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", postIds.toArray())),
                rs -> {
                    counts.put(rs.getLong(1), rs.getLong(2));
                });
        return counts;
    }

    /** 以绝对值写回，重复执行结果相同；值没变的行不写，避免无谓的行锁和 WAL */
    public void updateLikeCounts(Map<Long, Long> counts) {
        if (counts.isEmpty()) return;
        List<Object[]> args = counts.entrySet().stream()
                .map(e -> new Object[]{e.getValue(), e.getKey(), e.getValue()})
                .toList();
        jdbc.batchUpdate(
                "UPDATE posts SET like_count = ? WHERE id = ? AND like_count IS DISTINCT FROM ?",
                args);
    }
}
