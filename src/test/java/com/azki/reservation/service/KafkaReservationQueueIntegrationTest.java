package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationMode;
import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.entity.AvailableSlot;
import com.azki.reservation.entity.Resource;
import com.azki.reservation.entity.ResourceType;
import com.azki.reservation.entity.User;
import com.azki.reservation.entity.UserRole;
import com.azki.reservation.repository.ReservationRepository;
import com.azki.reservation.repository.ResourceRepository;
import com.azki.reservation.repository.TimeSlotRepository;
import com.azki.reservation.repository.UserRepository;
import com.azki.reservation.support.ContainerIntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
    "reservation.queue.backend=kafka",
    "reservation.queue.kafka.consumer-concurrency=1",
    "reservation.queue.kafka.partitions=1",
    "reservation.queue.kafka.retry-initial-delay-ms=100",
    "reservation.queue.kafka.retry-multiplier=1",
    "reservation.queue.kafka.retry-max-delay-ms=100"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaReservationQueueIntegrationTest extends ContainerIntegrationTestSupport {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("apache/kafka-native:3.8.0")
    ).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired private ReservationQueueService queueService;
    @Autowired private UserRepository users;
    @Autowired private ResourceRepository resources;
    @Autowired private TimeSlotRepository slots;
    @Autowired private ReservationRepository reservations;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void clean() {
        reservations.deleteAll();
        slots.deleteAll();
        resources.deleteAll();
        Set<String> queueKeys = redisTemplate.keys("reservation:*");
        if (queueKeys != null && !queueKeys.isEmpty()) {
            redisTemplate.delete(queueKeys);
        }
    }

    @Test
    void shouldPublishConsumeAndPersistReservationThroughKafka() {
        User user = user();
        slot(resource());

        ReservationRequestDto request = new ReservationRequestDto();
        request.setUserId(user.getId());
        request.setMode(ReservationMode.AUTO);

        String requestId = queueService.enqueueReservationRequest(request);
        assertEquals(1, queueService.getQueueLength());

        queueService.publishKafkaOutbox();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertEquals(1, reservations.count());
            assertEquals(ReservationQueueService.RequestStatus.SUCCESS.name(),
                queueService.getRequestStatus(requestId, user.getId()));
            assertEquals(0, queueService.getQueueLength());
        });
    }

    private User user() {
        User user = new User();
        user.setUserName("kafka-" + UUID.randomUUID());
        user.setEmail("kafka-" + UUID.randomUUID() + "@test.local");
        user.setPassword("encoded");
        user.setRole(UserRole.USER);
        return users.saveAndFlush(user);
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setName("Kafka Lab " + UUID.randomUUID());
        resource.setType(ResourceType.LAB);
        resource.setLocation("test");
        return resources.saveAndFlush(resource);
    }

    private AvailableSlot slot(Resource resource) {
        AvailableSlot slot = new AvailableSlot();
        slot.setResource(resource);
        slot.setStartTime(LocalDateTime.now().plusHours(1).withNano(0));
        slot.setEndTime(LocalDateTime.now().plusHours(2).withNano(0));
        slot.setReserved(false);
        return slots.saveAndFlush(slot);
    }
}
