package com.brayton.weibo.tools;

import com.brayton.weibo.embedding.EmbeddingClient;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.eval.RetrievalMetrics;
import com.brayton.weibo.repository.PostEmbeddingRepository;
import com.brayton.weibo.repository.PostRepository;
import com.brayton.weibo.repository.PostLexicalSearchRepository;
import com.brayton.weibo.rerank.RerankCandidate;
import com.brayton.weibo.rerank.Reranker;
import com.brayton.weibo.rerank.VoyageReranker;
import com.brayton.weibo.retrieval.HybridRrfStrategy;
import com.brayton.weibo.retrieval.LikeStrategy;
import com.brayton.weibo.retrieval.RerankStrategy;
import com.brayton.weibo.retrieval.RetrievalStrategy;
import com.brayton.weibo.retrieval.ScoredPost;
import com.brayton.weibo.retrieval.TsRankStrategy;
import com.brayton.weibo.retrieval.VectorStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 检索评测（RAG Day 9）：对标注集里的每条 query 跑一遍各检索策略，算 Recall / Precision / MRR / nDCG 与延迟。
 *
 * 触发方式（一次性，跑完即退出）：
 *   EVAL_RUN=true ./mvnw spring-boot:run -Dspring-boot.run.profiles=tools
 * backend 容器占着 8080 时再加 -Dspring-boot.run.arguments=--server.port=0
 *
 * <h3>环境变量</h3>
 * <ul>
 *   <li>{@code EVAL_SPLIT}：{@code train}（默认）/ {@code test} / {@code all}。调参只看 train；
 *       test 留到最终报数，中途不跑</li>
 *   <li>{@code EVAL_STRATEGIES}：逗号分隔，默认 {@code like,tsrank,vector}；可选 {@code rrf}</li>
 *   <li>{@code EVAL_K}：截断深度，默认 10</li>
 *   <li>{@code EVAL_EF_SEARCH}：向量路的 hnsw.ef_search，默认取 {@code embedding.search.ef-search}</li>
 *   <li>{@code EVAL_LATENCY_REPEAT}：每条 query 计时的次数，取中位数，默认 1；量延迟时调大</li>
 *   <li>{@code EVAL_STRATEGIES} 里的 {@code rrf-serial}：与 rrf 相同但两路串行，用来量并行的收益</li>
 *   <li>{@code EVAL_RRF_W}：rrf 向量一路的权重（词面一路为 1），默认 10；1 即等权 RRF</li>
 *   <li>扫参：{@code EVAL_EF_SEARCH} / {@code EVAL_RRF_N} / {@code EVAL_RRF_K} / {@code EVAL_RRF_W} 都可写成逗号分隔的多个值，vector 按 ef_search、
 *       rrf 按四者的笛卡尔积各展开成一个策略（名字如 {@code rrf-k60-n50-ef100-w2}），结果写到 {@code eval/results/sweep_{split}_{时间戳}/}，
 *       汇总改为每个配置一行</li>
 *   <li>{@code EVAL_TRGM_THRESHOLD}：tsrank 中文路的 word_similarity 下限，默认取 {@code search.trgm.threshold}</li>
 *   <li>{@code EVAL_RRF_N} / {@code EVAL_RRF_K}：rrf 策略每路召回条数（默认 20）与平滑常数（默认 10），默认值为 Day 11 选定配置；
 *       两路就是 tsrank 与 vector，上面两个参数同样作用于它</li>
 *   <li>{@code EVAL_STRATEGIES} 里的 {@code rerank-voyage} / {@code rerank-voyage-lite}：在 rrf 之上加 Voyage rerank
 *       （{@code rerank-3} / {@code rerank-3-lite}）。base 就是上面那组 rrf 参数，所以这时 rrf 参数与 ef_search 只能各给一个值</li>
 *   <li>{@code EVAL_RERANK_N}：送进 reranker 的候选深度，默认 30；可逗号分隔扫参（策略名如 {@code rerank-voyage-n50}）</li>
 *   <li>{@code EVAL_DIR}：默认 {@code ../eval}</li>
 * </ul>
 *
 * <h3>输出</h3>
 * {@code eval/results/{strategy}_{split}_{时间戳}.tsv} 逐 query 明细，
 * {@code eval/results/summary_{split}_{时间戳}.md} 汇总（同时打印到控制台）。
 * 汇总<b>按语料层与 query 类型分组</b>：词面检索在经验层能打、在共鸣层趋近于 0，合成一个总数会把这个分裂抹平。
 *
 * <h3>只在语料帖里排名</h3>
 * 与 Day 8 候选池同一口径：没有 corpus_meta 的真实帖子从未被标注，混在结果里会被当成不相关文档，
 * 不公平地挤掉名额。先按 k 取，剔除后不足 k 条再成倍加深重取（见 {@link #retrieveCorpusTopK}），保证仍是该策略真正的 top-k。
 * 不一次性多取「非语料帖总数」条：请求深度会改变执行计划——向量路 LIMIT 一大，规划器就从 HNSW 换成精确的全表扫描，
 * 测的就不再是线上那条路径了。
 *
 * <h3>query 向量缓存</h3>
 * 向量路的 query 向量缓存在 {@code eval/work/query_vectors.jsonl}（按模型区分），只对缺失的 query 调 API。
 * 反复跑（Day 11 扫参）不重复计费，且 query 向量固定，前后两次的差异只来自检索本身。
 * 因此向量路的延迟<b>不含</b> embedding API 的网络往返，报数时要注明。
 *
 * <h3>判定覆盖率</h3>
 * 没进 Day 8 候选池的帖子没有标注，算分时一律当不相关。三条基线都是候选池的来源（池深 20 ≥ k），
 * 覆盖率应接近 100%；之后的混合 / rerank 会把池外的帖子排上来，覆盖率掉得多，说明指标在低估它——
 * 这时要补标而不是直接信数字。所以汇总里每个策略都报 judged@k。
 *
 * <h3>rerank 策略</h3>
 * 每次调用都打 Voyage API、按 token 计费，所以和本地策略的跑法不同（见 {@link RerankProbe}）：
 * 不做逐条预热，只用第一条 query 预热一次连接；计时固定 1 次，不受 {@code EVAL_LATENCY_REPEAT} 影响；
 * 计时那次的结果按 (query, 候选, k) 缓存，紧接着的打分直接复用，只有剔除非语料帖后的加深重取才再打 API。
 * 延迟<b>含</b> rerank 的网络往返（这正是要量的），仍不含 query 向量化。reranker 失败直接让评测失败，不降级。
 * 汇总末尾单独报 token 用量与按官网单价估算的单次成本。
 *
 * <h3>无答案 query</h3>
 * Recall / nDCG 对它们没有定义（{@link RetrievalMetrics} 直接抛异常），不进均值，
 * 单独报「返回空结果」的比例——这是基线自身唯一能表达「站内没有」的方式。
 */
@Component
@Profile("tools")
@Order(RagEvalRunner.ORDER)
@RequiredArgsConstructor
public class RagEvalRunner implements CommandLineRunner {

    /** 必须显式指定且优先于 {@link ToolsShutdownRunner}，理由见该类注释。 */
    static final int ORDER = 400;

    private static final String CORPUS_POSTS = """
            SELECT p.id FROM posts p JOIN corpus_meta m ON m.post_id = p.id
            WHERE p.deleted = false AND p.visibility = ?
            """;

    private static final String NON_CORPUS_COUNT = """
            SELECT count(*) FROM posts p
            WHERE p.deleted = false AND p.visibility = ?
              AND NOT EXISTS (SELECT 1 FROM corpus_meta m WHERE m.post_id = p.id)
            """;

    private static final List<String> TYPES = List.of("keyword", "synonym", "mixed", "resonance");

    private final JdbcTemplate jdbc;
    private final PostRepository postRepository;
    private final PostEmbeddingRepository embeddingRepository;
    private final EmbeddingClient embeddingClient;
    private final Environment environment;
    private final PostLexicalSearchRepository lexicalRepository;
    /** rrf 两路并行用的线程池；同类型的 bean 有两个，按参数名匹配 {@link com.brayton.weibo.config.AsyncConfig#FEED_EXECUTOR} */
    private final ThreadPoolTaskExecutor feedTaskExecutor;
    /** 只用来拿 key 与地址：评测按模型各开一个 {@link VoyageReranker#offline} 实例 */
    private final VoyageReranker voyageReranker;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void run(String... args) throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("EVAL_RUN"))) {
            return;
        }
        Path evalDir = Path.of(env("EVAL_DIR", "../eval"));
        String split = env("EVAL_SPLIT", "train");
        int k = Integer.parseInt(env("EVAL_K", "10"));
        List<Integer> efSearches = ints(env("EVAL_EF_SEARCH", environment.getProperty("embedding.search.ef-search", "100")));
        // 中文 trgm 路的 word_similarity 下限；默认取线上配置，见 PostLexicalSearchRepository
        double trgmThreshold = Double.parseDouble(env("EVAL_TRGM_THRESHOLD", environment.getProperty("search.trgm.threshold", "0.05")));
        List<String> names = Arrays.stream(env("EVAL_STRATEGIES", "like,tsrank,vector").split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        // 默认值是 Day 11 在 train 上选定的配置（docs/EVAL.md「Day 11 · 加权 RRF」）；向量一路的权重，词面一路固定为 1
        List<Integer> rrfNs = ints(env("EVAL_RRF_N", "20"));
        List<Integer> rrfKs = ints(env("EVAL_RRF_K", "10"));
        List<Double> rrfWs = Arrays.stream(env("EVAL_RRF_W", "10").split(",")).map(String::strip)
                .filter(v -> !v.isEmpty()).map(Double::parseDouble).toList();
        List<Integer> rerankNs = ints(env("EVAL_RERANK_N", "30"));
        int latencyRepeat = Integer.parseInt(env("EVAL_LATENCY_REPEAT", "1"));
        if (latencyRepeat < 1) {
            throw new IllegalArgumentException("EVAL_LATENCY_REPEAT 至少为 1");
        }
        // 任一参数给了多个值就是扫参：策略名带上参数，汇总改成每个配置一行
        boolean sweep = efSearches.size() > 1 || rrfNs.size() > 1 || rrfKs.size() > 1 || rrfWs.size() > 1
                || rerankNs.size() > 1;

        List<EvalQuery> queries = loadQueries(evalDir, split, loadJudged(evalDir));
        Set<Long> corpus = new HashSet<>(jdbc.queryForList(CORPUS_POSTS, Long.class, PostEmbeddingRepository.VISIBILITY_PUBLIC));
        int nonCorpus = jdbc.queryForObject(NON_CORPUS_COUNT, Integer.class, PostEmbeddingRepository.VISIBILITY_PUBLIC);
        System.out.printf("📥 %s：%d 条 query，语料帖 %d 条（另有 %d 条非语料帖，从结果中剔除）%n",
                split, queries.size(), corpus.size(), nonCorpus);

        List<RetrievalStrategy> strategies = new ArrayList<>();
        Map<String, RerankProbe> rerankProbes = new HashMap<>();
        for (String name : names) {
            switch (name) {
                case "like" -> strategies.add(new LikeStrategy(postRepository));
                case "tsrank" -> strategies.add(new TsRankStrategy(lexicalRepository, trgmThreshold));
                case "vector" -> {
                    Map<String, float[]> vectors = queryVectors(evalDir, queries);
                    for (int ef : efSearches) {
                        RetrievalStrategy v = new VectorStrategy(embeddingRepository, embeddingClient.modelId(), vectors::get, ef);
                        strategies.add(sweep ? named("vector-ef" + ef, v) : v);
                    }
                }
                case "rrf", "rrf-serial" -> {
                    // rrf-serial 两路在调用线程上依次执行，只用来量并行省了多少时间，指标与 rrf 相同
                    java.util.concurrent.Executor executor = name.equals("rrf") ? feedTaskExecutor : Runnable::run;
                    Map<String, float[]> vectors = queryVectors(evalDir, queries);
                    for (int ef : efSearches) {
                        for (int n : rrfNs) {
                            for (int rrfK : rrfKs) {
                                for (double w : rrfWs) {
                                    RetrievalStrategy r = new HybridRrfStrategy(
                                            new TsRankStrategy(lexicalRepository, trgmThreshold),
                                            new VectorStrategy(embeddingRepository, embeddingClient.modelId(), vectors::get, ef),
                                            executor, n, rrfK, w);
                                    strategies.add(sweep ? named(name + "-k" + rrfK + "-n" + n + "-ef" + ef + "-w" + fmtWeight(w), r)
                                            : named(name, r));
                                }
                            }
                        }
                    }
                }
                case "rerank-voyage", "rerank-voyage-lite" -> {
                    if (efSearches.size() > 1 || rrfNs.size() > 1 || rrfKs.size() > 1 || rrfWs.size() > 1) {
                        throw new IllegalArgumentException(name + " 的 base 是单一 rrf 配置，ef_search / rrf 参数不能同时扫");
                    }
                    String model = name.endsWith("-lite") ? VoyageReranker.LITE_MODEL : VoyageReranker.DEFAULT_MODEL;
                    Map<String, float[]> vectors = queryVectors(evalDir, queries);
                    for (int n : rerankNs) {
                        RetrievalStrategy rrf = new HybridRrfStrategy(
                                new TsRankStrategy(lexicalRepository, trgmThreshold),
                                new VectorStrategy(embeddingRepository, embeddingClient.modelId(), vectors::get, efSearches.get(0)),
                                feedTaskExecutor, rrfNs.get(0), rrfKs.get(0), rrfWs.get(0));
                        VoyageReranker client = voyageReranker.offline(model);
                        MemoReranker memo = new MemoReranker(client);
                        String strategyName = sweep ? name + "-n" + n : name;
                        strategies.add(named(strategyName, new RerankStrategy(rrf, memo, this::postTexts, n, false)));
                        rerankProbes.put(strategyName, new RerankProbe(client, memo, n));
                    }
                }
                default -> throw new IllegalArgumentException("未知策略 " + name
                        + "，可选 like / tsrank / vector / rrf / rrf-serial / rerank-voyage / rerank-voyage-lite");
            }
        }

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        // 扫参一次几十个策略，逐 query 明细单独放一个目录，不和单次运行的结果混在一起
        Path outDir = evalDir.resolve("results");
        if (sweep) {
            outDir = outDir.resolve("sweep_" + split + "_" + ts);
        }
        Files.createDirectories(outDir);

        Map<String, List<QueryResult>> all = new LinkedHashMap<>();
        Map<String, Long> rerankTokens = new LinkedHashMap<>();
        for (RetrievalStrategy s : strategies) {
            RerankProbe probe = rerankProbes.get(s.name());
            if (probe == null) {
                // 先空跑一遍预热（JIT、连接池、PG 缓冲区），再计时的那一遍才算数
                for (EvalQuery q : queries) {
                    retrieveCorpusTopK(s, q.query(), k, corpus);
                }
            } else if (!queries.isEmpty()) {
                // rerank 每次调用都计费：只拿第一条预热一次连接，再清掉缓存，保证计时那次真的打到 API
                s.retrieve(queries.get(0).query(), k, null);
                probe.memo().clear();
            }
            int repeat = probe == null ? latencyRepeat : 1;
            long timedTokens = 0;
            List<QueryResult> results = new ArrayList<>(queries.size());
            for (EvalQuery q : queries) {
                // 计时只量深度为 k 的调用，与线上一致；剔除非语料帖所需的加深重取是评测自己的开销，不算进去。
                // 重复 repeat 次取中位数，压掉单次测量的抖动（GC、调度），分位数反映的是 query 之间的差异
                long tokensBefore = probe == null ? 0 : probe.client().totalTokens();
                long[] samples = new long[repeat];
                for (int i = 0; i < repeat; i++) {
                    long t0 = System.nanoTime();
                    s.retrieve(q.query(), k, null);
                    samples[i] = System.nanoTime() - t0;
                }
                if (probe != null) {
                    timedTokens += probe.client().totalTokens() - tokensBefore;
                }
                Arrays.sort(samples);
                long nanos = samples[(repeat - 1) / 2];
                List<ScoredPost> raw = retrieveCorpusTopK(s, q.query(), k, corpus);
                results.add(score(q, raw, corpus, k, nanos));
            }
            if (probe != null) {
                rerankTokens.put(s.name(), timedTokens);
            }
            all.put(s.name(), results);
            Path tsv = outDir.resolve(s.name() + "_" + split + "_" + ts + ".tsv");
            writeTsv(tsv, results);
            System.out.printf("   %-20s 完成 → %s%n", s.name(), tsv.normalize());
        }

        String header = String.format("""
                # 检索评测 · %s · %s

                - split = `%s`（%d 条 query），k = %d，ef_search = %s，trgm 阈值 = %s，rrf N = %s、k = %s、向量权重 = %s，embedding = `%s`
                - 只在语料帖（%d 条）中排名；非语料帖 %d 条从结果中剔除
                - 向量路延迟不含 embedding API 调用（query 向量走缓存）；每条 query 计时 %d 次取中位数
                """, split, ts, split, queries.size(), k, efSearches, trgmThreshold, rrfNs, rrfKs, rrfWs, embeddingClient.modelId(), corpus.size(), nonCorpus, latencyRepeat);
        if (!rerankProbes.isEmpty()) {
            header += "- rerank 策略：计时固定 1 次、含 Voyage API 往返；base 为上面那组 rrf 参数，失败不降级\n";
        }
        String summary = header + "\n" + (sweep ? summarizeSweep(all, k) : summarize(all, k))
                + (rerankProbes.isEmpty() ? "" : summarizeRerankUsage(rerankProbes, rerankTokens, queries.size()));
        Path md = outDir.resolve("summary_" + split + "_" + ts + ".md");
        Files.writeString(md, summary, StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(summary);
        System.out.printf("✅ 汇总写入 %s%n", md.normalize());
    }

    // ---------------------------------------------------------------- 打分

    /**
     * 取到剔除非语料帖后仍有 k 条为止：先按 k 取，不够就把深度翻倍重取，直到够了或策略已经没有更多结果。
     * 向量路一般一次就够；trgm 一路实测 40 条 train query 里有 32 条的 top-10 混有非语料帖，要重取。
     * 重取不计入延迟（见 run 里的计时）。
     */
    static List<ScoredPost> retrieveCorpusTopK(RetrievalStrategy s, String query, int k, Set<Long> corpus) {
        for (int depth = k; ; depth *= 2) {
            List<ScoredPost> raw = s.retrieve(query, depth, null);
            long kept = raw.stream().map(ScoredPost::postId).filter(corpus::contains).distinct().count();
            if (kept >= k || raw.size() < depth) {
                return raw;
            }
        }
    }

    /**
     * 单条 query 的打分：先剔除非语料帖并截断到 k，再算指标。无答案 query 只记返回条数与延迟。
     */
    static QueryResult score(EvalQuery q, List<ScoredPost> raw, Set<Long> corpus, int k, long nanos) {
        List<Long> ranked = raw.stream().map(ScoredPost::postId).filter(corpus::contains).distinct().limit(k).toList();
        if (q.isNoAnswer()) {
            return new QueryResult(q, ranked, nanos, null, null, null, null);
        }
        Set<Long> relevant = RetrievalMetrics.relevantSet(q.grades());
        return new QueryResult(q, ranked, nanos,
                RetrievalMetrics.recallAtK(ranked, relevant, k),
                RetrievalMetrics.precisionAtK(ranked, relevant, k),
                RetrievalMetrics.reciprocalRank(ranked, relevant),
                RetrievalMetrics.ndcgAtK(ranked, q.grades(), k));
    }

    /** 最近秩法（nearest-rank）分位数：样本只有几十个，不插值，报的就是某一次真实测到的延迟。 */
    static long percentile(List<Long> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot take a percentile of an empty list");
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }

    // ---------------------------------------------------------------- 汇总

    static String summarize(Map<String, List<QueryResult>> all, int k) {
        List<String> names = new ArrayList<>(all.keySet());
        StringBuilder sb = new StringBuilder();

        sb.append("## 总览（可答 query，宏平均）\n\n");
        sb.append(String.format("| 策略 | n | Recall@%d | P@%d | MRR | nDCG@%d | judged@%d | p50 ms | p95 ms |%n", k, k, k, k));
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (String name : names) {
            List<QueryResult> answerable = all.get(name).stream().filter(r -> !r.query().isNoAnswer()).toList();
            List<Long> latencies = all.get(name).stream().map(QueryResult::nanos).toList();
            sb.append(String.format("| %s | %d | %s | %s | %s | %s | %s | %.1f | %.1f |%n", name, answerable.size(),
                    fmt(mean(answerable, QueryResult::recall)), fmt(mean(answerable, QueryResult::precision)),
                    fmt(mean(answerable, QueryResult::rr)), fmt(mean(answerable, QueryResult::ndcg)),
                    judgedRate(all.get(name)), percentile(latencies, 50) / 1e6, percentile(latencies, 95) / 1e6));
        }
        sb.append("\njudged@k = 返回结果中有人工 / Claude 判定的比例（未判定的按不相关计分）；它与延迟都按全部 query（含无答案）统计。\n");

        // 层 × 类型：层是语料视角（经验 / 共鸣），类型是 query 视角，经验层再按类型展开
        LinkedHashMap<String, Predicate<EvalQuery>> groups = new LinkedHashMap<>();
        groups.put("**经验层**", q -> "experience".equals(q.layer()));
        for (String type : TYPES.subList(0, 3)) {
            groups.put("　· " + type, q -> "experience".equals(q.layer()) && type.equals(q.type()));
        }
        groups.put("**共鸣层**", q -> "resonance".equals(q.layer()));
        groups.put("**全部可答**", q -> !q.isNoAnswer());

        appendGroupTable(sb, "Recall@" + k, all, names, groups, QueryResult::recall);
        appendGroupTable(sb, "nDCG@" + k, all, names, groups, QueryResult::ndcg);
        appendGroupTable(sb, "MRR", all, names, groups, QueryResult::rr);
        appendGroupTable(sb, "P@" + k, all, names, groups, QueryResult::precision);

        sb.append("\n## 无答案 query\n\n");
        sb.append("指标无定义，不进上面的均值。基线没有分数阈值，「返回空结果」是它们唯一能表达「站内没有」的方式。\n\n");
        sb.append("| 策略 | n | 返回空结果 | 平均返回条数 |\n|---|---|---|---|\n");
        for (String name : names) {
            List<QueryResult> noAnswer = all.get(name).stream().filter(r -> r.query().isNoAnswer()).toList();
            long empty = noAnswer.stream().filter(r -> r.ranked().isEmpty()).count();
            double avgReturned = noAnswer.stream().mapToInt(r -> r.ranked().size()).average().orElse(Double.NaN);
            sb.append(String.format("| %s | %d | %d | %s |%n", name, noAnswer.size(), empty,
                    noAnswer.isEmpty() ? "—" : String.format("%.1f", avgReturned)));
        }

        sb.append("\n## 可答 query 返回空结果的条数\n\n");
        sb.append("| 策略 | " + String.join(" | ", groups.keySet()) + " |\n");
        sb.append("|---|" + "---|".repeat(groups.size()) + "\n");
        for (String name : names) {
            sb.append("| ").append(name);
            for (Predicate<EvalQuery> g : groups.values()) {
                List<QueryResult> rs = all.get(name).stream().filter(r -> !r.query().isNoAnswer() && g.test(r.query())).toList();
                sb.append(String.format(" | %d / %d", rs.stream().filter(r -> r.ranked().isEmpty()).count(), rs.size()));
            }
            sb.append(" |\n");
        }
        return sb.toString();
    }

    /**
     * 扫参汇总：每个策略一行，总体四项指标 + judged + 延迟 + 各分组 nDCG。
     * 几十个策略时按层 × 类型的宽表（每策略一列）没法读，这里转置成每策略一行；行序即配置顺序，不排序。
     */
    static String summarizeSweep(Map<String, List<QueryResult>> all, int k) {
        LinkedHashMap<String, Predicate<EvalQuery>> groups = new LinkedHashMap<>();
        for (String type : TYPES.subList(0, 3)) {
            groups.put(type, q -> "experience".equals(q.layer()) && type.equals(q.type()));
        }
        groups.put("共鸣层", q -> "resonance".equals(q.layer()));

        StringBuilder sb = new StringBuilder("## 扫参（可答 query，宏平均）\n\n");
        sb.append(String.format("| 策略 | Recall@%d | P@%d | MRR | nDCG@%d | judged@%d | p50 ms | p95 ms", k, k, k, k));
        groups.keySet().forEach(g -> sb.append(" | nDCG ").append(g));
        sb.append(" |\n|---|---|---|---|---|---|---|---|").append("---|".repeat(groups.size())).append("\n");
        all.forEach((name, rs) -> {
            List<QueryResult> answerable = rs.stream().filter(r -> !r.query().isNoAnswer()).toList();
            List<Long> latencies = rs.stream().map(QueryResult::nanos).toList();
            sb.append(String.format("| %s | %s | %s | %s | %s | %s | %.1f | %.1f", name,
                    fmt(mean(answerable, QueryResult::recall)), fmt(mean(answerable, QueryResult::precision)),
                    fmt(mean(answerable, QueryResult::rr)), fmt(mean(answerable, QueryResult::ndcg)),
                    judgedRate(rs), percentile(latencies, 50) / 1e6, percentile(latencies, 95) / 1e6));
            groups.values().forEach(g -> sb.append(" | ").append(fmt(mean(
                    answerable.stream().filter(r -> g.test(r.query())).toList(), QueryResult::ndcg))));
            sb.append(" |\n");
        });
        sb.append("\n分组 nDCG 的前三列是经验层的三类 query。judged@k 与延迟按全部 query（含无答案）统计。\n");
        return sb.toString();
    }

    /** Voyage 官网单价（美元 / 百万 token，2026-10 查自 docs.voyageai.com/docs/pricing）；只用于估算，不进账单。 */
    private static final Map<String, Double> RERANK_PRICE_PER_MTOK = Map.of(
            "voyage/" + VoyageReranker.DEFAULT_MODEL, 0.05,
            "voyage/" + VoyageReranker.LITE_MODEL, 0.02);

    /** 只统计计时那一次调用的 token：线上每次搜索恰好调一次，评测自己的加深重取不算。 */
    static String summarizeRerankUsage(Map<String, RerankProbe> probes, Map<String, Long> tokens, int queryCount) {
        StringBuilder sb = new StringBuilder("\n## Rerank 用量\n\n");
        sb.append("| 策略 | 模型 | 候选 N | 总 token | 平均 token / query | 估算成本 / query（美元） |\n");
        sb.append("|---|---|---|---|---|---|\n");
        tokens.forEach((name, total) -> {
            RerankProbe p = probes.get(name);
            String model = p.client().modelId();
            double avg = queryCount == 0 ? 0 : (double) total / queryCount;
            Double price = RERANK_PRICE_PER_MTOK.get(model);
            sb.append(String.format("| %s | `%s` | %d | %d | %.0f | %s |%n", name, model, p.n(), total, avg,
                    price == null ? "—" : String.format("%.7f", avg * price / 1e6)));
        });
        sb.append("\n计费 token = query token × 候选数 + 候选 token 之和；成本按官网单价估算，未扣免费额度。\n");
        return sb.toString();
    }

    private static void appendGroupTable(StringBuilder sb, String metric, Map<String, List<QueryResult>> all,
                                         List<String> names, Map<String, Predicate<EvalQuery>> groups,
                                         java.util.function.Function<QueryResult, Double> f) {
        sb.append("\n## ").append(metric).append(" · 按层 × 类型\n\n");
        sb.append("| 分组 | n | ").append(String.join(" | ", names)).append(" |\n");
        sb.append("|---|---|").append("---|".repeat(names.size())).append("\n");
        groups.forEach((label, g) -> {
            List<QueryResult> first = all.get(names.get(0)).stream()
                    .filter(r -> !r.query().isNoAnswer() && g.test(r.query())).toList();
            sb.append("| ").append(label).append(" | ").append(first.size());
            for (String name : names) {
                List<QueryResult> rs = all.get(name).stream()
                        .filter(r -> !r.query().isNoAnswer() && g.test(r.query())).toList();
                sb.append(" | ").append(fmt(mean(rs, f)));
            }
            sb.append(" |\n");
        });
    }

    /** 被判定过的结果数 / 返回结果总数；一条都没返回时显示 —。 */
    static String judgedRate(List<QueryResult> rs) {
        long returned = rs.stream().mapToLong(r -> r.ranked().size()).sum();
        long judged = rs.stream().mapToLong(r -> r.ranked().stream().filter(r.query().judged()::contains).count()).sum();
        return returned == 0 ? "—" : String.format("%.1f%%", 100.0 * judged / returned);
    }

    /** 空组返回 null（某个 split 里可能没有某类 query），表格里显示为 —。 */
    private static Double mean(List<QueryResult> rs, java.util.function.Function<QueryResult, Double> f) {
        return rs.isEmpty() ? null : RetrievalMetrics.mean(rs.stream().map(f).toList());
    }

    private static String fmt(Double v) {
        return v == null ? "—" : String.format("%.3f", v);
    }

    // ---------------------------------------------------------------- 读写

    /** qrels.tsv（含 0 分）里每条 query 判定过的帖子。 */
    private static Map<String, Set<Long>> loadJudged(Path evalDir) throws IOException {
        Path file = evalDir.resolve("qrels.tsv");
        if (!Files.exists(file)) {
            throw new IllegalStateException("找不到 " + file.toAbsolutePath().normalize());
        }
        Map<String, Set<Long>> out = new HashMap<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {   // 跳过表头 qid post_id gen_id grade annotator
            if (line.isBlank()) {
                continue;
            }
            String[] cols = line.split("\t");
            out.computeIfAbsent(cols[0], q -> new HashSet<>()).add(Long.parseLong(cols[1]));
        }
        return out;
    }

    private List<EvalQuery> loadQueries(Path evalDir, String split, Map<String, Set<Long>> judged) throws IOException {
        List<String> parts = switch (split) {
            case "train", "test" -> List.of(split);
            case "all" -> List.of("train", "test");
            default -> throw new IllegalArgumentException("EVAL_SPLIT 只能是 train / test / all，收到 " + split);
        };
        List<EvalQuery> out = new ArrayList<>();
        for (String part : parts) {
            Path file = evalDir.resolve(part).resolve("queries.jsonl");
            if (!Files.exists(file)) {
                throw new IllegalStateException("找不到 " + file.toAbsolutePath().normalize());
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                Map<Long, Integer> grades = new HashMap<>();
                n.path("relevant").properties().forEach(e -> grades.put(Long.parseLong(e.getKey()), e.getValue().asInt()));
                JsonNode layer = n.get("layer");
                String qid = n.get("qid").asText();
                out.add(new EvalQuery(qid, n.get("query").asText(), n.get("type").asText(),
                        layer == null || layer.isNull() ? null : layer.asText(), grades,
                        judged.getOrDefault(qid, Set.of())));
            }
        }
        return out;
    }

    /** 读缓存，缺的批量向量化后追加写回。缓存行：{"model":..., "query":..., "vector":[...]}。 */
    private Map<String, float[]> queryVectors(Path evalDir, List<EvalQuery> queries) throws IOException {
        Path cache = evalDir.resolve("work").resolve("query_vectors.jsonl");
        String model = embeddingClient.modelId();
        Map<String, float[]> out = new HashMap<>();
        if (Files.exists(cache)) {
            for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode n = mapper.readTree(line);
                if (!model.equals(n.get("model").asText())) {
                    continue;
                }
                JsonNode arr = n.get("vector");
                float[] v = new float[arr.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) arr.get(i).asDouble();
                }
                out.put(n.get("query").asText(), v);
            }
        }
        List<String> missing = queries.stream().map(EvalQuery::query).distinct().filter(q -> !out.containsKey(q)).toList();
        if (missing.isEmpty()) {
            System.out.printf("   query 向量全部命中缓存（%s）%n", model);
            return out;
        }
        System.out.printf("   向量化 %d 条未缓存的 query（%s）…%n", missing.size(), model);
        List<float[]> vectors = embeddingClient.embedQueries(missing);
        Files.createDirectories(cache.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(cache, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (int i = 0; i < missing.size(); i++) {
                out.put(missing.get(i), vectors.get(i));
                ObjectNode row = mapper.createObjectNode();
                row.put("model", model);
                row.put("query", missing.get(i));
                ArrayNode arr = row.putArray("vector");
                for (float x : vectors.get(i)) {
                    arr.add(x);
                }
                w.write(mapper.writeValueAsString(row));
                w.newLine();
            }
        }
        return out;
    }

    private static void writeTsv(Path file, List<QueryResult> results) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("qid\ttype\tlayer\tn_relevant\treturned\tjudged\trecall\tprecision\trr\tndcg\tlatency_ms\tranked");
            w.newLine();
            for (QueryResult r : results) {
                EvalQuery q = r.query();
                // ranked 写成 post_id:grade，判过 0 分记 0、未判定记 ?，便于直接看出哪几名命中、哪几名是池外的
                String ranked = r.ranked().stream()
                        .map(id -> id + ":" + (q.judged().contains(id) ? q.grades().getOrDefault(id, 0) : "?"))
                        .collect(Collectors.joining(","));
                w.write(String.join("\t", q.qid(), q.type(), Objects.toString(q.layer(), ""),
                        String.valueOf(RetrievalMetrics.relevantSet(q.grades()).size()), String.valueOf(r.ranked().size()),
                        String.valueOf(r.ranked().stream().filter(q.judged()::contains).count()),
                        cell(r.recall()), cell(r.precision()), cell(r.rr()), cell(r.ndcg()),
                        String.format("%.2f", r.nanos() / 1e6), ranked));
                w.newLine();
            }
        }
    }

    private static String cell(Double v) {
        return v == null ? "" : String.format("%.4f", v);
    }

    /** rerank 候选的正文：一次 findAllById（实体上的 @Where 已滤掉软删除的帖子）。 */
    private Map<Long, String> postTexts(Collection<Long> ids) {
        Map<Long, String> out = new HashMap<>();
        for (Post p : postRepository.findAllById(ids)) {
            out.put(p.getId(), p.getContent());
        }
        return out;
    }

    /** 扫参时策略名要带上参数，结果文件与汇总表才分得开。 */
    private static RetrievalStrategy named(String name, RetrievalStrategy inner) {
        return new RetrievalStrategy() {
            public String name() { return name; }
            public List<ScoredPost> retrieve(String query, int k, Long requesterId) {
                return inner.retrieve(query, k, requesterId);
            }
        };
    }

    /** 1.0 → "1"，1.5 → "1.5"：策略名里不出现多余的 ".0"。 */
    static String fmtWeight(double w) {
        return w == Math.rint(w) ? String.valueOf((long) w) : String.valueOf(w);
    }

    private static List<Integer> ints(String csv) {
        return Arrays.stream(csv.split(",")).map(String::strip).filter(v -> !v.isEmpty()).map(Integer::parseInt).toList();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /**
     * grades 只含 grade &gt; 0 的帖子（queries.jsonl 的口径），为空即无答案 query；
     * judged 是 qrels.tsv 里判定过的全部帖子（含 0 分），只用来算判定覆盖率。
     */
    record EvalQuery(String qid, String query, String type, String layer, Map<Long, Integer> grades, Set<Long> judged) {
        boolean isNoAnswer() {
            return RetrievalMetrics.relevantSet(grades).isEmpty();
        }
    }

    /** 一个 rerank 策略的评测探针：client 记 token，memo 让打分复用计时那次的结果。 */
    record RerankProbe(VoyageReranker client, MemoReranker memo, int n) {}

    /**
     * 按 (k, query, 候选 id 序列) 缓存重排结果。计时那次调用之后，打分的那次（同 query、同深度 k）直接命中，
     * 不重复计费；候选不同（加深重取）就是不同的 key，照常打 API。评测单线程，用 HashMap 即可。
     */
    static final class MemoReranker implements Reranker {
        private final Reranker inner;
        private final Map<String, List<ScoredPost>> cache = new HashMap<>();

        MemoReranker(Reranker inner) {
            this.inner = inner;
        }

        @Override
        public List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k) {
            String key = k + "\u0000" + query + "\u0000"
                    + candidates.stream().map(c -> String.valueOf(c.postId())).collect(Collectors.joining(","));
            List<ScoredPost> hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
            List<ScoredPost> out = inner.rerank(query, candidates, k);
            cache.put(key, out);
            return out;
        }

        @Override
        public String modelId() {
            return inner.modelId();
        }

        void clear() {
            cache.clear();
        }
    }

    /** 无答案 query 的四个指标为 null。 */
    record QueryResult(EvalQuery query, List<Long> ranked, long nanos,
                       Double recall, Double precision, Double rr, Double ndcg) {}
}
