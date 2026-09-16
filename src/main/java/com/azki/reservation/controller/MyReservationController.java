package com.azki.reservation.controller;

import com.azki.reservation.dto.reservation.ReservationDetailsDto;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.security.AuthenticatedUser;
import com.azki.reservation.service.UserReservationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "我的预约", description = "查询当前用户自己的预约")
@RestController
@RequestMapping("/api/v1/me/reservations")
@RequiredArgsConstructor
public class MyReservationController {
    private final UserReservationQueryService service;

    @Operation(summary = "分页查询我的预约")
    @GetMapping
    public Page<ReservationDetailsDto> findMine(
            @RequestParam(required = false) ReservationStatus status,
            Pageable pageable,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        return service.findMine(currentUser.id(), status, pageable);
    }
}
