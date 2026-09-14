package com.azki.reservation.exception;

/** 高并发下多次抢占失败或系统容量暂时不足时抛出。 */
public class ReservationCapacityExceededException extends BusinessException {
    public ReservationCapacityExceededException(String message) {
        super(message);
    }
}
