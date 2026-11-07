package com.brayton.weibo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.exceptions.SignatureVerificationException;
import org.junit.jupiter.api.Test;

class JWTServiceTest {

    private static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

    @Test
    void roundTripsUserId() {
        JWTService service = new JWTService(SECRET);
        String token = service.generateToken(42L);
        assertEquals(42L, service.getUserIdFromToken(token));
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = new JWTService(SECRET + "-other").generateToken(42L);
        JWTService service = new JWTService(SECRET);
        assertThrows(SignatureVerificationException.class, () -> service.getUserIdFromToken(token));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JWTService("too-short"));
    }

    @Test
    void rejectsKnownLeakedSecrets() {
        assertThrows(IllegalStateException.class, () -> new JWTService("BRAYTON_LITE_WEIBO_SECRET_KEY"));
        assertThrows(IllegalStateException.class,
                () -> new JWTService("A_STRONG_AND_SECURE_SECRET_KEY_FOR_SIGNATURE"));
        assertThrows(IllegalStateException.class, () -> new JWTService("CHANGE_ME_USE_A_LONG_RANDOM_STRING"));
    }
}
