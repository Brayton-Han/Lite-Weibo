package com.brayton.weibo.service;

import com.brayton.weibo.service.RedisService.FeedEntry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisServiceMergedFeedTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
    private final RedisService service = new RedisService(redis);

    @Test
    void mergesInboxAndOutboxesByScoreDedupesAndTagsSource() {
        // 管道结果顺序：收件箱，然后按 pullAuthorIds 顺序的各发件箱
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(
                tuples(10L, 500, 7L, 300),          // feed:1
                tuples(20L, 400, 7L, 300),          // outbox:100，7 两边都有
                tuples(30L, 600, 31L, 100)));       // outbox:200

        List<FeedEntry> merged = service.getMergedFeedAfter(1L, List.of(100L, 200L), Long.MAX_VALUE, 4);

        assertThat(merged).extracting(FeedEntry::postId).containsExactly(30L, 10L, 20L, 7L);
        // 重复的 7 保留收件箱那份（排序稳定，收件箱排在前面）
        assertThat(merged).extracting(FeedEntry::outboxAuthorId).containsExactly(200L, null, 100L, null);
    }

    @Test
    void returnsFewerThanSizeOnlyWhenAllSourcesAreExhausted() {
        when(redis.executePipelined(any(SessionCallback.class))).thenReturn(List.of(
                tuples(10L, 500), Set.of()));

        assertThat(service.getMergedFeedAfter(1L, List.of(100L), Long.MAX_VALUE, 5)).hasSize(1);
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
