package com.brayton.weibo.service;

import com.brayton.weibo.common.AfterCommit;
import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.entity.Post;
import com.brayton.weibo.entity.User;
import com.brayton.weibo.enums.Gender;
import com.brayton.weibo.enums.PostType;
import com.brayton.weibo.enums.PostVisibility;
import com.brayton.weibo.repository.PostRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 帖子快照的两级缓存：本地 Caffeine → Redis → DB（findByIdInWithUser）。
 *
 * 快照 = 帖子内容 + 计数 + 作者摘要，读路径命中时完全不查库。各部分的新鲜度策略不同：
 *  - 内容（正文、图片、可见性）：编辑/删除提交后删 Redis + 本地，并在 1 秒后再删一次（延迟双删），
 *    堵住"读线程先读到旧行、写线程删完缓存后、读线程才把旧行回填"的竞态。
 *    改为非公开属于隐私问题，不能靠 TTL 兜底；
 *  - 评论/转发数：提交后用 Lua 对 Redis 里的快照就地 HINCRBY，不删缓存——
 *    计数变化频繁，若每次都删，热帖等于持续击穿；
 *  - 点赞数：实时值由 {@link LikeCountService} 维护，快照里的 likeCount 是回填时的 DB 副本，
 *    只在拿不到 Redis 计数时兜底；
 *  - 作者摘要：改名/换头像不主动失效，最长 Redis TTL 后生效（一个作者的帖子无法廉价枚举）。
 *
 * 本地缓存只做 TTL 过期（{@link #LOCAL_TTL}），计数在本地有秒级延迟；多实例部署时，
 * 别的实例的本地缓存在编辑/删除后也最多旧这么久。
 *
 * 防护：查不到的 id 写空值标记（防穿透）；Redis TTL 加随机偏移（防集中过期）；
 * 本地缓存是 AsyncCache，同一实例上对同一 id 的并发未命中共享同一个加载中的 future，
 * 热帖 Redis key 过期时每个实例只回源一次（防击穿，跨实例不加锁，最多"实例数"次查库）；
 * Redis 读写失败一律降级为直接查库，不影响请求。
 */
@Slf4j
@Service
public class PostCacheService {

    static final Duration LOCAL_TTL = Duration.ofSeconds(5);
    static final Duration REDIS_TTL = Duration.ofMinutes(10);
    static final Duration REDIS_TTL_JITTER = Duration.ofMinutes(2);
    static final Duration MISSING_TTL = Duration.ofSeconds(60);
    static final long DOUBLE_DELETE_DELAY_MS = 1000;

    private static final String F_DATA = "data";
    private static final String F_MISSING = "missing";

    public enum Counter {
        COMMENT("comment"), REPOST("repost");

        final String field;

        Counter(String field) {
            this.field = field;
        }
    }

    /** 仅当快照存在（有 data 字段）时才加减，避免给空值标记或已过期的 key 造出残缺的 hash；下限为 0，与 SQL 一致 */
    private static final RedisScript<Long> ADJUST_COUNT = new DefaultRedisScript<>("""
            if redis.call('HEXISTS', KEYS[1], 'data') == 0 then return -1 end
            local v = redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2])
            if v < 0 then redis.call('HSET', KEYS[1], ARGV[1], 0); v = 0 end
            return v
            """, Long.class);

    public record AuthorSnapshot(Long id, String username, Gender gender, String avatarUrl, String bio,
                                 LocalDate birthday, LocalDate joinDate, int followerCount, int followCount) {

        static AuthorSnapshot from(User u) {
            return new AuthorSnapshot(u.getId(), u.getUsername(), u.getGender(), u.getAvatarUrl(), u.getBio(),
                    u.getBirthday(), u.getCreatedAt() == null ? null : u.getCreatedAt().toLocalDate(),
                    u.getFollowerCount(), u.getFollowCount());
        }

        public UserResponse toResponse(boolean following, boolean followed) {
            UserResponse r = new UserResponse();
            r.setId(id);
            r.setUsername(username);
            r.setGender(gender);
            r.setAvatarUrl(avatarUrl);
            r.setBio(bio);
            r.setBirthday(birthday);
            r.setJoinDate(joinDate);
            r.setFollowerCount(followerCount);
            r.setFollowCount(followCount);
            r.setFollowing(following);
            r.setFollowed(followed);
            return r;
        }
    }

    /**
     * 评论/转发数在 data JSON 里也有一份（回填时的值），读 Redis 时以 hash 中独立的计数字段为准，
     * 因为只有那几个字段会被 HINCRBY 就地更新。likeCount 只是 DB 副本，见类注释。
     */
    public record PostSnapshot(Long id, AuthorSnapshot author, PostType type, String content, List<String> images,
                               Long refPostId, PostVisibility visibility, LocalDateTime createdAt,
                               LocalDateTime updatedAt, boolean edited,
                               long likeCount, long commentCount, long repostCount) {

        static PostSnapshot from(Post p) {
            return new PostSnapshot(p.getId(), AuthorSnapshot.from(p.getUser()), p.getType(), p.getContent(),
                    p.getImages(), p.getRefPost() == null ? null : p.getRefPost().getId(), p.getVisibility(),
                    p.getCreatedAt(), p.getUpdatedAt(), p.isEdited(),
                    p.getLikeCount(), p.getCommentCount(), p.getRepostCount());
        }

        PostSnapshot withCounts(long comment, long repost) {
            return new PostSnapshot(id, author, type, content, images, refPostId, visibility, createdAt,
                    updatedAt, edited, likeCount, comment, repost);
        }
    }

    private final PostRepository postRepository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** value 为 Optional.empty() 表示已确认不存在（本地的空值标记） */
    private final AsyncCache<Long, Optional<PostSnapshot>> local = Caffeine.newBuilder()
            // 在调用线程上加载：回源可能查库，留在调用方的线程里才能沿用它的事务/连接上下文，也不占公共 ForkJoinPool
            .executor(Runnable::run)
            .maximumSize(10_000)
            .expireAfterWrite(LOCAL_TTL)
            .buildAsync();

    private final ScheduledExecutorService delayedEvictor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "post-cache-evict");
        t.setDaemon(true);
        return t;
    });

    public PostCacheService(PostRepository postRepository, StringRedisTemplate redis, ObjectMapper mapper) {
        this.postRepository = postRepository;
        this.redis = redis;
        this.mapper = mapper;
    }

    @PreDestroy
    void shutdown() {
        delayedEvictor.shutdown();
    }

    static String key(Long postId) {
        return "post:" + postId;
    }

    /**
     * 批量取快照，只返回存在（未删除）的帖子；顺序不保证，调用方按自己的 id 顺序组装。
     */
    public Map<Long, PostSnapshot> getPosts(Collection<Long> ids) {
        Map<Long, PostSnapshot> result = new HashMap<>();
        if (ids == null || ids.isEmpty()) return result;

        // 1. 本地；未命中的 id 先占位一个加载中的 future，其他线程再来同一 id 时等这个 future，不重复回源
        Map<Long, Optional<PostSnapshot>> snapshots;
        try {
            snapshots = local.getAll(ids, this::loadMisses).join();
        } catch (CompletionException e) {
            // 加载失败的 future 会被 Caffeine 移出缓存，下次请求重新回源
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw e;
        }
        snapshots.forEach((id, s) -> s.ifPresent(v -> result.put(id, v)));
        return result;
    }

    /** 本地未命中的一批 id：2. Redis → 3. DB，回填 Redis；查不到的写空值标记。每个 id 都有返回值 */
    private Map<Long, Optional<PostSnapshot>> loadMisses(Set<? extends Long> ids) {
        Map<Long, Optional<PostSnapshot>> result = new HashMap<>();

        // 2. Redis
        List<Long> afterRedis = readRedis(new ArrayList<>(ids), result);
        if (afterRedis.isEmpty()) return result;

        // 3. DB
        Map<Long, PostSnapshot> loaded = new HashMap<>();
        for (Post p : postRepository.findByIdInWithUser(afterRedis)) {
            loaded.put(p.getId(), PostSnapshot.from(p));
        }
        writeRedis(afterRedis, loaded);
        for (Long id : afterRedis) result.put(id, Optional.ofNullable(loaded.get(id)));
        return result;
    }

    /** 读 Redis，命中的（含空值标记）放进 result，返回仍未命中的 id */
    private List<Long> readRedis(List<Long> ids, Map<Long, Optional<PostSnapshot>> result) {
        List<Object> rows;
        try {
            rows = redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    for (Long id : ids) operations.opsForHash().entries(key(id));
                    return null;
                }
            });
        } catch (DataAccessException e) {
            log.warn("帖子缓存读 Redis 失败，降级查库：{}", e.getMessage());
            return ids;
        }
        if (rows == null || rows.size() != ids.size()) return ids;

        List<Long> misses = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            Optional<PostSnapshot> hit = parse(rows.get(i));
            if (hit == null) {
                misses.add(id);
            } else {
                result.put(id, hit);
            }
        }
        return misses;
    }

    /** null = 未命中；Optional.empty() = 空值标记；解析失败按未命中处理，回源后会覆盖坏数据 */
    private Optional<PostSnapshot> parse(Object row) {
        if (!(row instanceof Map<?, ?> hash) || hash.isEmpty()) return null;
        if (hash.containsKey(F_MISSING)) return Optional.empty();

        Object data = hash.get(F_DATA);
        if (data == null) return null;
        try {
            PostSnapshot s = mapper.readValue(data.toString(), PostSnapshot.class);
            return Optional.of(s.withCounts(
                    count(hash, Counter.COMMENT, s.commentCount()),
                    count(hash, Counter.REPOST, s.repostCount())));
        } catch (JsonProcessingException | NumberFormatException e) {
            log.warn("帖子缓存数据损坏，按未命中处理：{}", e.getMessage());
            return null;
        }
    }

    private static long count(Map<?, ?> hash, Counter c, long fallback) {
        Object v = hash.get(c.field);
        return v == null ? fallback : Long.parseLong(v.toString());
    }

    private void writeRedis(List<Long> ids, Map<Long, PostSnapshot> loaded) {
        try {
            redis.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public Object execute(RedisOperations operations) {
                    for (Long id : ids) {
                        String key = key(id);
                        PostSnapshot s = loaded.get(id);
                        // 先删再写：覆盖可能残留的坏数据或空值标记，不与新字段混在同一个 hash 里
                        operations.delete(key);
                        if (s == null) {
                            operations.opsForHash().put(key, F_MISSING, "1");
                            operations.expire(key, MISSING_TTL);
                        } else {
                            operations.opsForHash().putAll(key, toHash(s));
                            operations.expire(key, jitteredTtl());
                        }
                    }
                    return null;
                }
            });
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("帖子缓存回填 Redis 失败：{}", e.getMessage());
        }
    }

    private Map<String, String> toHash(PostSnapshot s) {
        try {
            return Map.of(
                    F_DATA, mapper.writeValueAsString(s),
                    Counter.COMMENT.field, Long.toString(s.commentCount()),
                    Counter.REPOST.field, Long.toString(s.repostCount()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Duration jitteredTtl() {
        return REDIS_TTL.plusSeconds(ThreadLocalRandom.current().nextLong(REDIS_TTL_JITTER.toSeconds() + 1));
    }

    /** 帖子内容变化（编辑、删除）：事务提交后删两级缓存，并延迟再删一次 Redis */
    public void evict(Long postId) {
        AfterCommit.run(() -> {
            evictNow(postId);
            delayedEvictor.schedule(() -> evictNow(postId), DOUBLE_DELETE_DELAY_MS, TimeUnit.MILLISECONDS);
        });
    }

    private void evictNow(Long postId) {
        // 若该 id 正在加载，Caffeine 会丢弃加载结果而不写回本地；已在等这个 future 的请求仍拿到加载值
        local.synchronous().invalidate(postId);
        try {
            redis.delete(key(postId));
        } catch (DataAccessException e) {
            // 删不掉只能等 TTL；记日志便于排查编辑后仍显示旧内容
            log.warn("帖子缓存删除失败 postId={}：{}", postId, e.getMessage());
        }
    }

    /** 计数变化：事务提交后对 Redis 快照就地加减；本地缓存不动，靠短 TTL 收敛 */
    public void adjustCount(Long postId, Counter counter, long delta) {
        AfterCommit.run(() -> {
            try {
                redis.execute(ADJUST_COUNT, List.of(key(postId)), counter.field, Long.toString(delta));
            } catch (DataAccessException e) {
                // 失败时该快照的计数偏差一次，最长到 TTL 过期回源后纠正
                log.warn("帖子缓存计数更新失败 postId={} {}：{}", postId, counter, e.getMessage());
            }
        });
    }
}
