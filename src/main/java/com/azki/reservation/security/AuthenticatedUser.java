package com.azki.reservation.security;

import com.azki.reservation.entity.UserRole;

/** 写入 Spring SecurityContext 的可信用户身份。 */
public record AuthenticatedUser(Long id, String email, UserRole role) {
}
