package com.azki.reservation.dto.slot;

import com.azki.reservation.entity.ResourceType;
import java.time.LocalDateTime;

/** 普通用户可见的时段信息，不暴露审计字段和实体内部状态。 */
public record SlotResponseDto(
        Long id,
        ResourceSummary resource,
        LocalDateTime startTime,
        LocalDateTime endTime,
        boolean available) {

    public record ResourceSummary(Long id, String name, ResourceType type, String location) { }
}
