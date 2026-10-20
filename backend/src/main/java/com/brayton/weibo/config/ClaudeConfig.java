package com.brayton.weibo.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Claude API client。key 只从环境变量 {@code ANTHROPIC_API_KEY} 读，不要写进配置文件。
 *
 * <p>与 {@code VOYAGE_API_KEY} 同一口径：缺 key 时应用照常启动，只在调用时由 API 返回 401。
 * SDK 自带重试（408/409/429/5xx 与连接错误，指数退避），这里只调次数和超时。
 */
@Configuration
public class ClaudeConfig {

    @Bean(destroyMethod = "close")
    public AnthropicClient anthropicClient(
            @Value("${ANTHROPIC_API_KEY:}") String apiKey,
            @Value("${claude.timeout-ms:60000}") long timeoutMs,
            @Value("${claude.max-retries:2}") int maxRetries
    ) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofMillis(timeoutMs))
                .maxRetries(maxRetries)
                .build();
    }
}
