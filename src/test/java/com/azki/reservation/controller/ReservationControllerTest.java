package com.azki.reservation.controller;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.dto.reservation.ReservationResponseDto;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.service.LoadMonitoringService;
import com.azki.reservation.service.ReservationQueueService;
import com.azki.reservation.service.ReservationService;
import com.azki.reservation.security.AuthenticatedUser;
import com.azki.reservation.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationControllerTest {

    @Mock
    private ReservationService reservationService;

    @Mock
    private ReservationQueueService reservationQueueService;

    @Mock
    private LoadMonitoringService loadMonitoringService;

    @InjectMocks
    private ReservationController reservationController;

    @Test
    void shouldQueueReservationWhenLoadIsHigh() {
        // 准备：构造请求并设定队列服务返回值。
        AuthenticatedUser currentUser = new AuthenticatedUser(1L, "test@example.com", UserRole.USER);
        String requestId = "request-123";

        when(loadMonitoringService.shouldQueueRequest()).thenReturn(true);
        when(reservationQueueService.enqueueReservationRequest(any(ReservationRequestDto.class)))
                .thenReturn(requestId);
        when(reservationQueueService.getRequestStatus(requestId)).thenReturn("QUEUED");

        // 执行：调用创建预约接口。
        ResponseEntity<ReservationResponseDto> response = reservationController.reserveNearest(currentUser);

        // 验证：请求应被接受并确实进入队列。
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(requestId, response.getBody().getRequestId());
        assertEquals("QUEUED", response.getBody().getStatus());
        verify(reservationQueueService).enqueueReservationRequest(argThat(request ->
                request.getUserId().equals(1L) && request.getEmail().equals("test@example.com")));
        verify(loadMonitoringService).incrementActiveRequests();
        verify(loadMonitoringService).decrementActiveRequests();
    }

    @Test
    void shouldProcessReservationDirectlyWhenLoadIsNormal() {
        AuthenticatedUser currentUser = new AuthenticatedUser(1L, "test@example.com", UserRole.USER);
        Reservation reservation = new Reservation();
        reservation.setId(42L);

        when(loadMonitoringService.shouldQueueRequest()).thenReturn(false);
        when(reservationService.reserveNearestSlot(1L)).thenReturn(reservation);

        ResponseEntity<ReservationResponseDto> response = reservationController.reserveNearest(currentUser);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("direct-42", response.getBody().getRequestId());
        assertEquals("SUCCESS", response.getBody().getStatus());
        verify(reservationQueueService, never()).enqueueReservationRequest(any());
        verify(loadMonitoringService).incrementActiveRequests();
        verify(loadMonitoringService).decrementActiveRequests();
    }

    @Test
    void shouldGetReservationStatus() {
        // 准备：模拟 Redis 中已有处理中状态。
        String requestId = "request-123";
        String status = "PROCESSING";

        when(reservationQueueService.getRequestStatus(requestId)).thenReturn(status);

        // 执行：查询指定 requestId。
        ResponseEntity<ReservationResponseDto> response = reservationController.getReservationStatus(requestId);

        // 验证：返回成功状态及对应内容。
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(requestId, response.getBody().getRequestId());
        assertEquals(status, response.getBody().getStatus());
        verify(reservationQueueService).getRequestStatus(requestId);
    }

    @Test
    void shouldReturnNotFoundWhenStatusIsNull() {
        // 准备：模拟状态键不存在或已过期。
        String requestId = "request-123";

        when(reservationQueueService.getRequestStatus(requestId)).thenReturn(null);

        // 执行：查询不存在的 requestId。
        ResponseEntity<ReservationResponseDto> response = reservationController.getReservationStatus(requestId);

        // 验证：控制器返回 404。
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(reservationQueueService).getRequestStatus(requestId);
    }

    @Test
    void shouldCancelReservation() {
        // 准备：预约服务可以正常完成取消。
        Long reservationId = 1L;
        AuthenticatedUser currentUser = new AuthenticatedUser(7L, "test@example.com", UserRole.USER);
        doNothing().when(reservationService).cancelReservation(reservationId, 7L);

        // 执行：调用取消接口。
        ResponseEntity<Void> response = reservationController.cancelReservation(reservationId, currentUser);

        // 验证：返回 204 且服务方法被调用一次。
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(reservationService).cancelReservation(reservationId, 7L);
    }
}
