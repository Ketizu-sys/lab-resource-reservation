package com.azki.reservation.service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Reservation;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.TimeSlotRepository;
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
 * <p>定期找出关联时段已经结束的预约，释放其时段并删除预约记录。
 * 过期时间以时段的 endTime 为准，因此提前创建的未来预约不会被误删。</p>
 */
@Service
public class ReservationExpiryService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationExpiryService.class);

    private final ReservationRepository reservationRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CacheableOperations cacheableOperations;

    public ReservationExpiryService(
            ReservationRepository reservationRepository,
            TimeSlotRepository timeSlotRepository,
            CacheableOperations cacheableOperations) {
        this.reservationRepository = reservationRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.cacheableOperations = cacheableOperations;
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
            reservationRepository.findExpiredReservations(now);

        if (expiredReservations.isEmpty()) {
            logger.info("No expired reservations found");
            return;
        }

        logger.info("Found {} expired reservations to process", expiredReservations.size());

        for (Reservation reservation : expiredReservations) {
            try {
                // 先释放关联时段，使其能够再次被分配。
                AvailableSlot slot = reservation.getAvailableSlot();
                slot.setReserved(false);
                timeSlotRepository.save(slot);

                // 再删除已经过期的预约记录。
                reservationRepository.delete(reservation);

                logger.info("Expired reservation deleted: id={}, user={}, slot={}",
                    reservation.getId(), reservation.getUser().getEmail(),
                    reservation.getAvailableSlot().getId());

            } catch (Exception e) {
                logger.error("Error processing expired reservation {}", reservation.getId(), e);
            }
        }

        // 时段可用性发生变化，清缓存以免继续返回旧结果。
        cacheableOperations.evictNextSlotCache();

        logger.info("Completed expired reservations cleanup, processed {} reservations",
            expiredReservations.size());
    }
}
