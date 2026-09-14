package com.azki.reservation.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordConfigTest {

    private static final String DEVELOPMENT_PASSWORD_HASH =
            "$2a$10$y.voNlH.YlxpEm2feoJ0D.u3AGQstPsP42VXag5uwNmOvSS5TWPCC";

    @Test
    void developmentPasswordHashShouldMatchDocumentedPassword() {
        PasswordEncoder passwordEncoder = new PasswordConfig().passwordEncoder();

        assertTrue(passwordEncoder.matches("password", DEVELOPMENT_PASSWORD_HASH));
    }
}
