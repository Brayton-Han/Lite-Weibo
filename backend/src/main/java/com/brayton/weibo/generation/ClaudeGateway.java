package com.brayton.weibo.generation;

import com.anthropic.client.AnthropicClient;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
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
 */
@Slf4j
@Component
public class ClaudeGateway {

    private final AnthropicClient client;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong cacheWriteTokens = new AtomicLong();
    private final AtomicLong cacheReadTokens = new AtomicLong();

    public ClaudeGateway(AnthropicClient client) {
        this.client = client;
    }

    /** @param purpose 这次调用的用途，进日志，如 "intent" / "answer" */
    public Message create(String purpose, MessageCreateParams params) {
        return call(purpose, params.model().asString(),
                () -> client.messages().create(params), Function.identity());
    }

    /**
     * 流式调用：每段文本增量交给 {@code onText}，结束后返回拼好的完整 {@link Message}，与非流式一样走错误分级和用量记录。
     * 流中途断开同样抛 {@link ClaudeCallException}；{@code onText} 自己抛的异常原样往外抛（用来中止生成），这时不记用量。
     */
    public Message stream(String purpose, MessageCreateParams params, Consumer<String> onText) {
        return call(purpose, params.model().asString(), () -> {
            MessageAccumulator accumulator = MessageAccumulator.create();
            try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params)) {
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
        return call(purpose, params.rawParams().model().asString(),
                () -> client.messages().create(params), StructuredMessage::rawMessage);
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
        } catch (NotFoundException e) {
            throw fail(purpose, ClaudeCallException.Kind.CONFIG, "404，模型 id 可能写错了：" + model, e);
        } catch (RateLimitException e) {
            throw fail(purpose, ClaudeCallException.Kind.RATE_LIMITED, "429 限流，SDK 重试后仍被限", e);
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw fail(purpose, ClaudeCallException.Kind.CONFIG,
                    e.statusCode() + "，检查环境变量 ANTHROPIC_API_KEY 与账号权限", e);
        } catch (InternalServerException e) {
            throw fail(purpose, ClaudeCallException.Kind.UPSTREAM, e.statusCode() + " 上游故障", e);
        } catch (AnthropicServiceException e) {
            // 529 overloaded 不在具名子类里，按状态码归到上游；其余 4xx 是请求本身的问题
            ClaudeCallException.Kind kind = e.statusCode() >= 500
                    ? ClaudeCallException.Kind.UPSTREAM : ClaudeCallException.Kind.BAD_REQUEST;
            throw fail(purpose, kind, "HTTP " + e.statusCode() + " " + e.errorType().map(Object::toString).orElse(""), e);
        } catch (AnthropicIoException e) {
            throw fail(purpose, ClaudeCallException.Kind.NETWORK, "网络错误", e);
        } catch (AnthropicRetryableException e) {
            throw fail(purpose, ClaudeCallException.Kind.NETWORK, "可重试错误，重试耗尽", e);
        }
        record(purpose, raw.apply(response), (System.nanoTime() - start) / 1_000_000);
        return response;
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
