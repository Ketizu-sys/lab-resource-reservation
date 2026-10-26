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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        when(reservationService.reserveNearestSlot(eq(1L), anyString()))
            .thenThrow(new BusinessException("temporary failure"))
            .thenReturn(null);

        queueService.processReservationQueue();
        queueService.processReservationQueue();
        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(firstId, 1L));
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(secondId, 2L));
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
        assertFalse(queueService.isUserAlreadyInQueue(1L));
        assertFalse(queueService.isUserAlreadyInQueue(2L));
    }

    @Test
    void exhaustedMessageShouldMoveToDlqWithoutDeletingNextMessage() {
        String failingId = queueService.enqueueReservationRequest(request(3L));
        String healthyId = queueService.enqueueReservationRequest(request(4L));
        when(reservationService.reserveNearestSlot(eq(3L), anyString()))
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
        assertFalse(queueService.isUserAlreadyInQueue(3L));
        assertFalse(queueService.isUserAlreadyInQueue(4L));
    }

    @Test
    void retryShouldKeepDedupAndReturnStatusToQueued() {
        String requestId = queueService.enqueueReservationRequest(request(5L));
        when(reservationService.reserveNearestSlot(eq(5L), anyString()))
                .thenThrow(new BusinessException("temporary failure"));

        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.QUEUED.name(),
                queueService.getRequestStatus(requestId, 5L));
        assertTrue(queueService.isUserAlreadyInQueue(5L));
        assertEquals(1, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
        assertEquals(0, queueService.getDLQLength());
    }

    @Test
    void permanentFailureShouldClearDedupAndKeepFailedStatus() {
        String requestId = queueService.enqueueReservationRequest(request(6L));
        when(reservationService.reserveNearestSlot(eq(6L), anyString()))
                .thenThrow(new DuplicateReservationException("already reserved"));

        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.FAILED.name(),
                queueService.getRequestStatus(requestId, 6L));
        assertFalse(queueService.isUserAlreadyInQueue(6L));
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
    void malformedJsonWithReadableMetadataShouldClearDedupAndPublishFailedStatus() {
        String raw = """
                {"request":{"userId":8,"mode":"UNKNOWN"},"attempts":0,"requestId":"malformed-8"}
                """;
        redisTemplate.opsForSet().add(ReservationQueueService.USER_SET_KEY, "8");
        redisTemplate.opsForList().rightPush(ReservationQueueService.QUEUE_KEY, raw);

        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.FAILED.name(),
                queueService.getRequestStatus("malformed-8", 8L));
        assertFalse(queueService.isUserAlreadyInQueue(8L));
        assertEquals(1, queueService.getDLQLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void shouldRecoverExpiredProcessingClaim() {
        String requestId = queueService.enqueueReservationRequest(request(7L));
        Object raw = redisTemplate.opsForList().leftPop(ReservationQueueService.QUEUE_KEY);
        // 升级前的处理中集合直接保存原始消息；恢复脚本仍需兼容该格式。
        redisTemplate.opsForZSet().add(ReservationQueueService.PROCESSING_KEY, raw, 0);
        redisTemplate.opsForSet().remove(ReservationQueueService.USER_SET_KEY, "7");

        long recovered = queueService.recoverStaleClaims();

        assertEquals(1, recovered);
        assertEquals(1, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
        assertTrue(queueService.isUserAlreadyInQueue(7L));
        assertEquals(ReservationQueueService.RequestStatus.QUEUED.name(),
                queueService.getRequestStatus(requestId, 7L));
        assertNull(queueService.getRequestStatus(requestId, 999L));
    }

    @Test
    void expiredConsumerMustNotAcknowledgeNewClaimForSameRequest() throws Exception {
        String requestId = queueService.enqueueReservationRequest(request(10L));
        CountDownLatch firstConsumerEntered = new CountDownLatch(1);
        CountDownLatch secondConsumerEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstConsumer = new CountDownLatch(1);
        CountDownLatch releaseSecondConsumer = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();

        when(reservationService.reserveNearestSlot(eq(10L), eq(requestId))).thenAnswer(invocation -> {
            int current = invocations.incrementAndGet();
            if (current == 1) {
                firstConsumerEntered.countDown();
                assertTrue(releaseFirstConsumer.await(10, TimeUnit.SECONDS));
            } else if (current == 2) {
                secondConsumerEntered.countDown();
                assertTrue(releaseSecondConsumer.await(10, TimeUnit.SECONDS));
            }
            return null;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(queueService::processReservationQueue);
            assertTrue(firstConsumerEntered.await(10, TimeUnit.SECONDS));

            // A 的 Claim 立即过期；B 回收同一消息并获得新的 claimToken。
            ReflectionTestUtils.setField(queueService, "claimTimeoutMs", 0L);
            Future<?> second = executor.submit(queueService::processReservationQueue);
            assertTrue(secondConsumerEntered.await(10, TimeUnit.SECONDS));

            // 旧消费者 A 先完成，但它不能删除 B 当前持有的 Claim。
            releaseFirstConsumer.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertEquals(1, queueService.getProcessingLength());
            assertTrue(queueService.isUserAlreadyInQueue(10L));
            assertEquals(ReservationQueueService.RequestStatus.PROCESSING.name(),
                queueService.getRequestStatus(requestId, 10L));

            releaseSecondConsumer.countDown();
            second.get(10, TimeUnit.SECONDS);
            assertEquals(0, queueService.getProcessingLength());
            assertFalse(queueService.isUserAlreadyInQueue(10L));
            assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(),
                queueService.getRequestStatus(requestId, 10L));
        } finally {
            releaseFirstConsumer.countDown();
            releaseSecondConsumer.countDown();
            executor.shutdownNow();
        }
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

        verify(reservationService).reserveSlot(9L, 42L, requestId);
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(), queueService.getRequestStatus(requestId, 9L));
        assertEquals(0, queueService.getProcessingLength());
        assertFalse(queueService.isUserAlreadyInQueue(9L));
    }
}
