package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationMode;
import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.entity.*;
import com.azki.reservation.repository.*;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 Redis 异步预约的数据库侧幂等保证。
 *
 * <p>队列采用 at-least-once 投递，消息可能被重放。reservation.request_id 上的
 * 唯一约束让 PostgreSQL 成为最终事实源，从而把投递语义收敛为
 * effectively-once 的业务效果。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AsyncReservationIdempotencyIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired private ReservationService reservationService;
    @Autowired private ReservationQueueService queueService;
    @Autowired private UserRepository users;
    @Autowired private ResourceRepository resources;
    @Autowired private TimeSlotRepository slots;
    @Autowired private ReservationRepository reservations;
    @Autowired private RedisTemplate<String, Object> redisTemplate;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        reservations.deleteAll();
        slots.deleteAll();
        resources.deleteAll();
        ReflectionTestUtils.setField(queueService, "batchSize", 1);
        ReflectionTestUtils.setField(queueService, "claimTimeoutMs", 300_000L);
    }

    @Test
    void shouldPersistRequestIdOnFirstAsyncReservation() {
        User user = user();
        Resource resource = resource();
        slot(resource, 1);

        String requestId = "itest-" + UUID.randomUUID();
        Reservation created = reservationService.reserveNearestSlot(user.getId(), requestId);

        assertEquals(requestId, created.getRequestId());
        assertEquals(1, reservations.count());
        assertTrue(reservations.findByRequestId(requestId).isPresent());
    }

    @Test
    void shouldNotCreateSecondReservationWhenSameRequestIdIsReplayed() {
        User user = user();
        Resource resource = resource();
        slot(resource, 1);
        slot(resource, 3);

        String requestId = "itest-" + UUID.randomUUID();
        Reservation first = reservationService.reserveNearestSlot(user.getId(), requestId);
        Reservation replayed = reservationService.reserveNearestSlot(user.getId(), requestId);

        assertEquals(first.getId(), replayed.getId());
        assertEquals(1, reservations.count());
    }

    @Test
    void shouldCreateSeparateReservationsForDifferentRequestIds() {
        User user = user();
        Resource resource = resource();
        slot(resource, 1);
        slot(resource, 3);

        Reservation first = reservationService.reserveNearestSlot(user.getId(), "itest-a-" + UUID.randomUUID());
        Reservation second = reservationService.reserveNearestSlot(user.getId(), "itest-b-" + UUID.randomUUID());

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, reservations.count());
    }

    @Test
    void manualReservationsShouldKeepNullRequestIdWithoutUniqueConflict() {
        Resource resource = resource();
        AvailableSlot first = slot(resource, 1);
        AvailableSlot second = slot(resource, 3);

        Reservation manualOne = reservationService.reserveSlot(user().getId(), first.getId());
        Reservation manualTwo = reservationService.reserveSlot(user().getId(), second.getId());

        assertNull(manualOne.getRequestId());
        assertNull(manualTwo.getRequestId());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE request_id IS NOT NULL", Integer.class));
    }

    /**
     * 核心恢复场景：数据库已经落库，但 Redis 中的 SUCCESS 状态丢失。
     * 此时必须以 PostgreSQL 为准恢复成功状态，且不能产生第二条预约。
     */
    @Test
    void shouldRestoreSuccessFromDatabaseWhenRedisStatusIsLost() {
        User user = user();
        Resource resource = resource();
        slot(resource, 1);

        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(user.getId());
        request.setMode(ReservationMode.AUTO);

        String requestId = queueService.enqueueReservationRequest(request);
        Object raw = redisTemplate.opsForList().leftPop(ReservationQueueService.QUEUE_KEY);
        assertNotNull(raw);
        redisTemplate.opsForList().rightPush(ReservationQueueService.QUEUE_KEY, raw);

        queueService.processReservationQueue();

        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(),
                queueService.getRequestStatus(requestId, user.getId()));
        assertEquals(1, reservations.count());

        // 模拟 SUCCESS 未写入：状态停留在 PROCESSING。
        redisTemplate.opsForValue().set(ReservationQueueService.STATUS_KEY_PREFIX + requestId,
                ReservationQueueService.RequestStatus.PROCESSING.name());
        // 同一条消息被重放。
        redisTemplate.opsForList().rightPush(ReservationQueueService.QUEUE_KEY, raw);
        queueService.processReservationQueue();

        assertEquals(1, reservations.count());
        assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(),
                queueService.getRequestStatus(requestId, user.getId()));
        assertEquals(0, queueService.getQueueLength());
        assertEquals(0, queueService.getProcessingLength());
    }

    @Test
    void databaseShouldEnforceUniqueRequestIdConstraint() {
        User user = user();
        AvailableSlot slot = slot(resource(), 1);
        String requestId = "itest-dup-" + UUID.randomUUID();

        insertCancelledReservation(user.getId(), slot.getId(), requestId);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uk_reservation_request_id'", Integer.class));

        assertThrows(DataIntegrityViolationException.class,
                () -> insertCancelledReservation(user.getId(), slot.getId(), requestId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE request_id = ?", Integer.class, requestId));
    }

    @Test
    void concurrentConsumersShouldProduceOnlyOneReservationForSameRequestId() throws Exception {
        User user = user();
        Resource resource = resource();
        slot(resource, 1);
        slot(resource, 3);
        slot(resource, 5);
        String requestId = "itest-conc-" + UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        reservationService.reserveNearestSlot(user.getId(), requestId);
                    } catch (Exception ignored) {
                        // 并发下允许其中一个线程因无可用时段失败；本用例只约束最终数据条数。
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, reservations.count(), "同一 requestId 并发处理最多只能产生一条预约");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM reservation WHERE request_id = ?", Integer.class, requestId));
    }

    private void insertCancelledReservation(Long userId, Long slotId, String requestId) {
        jdbc.update("""
                INSERT INTO reservation
                    (user_id, available_slot_id, reserved_at, status, cancelled_at, cancel_reason,
                     request_id, created_by, created_date, version)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'CANCELLED', CURRENT_TIMESTAMP, 'idempotency-test',
                        ?, 'SYSTEM', CURRENT_TIMESTAMP, 0)
                """, userId, slotId, requestId);
    }

    private User user() {
        User u = new User();
        u.setUserName("idem-" + UUID.randomUUID());
        u.setEmail("idem-" + UUID.randomUUID() + "@test.local");
        u.setPassword("encoded");
        u.setRole(UserRole.USER);
        return users.saveAndFlush(u);
    }

    private Resource resource() {
        Resource r = new Resource();
        r.setName("idem-" + UUID.randomUUID());
        r.setType(ResourceType.LAB);
        r.setLocation("test");
        r.setCapacity(1);
        r.setStatus(ResourceStatus.ACTIVE);
        return resources.saveAndFlush(r);
    }

    private AvailableSlot slot(Resource resource, int plusHours) {
        LocalDateTime start = LocalDateTime.now().plusHours(plusHours);
        AvailableSlot s = new AvailableSlot();
        s.setResource(resource);
        s.setStartTime(start);
        s.setEndTime(start.plusHours(1));
        return slots.saveAndFlush(s);
    }
}
