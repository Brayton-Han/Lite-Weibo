package com.brayton.weibo.tools;

import com.brayton.weibo.common.ChineseUtil;
import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 评测集候选池构造（RAG Day 8 · pooling）。
 *
 * 读 {@code eval/work/queries_draft.jsonl}（人工筛过的 80 条 query），对每条 query 用多个互相独立的
 * 来源各取 top-{@value #DEPTH} 汇成候选池，写 {@code eval/work/pool.jsonl} 交给标注。
 *
 * 触发方式（一次性，跑完即退出）：
 *   EVAL_POOL=true VOYAGE_API_KEY=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools
 * backend 容器占着 8080 时再加 -Dspring-boot.run.arguments=--server.port=0
 *
 * <h3>候选来源</h3>
 * <ul>
 *   <li>{@code like}：原样调用线上的 {@code searchChinesePosts} / {@code searchPosts}，含 {@code ORDER BY created_at DESC}</li>
 *   <li>{@code trgm}：pg_trgm {@code word_similarity} 排序——词面的「相关性排序」一路，与 like 的时间排序互补</li>
 *   <li>{@code vector}：与语义搜索端点同一条 {@link PostEmbeddingRepository#searchNearest}</li>
 *   <li>{@code random}：按 qid 定种子的随机抽样，估计池外漏标率用</li>
 *   <li>{@code family} / {@code seed}：种子帖及其同义表达族（docs/CORPUS_DESIGN.md §2 定义的 pooling 单位）。
 *       它来自语料元数据而不是任何检索系统，正好补上词面与向量都漏掉的那部分——否则池子的上限
 *       就是被测系统召回的并集，Recall 会系统性虚高</li>
 * </ul>
 *
 * <h3>只收语料帖</h3>
 * 没有 corpus_meta 记录的真实帖子（docs/CORPUS_DESIGN.md §6）不进池子：它们不属于评测语料，
 * Day 9 的检索策略也必须按同一口径把它们从结果里剔掉，否则会被当成未标注的不相关文档。
 *
 * <h3>不记录排名给标注者看</h3>
 * 池子里保留了每个来源的排名（Day 9 分析池覆盖用），但 eval_build 导出标注表时会去掉来源与排名并打乱顺序——
 * 标注者知道「这条是向量召回的第 1 名」就不再独立了。
 */
@Component
@Profile("tools")
@Order(EvalPoolRunner.ORDER)
@RequiredArgsConstructor
public class EvalPoolRunner implements CommandLineRunner {

    /** 必须显式指定且优先于 {@link ToolsShutdownRunner}，理由见该类注释。 */
    static final int ORDER = 300;

    static final int DEPTH = 20;
    private static final int VISIBILITY_PUBLIC = 0;   // PostVisibility.PUBLIC

    private static final String CORPUS_POSTS = """
            SELECT p.id FROM posts p JOIN corpus_meta m ON m.post_id = p.id
            WHERE p.deleted = false AND p.visibility = ?
            """;

    private static final String TRGM = """
            SELECT p.id FROM posts p JOIN corpus_meta m ON m.post_id = p.id
            WHERE p.deleted = false AND p.visibility = ?
            ORDER BY word_similarity(?, p.content) DESC, p.id
            LIMIT ?
            """;

    private static final String META = """
            SELECT m.post_id, m.gen_id, m.layer, m.cluster_id, m.family, p.content
            FROM corpus_meta m JOIN posts p ON p.id = m.post_id
            """;

    private final JdbcTemplate jdbc;
    private final PostRepository postRepository;
    private final PostEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("EVAL_POOL"))) {
            return;
        }
        Path dir = Path.of(Optional.ofNullable(System.getenv("EVAL_DIR")).orElse("../eval")).resolve("work");
        Path in = dir.resolve("queries_draft.jsonl");
        Path out = dir.resolve("pool.jsonl");
        if (!Files.exists(in)) {
            throw new IllegalStateException("找不到 " + in.toAbsolutePath() + "，先跑 tools/eval_build/gen_queries.py finalize");
        }
        List<JsonNode> queries = new ArrayList<>();
        for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                queries.add(mapper.readTree(line));
            }
        }

        Map<Long, Meta> meta = loadMeta();
        Map<String, Long> byGenId = new HashMap<>();
        meta.values().forEach(m -> byGenId.put(m.genId(), m.postId()));
        List<Long> corpusIds = jdbc.queryForList(CORPUS_POSTS + " ORDER BY p.id", Long.class, VISIBILITY_PUBLIC);

        System.out.printf("📥 %d 条 query，语料帖 %d 条，向量化 query…%n", queries.size(), corpusIds.size());
        List<float[]> vectors = embeddingClient.embedQueries(queries.stream().map(q -> q.get("query").asText()).toList());

        Map<String, Integer> unique = new TreeMap<>();   // 只由该来源贡献的候选数，衡量每一路是否承重
        long poolTotal = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = 0; i < queries.size(); i++) {
                JsonNode q = queries.get(i);
                String qid = q.get("qid").asText();
                String text = q.get("query").asText();

                // 来源名 → 按排名的 post_id；LinkedHashMap 让输出顺序稳定
                Map<String, List<Long>> sources = new LinkedHashMap<>();
                sources.put("like", like(text).stream().filter(meta::containsKey).toList());
                sources.put("trgm", jdbc.queryForList(TRGM, Long.class, VISIBILITY_PUBLIC, text, DEPTH));
                sources.put("vector", embeddingRepository.searchNearest(vectors.get(i), embeddingClient.modelId(), DEPTH)
                        .stream().map(PostEmbeddingRepository.Hit::postId).filter(meta::containsKey).toList());
                sources.put("random", random(corpusIds, qid));

                Long seedId = null;
                String seedGenId = q.path("seed_gen_id").asText(null);
                if (seedGenId != null) {
                    seedId = byGenId.get(seedGenId);
                    if (seedId == null) {
                        throw new IllegalStateException(qid + " 的种子帖 " + seedGenId + " 不在 corpus_meta 中");
                    }
                    Meta s = meta.get(seedId);
                    sources.put("family", meta.values().stream()
                            .filter(m -> Objects.equals(m.cluster(), s.cluster()) && Objects.equals(m.family(), s.family()))
                            .map(Meta::postId).sorted().toList());
                    sources.put("seed", List.of(seedId));
                }

                Map<Long, ObjectNode> pool = new LinkedHashMap<>();
                sources.forEach((name, ids) -> {
                    for (int rank = 0; rank < ids.size(); rank++) {
                        long id = ids.get(rank);
                        ObjectNode c = pool.computeIfAbsent(id, k -> candidate(meta.get(k)));
                        // family / seed 没有排名意义，记 true；检索来源记 1 起的排名
                        if (name.equals("family") || name.equals("seed")) {
                            ((ObjectNode) c.get("sources")).put(name, true);
                        } else {
                            ((ObjectNode) c.get("sources")).put(name, rank + 1);
                        }
                    }
                });
                pool.values().forEach(c -> {
                    if (c.get("sources").size() == 1) {
                        unique.merge(c.get("sources").fieldNames().next(), 1, Integer::sum);
                    }
                });
                poolTotal += pool.size();

                ObjectNode row = mapper.createObjectNode();
                row.put("qid", qid);
                row.put("query", text);
                row.put("type", q.get("type").asText());
                if (seedId == null) {
                    row.putNull("seed_post_id");
                } else {
                    row.put("seed_post_id", seedId);
                }
                ArrayNode arr = row.putArray("candidates");
                pool.values().forEach(arr::add);
                w.write(mapper.writeValueAsString(row));
                w.newLine();
            }
        }

        System.out.printf("✅ 写入 %s：%d 条 query，候选池平均 %.1f 条，共 %d 个 (query, 帖子) 对%n",
                out.toAbsolutePath().normalize(), queries.size(), (double) poolTotal / queries.size(), poolTotal);
        System.out.println("   仅由单一来源贡献的候选数（某一路长期为 0 说明它被其他来源完全覆盖）：" + unique);
    }

    /** 与 PostService#getSearchResults 同一分支与参数，只是不组装 DTO（DTO 会去查 Redis 的点赞状态）。 */
    private List<Long> like(String query) {
        List<PostVisibility> pub = List.of(PostVisibility.PUBLIC);
        List<Post> posts = ChineseUtil.containsChinese(query)
                ? postRepository.searchChinesePosts("%" + query + "%", Long.MAX_VALUE, pub, PageRequest.of(0, DEPTH))
                : postRepository.searchPosts(query, Long.MAX_VALUE, pub, PageRequest.of(0, DEPTH));
        return posts.stream().map(Post::getId).toList();
    }

    /** 种子取 qid 的稳定哈希：重跑结果一致，且不同 query 抽到的样本互不相同。 */
    private static List<Long> random(List<Long> corpusIds, String qid) {
        List<Long> shuffled = new ArrayList<>(corpusIds);
        Collections.shuffle(shuffled, new Random(qid.hashCode() * 31L + 20261014L));
        return shuffled.subList(0, Math.min(DEPTH, shuffled.size()));
    }

    private ObjectNode candidate(Meta m) {
        ObjectNode c = mapper.createObjectNode();
        c.put("post_id", m.postId());
        c.put("gen_id", m.genId());
        c.put("layer", m.layer());
        c.put("cluster", m.cluster());
        c.put("family", m.family());
        c.put("content", m.content());
        c.putObject("sources");
        return c;
    }

    private Map<Long, Meta> loadMeta() {
        Map<Long, Meta> out = new HashMap<>();
        jdbc.query(META, rs -> {
            long id = rs.getLong("post_id");
            out.put(id, new Meta(id, rs.getString("gen_id"), rs.getString("layer"),
                    rs.getString("cluster_id"), rs.getString("family"), rs.getString("content")));
        });
        return out;
    }

    private record Meta(long postId, String genId, String layer, String cluster, String family, String content) {}
}
