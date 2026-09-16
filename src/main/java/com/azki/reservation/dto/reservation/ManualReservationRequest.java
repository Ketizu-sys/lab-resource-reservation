package com.azki.reservation.dto.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 手工预约请求；用户身份始终由 JWT 提供。 */
public record ManualReservationRequest(
        @NotNull(message = "slotId is required")
        @Positive(message = "slotId must be positive")
        Long slotId) { }
