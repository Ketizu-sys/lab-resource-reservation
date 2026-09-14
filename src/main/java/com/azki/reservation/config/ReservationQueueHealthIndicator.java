package com.azki.reservation.config;

import com.azki.reservation.service.ReservationQueueService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 预约队列健康检查器。
 * 根据主队列和死信队列积压量，把 Actuator 健康状态标记为正常、警告或故障。
 */
@Component
public class ReservationQueueHealthIndicator implements HealthIndicator {

    private final ReservationQueueService reservationQueueService;

    // 队列健康阈值：主队列超过 50 警告、超过 100 故障；DLQ 超过 10 警告。
    private static final int QUEUE_WARNING_THRESHOLD = 50;
    private static final int QUEUE_CRITICAL_THRESHOLD = 100;
    private static final int DLQ_WARNING_THRESHOLD = 10;

    public ReservationQueueHealthIndicator(ReservationQueueService reservationQueueService) {
        this.reservationQueueService = reservationQueueService;
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
        if (queueSize > QUEUE_CRITICAL_THRESHOLD) {
            return builder.down()
                .withDetail("error", "Queue size exceeds critical threshold")
                .build();
        } else if (queueSize > QUEUE_WARNING_THRESHOLD) {
            return builder.status("WARNING")
                .withDetail("warning", "Queue size exceeds warning threshold")
                .build();
        }

        // 主队列正常时再检查不可自动恢复的死信数量。
        if (dlqSize > DLQ_WARNING_THRESHOLD) {
            return builder.status("WARNING")
                .withDetail("warning", "Dead letter queue size exceeds threshold")
                .build();
        }

        return builder.build();
    }
}
