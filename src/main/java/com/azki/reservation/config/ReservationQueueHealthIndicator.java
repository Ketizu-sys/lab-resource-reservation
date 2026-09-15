package com.azki.reservation.config;

import com.azki.reservation.service.ReservationQueueService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预约队列健康检查器。
 * 根据主队列和死信队列积压量，把 Actuator 健康状态标记为正常、警告或故障。
 */
@Component
public class ReservationQueueHealthIndicator implements HealthIndicator {

    private final ReservationQueueService reservationQueueService;

    private final int queueWarningThreshold;
    private final int queueCriticalThreshold;
    private final int dlqWarningThreshold;

    public ReservationQueueHealthIndicator(
            ReservationQueueService reservationQueueService,
            @Value("${reservation.health.queue-warning-threshold:50}") int queueWarningThreshold,
            @Value("${reservation.health.queue-critical-threshold:100}") int queueCriticalThreshold,
            @Value("${reservation.health.dlq-warning-threshold:10}") int dlqWarningThreshold) {
        if (queueWarningThreshold < 0
                || queueCriticalThreshold <= queueWarningThreshold
                || dlqWarningThreshold < 0) {
            throw new IllegalArgumentException("Reservation health thresholds are invalid");
        }
        this.reservationQueueService = reservationQueueService;
        this.queueWarningThreshold = queueWarningThreshold;
        this.queueCriticalThreshold = queueCriticalThreshold;
        this.dlqWarningThreshold = dlqWarningThreshold;
    }

    @Override
    public Health health() {
        long queueSize = reservationQueueService.getQueueLength();
        long dlqSize = reservationQueueService.getDLQLength();

        // 无论最终状态如何，都在健康详情中带回主队列和 DLQ 长度。
        Health.Builder builder = Health.up()
            .withDetail("queueSize", queueSize)
            .withDetail("deadLetterQueueSize", dlqSize);

        // 主队列严重积压优先判定为 DOWN，其次是 WARNING。
        if (queueSize > queueCriticalThreshold) {
            return builder.down()
                .withDetail("error", "Queue size exceeds critical threshold")
                .build();
        } else if (queueSize > queueWarningThreshold) {
            return builder.status("WARNING")
                .withDetail("warning", "Queue size exceeds warning threshold")
                .build();
        }

        // 主队列正常时再检查不可自动恢复的死信数量。
        if (dlqSize > dlqWarningThreshold) {
            return builder.status("WARNING")
                .withDetail("warning", "Dead letter queue size exceeds threshold")
                .build();
        }

        return builder.build();
    }
}
