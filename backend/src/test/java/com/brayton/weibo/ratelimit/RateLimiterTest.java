package com.brayton.weibo.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 令牌桶本身的行为（Lua）用真实 Redis 验过，见 EVAL.md「Day 17」；这里测 Java 一侧的解析与兜底 */
class RateLimiterTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final RateLimiter limiter = new RateLimiter(redis, meters, true);
    private final RateLimiter.Policy ask = new RateLimiter.Policy("ask", 20, Duration.ofHours(1));

    @Test
    @SuppressWarnings("unchecked")
    void passesCapacityAndRefillRateToTheScript() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(List.of(1L, 0L));

        assertThat(limiter.tryAcquire(ask, 7L).allowed()).isTrue();
        // 20 个 / 3_600_000 ms
        verify(redis).execute(eq(RateLimiter.TOKEN_BUCKET), eq(List.of("ratelimit:ask:7")),
                eq("20"), eq(String.valueOf(20 / 3_600_000.0)));
    }

    @Test
    void rejectionRoundsTheWaitUpToWholeSeconds() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(List.of(0L, 179_001L));

        RateLimiter.Decision d = limiter.tryAcquire(ask, 7L);

        assertThat(d.allowed()).isFalse();
        assertThat(d.retryAfterSeconds()).isEqualTo(180);
        assertThat(meters.counter("ratelimit.decisions", "policy", "ask", "result", "rejected").count()).isEqualTo(1);
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(limiter.tryAcquire(ask, 7L).allowed()).isTrue();
        assertThat(meters.counter("ratelimit.decisions", "policy", "ask", "result", "error").count()).isEqualTo(1);
    }

    @Test
    void anonymousAndDisabledAreNotLimited() {
        assertThat(limiter.tryAcquire(ask, null).allowed()).isTrue();
        assertThat(new RateLimiter(redis, meters, false).tryAcquire(ask, 7L).allowed()).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    void checkThrowsForTheRejectingEndpoints() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenReturn(List.of(0L, 500L));

        assertThatThrownBy(() -> limiter.check(ask, 7L))
                .isInstanceOf(RateLimitedException.class)
                .extracting(e -> ((RateLimitedException) e).retryAfterSeconds()).isEqualTo(1L);
    }
}
