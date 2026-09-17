package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.dto.reservation.ReservationMode;
import com.azki.reservation.exception.DuplicateReservationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationQueueServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ReservationService reservationService;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private ReservationQueueService queueService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();

        queueService = new ReservationQueueService(redisTemplate, reservationService, objectMapper, meterRegistry);
    }

    @Test
    void shouldEnqueueReservationRequest() {
        // 准备：构造合法预约请求。
        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(1L);
        request.setMode(ReservationMode.AUTO);
        when(redisTemplate.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any()
        )).thenReturn(1L);

        // 执行：请求入队。
        String requestId = queueService.enqueueReservationRequest(request);

        // 验证：生成 requestId，并写入队列与初始状态。
        assertNotNull(requestId);
        verify(redisTemplate).execute(
            any(RedisScript.class),
            argThat(keys -> keys.contains("reservation:queue") && keys.contains("reservation:users:queued")),
            eq("1"), anyString(),
            eq(ReservationQueueService.RequestStatus.QUEUED.name()), anyLong()
        );
    }

    @Test
    void shouldGetRequestStatus() {
        // 准备：状态键当前为处理中。
        String requestId = "test-request-id";
        String status = ReservationQueueService.RequestStatus.PROCESSING.name();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reservation:status-owner:" + requestId)).thenReturn("1");
        when(valueOperations.get("reservation:status:" + requestId)).thenReturn(status);

        // 执行：读取请求状态。
        String result = queueService.getRequestStatus(requestId, 1L);

        // 验证：返回 Redis 中保存的状态。
        assertEquals(status, result);
        verify(valueOperations).get("reservation:status:" + requestId);
        verify(valueOperations).get("reservation:status-owner:" + requestId);
    }

    @Test
    void shouldReturnNullWhenRequestStatusNotFound() {
        // 准备：模拟 Redis 中不存在状态键。
        String requestId = "test-request-id";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reservation:status-owner:" + requestId)).thenReturn(null);

        // 执行：读取请求状态。
        String result = queueService.getRequestStatus(requestId, 1L);

        // 验证：服务返回 null。
        assertNull(result);
        verify(valueOperations, never()).get("reservation:status:" + requestId);
    }

    @Test
    void shouldHideRequestStatusFromDifferentUser() {
        String requestId = "test-request-id";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("reservation:status-owner:" + requestId)).thenReturn("2");

        assertNull(queueService.getRequestStatus(requestId, 1L));

        verify(valueOperations, never()).get("reservation:status:" + requestId);
    }

    @Test
    void shouldReportQueueLength() {
        // 准备：模拟队列中有五条消息。
        Long expectedLength = 5L;
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size(anyString())).thenReturn(expectedLength);

        // 执行：读取队列长度。
        long length = queueService.getQueueLength();

        // 验证：返回 Redis List 的长度。
        assertEquals(expectedLength, length);
        verify(listOperations).size("reservation:queue");
    }

    @Test
    void shouldHandleDuplicateReservationException() {
        // 准备：同一邮箱已经存在于排队集合。
        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(1L);
        request.setMode(ReservationMode.AUTO);

        when(redisTemplate.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any()
        )).thenReturn(0L);

        // 执行并验证：重复请求应抛异常，且不能再次写主队列。
        assertThrows(DuplicateReservationException.class, () -> queueService.enqueueReservationRequest(request));

        verify(redisTemplate).execute(
            any(RedisScript.class), anyList(), eq("1"),
            anyString(), eq(ReservationQueueService.RequestStatus.QUEUED.name()), anyLong()
        );
    }
}
