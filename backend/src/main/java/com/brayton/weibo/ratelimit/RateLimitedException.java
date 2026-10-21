package com.brayton.weibo.ratelimit;

/** 被限流，由 {@code GlobalExceptionHandler} 转成 HTTP 429 + {@code Retry-After} */
public class RateLimitedException extends RuntimeException {

    private final String policy;
    private final long retryAfterSeconds;

    public RateLimitedException(String policy, long retryAfterSeconds) {
        super("rate limited: " + policy, null, false, false);
        this.policy = policy;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String policy() {
        return policy;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
