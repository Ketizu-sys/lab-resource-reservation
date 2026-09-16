package com.azki.reservation.dto.reservation;

import lombok.Data;

@Data
/**
 * 自动预约的内部队列参数。
 * HTTP 接口不再反序列化该对象，userId 和 email 都由认证身份生成。
 */
public class ReservationRequestDto {
    private Long userId;
    private String email;
}
