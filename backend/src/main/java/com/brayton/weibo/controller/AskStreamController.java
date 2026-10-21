package com.brayton.weibo.controller;

import com.brayton.weibo.config.AsyncConfig;
import com.brayton.weibo.config.security.CustomUserDetails;
import com.brayton.weibo.dto.AskResponse;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.error.WeiboException;
import com.brayton.weibo.generation.QueryIntent;
import com.brayton.weibo.service.AskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 流式问答（RAG Day 16）：与 {@code GET /posts/search/ask} 同一条路径，用 SSE 推进度。事件按顺序：
 * <pre>
 * intent  {"intent": "EXPERIENCE" | "RESONANCE" | "RISK"}   意图分类完成，前端据此决定要不要展示帖子
 * delta   {"text": "..."}                                    生成的文本增量，只有经验类且过了检索阈值才有
 * result  AskResponse                                        最终结果，以它为准（可能把已推出的文本撤回成拒答）
 * error   {"code": 1802, "message": "..."}                   与非流式接口的错误码一致
 * </pre>
 * result 和 error 二选一，发完即关闭连接。用户关掉页面后下一次推送失败，生成随之中止，不再为没人看的回答付输出 token。
 * <p>Day 17 起降级（被限流、熔断、Claude 不可用 / 超时、线程池已满）都走 {@code result}（mode = DEGRADED），
 * error 只剩 query 不合法和意料之外的异常。
 */
@Slf4j
@RestController
public class AskStreamController {

    /**
     * 正常 10s 内结束。上限按最坏情况算：分类 8s × 2 次 + 生成 30s（流式读到一半超时不重试，但连接阶段的超时会重试一次）× 2，
     * 约 76s，再留点余量；超过就是挂住了
     */
    private static final long TIMEOUT_MS = 90_000;

    private final AskService askService;
    private final TaskExecutor executor;

    public AskStreamController(AskService askService, @Qualifier(AsyncConfig.ASK_EXECUTOR) TaskExecutor executor) {
        this.askService = askService;
        this.executor = executor;
    }

    @GetMapping(value = "/posts/search/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestParam String q, @AuthenticationPrincipal CustomUserDetails self) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(e -> closed.set(true));
        emitter.onCompletion(() -> closed.set(true));

        Long selfId = self.getId();
        try {
            executor.execute(() -> run(emitter, closed, q, selfId));
        } catch (TaskRejectedException e) {
            log.warn("流式问答线程池已满，降级");
            // 请求线程上只做本地的风险词表判断，不调外部服务
            try {
                send(emitter, closed, "result", askService.overloaded(q, selfId, listener(emitter, closed)));
                emitter.complete();
            } catch (WeiboException ex) {
                sendError(emitter, closed, ex.getCode(), ex.getMessage());
            } catch (UncheckedIOException ex) {
                emitter.completeWithError(ex);
            }
        }
        return emitter;
    }

    private void run(SseEmitter emitter, AtomicBoolean closed, String q, Long selfId) {
        try {
            AskResponse result = askService.askStreaming(q, selfId, listener(emitter, closed));
            send(emitter, closed, "result", result);
            emitter.complete();
        } catch (WeiboException e) {
            sendError(emitter, closed, e.getCode(), e.getMessage());
        } catch (UncheckedIOException e) {
            // 客户端断开：推送失败即中止生成，这里不用再回应
            log.info("流式问答客户端已断开，中止：{}", e.getMessage());
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            log.error("流式问答失败", e);
            sendError(emitter, closed, CommonErrorCode.ASK_UNAVAILABLE.getCode(), CommonErrorCode.ASK_UNAVAILABLE.getMessage());
        }
    }

    private static AskService.AskListener listener(SseEmitter emitter, AtomicBoolean closed) {
        return new AskService.AskListener() {
            @Override
            public void onIntent(QueryIntent intent) {
                send(emitter, closed, "intent", Map.of("intent", intent));
            }

            @Override
            public void onDelta(String text) {
                send(emitter, closed, "delta", Map.of("text", text));
            }
        };
    }

    /** 推送失败（连接已断、已超时）抛 {@link UncheckedIOException}，从回调里一路抛出去中止生成 */
    private static void send(SseEmitter emitter, AtomicBoolean closed, String name, Object data) {
        if (closed.get()) {
            throw new UncheckedIOException(new IOException("SSE 连接已关闭"));
        }
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            closed.set(true);
            throw new UncheckedIOException(e);
        }
    }

    private static void sendError(SseEmitter emitter, AtomicBoolean closed, int code, String message) {
        try {
            send(emitter, closed, "error", Map.of("code", code, "message", message));
            emitter.complete();
        } catch (UncheckedIOException ignored) {
            emitter.completeWithError(ignored);
        }
    }
}
