package com.azki.reservation.config;

import com.azki.reservation.service.ReservationQueueService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationQueueHealthIndicatorTest {

    @Test
    void shouldUseConfiguredQueueThresholds() {
        ReservationQueueService queueService = mock(ReservationQueueService.class);
        when(queueService.getQueueLength()).thenReturn(6L);
        when(queueService.getDLQLength()).thenReturn(0L);
        ReservationQueueHealthIndicator indicator =
            new ReservationQueueHealthIndicator(queueService, 5, 10, 2);

        Health health = indicator.health();

        assertEquals("WARNING", health.getStatus().getCode());
        assertEquals(6L, health.getDetails().get("queueSize"));
    }

    @Test
    void shouldRejectInconsistentThresholds() {
        ReservationQueueService queueService = mock(ReservationQueueService.class);

        assertThrows(IllegalArgumentException.class,
            () -> new ReservationQueueHealthIndicator(queueService, 10, 5, 2));
    }
}
