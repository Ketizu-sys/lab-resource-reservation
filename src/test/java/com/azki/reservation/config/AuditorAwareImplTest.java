package com.azki.reservation.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditorAwareImplTest {

    private final AuditorAwareImpl auditorAware = new AuditorAwareImpl();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUseSystemForAnonymousRequest() {
        AnonymousAuthenticationToken authentication = new AnonymousAuthenticationToken(
            "test-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertEquals("system", auditorAware.getCurrentAuditor().orElseThrow());
    }

    @Test
    void shouldUseAuthenticatedUserName() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("user@example.com", null, "ROLE_USER"));

        assertEquals("user@example.com", auditorAware.getCurrentAuditor().orElseThrow());
    }
}
