package com.brayton.weibo.service;

import com.brayton.weibo.generation.ClaudeGateway.CallUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiBudgetServiceTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T23:30:00Z"), ZoneOffset.UTC);
    private final AiBudgetService budget = new AiBudgetService(redis, new SimpleMeterRegistry(), true, 5.0,
            ZoneId.of("UTC"), clock);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void pricesEachTokenTypeSeparately() {
        // haiku-5-5：输入 0.10、输出 0.50、缓存写 0.125、缓存读 0.01（美元 / 百万 token）
        // 各 1 万 token，总输入 3 万，不到 10 万的长上下文档
        CallUsage u = new CallUsage("answer", "claude-haiku-5-5", 10_000, 10_000, 10_000, 10_000);
        assertThat(budget.cost(u)).isCloseTo((0.10 + 0.50 + 0.125 + 0.01) / 100, within(1e-12));
    }

    @Test
    void haikuLongPromptsUseTheHigherTier() {
        CallUsage u = new CallUsage("answer", "claude-haiku-5-5", 200_000, 0, 0, 0);
        assertThat(budget.cost(u)).isCloseTo(0.10, within(1e-9));   // 0.2 MTok × 0.50
    }

    @Test
    void unknownModelIsPricedAtTheMostExpensiveTier() {
        CallUsage u = new CallUsage("answer", "claude-something-new", 1_000_000, 0, 0, 0);
        assertThat(budget.cost(u)).isCloseTo(10.0, within(1e-9));
    }

    @Test
    void accumulatesMicroDollarsUnderTodaysUtcKey() {
        when(ops.increment(anyString(), anyLong())).thenReturn(150L);

        budget.onUsage(new CallUsage("intent", "claude-haiku-5-5", 1000, 100, 0, 0));

        // 1000 × 0.10 + 100 × 0.50 = 150 微美元
        verify(ops).increment("ai:cost:2026-10-20", 150L);
        verify(redis).expire(eq("ai:cost:2026-10-20"), any());
    }

    @Test
    void exhaustedOnceSpendReachesTheThreshold() {
        when(ops.get("ai:cost:2026-10-20")).thenReturn("4999999");
        assertThat(budget.exhausted()).isFalse();
        when(ops.get("ai:cost:2026-10-20")).thenReturn("5000000");
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    void failsClosedWhenRedisIsDown() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    void disabledBudgetNeverTripsOrRecords() {
        AiBudgetService off = new AiBudgetService(redis, new SimpleMeterRegistry(), false, 5.0, ZoneId.of("UTC"), clock);
        off.onUsage(new CallUsage("intent", "claude-haiku-5-5", 1000, 100, 0, 0));
        assertThat(off.exhausted()).isFalse();
        verifyNoInteractions(ops);
    }
}
