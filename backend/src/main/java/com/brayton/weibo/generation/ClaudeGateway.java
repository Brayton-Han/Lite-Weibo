package com.brayton.weibo.generation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.Usage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 所有 Claude 调用的唯一出口：统一做分级错误处理和 token 用量记录。
 *
 * <h3>分级捕获</h3>
 * 从最具体的子类往基类捕获，每类单独处理，不用一个 catch 全兜住——否则分不清能不能重试：
 * {@code NotFoundException} → {@code RateLimitException} → 401/403 → 5xx → 其余 {@code AnthropicServiceException}
 * → {@code AnthropicIoException}（网络）→ {@code AnthropicRetryableException}。结果统一转成 {@link ClaudeCallException}。
 *
 * <h3>用量</h3>
 * 每次成功调用打一行 info：用途、模型、输入/输出/缓存 token、耗时、message id。
 * 累计值在 {@link #totals()}，评测用它算单次成本（与 {@code VoyageReranker#totalTokens()} 同一用法）。
 * 注意 API 的 {@code input_tokens} 不含缓存部分：缓存写入按输入价 1.25 倍计费、读取约 0.1 倍，三者分开累计，
 * 只看 {@code input_tokens} 会少算成本。
 *
 * <h3>超时（Day 17）</h3>
 * 调用方按用途传单次超时（{@link RequestOptions#timeout}，即 OkHttp 的 callTimeout，流式时覆盖整个响应体），
 * 不传时用 client 级的 {@code claude.timeout-ms}。SDK 会把超时当网络错误重试，所以墙钟上限是 超时 ×（重试次数 + 1）；
 * 流读到一半超时不会重试（已经推出去的文本没法撤回重来），直接失败。超时单独归为 {@link ClaudeCallException.Kind#TIMEOUT}。
 *
 * <h3>指标与成本</h3>
 * Micrometer：{@code claude.calls}（耗时，按 purpose / outcome）、{@code claude.tokens}（按 purpose / type）、
 * {@code claude.prompt.cache.hit.ratio}（缓存读 / 全部输入）。每次成功调用的用量还会交给 {@link UsageListener}，
 * 成本熔断（{@code AiBudgetService}）靠它累计当日花费。
 */
@Slf4j
@Component
public class ClaudeGateway {

    /** 一次成功调用的 token 用量，交给 {@link UsageListener} */
    public record CallUsage(String purpose, String model, long inputTokens, long outputTokens,
                            long cacheWriteTokens, long cacheReadTokens) {}

    public interface UsageListener {
        void onUsage(CallUsage usage);
    }

    private final AnthropicClient client;
    private final MeterRegistry meters;
    private final List<UsageListener> listeners;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cacheWriteTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();

    /** 单测与评测工具用：不上报指标、不累计成本 */
    public ClaudeGateway(AnthropicClient client) {
        this(client, new SimpleMeterRegistry(), List.of());
    }

    @Autowired
    public ClaudeGateway(AnthropicClient client, MeterRegistry meters, List<UsageListener> listeners) {
        this.client = client;
        this.meters = meters;
        this.listeners = List.copyOf(listeners);
        Gauge.builder("claude.prompt.cache.hit.ratio", this, ClaudeGateway::cacheHitRatio)
                .description("缓存读 token / 全部输入 token（未命中 + 缓存写 + 缓存读），进程启动以来")
                .register(meters);
    }

    /** @param purpose 这次调用的用途，进日志和指标，如 "intent" / "answer" */
    public Message create(String purpose, MessageCreateParams params) {
        return create(purpose, params, null);
    }

    /** @param timeout 单次调用超时；null 用 client 级配置 */
    public Message create(String purpose, MessageCreateParams params, Duration timeout) {
        return call(purpose, params.model().asString(),
                () -> timeout == null ? client.messages().create(params)
                        : client.messages().create(params, options(timeout)),
                Function.identity());
    }

    /**
     * 流式调用：每段文本增量交给 {@code onText}，结束后返回拼好的完整 {@link Message}，与非流式一样走错误分级和用量记录。
     * 流中途断开同样抛 {@link ClaudeCallException}；{@code onText} 自己抛的异常原样往外抛（用来中止生成），这时不记用量。
     */
    public Message stream(String purpose, MessageCreateParams params, Consumer<String> onText) {
        return stream(purpose, params, null, onText);
    }

    public Message stream(String purpose, MessageCreateParams params, Duration timeout, Consumer<String> onText) {
        return call(purpose, params.model().asString(), () -> {
            MessageAccumulator accumulator = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> response = timeout == null
                    ? client.messages().createStreaming(params)
                    : client.messages().createStreaming(params, options(timeout))) {
                response.stream().forEach(event -> {
                    accumulator.accumulate(event);
                    event.contentBlockDelta()
                            .flatMap(d -> d.delta().text())
                            .ifPresent(t -> onText.accept(t.text()));
                });
            }
            return accumulator.message();
        }, Function.identity());
    }

    public <T> StructuredMessage<T> create(String purpose, StructuredMessageCreateParams<T> params) {
        return create(purpose, params, null);
    }

    public <T> StructuredMessage<T> create(String purpose, StructuredMessageCreateParams<T> params, Duration timeout) {
        return call(purpose, params.rawParams().model().asString(),
                () -> timeout == null ? client.messages().create(params)
                        : client.messages().create(params, options(timeout)),
                StructuredMessage::rawMessage);
    }

    private static RequestOptions options(Duration timeout) {
        return RequestOptions.builder().timeout(timeout).build();
    }

    public UsageTotals totals() {
        return new UsageTotals(calls.get(), inputTokens.get(), outputTokens.get(),
                cacheWriteTokens.get(), cacheReadTokens.get());
    }

    /** {@code inputTokens} 是未命中缓存的输入，不含 cacheWrite / cacheRead */
    public record UsageTotals(long calls, long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {}

    private <R> R call(String purpose, String model, Supplier<R> request, Function<R, Message> raw) {
        long start = System.nanoTime();
        R response;
        try {
            response = request.get();
        } catch (RuntimeException e) {
            ClaudeCallException failure = classify(purpose, model, e);
            if (failure == null) {
                // onText 自己抛的异常（中止生成）原样往外抛
                timer(purpose, "aborted", start);
                throw e;
            }
            timer(purpose, failure.kind().name(), start);
            throw failure;
        }
        timer(purpose, "ok", start);
        record(purpose, raw.apply(response), (System.nanoTime() - start) / 1_000_000);
        return response;
    }

    /** @return null 表示不是 SDK 的异常 */
    private static ClaudeCallException classify(String purpose, String model, RuntimeException ex) {
        try {
            throw ex;
        } catch (NotFoundException e) {
            return fail(purpose, ClaudeCallException.Kind.CONFIG, "404，模型 id 可能写错了：" + model, e);
        } catch (RateLimitException e) {
            return fail(purpose, ClaudeCallException.Kind.RATE_LIMITED, "429 限流，SDK 重试后仍被限", e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            return fail(purpose, ClaudeCallException.Kind.CONFIG,
                    e.statusCode() + "，检查环境变量 ANTHROPIC_API_KEY 与账号权限", e);
        } catch (InternalServerException e) {
            return fail(purpose, ClaudeCallException.Kind.UPSTREAM, e.statusCode() + " 上游故障", e);
        } catch (AnthropicServiceException e) {
            // 529 overloaded 不在具名子类里，按状态码归到上游；其余 4xx 是请求本身的问题
            ClaudeCallException.Kind kind = e.statusCode() >= 500
                    ? ClaudeCallException.Kind.UPSTREAM : ClaudeCallException.Kind.BAD_REQUEST;
            return fail(purpose, kind, "HTTP " + e.statusCode() + " " + e.errorType().map(Object::toString).orElse(""), e);
        } catch (AnthropicIoException e) {
            if (isTimeout(e)) {
                return fail(purpose, ClaudeCallException.Kind.TIMEOUT, "超时", e);
            }
            return fail(purpose, ClaudeCallException.Kind.NETWORK, "网络错误", e);
        } catch (AnthropicRetryableException e) {
            return fail(purpose, ClaudeCallException.Kind.NETWORK, "可重试错误，重试耗尽", e);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** OkHttp 的 callTimeout 抛 InterruptedIOException，读写超时抛它的子类 SocketTimeoutException */
    static boolean isTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private void timer(String purpose, String outcome, long startNanos) {
        Timer.builder("claude.calls")
                .description("Claude 调用耗时；outcome 为 ok、失败类型或 aborted（被调用方中止）")
                .tags("purpose", purpose, "outcome", outcome)
                .register(meters)
                .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    private double cacheHitRatio() {
        double read = cacheReadTokens.get();
        double all = inputTokens.get() + cacheWriteTokens.get() + read;
        return all == 0 ? 0 : read / all;
    }

    private void record(String purpose, Message message, long elapsedMs) {
        Usage u = message.usage();
        long cacheWrite = u.cacheCreationInputTokens().orElse(0L);
        long cacheRead = u.cacheReadInputTokens().orElse(0L);
        calls.incrementAndGet();
        inputTokens.addAndGet(u.inputTokens());
        outputTokens.addAndGet(u.outputTokens());
        cacheWriteTokens.addAndGet(cacheWrite);
        cacheReadTokens.addAndGet(cacheRead);
        log.info("claude purpose={} model={} in={} out={} cache_write={} cache_read={} ms={} stop={} id={}",
                purpose, message.model().asString(), u.inputTokens(), u.outputTokens(), cacheWrite, cacheRead,
                elapsedMs, message.stopReason().map(Object::toString).orElse("-"), message.id());
        tokens(purpose, "input", u.inputTokens());
        tokens(purpose, "output", u.outputTokens());
        tokens(purpose, "cache_write", cacheWrite);
        tokens(purpose, "cache_read", cacheRead);

        CallUsage usage = new CallUsage(purpose, message.model().asString(),
                u.inputTokens(), u.outputTokens(), cacheWrite, cacheRead);
        for (UsageListener listener : listeners) {
            try {
                listener.onUsage(usage);
            } catch (RuntimeException e) {
                // 记账失败不能让一次已经付过钱的成功调用变成失败
                log.warn("claude 用量回调失败 listener={}：{}", listener.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void tokens(String purpose, String type, long n) {
        Counter.builder("claude.tokens").tags("purpose", purpose, "type", type).register(meters).increment(n);
    }

    private static ClaudeCallException fail(String purpose, ClaudeCallException.Kind kind, String detail, Exception e) {
        String requestId = e instanceof AnthropicServiceException s ? requestId(s) : "-";
        log.warn("claude purpose={} 失败 kind={} {} request_id={}：{}", purpose, kind, detail, requestId, e.getMessage());
        return new ClaudeCallException(kind, "Claude 调用失败（" + purpose + "）：" + detail, e);
    }

    private static String requestId(AnthropicServiceException e) {
        List<String> ids = e.headers().values("request-id");
        return ids.isEmpty() ? "-" : ids.get(0);
    }
}
