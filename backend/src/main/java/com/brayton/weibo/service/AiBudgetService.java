package com.brayton.weibo.service;

import com.brayton.weibo.generation.ClaudeGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成本熔断（RAG Day 17）：按 Claude 返回的用量累计当日花费，超过 {@code ai.budget.daily-usd} 就关掉 AI 回答，只保留检索。
 *
 * <h3>怎么记</h3>
 * {@link ClaudeGateway} 每次成功调用后回调 {@link #onUsage}，按模型价格折成微美元 INCRBY 到 Redis {@code ai:cost:yyyy-MM-dd}，
 * 多实例共用一个计数，key 保留 3 天方便事后查。只记 Claude：Voyage 的 embedding / rerank 属于检索，
 * 熔断后检索还要继续用，它们的花费不该算进这个阈值。
 *
 * <h3>软上限</h3>
 * 先查后花：判断时还没发请求，花费要等调用结束才知道，所以并发的在途请求会让当日花费略超阈值，
 * 超出量不超过 问答线程数 × 单次成本（haiku-5-5 一次问答不到 0.1 美分）。要硬上限得先预扣再结算，这里不值得。
 *
 * <h3>Redis 不可用</h3>
 * 查不到当日花费时按已熔断处理（fail-closed）：成本是硬约束，宁可暂时没有 AI 回答，也不在看不见账单的时候继续花。
 * 限流器的取舍相反（fail-open），见 {@code RateLimiter}。
 *
 * <h3>价格</h3>
 * 2026-10-20 按 Anthropic 官方价目核对（美元 / 百万 token）。缓存写按 5 分钟 TTL 的 1.25 倍输入价，
 * 与 {@code AnswerPromptBuilder} 的 ephemeral 缓存一致。表里没有的模型按最贵的一档算并告警，宁可高估。
 * haiku-5-5 单次输入超过 10 万 token 时价格变为 5 倍，问答的输入只有几千 token，到不了这一档，但也照算。
 */
@Slf4j
@Service
public class AiBudgetService implements ClaudeGateway.UsageListener {

    static final String KEY_PREFIX = "ai:cost:";
    private static final Duration KEY_TTL = Duration.ofDays(3);
    private static final double MICROS_PER_USD = 1_000_000;

    /** 美元 / 百万 token；longContext 为 null 表示没有长上下文加价 */
    record Price(double input, double output, double cacheWrite, double cacheRead, Price longContext, long longContextAbove) {
        Price(double input, double output, double cacheWrite, double cacheRead) {
            this(input, output, cacheWrite, cacheRead, null, Long.MAX_VALUE);
        }
    }

    static final Map<String, Price> PRICES = Map.of(
            "claude-haiku-5-5", new Price(0.10, 0.50, 0.125, 0.01,
                    new Price(0.50, 2.50, 0.625, 0.05), 100_000),
            "claude-haiku-4-5", new Price(1.00, 5.00, 1.25, 0.10),
            "claude-sonnet-5-5", new Price(2.00, 10.00, 2.50, 0.20),
            "claude-opus-5-5", new Price(4.00, 20.00, 5.00, 0.20),
            "claude-fable-5-1", new Price(10.00, 50.00, 12.50, 0.25));

    /** 不认识的模型按这一档算 */
    static final Price MOST_EXPENSIVE = PRICES.get("claude-fable-5-1");

    private final StringRedisTemplate redis;
    private final MeterRegistry meters;
    private final boolean enabled;
    private final double dailyUsd;
    private final ZoneId zone;
    private final Clock clock;
    private final Set<String> warnedModels = ConcurrentHashMap.newKeySet();

    @Autowired
    public AiBudgetService(
            StringRedisTemplate redis, MeterRegistry meters,
            @Value("${ai.budget.enabled:true}") boolean enabled,
            @Value("${ai.budget.daily-usd:5.0}") double dailyUsd,
            // 与 Anthropic 控制台的账单日一致
            @Value("${ai.budget.zone:UTC}") String zone
    ) {
        this(redis, meters, enabled, dailyUsd, ZoneId.of(zone), Clock.systemUTC());
    }

    AiBudgetService(StringRedisTemplate redis, MeterRegistry meters, boolean enabled, double dailyUsd, ZoneId zone, Clock clock) {
        this.redis = redis;
        this.meters = meters;
        this.enabled = enabled;
        this.dailyUsd = dailyUsd;
        this.zone = zone;
        this.clock = clock;
        Gauge.builder("ai.cost.today.usd", this, AiBudgetService::todayUsdOrNaN)
                .description("当日 Claude 累计花费（美元），多实例共享，读自 Redis")
                .register(meters);
        Gauge.builder("ai.budget.daily.usd", () -> dailyUsd).description("当日花费阈值").register(meters);
    }

    /** 当日花费是否已到阈值；关闭熔断时恒为 false */
    public boolean exhausted() {
        if (!enabled) {
            return false;
        }
        try {
            return todayUsd() >= dailyUsd;
        } catch (RuntimeException e) {
            log.warn("读取当日 AI 花费失败，按已熔断处理：{}", e.getMessage());
            Counter.builder("ai.budget.check.errors").register(meters).increment();
            return true;
        }
    }

    public double todayUsd() {
        String v = redis.opsForValue().get(key());
        return v == null ? 0 : Long.parseLong(v) / MICROS_PER_USD;
    }

    @Override
    public void onUsage(ClaudeGateway.CallUsage usage) {
        if (!enabled) {
            return;
        }
        double usd = cost(usage);
        Counter.builder("ai.cost.usd").description("Claude 花费（美元），进程启动以来")
                .tags("purpose", usage.purpose(), "model", usage.model())
                .register(meters).increment(usd);
        // 向上取整到 1 微美元：一次调用再便宜也不记成 0
        long micros = (long) Math.ceil(usd * MICROS_PER_USD);
        String key = key();
        Long total = redis.opsForValue().increment(key, micros);
        if (total != null && total == micros) {
            redis.expire(key, KEY_TTL);
        }
        if (total != null && total - micros < dailyUsd * MICROS_PER_USD && total >= dailyUsd * MICROS_PER_USD) {
            log.warn("当日 AI 花费 {} 美元已达阈值 {}，AI 回答关闭到 {} 零点", total / MICROS_PER_USD, dailyUsd, zone);
        }
    }

    double cost(ClaudeGateway.CallUsage u) {
        Price price = PRICES.get(u.model());
        if (price == null) {
            price = MOST_EXPENSIVE;
            if (warnedModels.add(u.model())) {
                log.warn("模型 {} 不在价格表里，按最贵一档计费，请补上 AiBudgetService.PRICES", u.model());
            }
        }
        long totalInput = u.inputTokens() + u.cacheWriteTokens() + u.cacheReadTokens();
        if (price.longContext() != null && totalInput > price.longContextAbove()) {
            price = price.longContext();
        }
        return (u.inputTokens() * price.input() + u.outputTokens() * price.output()
                + u.cacheWriteTokens() * price.cacheWrite() + u.cacheReadTokens() * price.cacheRead()) / 1_000_000;
    }

    private String key() {
        return KEY_PREFIX + LocalDate.now(clock.withZone(zone));
    }

    private double todayUsdOrNaN() {
        try {
            return todayUsd();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
