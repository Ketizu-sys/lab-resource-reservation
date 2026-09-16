package com.azki.reservation.exception;

/** 请求的可见资源不存在。 */
public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
