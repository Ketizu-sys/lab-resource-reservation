package com.azki.reservation.exception;

/** 所有可预期业务错误的基类，由全局异常处理器统一转换为 HTTP 响应。 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}

