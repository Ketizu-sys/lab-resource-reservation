package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TimeSlotRepositoryTest extends ContainerIntegrationTestSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void findNextAvailable_shouldReturnNextUnreservedSlotAfterGivenTime() {
        // 准备：构造一个已占用时段和两个空闲时段。
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime slotTime1 = now.plusHours(1);
        LocalDateTime slotTime2 = now.plusHours(2);
        LocalDateTime slotTime3 = now.plusHours(3);

        // 最早的时段已被占用，应被查询跳过。
        AvailableSlot slot1 = new AvailableSlot();
        slot1.setStartTime(slotTime1);
        slot1.setEndTime(slotTime1.plusHours(1));
        slot1.setReserved(true);

        // 第一条空闲时段，应作为结果返回。
        AvailableSlot slot2 = new AvailableSlot();
        slot2.setStartTime(slotTime2);
        slot2.setEndTime(slotTime2.plusHours(1));
        slot2.setReserved(false);

        // 更晚的空闲时段，不应优先于 slot2。
        AvailableSlot slot3 = new AvailableSlot();
        slot3.setStartTime(slotTime3);
        slot3.setEndTime(slotTime3.plusHours(1));
        slot3.setReserved(false);

        // 持久化并刷新，使查询直接读取数据库状态。
        entityManager.persist(slot1);
        entityManager.persist(slot2);
        entityManager.persist(slot3);
        entityManager.flush();

        // 执行：查询离当前时间最近的空闲时段。
        Optional<AvailableSlot> result = timeSlotRepository.findNextAvailable(now);

        // 验证：返回 slot2，且它仍是未预约状态。
        assertTrue(result.isPresent());
        assertEquals(slot2.getStartTime(), result.get().getStartTime());
        assertFalse(result.get().isReserved());
    }

    @Test
    void findNextAvailable_shouldReturnEmptyWhenNoUnreservedSlotsAvailable() {
        // 准备：所有未来时段均已被占用。
        LocalDateTime now = LocalDateTime.now();

        // 两条时段都不可用。
        AvailableSlot slot1 = new AvailableSlot();
        slot1.setStartTime(now.plusHours(1));
        slot1.setEndTime(now.plusHours(2));
        slot1.setReserved(true);

        AvailableSlot slot2 = new AvailableSlot();
        slot2.setStartTime(now.plusHours(2));
        slot2.setEndTime(now.plusHours(3));
        slot2.setReserved(true);

        entityManager.persist(slot1);
        entityManager.persist(slot2);
        entityManager.flush();

        // 执行：查询最近空闲时段。
        Optional<AvailableSlot> result = timeSlotRepository.findNextAvailable(now);

        // 验证：没有候选项时返回空 Optional。
        assertTrue(result.isEmpty());
    }
}
