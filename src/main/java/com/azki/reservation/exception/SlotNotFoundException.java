package com.azki.reservation.exception;

/** 请求的时段不存在，或对普通用户而言当前不可见。 */
public class SlotNotFoundException extends RuntimeException {
    public SlotNotFoundException(String message) {
        super(message);
    }
}
