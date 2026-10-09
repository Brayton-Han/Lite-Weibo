package com.brayton.weibo.repository;

import com.brayton.weibo.embedding.PostChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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

    /** 「转发并评论」了该帖、且自身需要向量的帖子：它们的 chunk_text 里含有该帖正文。 */
    private static final String SELECT_QUOTING = """
            SELECT id FROM posts
            WHERE ref_post_id = ? AND deleted = false AND visibility = ? AND btrim(content) <> ''
            """;

    private static final String SELECT_STORED = """
            SELECT chunk_text FROM post_embeddings
            WHERE post_id = ? AND chunk_index = 0 AND model_id = ?
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

    public List<Long> findQuotingPostIds(long postId) {
        return jdbc.queryForList(SELECT_QUOTING, Long.class, postId, VISIBILITY_PUBLIC);
    }

    /** 当前模型下已入库的 chunk_text，用来判断帖子内容是否变了、要不要重新花 API 费用。 */
    public Optional<String> findStoredChunkText(long postId, String modelId) {
        return jdbc.queryForList(SELECT_STORED, String.class, postId, modelId).stream().findFirst();
    }

    /**
     * 用新向量替换该帖的全部旧向量，前提是帖子当前的 chunk_text 仍等于 {@code expectedText}。
     *
     * <h3>为什么要锁行并复核</h3>
     * 向量化在线程池里并发执行，同一条帖子连续编辑两次会产生两个任务；
     * 如果先读到旧内容的任务后写入，库里就会留下过期向量。这里锁住 posts 行（与编辑的 UPDATE 互斥），
     * 在锁内重算 chunk_text：已经变了说明之后还有一次编辑，它提交后会发出自己的事件，本次直接放弃。
     *
     * <h3>为什么删掉所有模型的旧向量</h3>
     * 内容变了，其他模型的向量也随之过期。过期比缺失更糟：缺失会被该模型的回填选中补齐，过期不会。
     *
     * @return false 表示内容在向量化期间又变了，没有写入
     */
    @Transactional
    public boolean replaceIfCurrent(long postId, String expectedText, float[] vector, String modelId) {
        lockPost(postId);
        if (!Objects.equals(findChunkText(postId).orElse(null), expectedText)) {
            return false;
        }
        jdbc.update("DELETE FROM post_embeddings WHERE post_id = ?", postId);
        insertAll(List.of(new Chunk(postId, expectedText, vector)), modelId);
        return true;
    }

    /**
     * 帖子已不需要向量（改为非公开、变成纯转发等）时删除它的全部向量。
     * 锁内复核的理由同 {@link #replaceIfCurrent}：防止与随后一次「改回公开」的任务交错，误删新向量。
     *
     * @return 删除的行数
     */
    @Transactional
    public int deleteIfIneligible(long postId) {
        lockPost(postId);
        if (findChunkText(postId).isPresent()) {
            return 0;
        }
        return jdbc.update("DELETE FROM post_embeddings WHERE post_id = ?", postId);
    }

    /** 帖子行不存在时查询为空、不加锁，不影响后续逻辑：此时它一定不需要向量。 */
    private void lockPost(long postId) {
        jdbc.queryForList("SELECT id FROM posts WHERE id = ? FOR UPDATE", Long.class, postId);
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
