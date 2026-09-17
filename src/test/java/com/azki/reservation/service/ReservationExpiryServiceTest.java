package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.User;
import com.azki.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证过期预约任务的调度单位，防止“分钟”被误写成“秒”。 */
@ExtendWith(MockitoExtension.class)
class ReservationExpiryServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    private ReservationExpiryService reservationExpiryService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        reservationExpiryService = new ReservationExpiryService(
                reservationRepository,
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void shouldInterpretExpiryCheckIntervalAsMinutes() throws NoSuchMethodException {
        Method method = ReservationExpiryService.class
            .getDeclaredMethod("processExpiredReservations");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${reservation.expiry.check-minutes:15}", scheduled.fixedDelayString());
        assertEquals(TimeUnit.MINUTES, scheduled.timeUnit());
    }

    @Test
    void shouldCompleteReservationWithoutReopeningSlotOrDeletingHistory() {
        AvailableSlot slot = new AvailableSlot();
        slot.setId(2L);
        slot.setReserved(true);
        User user = new User();
        user.setEmail("expired@example.com");
        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setStatus(ReservationStatus.ACTIVE);

        when(reservationRepository.findExpiredReservationsForUpdate(any(LocalDateTime.class)))
            .thenReturn(List.of(reservation));

        reservationExpiryService.processExpiredReservations();

        assertEquals(ReservationStatus.COMPLETED, reservation.getStatus());
        assertNotNull(reservation.getCompletedAt());
        assertEquals(true, slot.isReserved());
        verify(reservationRepository).save(reservation);
        verify(reservationRepository, never()).delete(any(Reservation.class));
    }
}
