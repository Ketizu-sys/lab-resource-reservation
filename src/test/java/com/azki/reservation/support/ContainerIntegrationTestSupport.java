package com.azki.reservation.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * 集成测试共享的 PostgreSQL 和 Redis 容器。
 * 测试类需配合 @Testcontainers(disabledWithoutDocker = true)，无 Docker 时明确跳过。
 */
public abstract class ContainerIntegrationTestSupport {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("reservation_test")
                    .withUsername("reservation_test")
                    .withPassword("reservation_test");

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
