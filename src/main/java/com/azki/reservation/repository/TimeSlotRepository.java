package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<AvailableSlot, Long> {
    /**
     * 查询当前时间之后的全部空闲时段，并按开始时间升序排列。
     * PESSIMISTIC_WRITE 会在事务期间锁住查询到的数据库行，降低并发抢占同一时段的概率。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT t FROM AvailableSlot t WHERE t.isReserved = false AND t.startTime >= :now ORDER BY t.startTime ASC")
    List<AvailableSlot> findAvailableSlots(@Param("now") LocalDateTime now);

    /** 从已排序结果中取第一条，即距离 now 最近的空闲时段。 */
    default Optional<AvailableSlot> findNextAvailable(LocalDateTime now) {
        List<AvailableSlot> slots = findAvailableSlots(now);
        return slots.isEmpty() ? Optional.empty() : Optional.of(slots.getFirst());
    }
}
