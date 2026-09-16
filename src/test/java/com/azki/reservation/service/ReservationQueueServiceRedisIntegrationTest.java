package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationQueueServiceRedisIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private ReservationQueueService queueService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoBean
    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        reset(reservationService);
        ReflectionTestUtils.setField(queueService, "batchSize", 1);
        ReflectionTestUtils.setField(queueService, "claimTimeoutMs", 300_000L);
    }

    @Test
    void shouldAtomicallyRejectDuplicateEmail() {
        ReservationRequestDto request = request("same@example.com");

        queueService.enqueueReservationRequest(request);

        assertThrows(
            DuplicateReservationException.class,
            () -> queueService.enqueueReservationRequest(request)
        );
        assertEquals(1, queueService.getQueueLength());
    }

    @Test
    void retryingFirstMessageShouldNotOverwriteSecondMessage() {
        String firstId = queueService.enqueueReservationRequest(request("first@example.com"));
        String secondId = queueService.enqueueReservationRequest(request("second@example.com"));
        when(reservationService.reserveNearestSlot(userId("first@example.com")))
            .thenThrow(new BusinessException("temporary failure"))
            .thenReturn(null);

        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(firstId));
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(secondId));
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void exhaustedMessageShouldMoveToDlqWithoutDeletingNextMessage() {
        String failingId = queueService.enqueueReservationRequest(request("failing@example.com"));
        String healthyId = queueService.enqueueReservationRequest(request("healthy@example.com"));
        when(reservationService.reserveNearestSlot(userId("failing@example.com")))
            .thenThrow(new BusinessException("temporary failure"));

        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.FAILED.name(), queueService.getRequestStatus(failingId));
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(healthyId));
        assertEquals(1, queueService.getDLQLength());
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void malformedMessageShouldBePreservedInDlq() {
        redisTemplate.opsForList().rightPush(ReservationQueueService.QUEUE_KEY, "not-json");

        queueService.processReservationQueue();

        assertEquals(1, queueService.getDLQLength());
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void shouldRecoverExpiredProcessingClaim() {
        redisTemplate.opsForZSet().add(ReservationQueueService.PROCESSING_KEY, "orphaned-message", 0);

        long recovered = queueService.recoverStaleClaims();

        assertEquals(1, recovered);
        assertEquals(1, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    private ReservationRequestDto request(String email) {
        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(userId(email));
        request.setEmail(email);
        return request;
    }

    private long userId(String email) {
        return Integer.toUnsignedLong(email.hashCode());
    }
}
