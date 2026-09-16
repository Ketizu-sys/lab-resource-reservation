package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationDetailsDto;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.exception.ReservationNotFoundException;
import com.azki.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 当前登录用户的预约列表和详情查询。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserReservationQueryService {
    private final ReservationRepository reservationRepository;
    private final ReservationDtoMapper mapper;

    public Page<ReservationDetailsDto> findMine(Long userId, ReservationStatus status, Pageable pageable) {
        Page<Reservation> page = status == null
                ? reservationRepository.findByUserIdOrderByReservedAtDesc(userId, pageable)
                : reservationRepository.findByUserIdAndStatusOrderByReservedAtDesc(userId, status, pageable);
        return page.map(mapper::toDto);
    }

    public ReservationDetailsDto findOwned(Long reservationId, Long userId) {
        Reservation reservation = reservationRepository.findDetailedById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException("Reservation not found for id: " + reservationId));
        if (!reservation.getUser().getId().equals(userId)) {
            throw new AccessDeniedException("Reservation does not belong to current user");
        }
        return mapper.toDto(reservation);
    }
}
