package com.brayton.weibo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 前端地址，逗号分隔可配多个；部署时用环境变量 CORS_ALLOWED_ORIGINS 覆盖 */
    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")                 // 允许所有的接口路径
                .allowedOrigins(allowedOrigins)          // 允许来自 Next.js 前端的地址
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 允许的方法
                .allowedHeaders("*")               // 允许所有的请求头
                .allowCredentials(true);           // 允许携带凭证（如 Cookies 或 Auth Header）
    }
}