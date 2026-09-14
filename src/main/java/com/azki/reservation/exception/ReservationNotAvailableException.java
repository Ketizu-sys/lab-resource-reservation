package com.azki.reservation.exception;

/** 找不到符合条件的空闲时段，或候选时段已失效时抛出。 */
public class ReservationNotAvailableException extends BusinessException {
    public ReservationNotAvailableException(String message) {
        super(message);
    }
}
