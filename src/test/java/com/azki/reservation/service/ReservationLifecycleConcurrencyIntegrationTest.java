package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.entity.User;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证取消与定时完成并发时不会互相覆盖生命周期状态。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationLifecycleConcurrencyIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired ReservationService reservationService;
    @Autowired ReservationExpiryService expiryService;
    @Autowired ReservationRepository reservations;
    @Autowired TimeSlotRepository slots;
    @Autowired ResourceRepository resources;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        reservations.deleteAll();
        slots.deleteAll();
        resources.deleteAll();
    }

    @Test
    void cancellationAndExpiryScanShouldLeaveFutureReservationCancelled() throws Exception {
        Reservation reservation = reservation(LocalDateTime.now().plusHours(2));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> cancellation = executor.submit(() -> {
                await(start);
                reservationService.cancelReservation(reservation.getId(), reservation.getUser().getId());
            });
            Future<?> expiry = executor.submit(() -> {
                await(start);
                expiryService.processExpiredReservations();
            });
            start.countDown();
            cancellation.get();
            expiry.get();
        }

        Reservation actual = reservations.findById(reservation.getId()).orElseThrow();
        assertEquals(ReservationStatus.CANCELLED, actual.getStatus());
        assertFalse(slots.findById(reservation.getAvailableSlot().getId()).orElseThrow().isReserved());
    }

    @Test
    void expiryShouldSkipReservationLockedByConcurrentLifecycleChange() throws Exception {
        Reservation reservation = reservation(LocalDateTime.now().minusHours(2));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> competingLifecycleChange = executor.submit(() -> transactions.executeWithoutResult(status -> {
                reservations.findByIdForUpdate(reservation.getId()).orElseThrow();
                locked.countDown();
                await(release);
            }));

            locked.await();
            expiryService.processExpiredReservations();
            assertEquals(ReservationStatus.ACTIVE,
                    reservations.findById(reservation.getId()).orElseThrow().getStatus());

            release.countDown();
            competingLifecycleChange.get();
        }

        expiryService.processExpiredReservations();
        assertEquals(ReservationStatus.COMPLETED,
                reservations.findById(reservation.getId()).orElseThrow().getStatus());
    }

    private Reservation reservation(LocalDateTime start) {
        String suffix = UUID.randomUUID().toString();
        User user = new User();
        user.setEmail("lifecycle-" + suffix + "@test.local");
        user.setUserName("lifecycle-" + suffix);
        user.setPassword("encoded");
        user = users.saveAndFlush(user);

        Resource resource = new Resource();
        resource.setName("lifecycle-resource-" + suffix);
        resource.setType(ResourceType.LAB);
        resource.setLocation("test");
        resource.setStatus(ResourceStatus.ACTIVE);
        resource.setCapacity(1);
        resource = resources.saveAndFlush(resource);

        AvailableSlot slot = new AvailableSlot();
        slot.setResource(resource);
        slot.setStartTime(start);
        slot.setEndTime(start.plusHours(1));
        slot.setReserved(true);
        slot = slots.saveAndFlush(slot);

        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(LocalDateTime.now());
        reservation.setStatus(ReservationStatus.ACTIVE);
        return reservations.saveAndFlush(reservation);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrency test", e);
        }
    }
}
