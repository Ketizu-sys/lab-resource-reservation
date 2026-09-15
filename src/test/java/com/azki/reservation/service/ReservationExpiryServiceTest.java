package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.User;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证过期预约任务的调度单位，防止“分钟”被误写成“秒”。 */
@ExtendWith(MockitoExtension.class)
class ReservationExpiryServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private TimeSlotRepository timeSlotRepository;

    @Mock
    private CacheableOperations cacheableOperations;

    @InjectMocks
    private ReservationExpiryService reservationExpiryService;

    @Test
    void shouldInterpretExpiryCheckIntervalAsMinutes() throws NoSuchMethodException {
        Method method = ReservationExpiryService.class
            .getDeclaredMethod("processExpiredReservations");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${reservation.expiry.check-minutes:15}", scheduled.fixedDelayString());
        assertEquals(TimeUnit.MINUTES, scheduled.timeUnit());
    }

    @Test
    void shouldReleaseSlotDeleteReservationAndEvictCache() {
        AvailableSlot slot = new AvailableSlot();
        slot.setId(2L);
        slot.setReserved(true);
        User user = new User();
        user.setEmail("expired@example.com");
        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);

        when(reservationRepository.findExpiredReservations(any(LocalDateTime.class)))
            .thenReturn(List.of(reservation));

        reservationExpiryService.processExpiredReservations();

        assertFalse(slot.isReserved());
        verify(timeSlotRepository).save(slot);
        verify(reservationRepository).delete(reservation);
        verify(cacheableOperations).evictNextSlotCache();
    }
}
