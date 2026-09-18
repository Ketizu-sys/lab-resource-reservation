package com.azki.reservation.dto.auth;

import com.azki.reservation.entity.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 注册成功响应，不包含密码明文或密码哈希。 */
@Schema(description = "注册成功后的用户公开信息，不会返回 JWT 或密码")
public record RegisterResponse(
        @Schema(example = "21") Long id,
        @Schema(example = "user@example.com") String email,
        @Schema(example = "user123") String username,
        @Schema(example = "USER") UserRole role,
        @Schema(description = "账号创建时间") Instant createdAt) {
}
