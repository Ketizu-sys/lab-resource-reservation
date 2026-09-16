package com.azki.reservation.repository;

import com.azki.reservation.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /**
     * 判断指定邮箱的用户是否已有开始时间晚于给定时刻的预约。
     * 用于落实“一个用户不能同时持有另一条未来预约”的业务约束。
     *
     * @param email 用户邮箱
     * @param dateTime 判断未来预约的时间下界，通常传当前时间
     * @return 存在未来预约时返回 true
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Reservation r " +
           "JOIN r.user u JOIN r.availableSlot a " +
           "WHERE u.email = :email AND a.startTime > :dateTime")
    boolean existsByUserEmailAndStartTimeAfter(@Param("email") String email, @Param("dateTime") LocalDateTime dateTime);

    /**
     * 查询时段已经结束的预约，供 ReservationExpiryService 批量清理。
     * JOIN FETCH 会在同一次查询中加载关联时段，避免清理循环内逐条查询。
     *
     * @param now 本次清理任务的当前时间
     * @return 结束时间早于当前时间的预约
     */
    @Query("SELECT r FROM Reservation r JOIN FETCH r.availableSlot a WHERE a.endTime < :now")
    List<Reservation> findExpiredReservations(@Param("now") LocalDateTime now);
}
