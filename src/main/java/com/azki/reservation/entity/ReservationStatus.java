package com.azki.reservation.entity;

/**
 * 预约生命周期状态。
 *
 * <p>ACTIVE 表示预约仍然有效；CANCELLED 表示用户在开始前主动取消；
 * COMPLETED 表示预约时段已经自然结束。状态保留在数据库中，便于审计和历史查询。</p>
 */
public enum ReservationStatus {
    ACTIVE,
    CANCELLED,
    COMPLETED
}
