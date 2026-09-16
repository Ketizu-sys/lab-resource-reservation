package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<AvailableSlot, Long> {
    /**
     * 只读查询最近的空闲时段，供页面展示和短时间缓存使用。
     */
    Optional<AvailableSlot> findFirstByIsReservedFalseAndStartTimeGreaterThanEqualOrderByStartTimeAsc(
        LocalDateTime now
    );

    /**
     * 在预约事务中只锁定一条最近时段。
     *
     * <p>{@code SKIP LOCKED} 让并发事务跳过已被其他请求占用的候选行，
     * 避免像原实现一样查询并锁住全部空闲时段。</p>
     */
    @Query(value = """
        SELECT *
        FROM available_slot
        WHERE is_reserved = false
          AND start_time >= :now
        ORDER BY start_time ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<AvailableSlot> findNextAvailableForUpdate(@Param("now") LocalDateTime now);
}
