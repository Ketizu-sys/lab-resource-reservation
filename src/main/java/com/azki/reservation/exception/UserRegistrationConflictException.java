package com.azki.reservation.exception;

/** 注册邮箱或用户名与已有账号冲突。 */
public class UserRegistrationConflictException extends RuntimeException {

    public UserRegistrationConflictException(String message) {
        super(message);
    }

    public UserRegistrationConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
