package com.brayton.weibo.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 线上用到的限流策略，都按 userId：
 * <ul>
 *   <li>{@code ask}：站内问答（含流式），默认 20 次 / 小时。每次至少一次 Claude 调用，一个循环调用就能烧掉额度。
 *       被限后不报错，降级成检索结果（见 {@code AskService}）</li>
 *   <li>{@code search}：语义搜索与检索详情，默认 60 次 / 分钟。每次一次 embedding（语义搜索还有一次 rerank），
 *       便宜但不是免费的；被限直接 429</li>
 *   <li>{@code upload}：图片上传，默认 30 次 / 10 分钟（每次最多 9 张、每张 ≤ 10MB）。
 *       挡住刷 R2 存储和流量；被限直接 429</li>
 * </ul>
 * 关键词搜索只查本地 PG，不限。
 */
@Component
public class RateLimitPolicies {

    private final RateLimiter.Policy ask;
    private final RateLimiter.Policy search;
    private final RateLimiter.Policy upload;

    public RateLimitPolicies(
            @Value("${ratelimit.ask.capacity:20}") int askCapacity,
            @Value("${ratelimit.ask.period:1h}") Duration askPeriod,
            @Value("${ratelimit.search.capacity:60}") int searchCapacity,
            @Value("${ratelimit.search.period:1m}") Duration searchPeriod,
            @Value("${ratelimit.upload.capacity:30}") int uploadCapacity,
            @Value("${ratelimit.upload.period:10m}") Duration uploadPeriod
    ) {
        this.ask = new RateLimiter.Policy("ask", askCapacity, askPeriod);
        this.search = new RateLimiter.Policy("search", searchCapacity, searchPeriod);
        this.upload = new RateLimiter.Policy("upload", uploadCapacity, uploadPeriod);
    }

    public RateLimiter.Policy ask() {
        return ask;
    }

    public RateLimiter.Policy search() {
        return search;
    }

    public RateLimiter.Policy upload() {
        return upload;
    }
}
