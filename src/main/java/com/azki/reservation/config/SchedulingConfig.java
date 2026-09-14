package com.azki.reservation.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务总开关配置。
 *
 * <p>默认启用预约队列消费、过期预约清理和 Redis 状态清理等
 * {@code @Scheduled} 任务。测试环境可设置
 * {@code reservation.scheduling.enabled=false}，避免后台线程自动消费测试数据。</p>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
    prefix = "reservation.scheduling",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class SchedulingConfig {
}
