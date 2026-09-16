package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationDetailsDto;
import com.azki.reservation.entity.Reservation;
import org.springframework.stereotype.Component;

/** 集中维护预约实体到 API DTO 的转换。 */
@Component
public class ReservationDtoMapper {
    public ReservationDetailsDto toDto(Reservation reservation) {
        var slot = reservation.getAvailableSlot();
        var resource = slot.getResource();
        return new ReservationDetailsDto(reservation.getId(), reservation.getStatus(),
                new ReservationDetailsDto.ResourceSummary(resource.getId(), resource.getName(),
                        resource.getType(), resource.getLocation()),
                slot.getId(), slot.getStartTime(), slot.getEndTime(), reservation.getReservedAt(),
                reservation.getCancelledAt(), reservation.getCompletedAt(), reservation.getCancelReason());
    }
}
