package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.entity.User;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.exception.ReservationNotAvailableException;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private TimeSlotRepository timeSlotRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CacheableOperations cacheableOperations;

    private MeterRegistry meterRegistry;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        reservationService = new ReservationService(
                timeSlotRepository,
                reservationRepository,
                userRepository,
                meterRegistry,
                cacheableOperations
        );
    }

    @Test
    void shouldFindNextAvailableSlot() {
        // 准备：构造一条未来空闲时段。
        LocalDateTime now = LocalDateTime.now();
        AvailableSlot availableSlot = new AvailableSlot();
        availableSlot.setId(1L);
        availableSlot.setStartTime(now.plusHours(1));
        availableSlot.setEndTime(now.plusHours(2));
        availableSlot.setReserved(false);

        when(cacheableOperations.findNextAvailableSlotCached(any(LocalDateTime.class)))
                .thenReturn(Optional.of(availableSlot));

        // 执行：查询最近空闲时段。
        Optional<AvailableSlot> result = reservationService.findNextAvailableSlotCached();

        // 验证：返回预期时段。
        assertTrue(result.isPresent());
        assertEquals(availableSlot.getId(), result.get().getId());
        assertEquals(availableSlot.getStartTime(), result.get().getStartTime());
    }

    @Test
    void shouldReserveNearestSlot() {
        // 准备：用户存在、没有重复预约且有可分配时段。
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();

        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        AvailableSlot slot = new AvailableSlot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));
        slot.setReserved(false);

        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findNextAvailableForUpdate(any(LocalDateTime.class))).thenReturn(Optional.of(slot));
        when(timeSlotRepository.save(any(AvailableSlot.class))).thenReturn(slot);
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenReturn(reservation);
        when(reservationRepository.existsOverlappingReservation(
                eq(user.getId()), eq(ReservationStatus.ACTIVE), eq(slot.getStartTime()), eq(slot.getEndTime())))
                .thenReturn(false);

        // 执行：为用户创建预约。
        Reservation result = reservationService.reserveNearestSlot(email);

        // 验证：时段和预约均被保存。
        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(timeSlotRepository).save(any(AvailableSlot.class));
        verify(reservationRepository).saveAndFlush(any(Reservation.class));
        verify(userRepository).findByEmailForUpdate(email);
        verify(cacheableOperations, never()).findNextAvailableSlotCached(any(LocalDateTime.class));
        assertEquals(1, meterRegistry.timer("reservation.processing.time").count());
        assertEquals(1, meterRegistry.timer("reservation.slot.selection.time").count());
    }

    @Test
    void shouldThrowExceptionWhenUserAlreadyHasActiveReservation() {
        // 准备：用户已经持有与候选时段重叠的有效预约。
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        AvailableSlot slot = new AvailableSlot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findNextAvailableForUpdate(any(LocalDateTime.class))).thenReturn(Optional.of(slot));
        when(reservationRepository.existsOverlappingReservation(
                eq(user.getId()), eq(ReservationStatus.ACTIVE), eq(slot.getStartTime()), eq(slot.getEndTime())))
                .thenReturn(true);

        // 执行并验证：应拒绝重复预约。
        assertThrows(DuplicateReservationException.class, () -> reservationService.reserveNearestSlot(email));
    }

    @Test
    void shouldThrowExceptionWhenNoSlotsAvailable() {
        // 准备：用户存在，但没有任何可用时段。
        String email = "test@example.com";
        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        when(userRepository.findByEmailForUpdate(email)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findNextAvailableForUpdate(any(LocalDateTime.class))).thenReturn(Optional.empty());

        // 执行并验证：应抛出无可用预约异常。
        assertThrows(ReservationNotAvailableException.class, () -> reservationService.reserveNearestSlot(email));
        assertEquals(1, meterRegistry.timer("reservation.processing.time").count());
        assertEquals(1, meterRegistry.timer("reservation.slot.selection.time").count());
    }

    @Test
    void shouldCancelReservation() {
        // 准备：构造一条已有预约及其已占用时段。
        Long reservationId = 1L;
        LocalDateTime now = LocalDateTime.now();

        User user = new User();
        user.setId(1L);
        user.setEmail("test@example.com");

        AvailableSlot slot = new AvailableSlot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));
        slot.setReserved(true);

        Reservation reservation = new Reservation();
        reservation.setId(reservationId);
        reservation.setUser(user);
        reservation.setAvailableSlot(slot);
        reservation.setStatus(ReservationStatus.ACTIVE);

        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        // 执行：取消预约。
        reservationService.cancelReservation(reservationId);

        // 验证：时段被释放，预约转为已取消且历史记录仍保留。
        verify(timeSlotRepository).save(any(AvailableSlot.class));
        verify(reservationRepository).saveAndFlush(reservation);
        verify(reservationRepository, never()).delete(any(Reservation.class));
        verify(cacheableOperations).evictNextSlotCache();
        assertFalse(slot.isReserved());
        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertNotNull(reservation.getCancelledAt());
        assertEquals("Cancelled by user", reservation.getCancelReason());
    }

    @Test
    void shouldRejectCancellationWhenReservationIsNotActive() {
        Reservation reservation = reservationWithStatus(ReservationStatus.COMPLETED, LocalDateTime.now().plusHours(1));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));

        assertThrows(BusinessException.class, () -> reservationService.cancelReservation(1L));

        verify(timeSlotRepository, never()).save(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldRejectCancellationAfterSlotHasStarted() {
        Reservation reservation = reservationWithStatus(ReservationStatus.ACTIVE, LocalDateTime.now().minusMinutes(1));
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(reservation));

        assertThrows(BusinessException.class, () -> reservationService.cancelReservation(1L));

        verify(timeSlotRepository, never()).save(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    private Reservation reservationWithStatus(ReservationStatus status, LocalDateTime startTime) {
        AvailableSlot slot = new AvailableSlot();
        slot.setStartTime(startTime);
        slot.setEndTime(startTime.plusHours(1));
        slot.setReserved(true);

        Reservation reservation = new Reservation();
        reservation.setId(1L);
        reservation.setStatus(status);
        reservation.setAvailableSlot(slot);
        return reservation;
    }
}
