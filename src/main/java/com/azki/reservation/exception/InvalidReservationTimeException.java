package com.azki.reservation.exception;

/** 预约违反时间规则时使用，例如预约过去时间、非营业时间或超出允许时长。 */
public class InvalidReservationTimeException extends BusinessException {
    public InvalidReservationTimeException(String message) {
        super(message);
    }
}
