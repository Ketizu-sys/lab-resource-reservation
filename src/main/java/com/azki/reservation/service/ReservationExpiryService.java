package com.azki.reservation.service;

import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.ReservationStatus;
import com.azki.reservation.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 预约过期清理服务。
 *
 * <p>定期找出关联时段已经结束的 ACTIVE 预约并标记为 COMPLETED。
 * 历史预约不会删除，且已经结束的时段不会重新开放。</p>
 */
@Service
public class ReservationExpiryService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final ReservationRepository reservationRepository;

    public ReservationExpiryService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /**
     * 周期性处理过期预约，默认每 15 分钟执行一次。
     * 整个批次位于一个事务中，但循环内会捕获单条记录的异常并继续处理后续记录。
     */
    @Scheduled(
        fixedDelayString = "${reservation.expiry.check-minutes:15}",
        timeUnit = TimeUnit.MINUTES
    )
    @Transactional
    public void processExpiredReservations() {
        logger.info("Starting expired reservations check");

        LocalDateTime now = LocalDateTime.now();
        List<Reservation> expiredReservations =
            reservationRepository.findExpiredReservationsForUpdate(now);

        if (expiredReservations.isEmpty()) {
            logger.info("No expired reservations found");
            return;
        }

        logger.info("Found {} expired reservations to process", expiredReservations.size());

        for (Reservation reservation : expiredReservations) {
            reservation.setStatus(ReservationStatus.COMPLETED);
            reservation.setCompletedAt(now);
            reservationRepository.save(reservation);

            logger.info("Expired reservation completed: id={}, user={}, slot={}",
                reservation.getId(), reservation.getUser().getEmail(),
                reservation.getAvailableSlot().getId());
        }

        logger.info("Completed expired reservations update, processed {} reservations",
            expiredReservations.size());
    }
}
