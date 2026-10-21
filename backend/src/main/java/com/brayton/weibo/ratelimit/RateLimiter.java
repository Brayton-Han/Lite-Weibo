package com.brayton.weibo.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 令牌桶限流（RAG Day 17，顺手补 §6.3-24）。每个 (策略, 用户) 一个桶：容量 {@code capacity}，
 * 每 {@code period / capacity} 补回一个令牌，所以"20 次 / 小时"既允许一口气用掉 20 次，之后也只能每 3 分钟一次。
 *
 * <h3>为什么是 Lua</h3>
 * 读令牌数、按时间补充、扣减、写回必须原子，否则并发的两次请求会读到同一个余量各扣一次。
 * 时间取 Redis 的 {@code TIME} 而不是应用服务器的时钟，多实例之间时钟漂移不会让桶多补或少补。
 * 桶在补满所需的时间后过期，不活跃的用户不占内存。
 *
 * <h3>Redis 不可用</h3>
 * 放行（fail-open）并计数：限流保护的是公平和突发，Redis 挂几分钟里漏掉的限流，最坏也被成本熔断兜住；
 * 反过来 fail-closed 会让 Redis 一抖整站搜索都被拒。成本熔断的取舍相反，见 {@code AiBudgetService}。
 *
 * <p>指标 {@code ratelimit.decisions}（policy / result = allowed | rejected | error）。
 */
@Slf4j
@Component
public class RateLimiter {

    /** 一个限流策略：{@code name} 进 Redis key 与指标，只用小写字母和连字符 */
    public record Policy(String name, int capacity, Duration period) {
        public Policy {
            if (capacity <= 0 || period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("capacity 与 period 必须为正：" + capacity + " / " + period);
            }
        }

        double tokensPerMs() {
            return capacity / (double) period.toMillis();
        }
    }

    /** @param retryAfterSeconds 被拒时多久后会有下一个令牌；放行时为 0 */
    public record Decision(boolean allowed, long retryAfterSeconds) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    static final String KEY_PREFIX = "ratelimit:";

    /**
     * KEYS[1] 桶；ARGV[1] 容量，ARGV[2] 每毫秒补充的令牌数。返回 {是否放行, 被拒时还要等多少毫秒}。
     * 令牌数是小数，存成字符串；Lua 数字转 Redis 回复会截成整数，所以等待时间先向上取整再返回
     */
    static final RedisScript<List> TOKEN_BUCKET = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local wait = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              wait = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate))
            return {allowed, wait}
            """, List.class);

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;

    public RateLimiter(StringRedisTemplate redis, MeterRegistry meters,
                       @Value("${ratelimit.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.meters = meters;
        this.enabled = enabled;
    }

    /** @param subject 限流维度，目前是 userId；null（匿名、离线工具）不限 */
    public Decision tryAcquire(Policy policy, Object subject) {
        if (!enabled || subject == null) {
            return Decision.ALLOWED;
        }
        Decision decision;
        try {
            List<?> r = redis.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + policy.name() + ":" + subject),
                    String.valueOf(policy.capacity()), String.valueOf(policy.tokensPerMs()));
            boolean allowed = ((Number) r.get(0)).longValue() == 1;
            long waitMs = ((Number) r.get(1)).longValue();
            decision = allowed ? Decision.ALLOWED : new Decision(false, Math.max(1, (waitMs + 999) / 1000));
        } catch (RuntimeException e) {
            log.warn("限流检查失败，放行 policy={} subject={}：{}", policy.name(), subject, e.getMessage());
            count(policy, "error");
            return Decision.ALLOWED;
        }
        count(policy, decision.allowed() ? "allowed" : "rejected");
        if (!decision.allowed()) {
            log.info("限流 policy={} subject={} retry_after={}s", policy.name(), subject, decision.retryAfterSeconds());
        }
        return decision;
    }

    /** 被拒就抛 {@link RateLimitedException}（HTTP 429），给不需要降级、直接拒绝的接口用 */
    public void check(Policy policy, Object subject) {
        Decision d = tryAcquire(policy, subject);
        if (!d.allowed()) {
            throw new RateLimitedException(policy.name(), d.retryAfterSeconds());
        }
    }

    private void count(Policy policy, String result) {
        Counter.builder("ratelimit.decisions").tags("policy", policy.name(), "result", result).register(meters).increment();
    }
}
