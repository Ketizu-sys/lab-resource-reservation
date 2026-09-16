package com.azki.reservation.dto.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

/** 管理员创建或更新单个时段的输入。 */
public record AdminSlotRequest(@NotNull @Positive Long resourceId,
                               @NotNull LocalDateTime startTime,
                               @NotNull LocalDateTime endTime) { }
