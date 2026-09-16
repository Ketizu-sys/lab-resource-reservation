package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.User;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证并发请求下的时段唯一性和用户时间重叠规则。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationConcurrencyIntegrationTest extends ContainerIntegrationTestSupport {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @BeforeEach
    void clearReservationData() {
        reservationRepository.deleteAll();
        timeSlotRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void concurrentNonOverlappingRequestsForSameUserShouldBothSucceed() throws Exception {
        String email = "concurrent-user@example.com";
        User user = new User();
        user.setEmail(email);
        user.setUserName("concurrent-user");
        user.setPassword("encoded-password");
        userRepository.saveAndFlush(user);

        LocalDateTime firstStart = LocalDateTime.now().plusHours(1);
        Resource resource = resourceRepository.saveAndFlush(resource());
        AvailableSlot first = slot(firstStart, resource);
        AvailableSlot second = slot(firstStart.plusHours(1), resource);
        timeSlotRepository.saveAllAndFlush(List.of(first, second));

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> reserveAfterSignal(start, email));
            Future<Boolean> secondResult = executor.submit(() -> reserveAfterSignal(start, email));
            start.countDown();

            long successes = List.of(firstResult.get(), secondResult.get()).stream()
                .filter(Boolean::booleanValue)
                .count();

            assertEquals(2, successes);
        }
    }

    @Test
    void concurrentOverlappingRequestsForSameUserShouldCreateOnlyOneReservation() throws Exception {
        String email = "overlap-user@example.com";
        User user = new User();
        user.setEmail(email);
        user.setUserName("overlap-user");
        user.setPassword("encoded-password");
        userRepository.saveAndFlush(user);

        LocalDateTime firstStart = LocalDateTime.now().plusHours(3);
        Resource resource = resourceRepository.saveAndFlush(resource());
        AvailableSlot first = slot(firstStart, resource);
        AvailableSlot second = slot(firstStart.plusMinutes(15), resource);
        timeSlotRepository.saveAllAndFlush(List.of(first, second));
        long before = reservationRepository.count();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> reserveAfterSignal(start, email));
            Future<Boolean> secondResult = executor.submit(() -> reserveAfterSignal(start, email));
            start.countDown();

            long successes = List.of(firstResult.get(), secondResult.get()).stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1, successes);
            assertEquals(before + 1, reservationRepository.count());
        }
    }

    @Test
    void concurrentRequestsForSameSlotShouldCreateOnlyOneReservation() throws Exception {
        String firstEmail = "slot-first@example.com";
        String secondEmail = "slot-second@example.com";
        userRepository.saveAndFlush(user(firstEmail, "slot-first"));
        userRepository.saveAndFlush(user(secondEmail, "slot-second"));

        Resource resource = resourceRepository.saveAndFlush(resource());
        timeSlotRepository.saveAndFlush(slot(LocalDateTime.now().plusHours(5), resource));
        long before = reservationRepository.count();

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> firstResult = executor.submit(() -> reserveAfterSignal(start, firstEmail));
            Future<Boolean> secondResult = executor.submit(() -> reserveAfterSignal(start, secondEmail));
            start.countDown();

            long successes = List.of(firstResult.get(), secondResult.get()).stream()
                    .filter(Boolean::booleanValue)
                    .count();

            assertEquals(1, successes);
            assertEquals(before + 1, reservationRepository.count());
        }
    }

    private boolean reserveAfterSignal(CountDownLatch start, String email) throws InterruptedException {
        start.await();
        try {
            reservationService.reserveNearestSlot(email);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private User user(String email, String userName) {
        User user = new User();
        user.setEmail(email);
        user.setUserName(userName);
        user.setPassword("encoded-password");
        return user;
    }

    private AvailableSlot slot(LocalDateTime start, Resource resource) {
        AvailableSlot slot = new AvailableSlot();
        slot.setStartTime(start);
        slot.setEndTime(start.plusMinutes(45));
        slot.setReserved(false);
        slot.setResource(resource);
        return slot;
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setName("concurrency-resource-" + UUID.randomUUID());
        resource.setType(ResourceType.LAB);
        resource.setLocation("test-location");
        resource.setStatus(ResourceStatus.ACTIVE);
        resource.setCapacity(1);
        return resource;
    }
}
