package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
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

import java.time.LocalDateTime;

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
    void existsByUserEmailAndStartTimeAfter_shouldReturnTrueWhenFutureReservationExists() {
        // 准备：创建用户、未来时段及其预约。
        String email = "test@azki.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureTime = now.plusHours(2);

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
        entityManager.persist(slot);

        // 建立用户与时段之间的预约关系。
        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(now);
        entityManager.persist(reservation);

        entityManager.flush();

        // 执行：从当前时间开始检查未来预约。
        boolean exists = reservationRepository.existsByUserEmailAndStartTimeAfter(email, now);

        // 验证：应检测到未来预约。
        assertTrue(exists);
    }

    @Test
    void existsByUserEmailAndStartTimeAfter_shouldReturnFalseWhenNoFutureReservationExists() {
        // 准备：创建一条只关联过去时段的预约。
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pastTime = now.minusHours(2);

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
        entityManager.persist(slot);

        // 建立历史预约记录。
        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setReservedAt(pastTime);
        entityManager.persist(reservation);

        entityManager.flush();

        // 执行：从当前时间开始检查未来预约。
        boolean exists = reservationRepository.existsByUserEmailAndStartTimeAfter(email, now);

        // 验证：过去的预约不应被视作未来预约。
        assertFalse(exists);
    }
}
