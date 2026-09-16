package com.azki.reservation.dto.reservation;

import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.ResourceType;
import java.time.LocalDateTime;

/** 预约的对外视图，避免直接序列化带懒加载关系的实体。 */
public record ReservationDetailsDto(
        Long id, ReservationStatus status, ResourceSummary resource, Long slotId,
        LocalDateTime startTime, LocalDateTime endTime, LocalDateTime reservedAt,
        LocalDateTime cancelledAt, LocalDateTime completedAt, String cancelReason) {
    public record ResourceSummary(Long id, String name, ResourceType type, String location) { }
}
