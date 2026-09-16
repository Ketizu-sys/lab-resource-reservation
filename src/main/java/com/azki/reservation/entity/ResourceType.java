package com.azki.reservation.entity;

/** 可被预约的资源类别；使用字符串持久化，避免枚举顺序变化破坏历史数据。 */
public enum ResourceType {
    LAB,
    MEETING_ROOM,
    GPU,
    EQUIPMENT,
    WORKSTATION
}
