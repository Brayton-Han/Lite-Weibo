package com.brayton.weibo.service;

import com.brayton.weibo.service.RedisService.FeedEntry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisServiceMergedFeedTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, Object> zset = mock(ZSetOperations.class);

    /** 发件箱内容，供 OutboxCache 的加载器读取 */
    private final Map<Long, OutboxCache.Snapshot> outboxes = new HashMap<>();
    private final OutboxCache outboxCache = new OutboxCache(
            ids -> { Map<Long, OutboxCache.Snapshot> m = new HashMap<>(); ids.forEach(id -> {
                if (outboxes.containsKey(id)) m.put(id, outboxes.get(id)); }); return m; },
            Duration.ofSeconds(3), 10_000, Ticker.systemTicker());
    private final RedisService service = new RedisService(redis, outboxCache);

    @Test
    void mergesInboxAndOutboxesByScoreDedupesAndTagsSource() {
        inbox(tuples(10L, 500, 7L, 300));
        outboxes.put(100L, snapshot(20L, 400, 7L, 300));   // 7 两边都有
        outboxes.put(200L, snapshot(30L, 600, 31L, 100));

        List<FeedEntry> merged = service.getMergedFeedAfter(1L, List.of(100L, 200L), Long.MAX_VALUE, 4);

        assertThat(merged).extracting(FeedEntry::postId).containsExactly(30L, 10L, 20L, 7L);
        // 重复的 7 保留收件箱那份（排序稳定，收件箱排在前面）
        assertThat(merged).extracting(FeedEntry::outboxAuthorId).containsExactly(200L, null, 100L, null);
    }

    @Test
    void outboxRespectsCursorLikeRedisWould() {
        inbox(Set.of());
        outboxes.put(100L, snapshot(3L, 600, 2L, 500, 1L, 400));

        // score < 500，等价于 ZREVRANGEBYSCORE -inf 499
        List<FeedEntry> merged = service.getMergedFeedAfter(1L, List.of(100L), 500, 5);

        assertThat(merged).extracting(FeedEntry::postId).containsExactly(1L);
    }

    @Test
    void returnsFewerThanSizeOnlyWhenAllSourcesAreExhausted() {
        inbox(tuples(10L, 500));

        assertThat(service.getMergedFeedAfter(1L, List.of(100L), Long.MAX_VALUE, 5)).hasSize(1);
    }

    @Test
    void randomSampleDrawsOutboxesFromLocalCopyWithoutTouchingRedis() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard("feed:1")).thenReturn(0L);
        outboxes.put(100L, snapshot(20L, 400, 21L, 300));
        outboxes.put(200L, snapshot(30L, 600));

        List<Long> ids = service.getRandomFeedSample(1L, List.of(100L, 200L), 50);

        assertThat(ids).hasSize(50).allMatch(id -> Set.of(20L, 21L, 30L).contains(id));
        verify(redis, never()).executePipelined(any(SessionCallback.class));
    }

    @Test
    void randomSampleReadsInboxPicksInOnePipeline() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard("feed:1")).thenReturn(3L);
        when(redis.executePipelined(any(SessionCallback.class)))
                .thenAnswer(inv -> java.util.Collections.nCopies(10, Set.of(10L)));

        List<Long> ids = service.getRandomFeedSample(1L, List.of(), 10);

        assertThat(ids).hasSize(10).containsOnly(10L);
        verify(redis, times(1)).executePipelined(any(SessionCallback.class));
    }

    @Test
    void randomSampleIsEmptyWhenAllSourcesAreEmpty() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.zCard("feed:1")).thenReturn(0L);

        assertThat(service.getRandomFeedSample(1L, List.of(100L), 10)).isEmpty();
    }

    private void inbox(Set<TypedTuple<Object>> tuples) {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.reverseRangeByScoreWithScores(eq("feed:1"), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(tuples);
    }

    private static OutboxCache.Snapshot snapshot(Object... idAndScore) {
        return OutboxCache.Snapshot.of(tuples(idAndScore));
    }

    /** 交替传入 postId, score */
    private static Set<TypedTuple<Object>> tuples(Object... idAndScore) {
        Set<TypedTuple<Object>> set = new LinkedHashSet<>();
        for (int i = 0; i < idAndScore.length; i += 2) {
            set.add(new DefaultTypedTuple<>(idAndScore[i], ((Number) idAndScore[i + 1]).doubleValue()));
        }
        return set;
    }
}
