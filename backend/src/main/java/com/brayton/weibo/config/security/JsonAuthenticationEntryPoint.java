package com.brayton.weibo.config.security;

import com.brayton.weibo.dto.ErrorResponse;
import com.brayton.weibo.error.CommonErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 需要登录的接口没有拿到有效身份时统一回 401 + ErrorResponse。
 * 不配置时 Spring Security 默认回 403，和"token 失效"的 401 不一致，前端无法区分"未登录"与"无权限"。
 *
 * <p>具体原因由 {@link JwtAuthenticationFilter} 放在请求属性里：没带 token → UNAUTHORIZED，
 * 过期 → TOKEN_EXPIRED，签名/格式不对 → TOKEN_INVALID。
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String AUTH_ERROR_ATTRIBUTE = JsonAuthenticationEntryPoint.class.getName() + ".AUTH_ERROR";

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        CommonErrorCode errorCode = request.getAttribute(AUTH_ERROR_ATTRIBUTE) instanceof CommonErrorCode code
                ? code
                : CommonErrorCode.UNAUTHORIZED;

        ErrorResponse body = new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage(),
                LocalDateTime.now(),
                request.getRequestURI()
        );

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
