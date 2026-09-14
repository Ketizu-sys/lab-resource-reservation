package com.azki.reservation.dto.reservation;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
/**
 * 创建预约或查询进度时的简化响应。
 * requestId 用于标识请求，status 表示当前处理状态。
 */
public class ReservationResponseDto {
    private String requestId;
    private String status;
}
