package com.azki.reservation.dto.auth;

import com.azki.reservation.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 登录成功后的响应，包含 JWT、令牌类型、用户基本信息、角色和过期时间。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponseDto {
    private String token;
    private String tokenType;
    private String email;
    private String userName;
    private UserRole role;
    private LocalDateTime expiresAt;
}
