package com.brayton.weibo.repository;

import com.brayton.weibo.embedding.PostChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * post_embeddings 的读写。Hibernate 不认识 vector 类型，所以没有 JPA 实体，一律走 JdbcTemplate；
 * 也因此 Post 上的 @Where(deleted = false) 管不到这里，涉及 posts 的 SQL 要手写软删除条件。
 */
@Repository
@RequiredArgsConstructor
public class PostEmbeddingRepository {

    public static final int VISIBILITY_PUBLIC = 0;   // PostVisibility.PUBLIC（ORDINAL）

    /** 与回填的选取条件一致：帖子本身 PUBLIC 且未删；原帖已删或非 PUBLIC 时 ref_content 为 NULL。 */
    private static final String SELECT_SOURCE = """
            SELECT p.content, r.content AS ref_content
            FROM posts p
            LEFT JOIN posts r
                   ON r.id = p.ref_post_id AND r.deleted = false AND r.visibility = ?
            WHERE p.id = ? AND p.deleted = false AND p.visibility = ?
            """;

    private static final String INSERT = """
            INSERT INTO post_embeddings (post_id, chunk_index, chunk_text, embedding, model_id)
            VALUES (?, 0, ?, ?::vector, ?)
            ON CONFLICT (post_id, chunk_index, model_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    /**
     * 按当前库里的状态算出该帖的 chunk_text。
     * 为空表示不需要向量：帖子不存在、已删除、非 PUBLIC，或是纯转发。
     */
    public Optional<String> findChunkText(long postId) {
        List<String> texts = jdbc.query(SELECT_SOURCE,
                (rs, i) -> PostChunker.chunkText(rs.getString("content"), rs.getString("ref_content")),
                VISIBILITY_PUBLIC, postId, VISIBILITY_PUBLIC);
        return texts.isEmpty() ? Optional.empty() : Optional.ofNullable(texts.get(0));
    }

    /** 批量写入；已存在的 (post_id, chunk_index, model_id) 跳过，重跑与并发写都安全。 */
    public void insertAll(List<Chunk> chunks, String modelId) {
        List<Object[]> rows = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            rows.add(new Object[]{c.postId(), c.text(), toPgVector(c.vector()), modelId});
        }
        jdbc.batchUpdate(INSERT, rows);
    }

    /** pgvector 的文本格式 [x1,x2,...]；SQL 侧用 ?::vector 转换，免引 pgvector-java 依赖。 */
    static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);   // Float.toString 可能输出 1.0E-5 这样的科学计数法，pgvector 能解析
        }
        return sb.append(']').toString();
    }

    public record Chunk(long postId, String text, float[] vector) {}
}
