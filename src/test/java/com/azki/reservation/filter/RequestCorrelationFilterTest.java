package com.azki.reservation.filter;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void shouldReuseSafeRequestIdAndClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "request-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> idSeenInsideChain = new AtomicReference<>();

        filter.doFilter(request, response,
            (servletRequest, servletResponse) -> idSeenInsideChain.set(MDC.get("requestId")));

        assertEquals("request-123", idSeenInsideChain.get());
        assertEquals("request-123", response.getHeader(RequestCorrelationFilter.HEADER_NAME));
        assertNull(MDC.get("requestId"));
    }

    @Test
    void shouldReplaceUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "invalid\nlog-entry");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        String generated = response.getHeader(RequestCorrelationFilter.HEADER_NAME);
        assertFalse(generated.contains("\n"));
        assertFalse(generated.equals("invalid\nlog-entry"));
    }
}
