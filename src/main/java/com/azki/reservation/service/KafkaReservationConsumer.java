package com.azki.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/** Kafka 预约消费者；重试 Topic 与 DLT 由 Spring Kafka 管理。 */
@Component
@ConditionalOnProperty(name = "reservation.queue.backend", havingValue = "kafka")
public class KafkaReservationConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KafkaReservationConsumer.class);

    private final ReservationQueueService queueService;

    public KafkaReservationConsumer(ReservationQueueService queueService) {
        this.queueService = queueService;
    }

    @RetryableTopic(
        attempts = "${reservation.queue.kafka.retry-attempts:3}",
        backoff = @Backoff(
            delayExpression = "${reservation.queue.kafka.retry-initial-delay-ms:10000}",
            multiplierExpression = "${reservation.queue.kafka.retry-multiplier:6}",
            maxDelayExpression = "${reservation.queue.kafka.retry-max-delay-ms:60000}"
        ),
        autoCreateTopics = "${reservation.queue.kafka.auto-create-topics:true}",
        numPartitions = "${reservation.queue.kafka.partitions:3}",
        replicationFactor = "${reservation.queue.kafka.replication-factor:1}"
    )
    @KafkaListener(
        topics = "${reservation.queue.kafka.topic:reservation.requests.v1}",
        groupId = "${reservation.queue.kafka.consumer-group:reservation-workers-v1}",
        concurrency = "${reservation.queue.kafka.consumer-concurrency:3}"
    )
    public void consume(String rawJson) {
        queueService.processKafkaMessage(rawJson);
    }

    @DltHandler
    public void deadLetter(String rawJson) {
        logger.error("Reservation message exhausted Kafka retries and reached DLT");
        queueService.handleKafkaDeadLetter(rawJson);
    }
}
