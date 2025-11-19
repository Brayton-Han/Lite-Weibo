package com.brayton.weibo.config.security;

import com.brayton.weibo.dto.UserResponse;
import com.brayton.weibo.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;
import com.auth0.jwt.exceptions.JWTVerificationException;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JWTService jwtService;
    private final UserService userService; // 或 UserDetailsService

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. 从请求头获取 Token (Bearer Token 格式)
        String authHeader = request.getHeader("Authorization");
        String token = null;
        Long userId = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token != null) {
            try {
                // 2. 验证 Token 并获取用户ID
                userId = jwtService.getUserIdFromToken(token);

                // 3. 将用户信息设置到 SecurityContext 中 (表示已认证)
                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                    CustomUserDetails userPrincipal = new CustomUserDetails(userId);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JWTVerificationException e) {
                // Token 验证失败（如过期、签名无效），清除上下文并返回 401
                SecurityContextHolder.clearContext();
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired JWT");
                return; // 停止链式调用
            }
        }

        filterChain.doFilter(request, response);
    }
}