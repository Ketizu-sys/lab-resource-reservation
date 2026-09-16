package com.azki.reservation.repository;

import com.azki.reservation.entity.AvailableSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.azki.reservation.entity.ResourceType;

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

    /** 按资源、资源类型和时间窗口分页查询普通用户可见的空闲时段。 */
    @Query("""
        SELECT s FROM AvailableSlot s JOIN s.resource r
        WHERE s.isReserved = false
          AND s.startTime >= :start
          AND r.status = com.azki.reservation.entity.ResourceStatus.ACTIVE
          AND (:resourceId IS NULL OR r.id = :resourceId)
          AND (:resourceType IS NULL OR r.type = :resourceType)
          AND s.endTime <= :end
        ORDER BY s.startTime ASC
        """)
    Page<AvailableSlot> findAvailableSlots(
            @Param("resourceId") Long resourceId,
            @Param("resourceType") ResourceType resourceType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            Pageable pageable);

    @Query("""
        SELECT s FROM AvailableSlot s JOIN FETCH s.resource r
        WHERE s.id = :id AND s.isReserved = false AND s.startTime >= :now
          AND r.status = com.azki.reservation.entity.ResourceStatus.ACTIVE
        """)
    Optional<AvailableSlot> findVisibleAvailableById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
