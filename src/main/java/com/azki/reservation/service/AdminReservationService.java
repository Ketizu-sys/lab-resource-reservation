package com.azki.reservation.service;

import com.azki.reservation.dto.admin.AdminReservationResponse;
import com.azki.reservation.entity.*;
import com.azki.reservation.repository.ReservationRepository;
import jakarta.persistence.criteria.Join;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminReservationService {
    private final ReservationRepository repository;
    private final ReservationService reservationService;
    private final ReservationDtoMapper mapper;

    @Transactional(readOnly = true)
    public Page<AdminReservationResponse> findAll(Long userId, Long resourceId, ReservationStatus status,
                                                   LocalDateTime start, LocalDateTime end, Pageable pageable) {
        Specification<Reservation> spec = Specification.allOf();
        if (userId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("user").get("id"), userId));
        if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        if (resourceId != null) spec = spec.and((root, q, cb) -> {
            Join<Reservation, AvailableSlot> slot = root.join("availableSlot");
            return cb.equal(slot.get("resource").get("id"), resourceId);
        });
        if (start != null) spec = spec.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("availableSlot").get("startTime"), start));
        if (end != null) spec = spec.and((root, q, cb) ->
                cb.lessThanOrEqualTo(root.get("availableSlot").get("endTime"), end));
        return repository.findAll(spec, pageable).map(r ->
                new AdminReservationResponse(r.getUser().getId(), r.getUser().getEmail(), mapper.toDto(r)));
    }

    public void cancel(Long id) { reservationService.cancelReservationAsAdmin(id); }
}
