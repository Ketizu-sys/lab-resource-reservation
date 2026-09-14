package com.azki.reservation.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 当前应用实例的轻量负载计数器。
 *
 * <p>它只统计正在执行创建预约接口的请求数，不代表 CPU、数据库或 Redis 的真实负载，
 * 也不会在多个应用实例之间共享。达到阈值后，控制器会选择异步排队。</p>
 */
@Service
public class LoadMonitoringService {
    private static final Logger logger = LoggerFactory.getLogger(LoadMonitoringService.class);

    private final AtomicInteger activeRequests = new AtomicInteger(0);
    private final MeterRegistry meterRegistry;

    @Value("${reservation.request.threshold:5}")
    private int requestThreshold;

    public LoadMonitoringService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.meterRegistry.gauge("reservation.active.requests", activeRequests);
    }

    /**
     * 根据当前活动请求数判断是否排队。
     *
     * @return 达到阈值返回 true，否则可同步处理
     */
    public boolean shouldQueueRequest() {
        int currentLoad = activeRequests.get();
        boolean shouldQueue = currentLoad >= requestThreshold;

        if (shouldQueue) {
            logger.debug("High load detected ({} active requests). Request will be queued.", currentLoad);
        } else {
            logger.debug("Normal load ({} active requests). Request will be processed directly.", currentLoad);
        }

        return shouldQueue;
    }

    /** 请求进入核心处理区前增加计数。 */
    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    /** 请求结束时减少计数，调用方应放在 finally 中执行。 */
    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }
}
