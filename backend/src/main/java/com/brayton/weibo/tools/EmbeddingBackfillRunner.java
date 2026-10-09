package com.brayton.weibo.tools;

import com.brayton.weibo.embedding.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 帖子全量向量化（RAG Day 5）：把尚无当前模型向量的帖子送去 embedding，写入 post_embeddings。
 *
 * 触发方式（一次性，跑完即退出）：
 *   EMBEDDING_BACKFILL=true VOYAGE_API_KEY=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools
 * 先小规模试跑：再加 EMBEDDING_BACKFILL_LIMIT=10
 *
 * <h3>可安全重跑、可断点续跑</h3>
 * 只选「当前 model_id 下还没有向量」的帖子，写入靠唯一约束 + ON CONFLICT DO NOTHING 兜底。
 * 不包整体事务：每批写完即提交。中途被限流打断或手动停掉，已完成的批次不丢，重跑从断点继续。
 * 失败时异常照常抛出，让进程以非零码退出——不吞掉错误假装成功。
 *
 * <h3>只向量化 PUBLIC 帖子</h3>
 * 站内搜索本就只返回 PUBLIC 帖子；仅好友/私密内容没有理由发给第三方 embedding 服务。
 * 帖子改可见性后的向量增删属于 Day 6 的生命周期处理。
 *
 * <h3>chunk_text 怎么拼</h3>
 * 一帖一个 chunk（chunk_index = 0）：
 * <ul>
 *   <li>原创帖：正文</li>
 *   <li>转发并评论：评论 + 原帖正文。「转发并评论」的语义由两段共同构成，只 embed 评论
 *       （常常是「好烦啊」这类短句）会丢掉上下文</li>
 *   <li>纯转发（自身正文为空）：跳过。它的内容就是原帖，原帖已有向量，再 embed 一份
 *       只会让同一段文字在检索结果里出现两次</li>
 * </ul>
 * 原帖已删除或非 PUBLIC 时，只用评论本身，不把它的内容带出去。
 */
@Component
@Profile("tools")
@Order(EmbeddingBackfillRunner.ORDER)
@RequiredArgsConstructor
public class EmbeddingBackfillRunner implements CommandLineRunner {

    /** 排在语料导入（100）之后、ToolsShutdownRunner（LOWEST_PRECEDENCE）之前，原因见后者注释。 */
    static final int ORDER = 200;

    /** 与 VoyageEmbeddingClient 的内部拆批一致，一批恰好对应一次 API 调用。 */
    private static final int BATCH_SIZE = 128;

    private static final int VISIBILITY_PUBLIC = 0;   // PostVisibility.PUBLIC（ORDINAL）

    private static final String SELECT_PENDING = """
            SELECT p.id, p.content, r.content AS ref_content
            FROM posts p
            LEFT JOIN posts r
                   ON r.id = p.ref_post_id AND r.deleted = false AND r.visibility = ?
            WHERE p.deleted = false
              AND p.visibility = ?
              AND p.id > ?
              AND NOT EXISTS (SELECT 1 FROM post_embeddings e
                              WHERE e.post_id = p.id AND e.model_id = ?)
            ORDER BY p.id
            LIMIT ?
            """;

    private static final String INSERT = """
            INSERT INTO post_embeddings (post_id, chunk_index, chunk_text, embedding, model_id)
            VALUES (?, 0, ?, ?::vector, ?)
            ON CONFLICT (post_id, chunk_index, model_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(System.getenv("EMBEDDING_BACKFILL"))) {
            return;
        }
        String modelId = embeddingClient.modelId();
        int limit = Optional.ofNullable(System.getenv("EMBEDDING_BACKFILL_LIMIT"))
                .filter(v -> !v.isBlank()).map(Integer::parseInt).orElse(Integer.MAX_VALUE);

        System.out.printf("%n🧮 开始向量化帖子：model=%s dim=%d%n",
                modelId, embeddingClient.dimensions());
        checkDimensions();

        Integer pending = jdbc.queryForObject("""
                SELECT count(*) FROM posts p
                WHERE p.deleted = false AND p.visibility = ?
                  AND btrim(p.content) <> ''   -- 纯转发永远会被跳过，不计入待处理
                  AND NOT EXISTS (SELECT 1 FROM post_embeddings e
                                  WHERE e.post_id = p.id AND e.model_id = ?)
                """, Integer.class, VISIBILITY_PUBLIC, modelId);
        System.out.printf("  待处理 %,d 条%s%n", pending,
                limit < Integer.MAX_VALUE ? "（本次上限 " + limit + "）" : "");

        long t0 = System.currentTimeMillis();
        long lastId = 0;
        int written = 0;
        int skipped = 0;

        while (written < limit) {
            List<Pending> batch = jdbc.query(SELECT_PENDING,
                    (rs, i) -> new Pending(rs.getLong("id"), rs.getString("content"), rs.getString("ref_content")),
                    VISIBILITY_PUBLIC, VISIBILITY_PUBLIC, lastId, modelId,
                    Math.min(BATCH_SIZE, limit - written));
            if (batch.isEmpty()) {
                break;
            }
            // 按 id 推进游标，而不是依赖 NOT EXISTS 重新筛：被跳过的纯转发永远不会有向量，
            // 不推进游标就会被反复选中、死循环
            lastId = batch.get(batch.size() - 1).id();

            List<Long> ids = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (Pending p : batch) {
                String text = chunkText(p);
                if (text == null) {
                    skipped++;
                    continue;
                }
                ids.add(p.id());
                texts.add(text);
            }
            if (texts.isEmpty()) {
                continue;
            }

            List<float[]> vectors;
            try {
                vectors = embeddingClient.embedDocuments(texts);
            } catch (RuntimeException e) {
                System.out.printf("%n❌ 在 post_id > %d 的批次失败；已写入 %,d 条不受影响，重跑即从断点继续%n",
                        batch.get(0).id() - 1, written);
                throw e;
            }

            List<Object[]> rows = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                rows.add(new Object[]{ids.get(i), texts.get(i), toPgVector(vectors.get(i)), modelId});
            }
            jdbc.batchUpdate(INSERT, rows);
            written += rows.size();

            System.out.printf("  ✓ %,5d / %,d  (至 post_id=%d, %.1fs)%n",
                    written, pending, lastId, (System.currentTimeMillis() - t0) / 1000.0);
        }

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM post_embeddings WHERE model_id = ?", Integer.class, modelId);
        System.out.printf("%n✅ 完成：本次写入 %,d 条 · 跳过纯转发 %d 条 · 该模型累计 %,d 条 · 耗时 %.1fs%n%n",
                written, skipped, total, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /**
     * 在花任何 API 费用之前确认维度一致。不查的话，失配要到 INSERT 时才由 pgvector 报错，
     * 那时这一批的 embedding 已经付过钱了。对 vector(N) 列，atttypmod 就是 N。
     */
    private void checkDimensions() {
        Integer column = jdbc.queryForObject("""
                SELECT atttypmod FROM pg_attribute
                WHERE attrelid = 'post_embeddings'::regclass AND attname = 'embedding'
                """, Integer.class);
        if (column == null || column != embeddingClient.dimensions()) {
            throw new IllegalStateException("维度不一致：post_embeddings.embedding 为 vector(" + column
                    + ")，而 " + embeddingClient.modelId() + " 配置为 " + embeddingClient.dimensions()
                    + "。改 embedding.voyage.dimensions，或新写迁移修改列类型并重建 HNSW 索引");
        }
    }

    /** 返回 null 表示该帖不需要向量（纯转发）。 */
    static String chunkText(Pending p) {
        String own = p.content() == null ? "" : p.content().strip();
        if (own.isEmpty()) {
            return null;
        }
        String ref = p.refContent() == null ? "" : p.refContent().strip();
        return ref.isEmpty() ? own : own + "\n\n[转发原文] " + ref;
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

    record Pending(long id, String content, String refContent) {}
}
