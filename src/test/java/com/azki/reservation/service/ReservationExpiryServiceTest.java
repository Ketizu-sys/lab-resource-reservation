package com.azki.reservation.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证过期预约任务的调度单位，防止“分钟”被误写成“秒”。 */
class ReservationExpiryServiceTest {

    @Test
    void shouldInterpretExpiryCheckIntervalAsMinutes() throws NoSuchMethodException {
        Method method = ReservationExpiryService.class
            .getDeclaredMethod("processExpiredReservations");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${reservation.expiry.check-minutes:15}", scheduled.fixedDelayString());
        assertEquals(TimeUnit.MINUTES, scheduled.timeUnit());
    }
}
