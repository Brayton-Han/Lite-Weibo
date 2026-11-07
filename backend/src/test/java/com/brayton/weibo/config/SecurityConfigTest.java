package com.brayton.weibo.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.brayton.weibo.config.security.JsonAuthenticationEntryPoint;
import com.brayton.weibo.error.CommonErrorCode;
import com.brayton.weibo.service.JWTService;
import jakarta.servlet.DispatcherType;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 走真实的 SecurityFilterChain，验证 401/放行行为；业务控制器用桩代替。 */
@WebMvcTest(controllers = SecurityConfigTest.StubController.class)
@Import({SecurityConfig.class, JsonAuthenticationEntryPoint.class, JWTService.class, SecurityConfigTest.StubController.class})
@TestPropertySource(properties = "jwt.secret=" + SecurityConfigTest.SECRET)
class SecurityConfigTest {

    static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

    @RestController
    static class StubController {
        @PostMapping("/login")
        String login() {
            return "login-ok";
        }

        @GetMapping("/protected")
        String protectedEndpoint() {
            return "protected-ok";
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JWTService jwtService;

    @Test
    void missingTokenOnProtectedEndpointIs401Json() throws Exception {
        mvc.perform(get("/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(CommonErrorCode.UNAUTHORIZED.getCode()))
                .andExpect(jsonPath("$.path").value("/protected"));
    }

    @Test
    void expiredTokenOnProtectedEndpointIs401WithReason() throws Exception {
        mvc.perform(get("/protected").header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.TOKEN_EXPIRED.getCode()));
    }

    @Test
    void forgedTokenOnProtectedEndpointIs401WithReason() throws Exception {
        String forged = new JWTService(SECRET + "-attacker").generateToken(1L);
        mvc.perform(get("/protected").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.TOKEN_INVALID.getCode()));
    }

    @Test
    void validTokenReachesProtectedEndpoint() throws Exception {
        mvc.perform(get("/protected").header("Authorization", "Bearer " + jwtService.generateToken(1L)))
                .andExpect(status().isOk())
                .andExpect(content().string("protected-ok"));
    }

    @Test
    void staleTokenDoesNotBlockLogin() throws Exception {
        mvc.perform(post("/login").header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isOk())
                .andExpect(content().string("login-ok"));
    }

    @Test
    void errorDispatchIsNotRewrittenTo401() throws Exception {
        mvc.perform(get("/protected").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    return request;
                }))
                .andExpect(status().isOk());
    }

    private static String expiredToken() {
        return JWT.create()
                .withClaim("userId", 1L)
                .withIssuer("LiteWeibo")
                .withExpiresAt(new Date(System.currentTimeMillis() - 60_000))
                .sign(Algorithm.HMAC256(SECRET));
    }
}
