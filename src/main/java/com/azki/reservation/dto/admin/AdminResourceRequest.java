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
        @Min(value = 1, message = "当前版本资源容量必须为 1")
        @Max(value = 1, message = "当前版本资源容量必须为 1") int capacity,
        @Size(max = 1000) String description) { }
