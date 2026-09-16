package com.azki.reservation.dto.admin;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.time.LocalTime;

/** 按日期范围和每日时间窗批量生成等长时段。 */
public record BatchSlotRequest(
        @NotNull @Positive Long resourceId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull LocalTime dailyStartTime,
        @NotNull LocalTime dailyEndTime,
        @Positive int durationMinutes) { }
