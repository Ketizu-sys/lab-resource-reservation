package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.dto.reservation.ReservationMode;
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
import static org.mockito.Mockito.verify;

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
        ReservationRequestDto request = request(1L);

        queueService.enqueueReservationRequest(request);

        assertThrows(
            DuplicateReservationException.class,
            () -> queueService.enqueueReservationRequest(request)
        );
        assertEquals(1, queueService.getQueueLength());
    }

    @Test
    void retryingFirstMessageShouldNotOverwriteSecondMessage() {
        String firstId = queueService.enqueueReservationRequest(request(1L));
        String secondId = queueService.enqueueReservationRequest(request(2L));
        when(reservationService.reserveNearestSlot(1L))
            .thenThrow(new BusinessException("temporary failure"))
            .thenReturn(null);

        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(firstId, 1L));
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(secondId, 2L));
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void exhaustedMessageShouldMoveToDlqWithoutDeletingNextMessage() {
        String failingId = queueService.enqueueReservationRequest(request(3L));
        String healthyId = queueService.enqueueReservationRequest(request(4L));
        when(reservationService.reserveNearestSlot(3L))
            .thenThrow(new BusinessException("temporary failure"));

        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.FAILED.name(), queueService.getRequestStatus(failingId, 3L));
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(healthyId, 4L));
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

    private ReservationRequestDto request(Long userId) {
        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(userId);
        request.setMode(ReservationMode.AUTO);
        return request;
    }

    @Test
    void manualQueueMessageShouldCallUnifiedManualReservationEntry() {
        ReservationRequestDto manual = request(9L);
        manual.setMode(ReservationMode.MANUAL);
        manual.setSlotId(42L);
        String requestId = queueService.enqueueReservationRequest(manual);

        queueService.processReservationQueue();

        verify(reservationService).reserveSlot(9L, 42L);
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(requestId, 9L));
        assertEquals(0, queueService.getProcessingLength());
    }
}
