package com.brayton.weibo.generation;

/**
 * Claude 调用失败，已按能不能重试分好类。
 * 与 {@code RerankException} 一样是基础设施错误，不走 WeiboException 的错误码体系；调用方按 {@link #kind()} 决定降级还是报错。
 *
 * <p>SDK 自己已经对 408/409/429/5xx 与连接错误重试过 {@code claude.max-retries} 次，
 * 走到这里的都是重试耗尽或本来就不该重试的。{@link #retryable()} 的意思是"稍后再来可能会好"，不是"马上再试一次"。
 */
public class ClaudeCallException extends RuntimeException {

    public enum Kind {
        /** 401/403/404：key 无效、没权限、模型 id 写错。重试没用，要改配置 */
        CONFIG(false),
        /** 400/422 等其他 4xx：请求本身有问题，是我们的 bug */
        BAD_REQUEST(false),
        /** 429：限流，SDK 退避重试后仍被限 */
        RATE_LIMITED(true),
        /** 5xx / 529 overloaded：上游故障 */
        UPSTREAM(true),
        /** 拿到响应之前就断了：DNS、连接 */
        NETWORK(true),
        /** 超过这次调用的超时（含流式读到一半超时），见 {@code ClaudeGateway} */
        TIMEOUT(true),
        /** HTTP 200 但结果不可用：被截断（max_tokens）、拒答（refusal）、缺字段 */
        BAD_RESPONSE(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }
    }

    private final Kind kind;

    public ClaudeCallException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ClaudeCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return kind.retryable;
    }
}
