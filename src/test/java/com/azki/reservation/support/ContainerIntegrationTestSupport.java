package com.azki.reservation.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;

import java.time.Duration;

/**
 * 集成测试共享的 MySQL 和 Redis 容器。
 * 测试类需配合 @Testcontainers(disabledWithoutDocker = true)，无 Docker 时明确跳过。
 */
public abstract class ContainerIntegrationTestSupport {

    @Container
    protected static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("reservation_test")
                    .withUsername("reservation_test")
                    .withPassword("reservation_test")
                    // Docker Desktop 负载较高时初始化可能超过 Testcontainers 默认的 60 秒。
                    .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379)
                    .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ContainerIntegrationTestSupport::mysqlJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static String mysqlJdbcUrl() {
        String jdbcUrl = MYSQL.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }
}
