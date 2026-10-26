package com.azki.reservation.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** 仅在 Kafka 队列模式启用时声明预约主 Topic。 */
@Configuration
@ConditionalOnProperty(name = "reservation.queue.backend", havingValue = "kafka")
public class KafkaQueueConfig {

    @Bean
    public NewTopic reservationRequestsTopic(
        @Value("${reservation.queue.kafka.topic:reservation.requests.v1}") String topic,
        @Value("${reservation.queue.kafka.partitions:3}") int partitions,
        @Value("${reservation.queue.kafka.replication-factor:1}") short replicationFactor
    ) {
        return TopicBuilder.name(topic)
            .partitions(partitions)
            .replicas(replicationFactor)
            .build();
    }
}
