package com.brayton.weibo.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JWTService {

    private static final long EXPIRATION_TIME = 3600000; // 1小时 (毫秒)
    private static final String USER_ID_CLAIM = "userId";
    private static final String ISSUER = "LiteWeibo";

    // HS256 的密钥至少要和哈希输出一样长（RFC 7518 §3.2）
    private static final int MIN_SECRET_BYTES = 32;

    // 出现在 git 历史、示例配置或旧代码里的值，等同于公开，拒绝启动
    private static final Set<String> KNOWN_INSECURE_SECRETS = Set.of(
            "BRAYTON_LITE_WEIBO_SECRET_KEY",
            "A_STRONG_AND_SECURE_SECRET_KEY_FOR_SIGNATURE",
            "CHANGE_ME_USE_A_LONG_RANDOM_STRING"
    );

    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    // 不设默认值：未配置 jwt.secret（或环境变量 JWT_SECRET）时启动失败，而不是悄悄用一个公开的密钥
    public JWTService(@Value("${jwt.secret}") String secret) {
        if (KNOWN_INSECURE_SECRETS.contains(secret)) {
            throw new IllegalStateException(
                    "jwt.secret 使用的是已公开的值，请换成新的随机串（例如 openssl rand -base64 48）");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节，请用 openssl rand -base64 48 生成");
        }
        // Algorithm 与 JWTVerifier 都是线程安全的，构造一次复用
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
    }

    // 1. 生成 JWT
    public String generateToken(Long userId) {
        long now = System.currentTimeMillis();
        return JWT.create()
                .withClaim(USER_ID_CLAIM, userId) // 载荷中的用户ID
                .withIssuer(ISSUER) // 签发者
                .withIssuedAt(new Date(now)) // 签发时间
                .withExpiresAt(new Date(now + EXPIRATION_TIME)) // 过期时间
                .sign(algorithm);
    }

    // 2. 验证并解析 JWT
    public DecodedJWT verifyToken(String token) {
        return verifier.verify(token); // 验证签名和各种 Claim (如过期时间)
    }

    // 3. 从解析后的 Token 中获取用户ID
    public Long getUserIdFromToken(String token) {
        DecodedJWT jwt = verifyToken(token);
        return jwt.getClaim(USER_ID_CLAIM).asLong();
    }
}
