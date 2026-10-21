package com.brayton.weibo.service;

import com.brayton.weibo.dto.AskResponse.Mode;
import com.brayton.weibo.dto.AskResponse.Refusal;
import com.brayton.weibo.generation.CitedAnswer;
import com.brayton.weibo.generation.QueryIntent;
import com.brayton.weibo.retrieval.ScoredPost;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 热门 query 的问答结果缓存（RAG Day 17）：同一个问题在 TTL 内只付一次分类 + 生成的钱，命中时 1 次 Redis GET 就返回。
 *
 * <h3>缓存什么</h3>
 * 只缓存与用户无关的部分：意图、模式、拒答层、带引用的回答、共鸣类的 (postId, 分数)。帖子卡片（作者、点赞数、我是否点过赞）
 * 每次命中都按当前用户重新加载，顺带把缓存期间被删或改为非公开的帖子滤掉——缓存不能让删掉的帖子复活。
 * 风险类也缓存（省一次分类），文案与热线不进缓存，命中时用代码里的最新版本。
 *
 * <h3>不缓存什么</h3>
 * 降级结果（下次可能就好了）、FILTERED（要留着每次都记日志排查）、NO_DOCS（帖子刚被删，属于瞬时状态）。
 *
 * <h3>key</h3>
 * query 归一化（去首尾空白、合并空白、小写）后连同影响结果的配置（top-k、拒答阈值、版本号）一起取 SHA-256。
 * 改 prompt、换模型这类不在配置里的改动，要手动把 {@link #VERSION} 加一，让旧缓存一次性失效。
 *
 * <h3>TTL</h3>
 * 默认 30 分钟（{@code ask.cache.ttl}）：新帖发出来之后最多 30 分钟才会出现在同一问题的回答里，这是省钱的代价。
 * Redis 读写失败一律当未命中处理，缓存挂了问答照常。
 */
@Slf4j
@Component
public class AskCache {

    static final String VERSION = "v1";
    static final String KEY_PREFIX = "ask:cache:" + VERSION + ":";

    /** 进缓存的结果；answer 只在 ANSWER 有，hits 只在 RESONANCE 有 */
    public record Entry(QueryIntent intent, Mode mode, Refusal refusal, CitedAnswer answer, List<ScoredPost> hits) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final Duration ttl;
    private final String configFingerprint;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;

    public AskCache(
            StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters,
            @Value("${ask.cache.enabled:true}") boolean enabled,
            @Value("${ask.cache.ttl:30m}") Duration ttl,
            @Value("${ask.refusal.min-top1-score:0.40}") double minTop1Score,
            @Value("${ask.top-k:10}") int topK
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.enabled = enabled;
        this.ttl = ttl;
        this.configFingerprint = "k=" + topK + "|t=" + minTop1Score;
        this.hits = Counter.builder("ask.cache").tag("result", "hit").register(meters);
        this.misses = Counter.builder("ask.cache").tag("result", "miss").register(meters);
        this.errors = Counter.builder("ask.cache").tag("result", "error").register(meters);
    }

    public Optional<Entry> get(String query) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key(query));
            if (json == null) {
                misses.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(mapper.readValue(json, Entry.class));
        } catch (Exception e) {
            log.warn("问答缓存读取失败，按未命中处理：{}", e.getMessage());
            errors.increment();
            return Optional.empty();
        }
    }

    public void put(String query, Entry entry) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key(query), mapper.writeValueAsString(entry), ttl);
        } catch (Exception e) {
            log.warn("问答缓存写入失败：{}", e.getMessage());
            errors.increment();
        }
    }

    static String normalize(String query) {
        return query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    String key(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((configFingerprint + "|" + normalize(query)).getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
