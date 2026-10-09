package com.brayton.weibo.embedding;

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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Voyage AI 的 embedding 实现，直接调 REST 接口（POST /v1/embeddings），不引 SDK。
 *
 * <h3>配置</h3>
 * API key 只从环境变量 {@code VOYAGE_API_KEY} 读取，不进任何 yml。
 * 缺 key 时应用照常启动，只在真正调用时报错——不做 RAG 的开发者不该被它挡住。
 *
 * <h3>拆批</h3>
 * 服务端上限是单请求 1000 条、32 万 token（voyage-4）。语料单条 ≤200 字，
 * 按条数切 128 一批离 token 上限还很远，所以不做 token 估算。
 *
 * <h3>重试</h3>
 * 只重试 429、5xx 与网络错误；其余 4xx（key 错、参数错）重试也不会好，立即失败。
 * 服务端给了 Retry-After 时以它为准；否则 429 按分钟窗口退避（≥20s），5xx 与网络错误按秒级指数退避。
 *
 * <h3>查询走快速失败</h3>
 * 上面的退避是为后台入库设计的：任务在线程池里等一分钟无所谓。{@link #embedQuery} 跑在用户的搜索请求里，
 * 限流时等 20–60s 再重试、最多数次，请求就会挂好几分钟。所以查询不重试 429（同一分钟窗口内重试必然再撞），
 * 5xx / 网络错误最多 {@value #QUERY_MAX_ATTEMPTS} 次，失败尽快交还调用方，让用户自己重试。
 */
@Slf4j
@Component
public class VoyageEmbeddingClient implements EmbeddingClient {

    static final int QUERY_MAX_ATTEMPTS = 2;

    private final RestClient http;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int batchSize;
    private final int maxAttempts;
    private final Sleeper sleeper;

    @Autowired
    public VoyageEmbeddingClient(
            @Value("${embedding.voyage.base-url:https://api.voyageai.com}") String baseUrl,
            @Value("${VOYAGE_API_KEY:}") String apiKey,
            @Value("${embedding.voyage.model:voyage-4}") String model,
            @Value("${embedding.voyage.dimensions:1024}") int dimensions,
            @Value("${embedding.voyage.batch-size:128}") int batchSize,
            @Value("${embedding.voyage.max-attempts:5}") int maxAttempts
    ) {
        this(RestClient.builder().baseUrl(baseUrl).requestFactory(timeouts()),
                apiKey, model, dimensions, batchSize, maxAttempts, Thread::sleep);
    }

    /** 测试入口：注入可被 MockRestServiceServer 绑定的 builder，以及不真睡的 sleeper。 */
    VoyageEmbeddingClient(RestClient.Builder builder, String apiKey, String model,
                          int dimensions, int batchSize, int maxAttempts, Sleeper sleeper) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batch-size 必须在 1..1000 之间（Voyage 单请求上限 1000 条）");
        }
        this.http = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sleeper = sleeper;
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(text), "query", Math.min(QUERY_MAX_ATTEMPTS, maxAttempts), false).get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            out.addAll(embed(texts.subList(from, Math.min(from + batchSize, texts.size())), "document",
                    maxAttempts, true));
        }
        return out;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelId() {
        return "voyage/" + model;
    }

    // ---------------------------------------------------------------- 单批请求

    private List<float[]> embed(List<String> batch, String inputType, int attempts, boolean retryRateLimit) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingException("未设置环境变量 VOYAGE_API_KEY");
        }
        for (String t : batch) {
            // Voyage 对空串返回 400；在这里拦下来，错误信息能指出是调用方的问题
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("待向量化文本不能为空");
            }
        }

        // truncation=false：超长输入宁可报错也不静默截断，否则向量只代表前半段且无人知道
        EmbedRequest req = new EmbedRequest(batch, model, inputType, dimensions, false);
        EmbedResponse resp = callWithRetry(req, attempts, retryRateLimit);
        return toVectors(resp, batch.size());
    }

    private EmbedResponse callWithRetry(EmbedRequest req, int maxAttempts, boolean retryRateLimit) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post()
                        .uri("/v1/embeddings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(req)
                        .retrieve()
                        .body(EmbedResponse.class);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean retryable = (status == 429 && retryRateLimit) || status >= 500;
                if (!retryable || attempt >= maxAttempts) {
                    throw new EmbeddingException("Voyage 请求失败 HTTP " + status + "（第 " + attempt
                            + " 次）：" + e.getResponseBodyAsString(), e);
                }
                backoff(attempt, retryAfterMillis(e.getResponseHeaders()), status == 429, "HTTP " + status);
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) {
                    throw new EmbeddingException("Voyage 网络错误，重试 " + attempt + " 次后放弃", e);
                }
                backoff(attempt, -1, false, e.getClass().getSimpleName());
            }
        }
    }

    private List<float[]> toVectors(EmbedResponse resp, int expected) {
        if (resp == null || resp.data() == null || resp.data().size() != expected) {
            throw new EmbeddingException("Voyage 返回条数不符：期望 " + expected + "，实际 "
                    + (resp == null || resp.data() == null ? 0 : resp.data().size()));
        }
        // 文档没有承诺 data 按输入顺序返回，按 index 排一次才能保证与入参一一对应
        List<EmbedData> sorted = new ArrayList<>(resp.data());
        sorted.sort(Comparator.comparingInt(EmbedData::index));

        List<float[]> out = new ArrayList<>(expected);
        for (EmbedData d : sorted) {
            List<Double> v = d.embedding();
            if (v.size() != dimensions) {
                throw new EmbeddingException("向量维度不符：配置 " + dimensions + "，实际 " + v.size());
            }
            float[] f = new float[v.size()];
            for (int i = 0; i < f.length; i++) {
                f[i] = v.get(i).floatValue();
            }
            out.add(f);
        }
        return out;
    }

    // ---------------------------------------------------------------- 退避

    private void backoff(int attempt, long retryAfterMillis, boolean rateLimited, String reason) {
        long wait;
        if (retryAfterMillis > 0) {
            wait = retryAfterMillis;
        } else if (rateLimited) {
            // Voyage 按「每分钟」计 RPM/TPM。秒级退避始终落在同一个分钟窗口里，必然连续 429
            // （实测：免费层 1→2→4→8s 重试全部失败）。所以从 20s 起步、封顶 60s，
            // 抖动只往上加 [1.0, 1.5)，保证不会短于基准值
            long base = Math.min(60_000L, 20_000L << (attempt - 1));
            wait = (long) (base * (1.0 + ThreadLocalRandom.current().nextDouble() * 0.5));
        } else {
            // 5xx / 网络抖动是瞬时故障：1s, 2s, 4s, 8s ... 封顶 30s，乘 [0.5, 1.0) 的抖动错开并发重试
            long exp = Math.min(30_000L, 1_000L << (attempt - 1));
            wait = (long) (exp * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
        }
        log.warn("Voyage {}，{} ms 后第 {} 次重试", reason, wait, attempt + 1);
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("重试等待被中断", ie);
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
            return -1;   // HTTP-date 形式的 Retry-After 少见，退回指数退避
        }
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(60));   // 128 条一批，服务端处理通常在秒级
        return f;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    // ---------------------------------------------------------------- 报文

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EmbedRequest(
            List<String> input,
            String model,
            @JsonProperty("input_type") String inputType,
            @JsonProperty("output_dimension") Integer outputDimension,
            Boolean truncation
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(List<EmbedData> data, String model, Usage usage) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedData(List<Double> embedding, int index) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(@JsonProperty("total_tokens") long totalTokens) {}
}
