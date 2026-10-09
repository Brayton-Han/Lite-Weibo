package com.brayton.weibo.tools;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.embedding.PostChunker;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostEmbeddingRepository.Chunk;
import com.brayton.weibo.service.EmbeddingWriteService;
import com.brayton.weibo.service.EmbeddingWriteService.SyncResult;
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
 * 只体检、不修复：再加 EMBEDDING_BACKFILL_DRY_RUN=true（不调 API、不写库，也不需要 VOYAGE_API_KEY）
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
 * <h3>与增量向量化的关系</h3>
 * 新帖由 EmbeddingWriteService 在发帖后实时向量化，两者共用 {@link PostChunker} 的拼接规则
 * 与 {@link PostEmbeddingRepository} 的写入。增量路径失败只记日志、不重试，
 * 漏掉的帖子再跑一次本工具即可补齐。
 *
 * <h3>先校正，再补缺</h3>
 * 增量路径失败（限流重试耗尽、队列满被丢弃）会留下两类问题，「只补缺」都发现不了：
 * <ul>
 *   <li>残留：帖子已删除或改为非公开，向量还在——检索 SQL 的读时过滤挡得住它本身，
 *       但挡不住转发帖向量里拼着的原帖正文</li>
 *   <li>过期：帖子被编辑过，库里还是旧内容的向量</li>
 * </ul>
 * 所以每次运行先扫一遍已有向量，按当前内容重算 chunk_text 与入库的比对：残留的删除，过期的交给
 * {@link EmbeddingWriteService#syncPost} 重做（与线上同一套锁内复核逻辑）。只比对当前模型的向量，
 * 当前模型还没有向量的帖子属于「缺失」，留给后面的批量补缺——换模型时不会把全库逐条重算一遍。
 *
 * <h3>体检模式（失败可观测）</h3>
 * 增量路径的失败只在 backend 日志里留一行 WARN，日志会滚动，队列满被丢弃的任务甚至不知道是哪条帖子。
 * DRY_RUN 直接以数据库为准，列出缺失 / 残留 / 过期三类各有哪些帖子。判定与修复走同一个
 * {@link #classify}，不在 SQL 里另写一份拼接规则——两份规则迟早漂移，体检结果就不可信了。
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

    private static final int VISIBILITY_PUBLIC = PostEmbeddingRepository.VISIBILITY_PUBLIC;

    /** 有向量（任一模型）的帖子，连同按当前状态重算 chunk_text 所需的字段与当前模型已入库的文本。 */
    private static final String SELECT_EMBEDDED = """
            SELECT e.post_id, p.content, r.content AS ref_content,
                   (p.deleted = false AND p.visibility = ?) AS eligible,
                   cur.chunk_text AS stored
            FROM (SELECT DISTINCT post_id FROM post_embeddings
                  WHERE post_id > ? ORDER BY post_id LIMIT ?) e
            JOIN posts p ON p.id = e.post_id
            LEFT JOIN posts r
                   ON r.id = p.ref_post_id AND r.deleted = false AND r.visibility = ?
            LEFT JOIN post_embeddings cur
                   ON cur.post_id = e.post_id AND cur.chunk_index = 0 AND cur.model_id = ?
            ORDER BY e.post_id
            """;

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

    private final JdbcTemplate jdbc;
    private final EmbeddingClient embeddingClient;
    private final PostEmbeddingRepository embeddingRepository;
    private final EmbeddingWriteService embeddingWriteService;

    @Override
    public void run(String... args) {
        if (!"true".equalsIgnoreCase(System.getenv("EMBEDDING_BACKFILL"))) {
            return;
        }
        String modelId = embeddingClient.modelId();
        int limit = Optional.ofNullable(System.getenv("EMBEDDING_BACKFILL_LIMIT"))
                .filter(v -> !v.isBlank()).map(Integer::parseInt).orElse(Integer.MAX_VALUE);

        boolean dryRun = "true".equalsIgnoreCase(System.getenv("EMBEDDING_BACKFILL_DRY_RUN"));

        System.out.printf("%n🧮 %s：model=%s dim=%d%n",
                dryRun ? "向量体检（只读）" : "开始向量化帖子", modelId, embeddingClient.dimensions());
        checkDimensions();
        Drift drift = scanEmbedded(modelId);

        if (dryRun) {
            report(drift, scanMissing(modelId));
            return;
        }
        reconcile(drift);

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
                String text = PostChunker.chunkText(p.content(), p.refContent());
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

            List<Chunk> chunks = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                chunks.add(new Chunk(ids.get(i), texts.get(i), vectors.get(i)));
            }
            embeddingRepository.insertAll(chunks, modelId);
            written += chunks.size();

            System.out.printf("  ✓ %,5d / %,d  (至 post_id=%d, %.1fs)%n",
                    written, pending, lastId, (System.currentTimeMillis() - t0) / 1000.0);
        }

        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM post_embeddings WHERE model_id = ?", Integer.class, modelId);
        System.out.printf("%n✅ 完成：本次写入 %,d 条 · 跳过纯转发 %d 条 · 该模型累计 %,d 条 · 耗时 %.1fs%n%n",
                written, skipped, total, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** 扫描已有向量，找出残留与过期的帖子。只读。 */
    private Drift scanEmbedded(String modelId) {
        long lastId = 0;
        List<Long> orphans = new ArrayList<>();
        List<Long> stale = new ArrayList<>();

        while (true) {
            List<Embedded> batch = jdbc.query(SELECT_EMBEDDED,
                    (rs, i) -> new Embedded(rs.getLong("post_id"), rs.getString("content"),
                            rs.getString("ref_content"), rs.getBoolean("eligible"), rs.getString("stored")),
                    VISIBILITY_PUBLIC, lastId, BATCH_SIZE * 8, VISIBILITY_PUBLIC, modelId);
            if (batch.isEmpty()) {
                break;
            }
            lastId = batch.get(batch.size() - 1).postId();
            for (Embedded e : batch) {
                switch (classify(e)) {
                    case ORPHAN -> orphans.add(e.postId());
                    case STALE -> stale.add(e.postId());
                    case OK -> { }
                }
            }
        }
        return new Drift(orphans, stale);
    }

    /** 当前模型下缺失向量的帖子（不含纯转发，它们本就不需要向量）。只读。 */
    private List<Long> scanMissing(String modelId) {
        long lastId = 0;
        List<Long> missing = new ArrayList<>();
        while (true) {
            List<Pending> batch = jdbc.query(SELECT_PENDING,
                    (rs, i) -> new Pending(rs.getLong("id"), rs.getString("content"), rs.getString("ref_content")),
                    VISIBILITY_PUBLIC, VISIBILITY_PUBLIC, lastId, modelId, BATCH_SIZE * 8);
            if (batch.isEmpty()) {
                return missing;
            }
            lastId = batch.get(batch.size() - 1).id();
            for (Pending p : batch) {
                if (PostChunker.chunkText(p.content(), p.refContent()) != null) {
                    missing.add(p.id());
                }
            }
        }
    }

    private static void report(Drift drift, List<Long> missing) {
        System.out.println();
        printIds("缺失（应有向量但没有）", missing);
        printIds("残留（已删除 / 非公开，向量还在）", drift.orphans());
        printIds("过期（向量对应的不是当前内容）", drift.stale());
        boolean healthy = missing.isEmpty() && drift.orphans().isEmpty() && drift.stale().isEmpty();
        System.out.println(healthy
                ? "%n✅ 向量与帖子一致%n".formatted()
                : "%n⚠️ 去掉 EMBEDDING_BACKFILL_DRY_RUN 重跑即可修复（需要 VOYAGE_API_KEY）%n".formatted());
    }

    /** 帖子 id 只列前若干个：首次回填前「缺失」是全库，全打出来没有意义。 */
    private static void printIds(String label, List<Long> ids) {
        int shown = Math.min(ids.size(), 20);
        String head = ids.subList(0, shown).toString();
        System.out.printf("  %s：%,d 条%s%n", label, ids.size(),
                ids.isEmpty() ? "" : "  " + head + (ids.size() > shown ? " …" : ""));
    }

    /** 修复扫描结果：残留的删除，过期的重做。失败的帖子不中断，处理完后统一以异常退出。 */
    private void reconcile(Drift drift) {
        System.out.println("  校正已有向量（残留 / 过期）…");
        int removed = 0;
        for (long id : drift.orphans()) {
            removed += embeddingRepository.deleteIfIneligible(id);
        }

        int reembedded = 0;
        int failed = 0;
        for (long id : drift.stale()) {
            SyncResult r = embeddingWriteService.syncPost(id);
            reembedded += r.embedded();
            removed += r.removed();
            failed += r.failed();
        }
        System.out.printf("  ✓ 删除残留向量 %d 行 · 过期 %d 条，重做 %d 条%n", removed, drift.stale().size(), reembedded);
        if (failed > 0) {
            throw new IllegalStateException(failed + " 条过期向量重做失败（见上方 WARN 日志），重跑即可");
        }
    }

    enum Verdict { OK, ORPHAN, STALE }

    /**
     * ORPHAN：帖子已不需要向量（已删除 / 非 PUBLIC / 纯转发），却还有向量；
     * STALE：当前模型的向量对应的不是当前内容。当前模型没有向量（stored 为 null）不算过期。
     */
    static Verdict classify(Embedded e) {
        String expected = e.eligible() ? PostChunker.chunkText(e.content(), e.refContent()) : null;
        if (expected == null) {
            return Verdict.ORPHAN;
        }
        return e.stored() != null && !e.stored().equals(expected) ? Verdict.STALE : Verdict.OK;
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

    record Pending(long id, String content, String refContent) {}

    record Embedded(long postId, String content, String refContent, boolean eligible, String stored) {}

    record Drift(List<Long> orphans, List<Long> stale) {}
}
