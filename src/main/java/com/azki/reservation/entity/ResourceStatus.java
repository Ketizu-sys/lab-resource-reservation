package com.azki.reservation.entity;

/** 资源当前可用状态；非 ACTIVE 资源在后续预约与查询中不应对普通用户开放。 */
public enum ResourceStatus {
    ACTIVE,
    DISABLED,
    MAINTENANCE
}
