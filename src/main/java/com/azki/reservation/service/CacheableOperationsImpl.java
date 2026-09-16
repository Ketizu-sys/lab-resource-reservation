package com.azki.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.repository.TimeSlotRepository;

import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 最近空闲时段的缓存实现。
 *
 * <p>固定键 {@code single} 表示整个系统当前共用一个“最近时段”缓存项；
 * 预约或取消改变时段状态后，必须调用 {@link #evictNextSlotCache()} 主动失效。</p>
 */
@Service
@RequiredArgsConstructor
public class CacheableOperationsImpl implements CacheableOperations {

    private final TimeSlotRepository timeSlotRepository;
    private static final Logger logger = LoggerFactory.getLogger(CacheableOperationsImpl.class);

    @Override
    @Cacheable(value = "nextSlot", key = "'single'")
    public Optional<AvailableSlot> findNextAvailableSlotCached(LocalDateTime now) {
        // 只有缓存未命中时才会进入方法体并查询 PostgreSQL。
        logger.debug("Finding next available time slot (cached)");
        Optional<AvailableSlot> slot = timeSlotRepository
            .findFirstByIsReservedFalseAndStartTimeGreaterThanEqualOrderByStartTimeAsc(now);
        if (slot.isPresent()) {
            logger.debug("Found available slot: id={}, startTime={}", slot.get().getId(), slot.get().getStartTime());
        } else {
            logger.debug("No available time slots found");
        }
        return slot;
    }

    @Override
    @CacheEvict(value = "nextSlot", key = "'single'")
    public void evictNextSlotCache() {
        // 方法体无需处理数据，真正的删除动作由 Spring Cache 拦截器完成。
        logger.debug("Evicting nextSlot cache");
    }
}
