package com.azki.reservation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 为 Slot 业务时间和预约生命周期提供统一的业务时区。 */
@Configuration
public class BusinessTimeConfig {

    @Bean
    Clock reservationClock(@Value("${reservation.time-zone:Asia/Shanghai}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
