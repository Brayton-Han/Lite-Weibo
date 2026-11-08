package com.brayton.weibo.service;

import com.brayton.weibo.service.RedisService.FeedEntry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxCacheTest {

    private final AtomicLong nanos = new AtomicLong();
    private final List<List<Long>> loads = new CopyOnWriteArrayList<>();
    private final Map<Long, OutboxCache.Snapshot> redis = new ConcurrentHashMap<>();

    private OutboxCache cache(Function<List<Long>, Map<Long, OutboxCache.Snapshot>> loader) {
        return new OutboxCache(loader, Duration.ofSeconds(3), 10_000, nanos::get);
    }

    private final OutboxCache cache = cache(ids -> {
        loads.add(List.copyOf(ids));
        Map<Long, OutboxCache.Snapshot> m = new HashMap<>();
        ids.forEach(id -> { if (redis.containsKey(id)) m.put(id, redis.get(id)); });
        return m;
    });

    @Test
    void servesFromLocalCopyUntilTtlExpires() {
        redis.put(1L, snapshot(10L, 100));

        cache.getAll(List.of(1L));
        redis.put(1L, snapshot(11L, 200, 10L, 100));
        assertThat(cache.getAll(List.of(1L)).get(1L).size()).isEqualTo(1);   // 仍是旧副本
        assertThat(loads).hasSize(1);

        nanos.addAndGet(Duration.ofSeconds(4).toNanos());
        assertThat(cache.getAll(List.of(1L)).get(1L).size()).isEqualTo(2);
        assertThat(loads).hasSize(2);
    }

    @Test
    void invalidateForcesReload() {
        redis.put(1L, snapshot(10L, 100));
        cache.getAll(List.of(1L));

        cache.invalidate(1L);
        cache.getAll(List.of(1L));

        assertThat(loads).hasSize(2);
    }

    @Test
    void batchesMissesIntoOneLoadAndCachesEmptyOutboxes() {
        redis.put(1L, snapshot(10L, 100));
        cache.getAll(List.of(1L));

        Map<Long, OutboxCache.Snapshot> got = cache.getAll(List.of(1L, 2L, 3L));
        cache.getAll(List.of(2L, 3L));

        assertThat(loads).hasSize(2);
        assertThat(loads.get(1)).containsExactlyInAnyOrder(2L, 3L);   // 只回源未命中的，且合并成一次
        assertThat(got.get(2L).size()).isZero();
    }

    @Test
    void concurrentMissesOnSameKeyLoadOnce() throws Exception {
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        OutboxCache slow = cache(ids -> {
            calls.incrementAndGet();
            inLoader.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return Map.of(1L, snapshot(10L, 100));
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<Long, OutboxCache.Snapshot>>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> slow.getAll(List.of(1L))));
            inLoader.await();
            for (int i = 0; i < 7; i++) futures.add(pool.submit(() -> slow.getAll(List.of(1L))));
            Thread.sleep(50);
            release.countDown();
            for (Future<Map<Long, OutboxCache.Snapshot>> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS).get(1L).size()).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void redisFailurePropagatesAndIsNotCached() {
        AtomicInteger calls = new AtomicInteger();
        OutboxCache failing = cache(ids -> {
            if (calls.incrementAndGet() == 1) throw new RedisConnectionFailureException("down");
            return Map.of(1L, snapshot(10L, 100));
        });

        assertThatThrownBy(() -> failing.getAll(List.of(1L))).isInstanceOf(RedisConnectionFailureException.class);
        assertThat(failing.getAll(List.of(1L)).get(1L).size()).isEqualTo(1);
    }

    @Test
    void beforeMatchesReverseRangeByScoreSemantics() {
        OutboxCache.Snapshot s = snapshot(5L, 500, 4L, 400, 3L, 400, 2L, 200);

        assertThat(s.before(9L, Long.MAX_VALUE, 2)).extracting(FeedEntry::postId).containsExactly(5L, 4L);
        assertThat(s.before(9L, 400, 10)).extracting(FeedEntry::postId).containsExactly(2L);   // 严格小于
        assertThat(s.before(9L, 401, 10)).extracting(FeedEntry::postId).containsExactly(4L, 3L, 2L);
        assertThat(s.before(9L, 100, 10)).isEmpty();
        assertThat(s.before(9L, Long.MAX_VALUE, 1)).extracting(FeedEntry::outboxAuthorId).containsExactly(9L);
    }

    private static OutboxCache.Snapshot snapshot(Object... idAndScore) {
        Set<TypedTuple<Object>> set = new LinkedHashSet<>();
        for (int i = 0; i < idAndScore.length; i += 2) {
            set.add(new DefaultTypedTuple<>(idAndScore[i], ((Number) idAndScore[i + 1]).doubleValue()));
        }
        return OutboxCache.Snapshot.of(set);
    }
}
