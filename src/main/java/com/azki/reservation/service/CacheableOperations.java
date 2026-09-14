package com.azki.reservation.service;

import java.time.LocalDateTime;
import java.util.Optional;

import com.azki.reservation.entity.AvailableSlot;

/**
 * 可缓存操作的抽象接口。
 *
 * <p>Spring 的缓存注解依赖代理对象；同一个类内部直接调用自身方法时不会经过代理，
 * 因此把缓存读写单独拆成 Bean，确保 {@code @Cacheable}/{@code @CacheEvict} 生效。</p>
 */
public interface CacheableOperations {

    /**
     * 查找并缓存当前时间之后最近的空闲时段。
     *
     * @param now 查询时间下界
     * @return 找到时返回时段，否则返回空 Optional
     */
    Optional<AvailableSlot> findNextAvailableSlotCached(LocalDateTime now);

    /** 清除最近空闲时段缓存，使下一次查询重新访问数据库。 */
    void evictNextSlotCache();
}
