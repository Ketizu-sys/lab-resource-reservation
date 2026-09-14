package com.azki.reservation.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求参数，只负责传输与格式校验，不包含认证逻辑。 */
@Data
public class LoginRequestDto {

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email format is required")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;
}
