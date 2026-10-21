package com.brayton.weibo.service;

import com.brayton.weibo.dto.AskResponse.Mode;
import com.brayton.weibo.generation.CitedAnswer;
import com.brayton.weibo.generation.QueryIntent;
import com.brayton.weibo.retrieval.ScoredPost;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AskCacheTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AskCache cache = new AskCache(redis, new ObjectMapper(), new SimpleMeterRegistry(),
            true, Duration.ofMinutes(30), 0.40, 10);

    {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void sameQuestionWithDifferentSpacingOrCaseSharesAKey() {
        assertThat(cache.key("  Redisson  看门狗 ")).isEqualTo(cache.key("redisson 看门狗"));
        assertThat(cache.key("redisson 看门狗")).isNotEqualTo(cache.key("redisson看门狗续期"));
    }

    @Test
    void differentRetrievalConfigDoesNotShareEntries() {
        AskCache other = new AskCache(redis, new ObjectMapper(), new SimpleMeterRegistry(),
                true, Duration.ofMinutes(30), 0.45, 10);
        assertThat(other.key("q")).isNotEqualTo(cache.key("q"));
    }

    @Test
    void entriesRoundTripThroughJson() {
        AtomicReference<String> stored = new AtomicReference<>();
        doAnswer(inv -> { stored.set(inv.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString(), eq(Duration.ofMinutes(30)));
        CitedAnswer answer = new CitedAnswer("押一付三",
                List.of(new CitedAnswer.Segment("押一付三", List.of(new CitedAnswer.Citation(1, 9, "押一付三", 0, 4)))),
                List.of(new CitedAnswer.Source(1, 9, "@a · 2026-03-01")));
        AskCache.Entry entry = new AskCache.Entry(QueryIntent.EXPERIENCE, Mode.ANSWER, null, answer, null);
        AskCache.Entry resonance = new AskCache.Entry(QueryIntent.RESONANCE, Mode.RESONANCE, null, null,
                List.of(new ScoredPost(3, 0.7)));

        cache.put("q", entry);
        when(ops.get(cache.key("q"))).thenReturn(stored.get());
        assertThat(cache.get("q")).contains(entry);

        cache.put("r", resonance);
        when(ops.get(cache.key("r"))).thenReturn(stored.get());
        assertThat(cache.get("r")).contains(resonance);
    }

    @Test
    void redisFailureIsAMiss() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(cache.get("q")).isEmpty();
    }
}
