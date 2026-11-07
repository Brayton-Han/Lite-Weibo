package com.brayton.weibo.config.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.service.JWTService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

    private final JWTService jwtService = new JWTService(SECRET);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesAndContinues() throws Exception {
        MockHttpServletRequest request = requestWithToken(jwtService.generateToken(7L));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "filter chain should continue");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals(7L, ((CustomUserDetails) auth.getPrincipal()).getId());
    }

    @Test
    void expiredTokenDoesNotBlockChain() throws Exception {
        String expired = JWT.create()
                .withClaim("userId", 7L)
                .withIssuer("LiteWeibo")
                .withExpiresAt(new Date(System.currentTimeMillis() - 60_000))
                .sign(Algorithm.HMAC256(SECRET));
        MockHttpServletRequest request = requestWithToken(expired);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // 不在过滤器里回 401：公开接口（如 /login）要能照常处理
        assertNotNull(chain.getRequest(), "filter chain should continue");
        assertFalse(response.isCommitted());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(CommonErrorCode.TOKEN_EXPIRED,
                request.getAttribute(JsonAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE));
    }

    @Test
    void forgedTokenIsMarkedInvalid() throws Exception {
        String forged = new JWTService(SECRET + "-attacker").generateToken(7L);
        MockHttpServletRequest request = requestWithToken(forged);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(CommonErrorCode.TOKEN_INVALID,
                request.getAttribute(JsonAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE));
    }

    @Test
    void entryPointReturns401WithReasonFromFilter() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(mapper);

        MockHttpServletRequest noToken = new MockHttpServletRequest("GET", "/posts");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        entryPoint.commence(noToken, r1, new InsufficientAuthenticationException("x"));
        assertEquals(401, r1.getStatus());
        assertTrue(r1.getContentType().startsWith("application/json"));
        JsonNode body1 = mapper.readTree(r1.getContentAsString());
        assertEquals(CommonErrorCode.UNAUTHORIZED.getCode(), body1.get("code").asInt());
        assertEquals("/posts", body1.get("path").asText());

        MockHttpServletRequest expired = new MockHttpServletRequest("GET", "/posts");
        expired.setAttribute(JsonAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, CommonErrorCode.TOKEN_EXPIRED);
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        entryPoint.commence(expired, r2, new InsufficientAuthenticationException("x"));
        assertEquals(401, r2.getStatus());
        assertEquals(CommonErrorCode.TOKEN_EXPIRED.getCode(),
                mapper.readTree(r2.getContentAsString()).get("code").asInt());
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/posts");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
