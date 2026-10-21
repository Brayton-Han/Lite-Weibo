package com.brayton.weibo.rerank;

import com.brayton.weibo.retrieval.ScoredPost;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voyage AI 的 rerank 实现（cross-encoder，托管 API），直接调 REST 接口（POST /v1/rerank），不引 SDK。
 *
 * <h3>配置</h3>
 * 与 {@code VoyageEmbeddingClient} 共用环境变量 {@code VOYAGE_API_KEY}，缺 key 时应用照常启动，只在调用时报错。
 * 模型默认 {@value #DEFAULT_MODEL}，可用 {@code rerank.voyage.model} 改；{@value #LITE_MODEL} 更快更便宜。
 *
 * <h3>计费</h3>
 * 计费 token = query token × 候选数 + 全部候选 token 之和：query 按候选数重复计费，候选数翻倍成本也翻倍。
 * 每次调用的 {@code usage.total_tokens} 累加在 {@link #totalTokens()}，评测用它算单次成本。
 *
 * <h3>两种重试口径</h3>
 * Spring 注入的实例跑在用户的搜索请求里，走快速失败：不重试 429（Voyage 按分钟窗口限流，同一窗口内重试必然再撞），
 * 5xx / 网络错误最多 {@value #ONLINE_MAX_ATTEMPTS} 次，读超时默认 2s；失败抛 {@link RerankException}，由调用方降级。
 * {@link #offline(String)} 给离线评测：429 也按分钟窗口退避重试，超时放宽——评测等得起，被限流打断反而白跑。
 * 退避规则与 {@code VoyageEmbeddingClient} 相同（Retry-After 优先，429 从 20s 起步），理由见该类注释。
 */
@Slf4j
@Component
public class VoyageReranker implements Reranker {

    public static final String DEFAULT_MODEL = "rerank-3";
    public static final String LITE_MODEL = "rerank-3-lite";
    static final int ONLINE_MAX_ATTEMPTS = 2;
    static final int OFFLINE_MAX_ATTEMPTS = 5;

    private final RestClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxAttempts;
    private final boolean retryRateLimit;
    private final Sleeper sleeper;
    private final AtomicLong totalTokens = new AtomicLong();

    @Autowired
    public VoyageReranker(
            @Value("${embedding.voyage.base-url:https://api.voyageai.com}") String baseUrl,
            @Value("${VOYAGE_API_KEY:}") String apiKey,
            @Value("${rerank.voyage.model:" + DEFAULT_MODEL + "}") String model,
            @Value("${rerank.voyage.timeout-ms:2000}") long timeoutMs
    ) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts(Duration.ofMillis(timeoutMs))),
                baseUrl, apiKey, model, ONLINE_MAX_ATTEMPTS, false, Thread::sleep);
    }

    /** 测试入口：注入可被 MockRestServiceServer 绑定的 builder，以及不真睡的 sleeper。 */
    VoyageReranker(RestClient.Builder builder, String baseUrl, String apiKey, String model,
                   int maxAttempts, boolean retryRateLimit, Sleeper sleeper) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = maxAttempts;
        this.retryRateLimit = retryRateLimit;
        this.sleeper = sleeper;
    }

    /** 离线评测用的新实例：同一个 key 与地址，指定模型，429 退避重试，读超时 30s；token 计数独立。 */
    public VoyageReranker offline(String model) {
        return new VoyageReranker(RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts(Duration.ofSeconds(30))),
                baseUrl, apiKey, model, OFFLINE_MAX_ATTEMPTS, true, Thread::sleep);
    }

    @Override
    public List<ScoredPost> rerank(String query, List<RerankCandidate> candidates, int k) {
        if (k <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new RerankException("未设置环境变量 VOYAGE_API_KEY");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        List<String> documents = new ArrayList<>(candidates.size());
        for (RerankCandidate c : candidates) {
            // 空正文在这里拦下来，错误信息能指出是调用方的问题；RerankStrategy 会先把它们挑出去
            if (c.text() == null || c.text().isBlank()) {
                throw new IllegalArgumentException("候选正文不能为空，postId=" + c.postId());
            }
            documents.add(c.text());
        }
        int topK = Math.min(k, candidates.size());
        RerankResponse resp = callWithRetry(new RerankRequest(query, documents, model, topK, false));
        if (resp.usage() != null) {
            totalTokens.addAndGet(resp.usage().totalTokens());
        }
        return toRanking(resp, candidates, topK);
    }

    @Override
    public String modelId() {
        return "voyage/" + model;
    }

    /** 本实例累计消耗的 token（每次调用的 usage.total_tokens 之和）。 */
    public long totalTokens() {
        return totalTokens.get();
    }

    // ---------------------------------------------------------------- 响应

    private static List<ScoredPost> toRanking(RerankResponse resp, List<RerankCandidate> candidates, int topK) {
        if (resp == null || resp.data() == null || resp.data().size() != topK) {
            throw new RerankException("Voyage rerank 返回条数不符：期望 " + topK + "，实际 "
                    + (resp == null || resp.data() == null ? 0 : resp.data().size()));
        }
        BitSet seen = new BitSet(candidates.size());
        for (RerankData d : resp.data()) {
            if (d.index() < 0 || d.index() >= candidates.size() || seen.get(d.index())) {
                throw new RerankException("Voyage rerank 返回了越界或重复的 index " + d.index());
            }
            seen.set(d.index());
        }
        // 文档说 data 已按分数降序，这里仍自己排一次：同分按输入下标（第一阶段名次）打破平局，输出确定
        List<RerankData> sorted = new ArrayList<>(resp.data());
        sorted.sort(Comparator.comparingDouble(RerankData::relevanceScore).reversed()
                .thenComparingInt(RerankData::index));
        return sorted.stream()
                .map(d -> new ScoredPost(candidates.get(d.index()).postId(), d.relevanceScore()))
                .toList();
    }

    // ---------------------------------------------------------------- 重试

    private RerankResponse callWithRetry(RerankRequest req) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post()
                        .uri("/v1/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(req)
                        .retrieve()
                        .body(RerankResponse.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = (status == 429 && retryRateLimit) || status >= 500;
                if (!retryable || attempt >= maxAttempts) {
                    throw new RerankException("Voyage rerank 请求失败 HTTP " + status + "（第 " + attempt
                            + " 次）：" + e.getResponseBodyAsString(), e);
                }
                backoff(attempt, retryAfterMillis(e.getResponseHeaders()), status == 429, "HTTP " + status);
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw new RerankException("Voyage rerank 网络错误，重试 " + attempt + " 次后放弃", e);
                }
                backoff(attempt, -1, false, e.getClass().getSimpleName());
            } catch (RestClientException e) {
                // 拿到响应头之后、读响应体时超时（读超时打在 body 上）或响应体解析失败：不是上面两类，
                // 不接住就会以 500 冒出去、绕过调用方的降级（Day 17 实测 rerank 超时把语义搜索打成了 500）
                if (attempt >= maxAttempts) {
                    throw new RerankException("Voyage rerank 读取响应失败，重试 " + attempt + " 次后放弃：" + e.getMessage(), e);
                }
                backoff(attempt, -1, false, e.getClass().getSimpleName());
            }
        }
    }

    private void backoff(int attempt, long retryAfterMillis, boolean rateLimited, String reason) {
        long wait;
        if (retryAfterMillis > 0) {
            wait = retryAfterMillis;
        } else if (rateLimited) {
            // 分钟窗口限流：20s 起步、封顶 60s，抖动只往上加，见 VoyageEmbeddingClient
            long base = Math.min(60_000L, 20_000L << (attempt - 1));
            wait = (long) (base * (1.0 + ThreadLocalRandom.current().nextDouble() * 0.5));
        } else {
            // 线上只会走到这里一次（最多 2 次尝试），起步 200ms，不让一次搜索多等一秒
            long first = retryRateLimit ? 1_000L : 200L;
            long exp = Math.min(30_000L, first << (attempt - 1));
            wait = (long) (exp * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
        }
        log.warn("Voyage rerank {}，{} ms 后第 {} 次重试", reason, wait, attempt + 1);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RerankException("重试等待被中断", ie);
        }
    }

    private static long retryAfterMillis(HttpHeaders headers) {
        String v = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null) {
            return -1;
        }
        try {
            return Long.parseLong(v.trim()) * 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static SimpleClientHttpRequestFactory timeouts(Duration read) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(read);
        return f;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    // ---------------------------------------------------------------- 报文

    /** truncation 不传，用服务端默认 true：帖子 ≤ 200 字，离单条 32K token 的上限很远。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RerankRequest(
            String query,
            List<String> documents,
            String model,
            @JsonProperty("top_k") Integer topK,
            @JsonProperty("return_documents") Boolean returnDocuments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankResponse(List<RerankData> data, String model, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankData(int index, @JsonProperty("relevance_score") double relevanceScore) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("total_tokens") long totalTokens) {}
}
