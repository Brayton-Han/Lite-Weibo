package com.brayton.weibo.config.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JWTService {

    // 秘钥存储在配置文件 application.yml 中
    @Value("${jwt.secret:A_STRONG_AND_SECURE_SECRET_KEY_FOR_SIGNATURE}")
    private String secret;

    private static final long EXPIRATION_TIME = 86400000; // 24小时 (毫秒)
    private static final String USER_ID_CLAIM = "userId";
    private static final String ISSUER = "LiteWeibo";

    // 1. 生成 JWT
    public String generateToken(Long userId) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.create()
                .withClaim(USER_ID_CLAIM, userId) // 载荷中的用户ID
                .withIssuer(ISSUER) // 签发者
                .withIssuedAt(new Date()) // 签发时间
                .withExpiresAt(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // 过期时间
                .sign(algorithm);
    }

    // 2. 验证并解析 JWT
    public DecodedJWT verifyToken(String token) {
        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build()
                .verify(token); // 验证签名和各种 Claim (如过期时间)
    }

    // 3. 从解析后的 Token 中获取用户ID
    public Long getUserIdFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        return jwt.getClaim(USER_ID_CLAIM).asLong();
    }
}