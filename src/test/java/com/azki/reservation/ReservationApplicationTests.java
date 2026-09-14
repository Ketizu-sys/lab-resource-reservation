package com.azki.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.azki.reservation.support.ContainerIntegrationTestSupport;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationApplicationTests extends ContainerIntegrationTestSupport {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldRoundTripValueThroughRedis() {
        String key = "test:redis:" + UUID.randomUUID();

        try {
            redisTemplate.opsForValue().set(key, "可正常序列化的测试值");
            assertEquals("可正常序列化的测试值", redisTemplate.opsForValue().get(key));
        } finally {
            redisTemplate.delete(key);
        }
    }

}
