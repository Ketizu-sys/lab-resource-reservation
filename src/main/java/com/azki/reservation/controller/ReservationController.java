package com.azki.reservation.controller;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.dto.reservation.ReservationResponseDto;
import com.azki.reservation.dto.reservation.ManualReservationRequest;
import com.azki.reservation.dto.reservation.ReservationDetailsDto;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.service.LoadMonitoringService;
import com.azki.reservation.service.ReservationQueueService;
import com.azki.reservation.service.ReservationService;
import com.azki.reservation.service.ReservationDtoMapper;
import com.azki.reservation.service.UserReservationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.azki.reservation.security.AuthenticatedUser;
import jakarta.validation.Valid;

/**
 * 预约业务的 HTTP 入口。
 *
 * <p>创建预约时先通过 {@link LoadMonitoringService} 判断当前实例的并发压力：
 * 低负载时同步写数据库，高负载时把请求放进 Redis 队列。查询状态仅适用于排队请求。</p>
 */
@Tag(name = "预约管理", description = "创建预约、查询排队状态和取消预约")
@RestController
@RequestMapping("/api/v1/reservations")
public class ReservationController {

    private static final Logger logger = LoggerFactory.getLogger(ReservationController.class);
    private final ReservationService reservationService;
    private final ReservationQueueService reservationQueueService;
    private final LoadMonitoringService loadMonitoringService;
    private final ReservationDtoMapper reservationDtoMapper;
    private final UserReservationQueryService userReservationQueryService;

    @Autowired
    public ReservationController(
            ReservationService reservationService,
            ReservationQueueService reservationQueueService,
            LoadMonitoringService loadMonitoringService,
            ReservationDtoMapper reservationDtoMapper,
            UserReservationQueryService userReservationQueryService) {
        this.reservationService = reservationService;
        this.reservationQueueService = reservationQueueService;
        this.loadMonitoringService = loadMonitoringService;
        this.reservationDtoMapper = reservationDtoMapper;
        this.userReservationQueryService = userReservationQueryService;
    }

    @Operation(summary = "查询自己的预约详情")
    @GetMapping("/{id}")
    public ReservationDetailsDto findReservation(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        return userReservationQueryService.findOwned(id, currentUser.id());
    }

    @Operation(summary = "取消自己的预约")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelOwnReservation(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        reservationService.cancelReservation(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "预约指定时段")
    @PostMapping
    public ResponseEntity<ReservationDetailsDto> reserveSlot(
            @Valid @RequestBody ManualReservationRequest request,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        Reservation reservation = reservationService.reserveSlot(currentUser.id(), request.slotId());
        return ResponseEntity.ok(reservationDtoMapper.toDto(reservation));
    }

    @Operation(summary = "预约最近的空闲时段")
    @PostMapping({"/auto", "/reserve"})
    public ResponseEntity<ReservationResponseDto> reserveNearest(
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(currentUser.id());
        request.setEmail(currentUser.email());
        try {
            // 先计入当前正在处理的请求；计数是 shouldQueueRequest() 的判断依据。
            loadMonitoringService.incrementActiveRequests();

            // 超过并发阈值时快速返回 requestId，让后台队列慢慢消化请求。
            if (loadMonitoringService.shouldQueueRequest()) {
                // 高负载路径：写入 Redis，HTTP 202 表示“已接收但尚未处理完成”。
                logger.info("Processing reservation request for {} through queue due to high load", request.getEmail());
                String requestId = reservationQueueService.enqueueReservationRequest(request);
                String status = reservationQueueService.getRequestStatus(requestId);
                return ResponseEntity.accepted().body(new ReservationResponseDto(requestId, status));
            } else {
                // 正常负载路径：在当前 HTTP 请求中完成选时段和数据库写入。
                logger.info("Processing reservation request for {} directly", request.getEmail());
                Reservation reservation = reservationService.reserveNearestSlot(currentUser.id());
                String requestId = "direct-" + reservation.getId();
                return ResponseEntity.ok().body(new ReservationResponseDto(requestId, "SUCCESS"));
            }
        } finally {
            // 无论成功还是抛异常都必须归还计数，避免系统永久误判为高负载。
            loadMonitoringService.decrementActiveRequests();
        }
    }

    @Operation(summary = "根据 requestId 查询排队请求状态")
    @GetMapping("/status/{requestId}")
    public ResponseEntity<ReservationResponseDto> getReservationStatus(@PathVariable String requestId) {
        String status = reservationQueueService.getRequestStatus(requestId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReservationResponseDto(requestId, status));
    }

    @Operation(summary = "根据预约 ID 取消预约")
    @DeleteMapping("/cancel/{id}")
    public ResponseEntity<Void> cancelReservation(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser currentUser) {
        reservationService.cancelReservation(id, currentUser.id());
        return ResponseEntity.noContent().build();
    }
}

