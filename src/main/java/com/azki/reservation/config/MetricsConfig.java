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
        // 业务代码需要使用此 Timer 包裹预约流程，才会产生实际采样数据。
        return Timer.builder("reservation.processing.time")
                .description("Time taken to process a reservation")
                .register(registry);
    }

    @Bean
    public Timer slotSelectionTimer(MeterRegistry registry) {
        // 用于观察选择空闲时段所消耗的时间。
        return Timer.builder("reservation.slot.selection.time")
                .description("Time taken to select an available slot")
                .register(registry);
    }
}
