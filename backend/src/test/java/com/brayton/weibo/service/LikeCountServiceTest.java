package com.brayton.weibo.service;

import com.brayton.weibo.repository.LikeCountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeCountServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final LikeCountRepository repository = mock(LikeCountRepository.class);
    private final LikeCountService service = new LikeCountService(redis, repository);

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void returnsRedisCountsWithoutTouchingDb() {
        when(values.multiGet(List.of("cnt:like:1", "cnt:like:2"))).thenReturn(List.of("5", "0"));

        assertThat(service.getCounts(List.of(1L, 2L))).isEqualTo(Map.of(1L, 5L, 2L, 0L));
        verifyNoInteractions(repository);
    }

    @Test
    void rebuildsOnlyMissesWhoseLockWasAcquired() {
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("5", null, null));
        // 第一次管道：抢锁，2 抢到、3 没抢到；第二次管道：回写计数
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenReturn(List.of(true, false))
                .thenReturn(List.of());
        when(repository.countByPostIds(List.of(2L))).thenReturn(Map.of(2L, 42L));

        Map<Long, Long> counts = service.getCounts(List.of(1L, 2L, 3L));

        // 3 不返回，由调用方用 DB 副本兜底
        assertThat(counts).isEqualTo(Map.of(1L, 5L, 2L, 42L));
        verify(repository).countByPostIds(List.of(2L));
    }

    @Test
    void redisFailureReturnsEmptySoCallerFallsBack() {
        when(values.multiGet(anyList())).thenThrow(new QueryTimeoutException("redis down"));

        assertThat(service.getCounts(List.of(1L))).isEmpty();
    }

    @Test
    void adjustMarksDirtyOutsideTransaction() {
        service.adjust(9L, 1);

        verify(redis).execute(any(RedisScript.class),
                eq(List.of("cnt:like:9", LikeCountService.DIRTY)), eq("1"), eq("9"));
    }

    @Test
    void flushWritesAbsoluteValuesAndRecountsExpiredCounters() {
        when(redis.execute(any(RedisScript.class), eq(List.of(LikeCountService.DIRTY, LikeCountService.FLUSHING))))
                .thenReturn(List.of("1", "2"));
        when(values.multiGet(List.of("cnt:like:1", "cnt:like:2"))).thenReturn(Arrays.asList("7", null));
        when(repository.countByPostIds(List.of(2L))).thenReturn(Map.of(2L, 3L));

        service.flush();

        verify(repository).updateLikeCounts(Map.of(1L, 7L, 2L, 3L));
        verify(redis).delete(LikeCountService.FLUSHING);
    }

    @Test
    void flushKeepsProcessingSetWhenDbWriteFails() {
        when(redis.execute(any(RedisScript.class), eq(List.of(LikeCountService.DIRTY, LikeCountService.FLUSHING))))
                .thenReturn(List.of("1"));
        when(values.multiGet(anyList())).thenReturn(List.of("7"));
        when(repository.countByPostIds(anyList())).thenReturn(Map.of());
        doThrow(new QueryTimeoutException("db down")).when(repository).updateLikeCounts(anyMap());

        service.flush();

        // 处理中集合保留，下一轮重做
        verify(redis, never()).delete(LikeCountService.FLUSHING);
    }
}
