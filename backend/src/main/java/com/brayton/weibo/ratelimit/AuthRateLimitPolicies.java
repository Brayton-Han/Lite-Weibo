package com.brayton.weibo.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录 / 注册的限流策略（匿名接口，没有 userId，按 IP 和用户名）：
 * <ul>
 *   <li>{@code login-ip}：同一 IP 默认 30 次 / 10 分钟，挡住对多个账号的撞库</li>
 *   <li>{@code login-user}：同一用户名默认 10 次 / 10 分钟，挡住对单个账号的多 IP 爆破。
 *       代价是攻击期间账号本人也会被暂时限住，令牌按时间回补，不会永久锁死</li>
 *   <li>{@code register-ip}：同一 IP 默认 10 次 / 小时，挡住批量注册</li>
 * </ul>
 * 与 {@link RateLimitPolicies} 分开，避免改动它已有的构造参数。
 */
@Component
public class AuthRateLimitPolicies {

    private final RateLimiter.Policy loginIp;
    private final RateLimiter.Policy loginUser;
    private final RateLimiter.Policy registerIp;

    public AuthRateLimitPolicies(
            @Value("${ratelimit.login-ip.capacity:30}") int loginIpCapacity,
            @Value("${ratelimit.login-ip.period:10m}") Duration loginIpPeriod,
            @Value("${ratelimit.login-user.capacity:10}") int loginUserCapacity,
            @Value("${ratelimit.login-user.period:10m}") Duration loginUserPeriod,
            @Value("${ratelimit.register-ip.capacity:10}") int registerIpCapacity,
            @Value("${ratelimit.register-ip.period:1h}") Duration registerIpPeriod
    ) {
        this.loginIp = new RateLimiter.Policy("login-ip", loginIpCapacity, loginIpPeriod);
        this.loginUser = new RateLimiter.Policy("login-user", loginUserCapacity, loginUserPeriod);
        this.registerIp = new RateLimiter.Policy("register-ip", registerIpCapacity, registerIpPeriod);
    }

    public RateLimiter.Policy loginIp() {
        return loginIp;
    }

    public RateLimiter.Policy loginUser() {
        return loginUser;
    }

    public RateLimiter.Policy registerIp() {
        return registerIp;
    }
}
