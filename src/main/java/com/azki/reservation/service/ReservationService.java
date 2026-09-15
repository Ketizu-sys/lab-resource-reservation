package com.azki.reservation.service;

import com.azki.reservation.entity.Reservation;
import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.User;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.exception.ReservationCapacityExceededException;
import com.azki.reservation.exception.ReservationNotAvailableException;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 预约领域的核心业务服务。
 *
 * <p>负责校验用户、阻止重复预约、选择最近时段、写入预约、取消预约，
 * 并在时段状态改变后维护缓存一致性。同步接口和 Redis 队列最终都会调用本类。</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final TimeSlotRepository timeSlotRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final CacheableOperations cacheableOperations;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final Logger logger = LoggerFactory.getLogger(ReservationService.class);

    /**
     * 查询最近空闲时段，实际缓存逻辑委托给独立 Bean，以确保经过 Spring 缓存代理。
     *
     * @return 最近空闲时段；没有候选时返回空 Optional
     */
    public Optional<AvailableSlot> findNextAvailableSlotCached() {
        return cacheableOperations.findNextAvailableSlotCached(LocalDateTime.now());
    }

    /**
     * 为指定邮箱的用户预约当前时间之后最近的空闲时段。
     *
     * <p>整个流程在一个事务中完成；若 Hibernate 报告乐观锁冲突，Spring Retry
     * 计划最多执行三次并使用递增退避。只有应用启用 Retry AOP 时该注解才会生效。</p>
     *
     * @param email 发起预约的用户邮箱
     * @return 已持久化的预约记录
     * @throws BusinessException 用户不存在、已有未来预约或没有可用时段时抛出
     */
    @Retryable(
        retryFor = OptimisticLockingFailureException.class,
        notRecoverable = BusinessException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 10, multiplier = 1.5)
    )
    @Transactional
    public Reservation reserveNearestSlot(String email) {
        logger.info("Attempting to reserve nearest slot for user: {}", email);
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        logger.warn("User not found for email: {}", email);
                        return new BusinessException("User not found for email: " + email);
                    });
            logger.debug("Found user: id={}, email={}", user.getId(), user.getEmail());

            // 业务约束：同一用户不能同时拥有另一条未来预约。
            if (reservationRepository.existsByUserEmailAndStartTimeAfter(email, LocalDateTime.now())) {
                logger.warn("Duplicate reservation attempt detected for user: {}", email);
                throw new DuplicateReservationException("User already has an active reservation");
            }

            Reservation reservation = attemptReservation(user);
            logger.info("Successfully created reservation: id={} for user={} at time={}",
                    reservation.getId(), email, reservation.getAvailableSlot().getStartTime());
            meterRegistry.counter("reservation.success").increment();
            return reservation;
        } catch (BusinessException e) {
            logger.error("Failed to create reservation for user: {}. Reason: {}", email, e.getMessage());
            meterRegistry.counter("reservation.failed").increment();
            throw e;
        }
    }

    /**
     * 乐观锁重试全部耗尽后的恢复入口。
     * 方法参数需与被重试方法一致，并在最前面增加触发恢复的异常参数。
     *
     * @param e 最终一次乐观锁异常
     * @param email 原预约方法收到的用户邮箱
     * @return 本实现不会正常返回
     * @throws ReservationCapacityExceededException 将并发冲突转换为容量繁忙提示
     */
    @Recover
    public Reservation recoverFromOptimisticLockingFailure(OptimisticLockingFailureException e, String email) {
        logger.error("Failed to reserve slot after {} attempts due to concurrent modifications", MAX_RETRY_ATTEMPTS);
        meterRegistry.counter("reservation.optimistic_locking_failures").increment();
        throw new ReservationCapacityExceededException("Unable to reserve time slot due to high demand, please try again later");
    }

    /**
     * 执行一次实际的时段占用和预约写入。
     *
     * @param user 发起预约的持久化用户
     * @return 新建并保存的预约
     * @throws ReservationNotAvailableException 没有候选时段
     * @throws OptimisticLockingFailureException 保存期间发现实体版本冲突
     */
    protected Reservation attemptReservation(User user) {
        // 写流程直接从数据库领取并锁定一条候选记录，不能依赖可能过期的缓存值。
        AvailableSlot freshSlot = timeSlotRepository.findNextAvailableForUpdate(LocalDateTime.now())
                .orElseThrow(() -> new ReservationNotAvailableException("No available time slots"));

        freshSlot.setReserved(true);
        // 保存时 Hibernate 会校验 Auditable.version，避免静默覆盖其他事务的更新。
        AvailableSlot savedSlot = timeSlotRepository.save(freshSlot);
        logger.info("Slot {} reserved for user {}", savedSlot.getId(), user.getEmail());

        evictNextSlotCache();

        Reservation reservation = new Reservation();
        reservation.setUser(user);
        reservation.setAvailableSlot(savedSlot);
        reservation.setReservedAt(LocalDateTime.now());

        try {
            // 立即 flush，让用户/时段唯一约束异常在本方法内出现并转换为业务异常。
            Reservation saved = reservationRepository.saveAndFlush(reservation);
            logger.info("Reservation {} created for user {} at slot {}",
                saved.getId(), user.getEmail(), savedSlot.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            logger.warn("Database rejected duplicate reservation for user {}", user.getEmail());
            throw new DuplicateReservationException("User or time slot already has an active reservation");
        }
    }

    /**
     * 根据预约 ID 取消预约：释放时段、删除预约记录并清除最近时段缓存。
     *
     * @param id 预约主键
     * @throws BusinessException 找不到预约时抛出
     */
    @Transactional
    public void cancelReservation(Long id) {
        logger.info("Attempting to cancel reservation with id: {}", id);
        Reservation reservation = reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Reservation not found for id: " + id));

        AvailableSlot slot = reservation.getAvailableSlot();
        slot.setReserved(false);
        timeSlotRepository.save(slot);
        logger.info("Slot {} freed from reservation {}", slot.getId(), id);

        reservationRepository.delete(reservation);
        logger.info("Reservation {} cancelled", id);

        meterRegistry.counter("reservation.cancelled").increment();
        evictNextSlotCache();
    }

    /** 统一封装缓存失效调用，所有改变时段可用性的流程都应经过这里。 */
    private void evictNextSlotCache() {
        cacheableOperations.evictNextSlotCache();
    }
}
