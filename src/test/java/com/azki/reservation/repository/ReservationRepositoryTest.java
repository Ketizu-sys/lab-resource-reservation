package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.entity.User;
import com.azki.reservation.config.JpaAuditingConfig;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationRepositoryTest extends ContainerIntegrationTestSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    void existsOverlappingReservation_shouldReturnTrueForOverlappingActiveReservation() {
        // 准备：创建用户、未来时段及其预约。
        String email = "test@azki.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureTime = now.plusHours(2);
        Resource resource = createResource("future-reservation-resource");

        // 创建预约用户。
        User user = new User();
        user.setEmail(email);
        user.setUserName("testuser");
        user.setPassword("password");
        entityManager.persist(user);

        // 创建未来且已被占用的时段。
        AvailableSlot slot = new AvailableSlot();
        slot.setStartTime(futureTime);
        slot.setEndTime(futureTime.plusHours(1));
        slot.setReserved(true);
        slot.setResource(resource);
        entityManager.persist(slot);

        // 建立用户与时段之间的预约关系。
        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(Instant.now());
        entityManager.persist(reservation);

        entityManager.flush();

        // 执行：从当前时间开始检查未来预约。
        boolean exists = reservationRepository.existsOverlappingReservation(
                user.getId(), ReservationStatus.ACTIVE, futureTime.plusMinutes(30), futureTime.plusHours(2));

        // 验证：应检测到未来预约。
        assertTrue(exists);
    }

    @Test
    void existsOverlappingReservation_shouldReturnFalseForNonOverlappingReservation() {
        // 准备：创建一条只关联过去时段的预约。
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pastTime = now.minusHours(2);
        Resource resource = createResource("past-reservation-resource");

        // 创建预约用户。
        User user = new User();
        user.setEmail(email);
        user.setUserName("testuser");
        user.setPassword("password");
        entityManager.persist(user);

        // 创建已经过去的时段。
        AvailableSlot slot = new AvailableSlot();
        slot.setStartTime(pastTime);
        slot.setEndTime(pastTime.plusHours(1));
        slot.setReserved(true);
        slot.setResource(resource);
        entityManager.persist(slot);

        // 建立历史预约记录。
        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(Instant.now().minusSeconds(7_200));
        entityManager.persist(reservation);

        entityManager.flush();

        // 执行：从当前时间开始检查未来预约。
        boolean exists = reservationRepository.existsOverlappingReservation(
                user.getId(), ReservationStatus.ACTIVE, now.plusHours(1), now.plusHours(2));

        // 验证：过去的预约不应被视作未来预约。
        assertFalse(exists);
    }

    @Test
    void findExpiredReservations_shouldUseSlotEndTimeInsteadOfReservationCreationTime() {
        LocalDateTime now = LocalDateTime.now();

        User expiredUser = createUser("expired@example.com");
        AvailableSlot expiredSlot = createSlot(now.minusHours(2), now.minusHours(1));
        Reservation expiredReservation = createReservation(expiredUser, expiredSlot, Instant.now().minusSeconds(604_800));

        User futureUser = createUser("future@example.com");
        AvailableSlot futureSlot = createSlot(now.plusDays(2), now.plusDays(2).plusHours(1));
        createReservation(futureUser, futureSlot, Instant.now().minusSeconds(604_800));

        entityManager.flush();
        entityManager.clear();

        List<Reservation> result = reservationRepository.findExpiredReservationsForUpdate(now);

        assertEquals(1, result.size());
        assertEquals(expiredReservation.getId(), result.get(0).getId());
        assertTrue(result.get(0).getAvailableSlot().getEndTime().isBefore(now));
    }

    @Test
    void cancelledReservationShouldNotBlockSameSlotAndShouldNotBeExpiredAgain() {
        LocalDateTime now = LocalDateTime.now();
        User firstUser = createUser("cancelled@example.com");
        User secondUser = createUser("replacement@example.com");
        AvailableSlot slot = createSlot(now.minusHours(2), now.minusHours(1));

        Reservation cancelled = createReservation(firstUser, slot, Instant.now().minusSeconds(86_400));
        cancelled.setStatus(ReservationStatus.CANCELLED);
        cancelled.setCancelledAt(Instant.now().minusSeconds(10_800));
        // IDENTITY 主键会在 persist 时立即插入；先刷新状态变更，再创建同一时段的新预约。
        entityManager.flush();

        Reservation replacement = createReservation(secondUser, slot, Instant.now().minusSeconds(7_200));
        entityManager.flush();
        entityManager.clear();

        assertNotNull(replacement.getId());
        assertTrue(reservationRepository.findExpiredReservationsForUpdate(now)
                .stream().anyMatch(r -> r.getId().equals(replacement.getId())));
        assertTrue(reservationRepository.findExpiredReservationsForUpdate(now)
                .stream().noneMatch(r -> r.getId().equals(cancelled.getId())));
    }

    private User createUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setUserName(email);
        user.setPassword("password");
        return entityManager.persist(user);
    }

    private AvailableSlot createSlot(LocalDateTime startTime, LocalDateTime endTime) {
        AvailableSlot slot = new AvailableSlot();
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setReserved(true);
        slot.setResource(createResource("resource-" + startTime));
        return entityManager.persist(slot);
    }

    private Resource createResource(String name) {
        Resource resource = new Resource();
        resource.setName(name);
        resource.setType(ResourceType.LAB);
        resource.setLocation("test-location");
        resource.setStatus(ResourceStatus.ACTIVE);
        resource.setCapacity(1);
        return entityManager.persist(resource);
    }

    private Reservation createReservation(User user, AvailableSlot slot, Instant reservedAt) {
        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(reservedAt);
        return entityManager.persist(reservation);
    }
}
