package com.azki.reservation.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注册预约处理和时段选择的 Micrometer 耗时指标。 */
@Configuration
public class MetricsConfig {

    @Bean
    public Timer reservationProcessingTimer(MeterRegistry registry) {
        // ReservationService 会记录从校验用户到写入预约的完整耗时。
        return Timer.builder("reservation.processing.time")
                .description("Time taken to process a reservation")
                .register(registry);
    }

    @Bean
    public Timer slotSelectionTimer(MeterRegistry registry) {
        // ReservationService 会单独记录带数据库锁的候选时段查询耗时。
        return Timer.builder("reservation.slot.selection.time")
                .description("Time taken to select an available slot")
                .register(registry);
    }
}
