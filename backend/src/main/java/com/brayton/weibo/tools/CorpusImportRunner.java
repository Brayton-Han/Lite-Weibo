package com.brayton.weibo.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * RAG 评测语料导入器（Day 3 Step 4）。
 *
 * 把 corpus/manifest.jsonl（靶区 2100 条）与 corpus/noise.jsonl（噪声 900 条）
 * 导入 posts，并在 corpus_meta 中登记分层元数据。
 *
 * 触发方式（一次性，跑完即退出）：
 *   CORPUS_IMPORT=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools
 *
 * 退出依赖 application-tools.yml 里的 {@code web-application-type: none}——
 * 不能像 FeedBackfillRunner 那样在 run() 里调 System.exit(0)：那个工具是 readOnly
 * 事务，直接退出无妨；本类是读写事务，在 @Transactional 方法内退出会在提交前杀掉 JVM。
 *
 * 导入后必须再跑一次 feed 回灌，否则前端看不到这些帖子：
 *   FEED_BACKFILL=true ./mvnw spring-boot:run
 *
 * <h3>为什么用 JdbcTemplate 而不是 JPA</h3>
 * {@code Post.createdAt} 上有 {@code @CreationTimestamp}，经 JPA 保存会被强制改写为
 * 当前时间，而本工具需要把时间戳打散到过去半年——否则「按时间排序」的 baseline
 * 会退化成「按导入顺序排序」，Day 9 的对照失去意义。直接走 SQL 绕开该生命周期回调。
 *
 * <h3>为什么挂 {@code @Profile("tools")}</h3>
 * 比在 run() 里判断环境变量更强：不激活该 profile 时这个 Bean 根本不会被实例化。
 * 避免重蹈分析报告 §6.3-29「压测代码常驻 src/main」的覆辙。
 */
@Component
@Profile("tools")
@Order(CorpusImportRunner.ORDER)
@RequiredArgsConstructor
public class CorpusImportRunner implements CommandLineRunner {

    /**
     * 必须显式指定，且要优先于 {@link ToolsShutdownRunner}。
     *
     * 未标 {@code @Order} 的 Bean 默认就是 {@code Ordered.LOWEST_PRECEDENCE}，
     * 与关闭器同级时执行顺序退化为不确定的 Bean 定义顺序——实测出现过关闭器先跑、
     * 导入器根本没执行、而日志看起来一切正常的情况。
     */
    static final int ORDER = 100;

    private static final int    USER_COUNT      = 40;
    private static final int    FOLLOW_MIN      = 12;
    private static final int    FOLLOW_MAX      = 20;
    private static final int    SPREAD_DAYS     = 180;   // 时间戳打散范围
    private static final long   SEED            = 20260918L;
    private static final String USER_PREFIX     = "corpus_u";
    private static final String DEFAULT_PASSWORD = "corpus123";

    // posts 的枚举列：visibility / status 是 ORDINAL（smallint），type 是 STRING
    private static final int VISIBILITY_PUBLIC = 0;   // PostVisibility.PUBLIC
    private static final int STATUS_NORMAL     = 0;   // PostStatus.NORMAL

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @Transactional
    public void run(String... args) throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("CORPUS_IMPORT"))) {
            return;
        }
        long t0 = System.currentTimeMillis();
        System.out.println("\n📥 开始导入 RAG 评测语料...\n");

        Path dir = Path.of(Optional.ofNullable(System.getenv("CORPUS_DIR")).orElse("../corpus"));
        List<JsonNode> rows = new ArrayList<>();
        rows.addAll(readJsonl(dir.resolve("manifest.jsonl")));
        rows.addAll(readJsonl(dir.resolve("noise.jsonl")));
        System.out.printf("  读取 %,d 条%n", rows.size());
        summarize(rows);

        if (!ensureEmpty()) return;

        Random rng = new Random(SEED);
        List<Long> userIds = createUsers();
        int follows = createFollows(userIds, rng);
        int posts = insertPosts(rows, userIds, rng);

        System.out.printf("%n✅ 完成：用户 %d · 关注关系 %d · 帖子 %,d · 耗时 %.1fs%n",
                userIds.size(), follows, posts, (System.currentTimeMillis() - t0) / 1000.0);
        System.out.println("\n⚠️ 下一步必须跑 feed 回灌，否则前端看不到这些帖子：");
        System.out.println("   FEED_BACKFILL=true ./mvnw spring-boot:run\n");
    }

    // ---------------------------------------------------------------- 读取

    private List<JsonNode> readJsonl(Path p) throws IOException {
        if (!Files.exists(p)) {
            throw new IllegalStateException("找不到语料文件：" + p.toAbsolutePath()
                    + "（可用 CORPUS_DIR 指定目录）");
        }
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(p)) {
            if (!line.isBlank()) out.add(mapper.readTree(line));
        }
        return out;
    }

    private void summarize(List<JsonNode> rows) {
        Map<String, Integer> byLayer = new TreeMap<>();
        for (JsonNode r : rows) byLayer.merge(r.get("layer").asText(), 1, Integer::sum);
        byLayer.forEach((k, v) -> System.out.printf("    %-12s %,5d%n", k, v));
    }

    /** 已导入过就拒绝，避免重复灌入。重导请先手动清理，工具不代做破坏性操作。 */
    private boolean ensureEmpty() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM corpus_meta", Integer.class);
        if (n != null && n > 0) {
            System.out.printf("%n⚠️ corpus_meta 已有 %,d 条记录，已导入过。%n", n);
            System.out.println("   如需重导，先执行（会级联删除对应 posts）：");
            System.out.println("   DELETE FROM posts WHERE id IN (SELECT post_id FROM corpus_meta);");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 写入

    /** 建 40 个承载语料的测试用户。已存在则复用，保证可重入。 */
    private List<Long> createUsers() {
        String hash = passwordEncoder.encode(DEFAULT_PASSWORD);
        List<Long> ids = new ArrayList<>(USER_COUNT);
        for (int i = 1; i <= USER_COUNT; i++) {
            String username = USER_PREFIX + String.format("%02d", i);
            Long existing = jdbc.query("SELECT id FROM users WHERE username = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, username);
            if (existing != null) { ids.add(existing); continue; }

            ids.add(jdbc.queryForObject("""
                    INSERT INTO users (username, email, password_hashed, gender, bio,
                                       follower_count, follow_count, created_at, updated_at)
                    VALUES (?, ?, ?, 0, ?, 0, 0, now(), now())
                    RETURNING id
                    """, Long.class,
                    username, username + "@corpus.invalid", hash,
                    "RAG 评测语料账号 · 非真实用户"));
        }
        System.out.printf("  用户 %d 个（密码统一 %s）%n", ids.size(), DEFAULT_PASSWORD);
        return ids;
    }

    /**
     * 建关注关系。每人关注 12–20 个随机对象。
     *
     * 刻意控制规模：FeedWriteService#resolveFeedTargets 对 PUBLIC 帖扇出给全部 follower，
     * 3000 帖 × 约 16 个 follower ≈ 5 万条 ZSET 写入，回灌可在秒级完成。
     * 若给每人几百个 follower，回灌会退化成百万级写入。
     */
    private int createFollows(List<Long> userIds, Random rng) {
        int n = 0;
        for (Long follower : userIds) {
            List<Long> pool = new ArrayList<>(userIds);
            pool.remove(follower);
            Collections.shuffle(pool, rng);
            int k = FOLLOW_MIN + rng.nextInt(FOLLOW_MAX - FOLLOW_MIN + 1);
            for (Long following : pool.subList(0, k)) {
                n += jdbc.update("""
                        INSERT INTO follows (follower_id, following_id, created_at)
                        VALUES (?, ?, now())
                        ON CONFLICT (follower_id, following_id) DO NOTHING
                        """, follower, following);
            }
        }
        jdbc.update("UPDATE users u SET follow_count = "
                + "(SELECT count(*) FROM follows f WHERE f.follower_id = u.id), "
                + "follower_count = (SELECT count(*) FROM follows f WHERE f.following_id = u.id) "
                + "WHERE u.username LIKE ?", USER_PREFIX + "%");
        System.out.printf("  关注关系 %d 条（每人 %d–%d）%n", n, FOLLOW_MIN, FOLLOW_MAX);
        return n;
    }

    /**
     * 插入帖子并登记 corpus_meta。时间戳打散到过去 SPREAD_DAYS 天内。
     *
     * ⚠️ <b>必须按时间升序插入</b>，让自增 id 与 created_at 同向单调。
     * 应用自身的写入路径天然满足这个不变式（帖子按时间先后创建），
     * 而查询层依赖它：{@code PostRepository#findNewestPosts} 用
     * {@code ORDER BY p.id DESC} + {@code p.id < :lastId} 做 keyset 分页——
     * 这是正确且高效的设计，但只在 id 序等于时间序时成立。
     *
     * 若给自增 id 配随机时间戳，就造出了应用永远不可能产生的数据：
     * 用户主页会按 id 倒序显示出乱序的时间（实测 04-21 → 05-11 → 06-25 → 09-10）。
     * Redis feed 不受影响，因为它用时间戳作 ZSET score。
     *
     * ⚠️ images 必须写 {@code '[]'} 而不是留 NULL。ImagesConverter 的写入侧会把 null
     * 转成 "[]"，所以经 JPA 保存的帖子永远不是 NULL；但直接走 SQL 就会留下 NULL，
     * 而读取侧一旦读到 NULL 就抛异常，任何按 JPA 查 Post 的代码路径都会挂掉
     * （实测 FeedBackfillRunner 因此整个回灌失败）。
     */
    private int insertPosts(List<JsonNode> rows, List<Long> userIds, Random rng) {
        // 先抽好时间戳再按时间升序插入，让自增 id 与 created_at 同向单调。
        // 见方法注释：这是应用自身写入路径天然保持、而查询层依赖的不变式。
        record Pending(JsonNode row, Long authorId, LocalDateTime ts) {}
        List<Pending> pending = new ArrayList<>(rows.size());
        for (JsonNode r : rows) {
            pending.add(new Pending(
                    r,
                    userIds.get(rng.nextInt(userIds.size())),
                    LocalDateTime.now()
                            .minusDays(rng.nextInt(SPREAD_DAYS))
                            .minusMinutes(rng.nextInt(24 * 60))));
        }
        pending.sort(Comparator.comparing(Pending::ts));

        int n = 0;
        for (Pending pd : pending) {
            JsonNode r = pd.row();
            Long authorId = pd.authorId();
            LocalDateTime ts = pd.ts();

            Long postId = jdbc.queryForObject("""
                    INSERT INTO posts (user_id, content, images, type, visibility, status,
                                       deleted, is_edited, like_count, comment_count,
                                       repost_count, created_at, updated_at)
                    VALUES (?, ?, '[]', 'ORIGINAL', ?, ?, false, false, 0, 0, 0, ?, ?)
                    RETURNING id
                    """, Long.class,
                    authorId, r.get("content").asText(),
                    VISIBILITY_PUBLIC, STATUS_NORMAL, ts, ts);

            jdbc.update("""
                    INSERT INTO corpus_meta (post_id, gen_id, layer, cluster_id, family,
                                             source, source_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    postId, r.get("gen_id").asText(), r.get("layer").asText(),
                    text(r, "cluster"), text(r, "family"),
                    text(r, "source"), text(r, "source_id"));

            if (++n % 500 == 0) System.out.printf("    ...已插入 %,d 条%n", n);
        }
        System.out.printf("  帖子 %,d 条（时间戳打散在过去 %d 天）%n", n, SPREAD_DAYS);
        return n;
    }

    /** JSONL 里噪声层的 cluster/family 是 null，靶区的 source/source_id 不存在。 */
    private String text(JsonNode r, String field) {
        JsonNode v = r.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }
}
