package com.brayton.weibo.config.security;

import com.brayton.weibo.config.TraceIdFilter;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.service.JWTService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import lombok.RequiredArgsConstructor;

/**
 * 只负责"有合法 token 就建立身份"，不在这里拒绝请求。
 *
 * <p>token 无效时只清空上下文、记下原因然后放行：公开接口（/login、/register 等）照常处理，
 * 需要登录的接口由授权规则拦下，交给 {@link JsonAuthenticationEntryPoint} 统一回 401。
 * 这样本地残留的过期 token 不会把登录请求也挡掉。
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JWTService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 1. 从请求头获取 Token (Bearer Token 格式)
        String authHeader = request.getHeader("Authorization");
        String token = null;

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token != null) {
            try {
                // 2. 验证 Token 并获取用户ID
                Long userId = jwtService.getUserIdFromToken(token);

                // 3. 将用户信息设置到 SecurityContext 中 (表示已认证)
                if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                    CustomUserDetails userPrincipal = new CustomUserDetails(userId);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    // 由 TraceIdFilter 在请求结束时清掉
                    MDC.put(TraceIdFilter.USER_ID, String.valueOf(userId));
                }
            } catch (TokenExpiredException e) {
                SecurityContextHolder.clearContext();
                request.setAttribute(JsonAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, CommonErrorCode.TOKEN_EXPIRED);
            } catch (JWTVerificationException e) {
                // 签名无效、格式错误、issuer 不对等
                SecurityContextHolder.clearContext();
                request.setAttribute(JsonAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, CommonErrorCode.TOKEN_INVALID);
            }
        }

        filterChain.doFilter(request, response);
    }
}
