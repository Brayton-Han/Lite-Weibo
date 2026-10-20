package com.brayton.weibo.service;

import com.brayton.weibo.common.AfterCommit;
import com.brayton.weibo.repository.LikeCountRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 点赞数：Redis 计数为读写主路径，异步批量写回 posts.like_count。
 *
 * 三份数据的关系：likes 表的行是唯一真相（点赞时同步写入，唯一约束防重复）；
 * Redis 计数 cnt:like:{postId} 是它的实时投影；posts.like_count 是 Redis 计数的异步副本，
 * 只在 Redis 不可用时兜底展示。点赞不再同步 UPDATE posts，热帖不再串行争抢同一行的行锁。
 *
 * <h3>写</h3>
 * 事务提交后用 Lua 对计数 INCRBY（key 不存在就不建，否则会从 0 开始错计），并把 postId 记进脏集合。
 *
 * <h3>读</h3>
 * 批量 MGET。缺失的 key 按 likes 表精确重算后 SET NX；每个帖子一把短锁，只放一个请求回源，
 * 其余请求本次不返回该帖计数，由调用方用快照里的 DB 值兜底（防击穿）。
 *
 * <h3>写回</h3>
 * 每 {@link #FLUSH_INTERVAL_MS} 毫秒把脏集合原子地 RENAME 成处理中集合，读出 Redis 绝对值批量写回。
 * 绝对值写回是幂等的：处理中途崩溃，残留的处理中集合下一轮整批重做即可；多实例同时跑也无妨。
 * 待写回的状态在 Redis 里而不在进程内存，应用重启不丢。
 *
 * <h3>偏差与自愈</h3>
 * 两种情况会让 Redis 计数偏离 likes 表：重算期间恰好有人点赞（COUNT 与 SET 之间的增量被跳过），
 * 或提交后写 Redis 失败。计数 key 的 TTL 在创建时设定、此后不续期，到期从 likes 表重算，
 * 所以偏差最多持续 {@link #COUNTER_TTL}；重算时同时标脏，DB 副本随之纠正。
 */
@Slf4j
@Service
public class LikeCountService {

    static final Duration COUNTER_TTL = Duration.ofHours(1);
    static final Duration COUNTER_TTL_JITTER = Duration.ofMinutes(10);
    static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(5);
    static final long FLUSH_INTERVAL_MS = 5000;
    static final int FLUSH_BATCH = 500;

    static final String DIRTY = "cnt:like:dirty";
    static final String FLUSHING = "cnt:like:flushing";

    /** KEYS: 计数, 脏集合；ARGV: delta, postId。下限为 0，与原 SQL 的 CASE 一致 */
    private static final RedisScript<Long> ADJUST = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              local v = redis.call('INCRBY', KEYS[1], ARGV[1])
              if v < 0 then redis.call('SET', KEYS[1], 0, 'KEEPTTL') end
            end
            redis.call('SADD', KEYS[2], ARGV[2])
            return 0
            """, Long.class);

    /** KEYS: 脏集合, 处理中集合。上一轮的处理中集合没清掉（崩溃或写库失败）就先重做它 */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TAKE_DIRTY = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 0 then
              if redis.call('EXISTS', KEYS[1]) == 0 then return {} end
              redis.call('RENAME', KEYS[1], KEYS[2])
            end
            return redis.call('SMEMBERS', KEYS[2])
            """, List.class);

    private final StringRedisTemplate redis;
    private final LikeCountRepository likeCountRepository;

    public LikeCountService(StringRedisTemplate redis, LikeCountRepository likeCountRepository) {
        this.redis = redis;
        this.likeCountRepository = likeCountRepository;
    }

    static String key(Long postId) {
        return "cnt:like:" + postId;
    }

    static String lockKey(Long postId) {
        return "cnt:like:lock:" + postId;
    }

    /** 点赞 +1 / 取消 -1，在当前事务提交后生效 */
    public void adjust(Long postId, long delta) {
        AfterCommit.run(() -> {
            try {
                redis.execute(ADJUST, List.of(key(postId), DIRTY), Long.toString(delta), postId.toString());
            } catch (DataAccessException e) {
                // 计数与 DB 副本都会少这一次，最长到计数 TTL 到期重算后纠正
                log.warn("点赞计数更新失败 postId={} delta={}：{}", postId, delta, e.getMessage());
            }
        });
    }

    /**
     * 批量取实时点赞数。返回的 map 可能缺某些帖子（Redis 不可用，或别的请求正在重算），
     * 调用方应以帖子上的 likeCount（DB 副本）兜底。
     */
    public Map<Long, Long> getCounts(Collection<Long> postIds) {
        Map<Long, Long> result = new HashMap<>();
        if (postIds == null || postIds.isEmpty()) return result;
        List<Long> ids = List.copyOf(new LinkedHashSet<>(postIds));

        List<String> values;
        try {
            values = redis.opsForValue().multiGet(ids.stream().map(LikeCountService::key).toList());
        } catch (DataAccessException e) {
            log.warn("读点赞计数失败，降级用 DB 副本：{}", e.getMessage());
            return result;
        }
        if (values == null || values.size() != ids.size()) return result;

        List<Long> misses = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String v = values.get(i);
            if (v == null) misses.add(ids.get(i));
            else result.put(ids.get(i), Long.parseLong(v));
        }
        if (!misses.isEmpty()) result.putAll(rebuild(misses));
        return result;
    }

    /** 只重算抢到锁的那部分；其余留给调用方兜底 */
    private Map<Long, Long> rebuild(List<Long> misses) {
        try {
            List<Object> locked = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    for (Long id : misses) {
                        operations.opsForValue().setIfAbsent(lockKey(id), "1", REBUILD_LOCK_TTL);
                    }
                    return null;
                }
            });
            List<Long> owned = new ArrayList<>();
            for (int i = 0; i < misses.size() && i < locked.size(); i++) {
                if (Boolean.TRUE.equals(locked.get(i))) owned.add(misses.get(i));
            }
            if (owned.isEmpty()) return Map.of();

            Map<Long, Long> counts = likeCountRepository.countByPostIds(owned);
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    for (Long id : owned) {
                        operations.opsForValue().setIfAbsent(key(id), counts.get(id).toString(), jitteredTtl());
                        operations.delete(lockKey(id));
                        // 顺带纠正 DB 副本：Redis 丢数据或写失败时，它可能落后
                        operations.opsForSet().add(DIRTY, id.toString());
                    }
                    return null;
                }
            });
            return counts;
        } catch (DataAccessException e) {
            log.warn("重建点赞计数失败：{}", e.getMessage());
            return Map.of();
        }
    }

    private static Duration jitteredTtl() {
        return COUNTER_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(COUNTER_TTL_JITTER.toSeconds() + 1));
    }

    /** 把 Redis 计数异步写回 posts.like_count */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS, initialDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        try {
            @SuppressWarnings("unchecked")
            List<Object> raw = redis.execute(TAKE_DIRTY, List.of(DIRTY, FLUSHING));
            if (raw == null || raw.isEmpty()) return;

            List<Long> ids = raw.stream().map(o -> Long.valueOf(o.toString())).toList();
            for (int from = 0; from < ids.size(); from += FLUSH_BATCH) {
                flushBatch(ids.subList(from, Math.min(from + FLUSH_BATCH, ids.size())));
            }
            redis.delete(FLUSHING);
            log.debug("点赞计数写回 {} 条", ids.size());
        } catch (RuntimeException e) {
            // 处理中集合保留，下一轮整批重做（绝对值写回，重做无副作用）
            log.warn("点赞计数写回失败，下一轮重试：{}", e.getMessage());
        }
    }

    private void flushBatch(List<Long> ids) {
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(LikeCountService::key).toList());

        Map<Long, Long> counts = new HashMap<>();
        List<Long> expired = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String v = values == null ? null : values.get(i);
            if (v == null) expired.add(ids.get(i));
            else counts.put(ids.get(i), Long.parseLong(v));
        }
        // 计数已过期（或从未建立：冷帖被点赞时不建 key），直接按 likes 表算
        counts.putAll(likeCountRepository.countByPostIds(expired));
        likeCountRepository.updateLikeCounts(counts);
    }
}
