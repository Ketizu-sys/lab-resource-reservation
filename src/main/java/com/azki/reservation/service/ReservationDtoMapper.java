package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationDetailsDto;
import com.azki.reservation.entity.Reservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/** 集中维护预约实体到 API DTO 的转换。 */
@Component
@RequiredArgsConstructor
public class ReservationDtoMapper {
    private final Clock reservationClock;

    public ReservationDetailsDto toDto(Reservation reservation) {
        var slot = reservation.getAvailableSlot();
        var resource = slot.getResource();
        return new ReservationDetailsDto(reservation.getId(), reservation.getStatus(),
                new ReservationDetailsDto.ResourceSummary(resource.getId(), resource.getName(),
                        resource.getType(), resource.getLocation()),
                slot.getId(), slot.getStartTime(), slot.getEndTime(), toBusinessTime(reservation.getReservedAt()),
                toBusinessTime(reservation.getCancelledAt()), toBusinessTime(reservation.getCompletedAt()),
                reservation.getCancelReason());
    }

    private LocalDateTime toBusinessTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, reservationClock.getZone());
    }
}
