package com.azki.reservation.dto.reservation;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Data
/** 创建预约的请求参数。当前业务仅根据用户邮箱自动选择最近空闲时段。 */
public class ReservationRequestDto {
    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;
}
