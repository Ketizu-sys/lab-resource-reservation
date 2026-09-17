package com.azki.reservation.repository;

import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, JpaSpecificationExecutor<Reservation> {

    @EntityGraph(attributePaths = {"availableSlot", "availableSlot.resource"})
    Page<Reservation> findByUserIdOrderByReservedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"availableSlot", "availableSlot.resource"})
    Page<Reservation> findByUserIdAndStatusOrderByReservedAtDesc(
            Long userId, ReservationStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "availableSlot", "availableSlot.resource"})
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    java.util.Optional<Reservation> findDetailedById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"user", "availableSlot", "availableSlot.resource"})
    @Query("SELECT r FROM Reservation r WHERE r.id = :id")
    java.util.Optional<Reservation> findByIdForUpdate(@Param("id") Long id);

    /**
     * 判断用户是否存在与候选时段重叠的有效预约。
     * 采用半开区间判断：[开始时间, 结束时间)，首尾相接的预约不算重叠。
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Reservation r " +
           "JOIN r.availableSlot a WHERE r.user.id = :userId AND r.status = :status " +
           "AND a.startTime < :candidateEnd AND a.endTime > :candidateStart")
    boolean existsOverlappingReservation(
            @Param("userId") Long userId,
            @Param("status") ReservationStatus status,
            @Param("candidateStart") LocalDateTime candidateStart,
            @Param("candidateEnd") LocalDateTime candidateEnd);

    /**
     * 查询时段已经结束的预约，供 ReservationExpiryService 批量清理。
     * JOIN FETCH 会在同一次查询中加载关联时段，避免清理循环内逐条查询。
     *
     * @param now 本次清理任务的当前时间
     * @return 结束时间早于当前时间的预约
     */
    /**
     * 领取本轮需要完成的预约。只锁 reservation 行并跳过其他事务正在处理的记录，
     * 与取消流程的“先锁预约、后更新时段”顺序保持一致。
     */
    @Query(value = """
            SELECT r.*
            FROM reservation r
            JOIN available_slot s ON s.id = r.available_slot_id
            WHERE r.status = 'ACTIVE' AND s.end_time < :now
            ORDER BY r.id
            FOR UPDATE OF r SKIP LOCKED
            """, nativeQuery = true)
    List<Reservation> findExpiredReservationsForUpdate(@Param("now") LocalDateTime now);
}
