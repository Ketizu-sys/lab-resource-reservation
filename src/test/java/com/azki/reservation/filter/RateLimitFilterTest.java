package com.azki.reservation.filter;

import com.azki.reservation.config.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitFilterTest {

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimitConfig config = new RateLimitConfig();
        config.setCapacity(1);
        config.setRefillTokens(1);
        config.setRefillPeriod(Duration.ofHours(1));
        config.setMaxTrackedClients(100);
        config.validate();
        filter = new RateLimitFilter(config);
    }

    @Test
    void shouldReturn429WhenSameClientExceedsReservationLimit() throws Exception {
        MockHttpServletRequest first = request("/api/v1/reservations/reserve", "10.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());

        MockHttpServletRequest second = request("/api/v1/reservations/reserve", "10.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
        assertEquals("3600", secondResponse.getHeader("Retry-After"));
    }

    @Test
    void shouldKeepDifferentClientsAndRouteGroupsIndependent() throws Exception {
        filter.doFilter(request("/api/v1/reservations/reserve", "10.0.0.1"),
            new MockHttpServletResponse(), new MockFilterChain());

        MockHttpServletResponse otherClientResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/v1/reservations/reserve", "10.0.0.2"),
            otherClientResponse, new MockFilterChain());

        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/login", "10.0.0.1"),
            loginResponse, new MockFilterChain());

        assertEquals(200, otherClientResponse.getStatus());
        assertEquals(200, loginResponse.getStatus());
    }

    @Test
    void shouldBypassUnprotectedPath() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/actuator/health", "10.0.0.1"), response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest request(String uri, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
