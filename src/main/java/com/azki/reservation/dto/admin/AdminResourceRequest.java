package com.azki.reservation.dto.admin;

import com.azki.reservation.entity.ResourceStatus;
import com.azki.reservation.entity.ResourceType;
import jakarta.validation.constraints.*;

/** 管理员创建或修改资源的输入。 */
public record AdminResourceRequest(
        @NotBlank String name,
        @NotNull ResourceType type,
        @NotBlank String location,
        @NotNull ResourceStatus status,
        @Positive int capacity,
        @Size(max = 1000) String description) { }
