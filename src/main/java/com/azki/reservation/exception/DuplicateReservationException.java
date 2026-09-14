package com.azki.reservation.exception;

/** 用户重复入队或已经持有未来预约时抛出。 */
public class DuplicateReservationException extends BusinessException {
    public DuplicateReservationException(String message) {
        super(message);
    }
}
