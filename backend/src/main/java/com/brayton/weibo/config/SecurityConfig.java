package com.brayton.weibo.config;

import com.brayton.weibo.config.security.JsonAuthenticationEntryPoint;
import com.brayton.weibo.service.JWTService;
import com.brayton.weibo.config.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JWTService jwtService;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;

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
                        // Actuator 只在管理端口（management.server.port）上提供，主端口上不存在这些路径；管理端口不对外映射
                        .requestMatchers("/actuator/**").permitAll()
                        // SseEmitter 结束时的 async dispatch 不经过 JWT 过滤器；原请求已经鉴权过，这里放行，否则响应已提交后再报 Access Denied
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        // 未处理异常转发到 /error 时 JWT 过滤器不会再跑，身份已丢；不放行的话原本的 500 会被改写成 401，前端误以为掉登录
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                )

                // 未登录 / token 无效统一回 401 + JSON（默认是 403）
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))

                // 注册 JWT 过滤器，放在 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
