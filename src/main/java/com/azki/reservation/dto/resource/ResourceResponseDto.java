package com.azki.reservation.dto.resource;

import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;

/** 面向客户端的资源信息，不暴露审计字段和 Hibernate 关联。 */
public record ResourceResponseDto(
        Long id,
        String name,
        ResourceType type,
        String location,
        ResourceStatus status,
        Integer capacity,
        String description) {
}
