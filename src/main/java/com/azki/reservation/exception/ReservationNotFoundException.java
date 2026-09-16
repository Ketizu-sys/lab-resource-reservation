package com.azki.reservation.exception;

/** 指定的预约记录不存在。 */
public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException(String message) { super(message); }
}
