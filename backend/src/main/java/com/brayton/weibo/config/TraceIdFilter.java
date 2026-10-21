package com.brayton.weibo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 给每个请求一个 traceId 放进 MDC（RAG Day 17，§6.3-22 的"MDC 注入 traceId"）：同一次请求的日志——包括流式问答
 * 在 ask 线程池、混合检索在 search 线程池上打的（{@code AsyncConfig#MDC_PROPAGATION}）——都带同一个 id，
 * 结构化日志里是独立字段，可以直接按它过滤。userId 由 {@code JwtAuthenticationFilter} 鉴权成功后放进去。
 *
 * <p>上游（网关、前端）带了 {@code X-Request-Id} 就沿用，格式不对的丢弃重新生成，免得日志被注入；
 * 响应头 {@code X-Trace-Id} 回传，用户报问题时可以凭它查日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";
    static final String REQUEST_HEADER = "X-Request-Id";
    static final String RESPONSE_HEADER = "X-Trace-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_HEADER);
        String traceId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID);
            MDC.remove(USER_ID);
        }
    }
}
