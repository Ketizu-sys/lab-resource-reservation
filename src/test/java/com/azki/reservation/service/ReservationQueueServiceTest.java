package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
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
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

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

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private RedisCleanupService redisCleanupService;

    private ObjectMapper objectMapper;
    private MeterRegistry meterRegistry;
    private ReservationQueueService queueService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        meterRegistry = new SimpleMeterRegistry();

        queueService = new ReservationQueueService(redisTemplate, reservationService, objectMapper, meterRegistry,redisCleanupService);
    }

    @Test
    void shouldEnqueueReservationRequest() {
        // 准备：构造合法预约请求。
        ReservationRequestDto request = new ReservationRequestDto();
        request.setEmail("test@example.com");
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(setOperations.isMember("reservation:emails:queued", "test@example.com")).thenReturn(false);

        // 执行：请求入队。
        String requestId = queueService.enqueueReservationRequest(request);

        // 验证：生成 requestId，并写入队列与初始状态。
        assertNotNull(requestId);
        verify(listOperations).rightPush(anyString(), anyString());
        verify(valueOperations).set(contains("reservation:status:"), eq(ReservationQueueService.RequestStatus.QUEUED.name()));
        verify(redisCleanupService).setExpiryOnStatusKey(contains("reservation:status:"));
        verify(setOperations).add("reservation:emails:queued", "test@example.com");
    }

    @Test
    void shouldGetRequestStatus() {
        // 准备：状态键当前为处理中。
        String requestId = "test-request-id";
        String status = ReservationQueueService.RequestStatus.PROCESSING.name();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(status);

        // 执行：读取请求状态。
        String result = queueService.getRequestStatus(requestId);

        // 验证：返回 Redis 中保存的状态。
        assertEquals(status, result);
        verify(valueOperations).get("reservation:status:" + requestId);
    }

    @Test
    void shouldReturnNullWhenRequestStatusNotFound() {
        // 准备：模拟 Redis 中不存在状态键。
        String requestId = "test-request-id";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        // 执行：读取请求状态。
        String result = queueService.getRequestStatus(requestId);

        // 验证：服务返回 null。
        assertNull(result);
        verify(valueOperations).get("reservation:status:" + requestId);
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
        request.setEmail("test@example.com");

        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("reservation:emails:queued", "test@example.com")).thenReturn(true);

        // 执行并验证：重复请求应抛异常，且不能再次写主队列。
        assertThrows(DuplicateReservationException.class, () -> queueService.enqueueReservationRequest(request));

        verify(listOperations, never()).rightPush(anyString(), any());
    }
}
