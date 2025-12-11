package com.brayton.weibo.config;

import com.brayton.weibo.config.security.JWTService;
import com.brayton.weibo.config.security.JwtAuthenticationFilter;
import com.brayton.weibo.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JWTService jwtService;
    private final UserService userService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 禁用 CSRF，因为我们使用 JWT (无状态)
                .csrf(csrf -> csrf.disable())

                // 设置会话管理为无状态 (STATELESS)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // CORES
                .cors(Customizer.withDefaults())

                // 授权规则配置
                .authorizeHttpRequests(auth -> auth
                        // 允许所有人访问登录和注册端点
                        .requestMatchers("/login", "/register", "ping").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                )

                // 注册 JWT 过滤器，放在 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, userService),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}