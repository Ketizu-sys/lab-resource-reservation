package com.azki.reservation.security.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String TEST_SECRET = "test-only-jwt-secret-with-at-least-32-bytes";

    @Test
    void shouldGenerateAndValidateTokenWithInjectedSecret() {
        JwtUtil jwtUtil = new JwtUtil(TEST_SECRET, 60_000);

        String token = jwtUtil.generateToken("user@example.com");

        assertTrue(jwtUtil.isTokenValid(token));
        assertEquals("user@example.com", jwtUtil.extractEmail(token));
        assertTrue(jwtUtil.getExpirationDate(token).isAfter(LocalDateTime.now()));
    }

    @Test
    void shouldRejectWeakSecretAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil("too-short", 60_000));
    }

    @Test
    void shouldRejectNonPositiveExpirationAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new JwtUtil(TEST_SECRET, 0));
    }
}
