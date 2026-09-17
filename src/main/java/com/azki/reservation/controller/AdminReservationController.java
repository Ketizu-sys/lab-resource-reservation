package com.azki.reservation.controller;

import com.azki.reservation.config.OpenApiConfig;
import com.azki.reservation.dto.admin.AdminReservationResponse;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.service.AdminReservationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/admin/reservations")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
public class AdminReservationController {
    private final AdminReservationService service;

    @GetMapping
    public Page<AdminReservationResponse> findAll(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            Pageable pageable) {
        return service.findAll(userId, resourceId, status, start, end, pageable);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        service.cancel(id); return ResponseEntity.noContent().build();
    }
}
