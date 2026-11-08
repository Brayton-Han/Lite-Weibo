package com.brayton.weibo.service;

import com.brayton.weibo.service.RedisService.FeedEntry;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * 大 V 发件箱 outbox:{authorId} 的本地缓存（热 key 防护）。
 *
 * 大 V 的发件箱被他的所有粉丝在读 feed 时反复读取，是典型的读多写少热 key。
 * 这里在每个实例内缓存**整个**发件箱：发件箱按 {@link FeedWriteService#FEED_MAX_SIZE} 截断，
 * 最多约 1000 条，整份缓存后任意游标位置都能在本地回答，查询语义与直接读 Redis 完全一致。
 * Redis 上每个发件箱的读 QPS 从"粉丝刷新次数"降到"实例数 / TTL"。
 *
 * 一致性：
 *  - 本实例上的写（发帖、截断、清理幽灵条目）立即失效本地副本；
 *  - 其他实例的本地副本靠 TTL 过期，大 V 新帖在粉丝 feed 中最多延迟 TTL 秒出现。
 *    作者本人不受影响：扇出时帖子同时写进了作者自己的收件箱；
 *  - 已删除的帖子在副本里多留一会儿也不会展示：读路径按 id 批量加载帖子，查不到即跳过。
 *
 * 防击穿：AsyncLoadingCache 对同一个 key 的并发未命中共享同一个加载中的 future，
 * 大 V 刚发帖、副本刚失效时，同一实例上的大量请求只回源一次；多个 key 的未命中合并成一次管道读取。
 *
 * 容量按条目总数（而非作者数）限制，最坏情况下内存约为 maxEntries × 16 字节。
 */
@Component
public class OutboxCache {

    /** 一个发件箱的不可变副本，按 score（发帖时间戳）倒序 */
    record Snapshot(long[] postIds, long[] scores) {

        static final Snapshot EMPTY = new Snapshot(new long[0], new long[0]);

        int size() {
            return postIds.length;
        }

        /** 等价于 ZREVRANGEBYSCORE outbox -inf (lastTimestamp - 1) LIMIT 0 size */
        List<FeedEntry> before(Long authorId, long lastTimestamp, int size) {
            // scores 倒序，二分找第一个 score < lastTimestamp 的位置
            int lo = 0, hi = scores.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (scores[mid] >= lastTimestamp) lo = mid + 1;
                else hi = mid;
            }
            int end = Math.min(scores.length, lo + size);
            List<FeedEntry> out = new ArrayList<>(Math.max(0, end - lo));
            for (int i = lo; i < end; i++) {
                out.add(new FeedEntry(postIds[i], scores[i], authorId));
            }
            return out;
        }

        static Snapshot of(Collection<? extends ZSetOperations.TypedTuple<?>> tuples) {
            if (tuples == null || tuples.isEmpty()) return EMPTY;
            long[] ids = new long[tuples.size()];
            long[] scores = new long[tuples.size()];
            int n = 0;
            for (ZSetOperations.TypedTuple<?> t : tuples) {
                if (t.getValue() == null || t.getScore() == null) continue;
                ids[n] = Long.parseLong(t.getValue().toString());
                scores[n] = t.getScore().longValue();
                n++;
            }
            return n == ids.length ? new Snapshot(ids, scores)
                    : new Snapshot(Arrays.copyOf(ids, n), Arrays.copyOf(scores, n));
        }
    }

    private final AsyncLoadingCache<Long, Snapshot> cache;

    @Autowired
    public OutboxCache(RedisTemplate<String, Object> redis,
                       @Value("${feed.outbox-cache.ttl:3s}") Duration ttl,
                       @Value("${feed.outbox-cache.max-entries:1000000}") long maxEntries) {
        this(ids -> loadFromRedis(redis, ids), ttl, maxEntries, Ticker.systemTicker());
    }

    OutboxCache(Function<List<Long>, Map<Long, Snapshot>> loader, Duration ttl, long maxEntries, Ticker ticker) {
        this.cache = Caffeine.newBuilder()
                // 在调用线程上加载：回源是一次 Redis 管道，不值得切线程，也不占用公共 ForkJoinPool
                .executor(Runnable::run)
                .ticker(ticker)
                .expireAfterWrite(ttl)
                .maximumWeight(maxEntries)
                .weigher((Long authorId, Snapshot s) -> s.size() + 1)
                .buildAsync(new CacheLoader<Long, Snapshot>() {
                    @Override
                    public Snapshot load(Long authorId) {
                        return loadAll(Set.of(authorId)).get(authorId);
                    }

                    @Override
                    public Map<Long, Snapshot> loadAll(Set<? extends Long> authorIds) {
                        Map<Long, Snapshot> loaded = new HashMap<>(loader.apply(new ArrayList<>(authorIds)));
                        // 空发件箱也缓存，否则每次都会回源
                        for (Long id : authorIds) loaded.putIfAbsent(id, Snapshot.EMPTY);
                        return loaded;
                    }
                });
    }

    /** 批量取副本，未命中的一次管道回源。Redis 异常原样抛出，与直接读 Redis 时的行为一致。 */
    Map<Long, Snapshot> getAll(Collection<Long> authorIds) {
        if (authorIds.isEmpty()) return Map.of();
        try {
            return cache.getAll(authorIds).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
    }

    /** 本实例写了该发件箱后调用 */
    void invalidate(Long authorId) {
        cache.synchronous().invalidate(authorId);
    }

    private static Map<Long, Snapshot> loadFromRedis(RedisTemplate<String, Object> redis, List<Long> authorIds) {
        List<Object> results = redis.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public Object execute(RedisOperations operations) {
                for (Long authorId : authorIds) {
                    operations.opsForZSet().reverseRangeWithScores(RedisService.outboxKey(authorId), 0, -1);
                }
                return null;
            }
        });

        Map<Long, Snapshot> out = new HashMap<>();
        for (int i = 0; i < authorIds.size() && i < results.size(); i++) {
            if (results.get(i) instanceof Collection<?> tuples) {
                @SuppressWarnings("unchecked")
                Collection<? extends ZSetOperations.TypedTuple<?>> typed =
                        (Collection<? extends ZSetOperations.TypedTuple<?>>) tuples;
                out.put(authorIds.get(i), Snapshot.of(typed));
            }
        }
        return out;
    }
}
