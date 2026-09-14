package com.azki.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableCaching
@EnableJpaRepositories
@EnableRetry
/**
 * 预约系统启动入口。
 *
 * <p>{@link SpringBootApplication} 负责组件扫描和自动配置；
 * {@link EnableCaching} 开启基于 Redis 的 Spring Cache；
 * {@link EnableJpaRepositories} 开启 JPA Repository 扫描；
 * {@link EnableRetry} 使业务方法上的声明式重试注解真正生效。</p>
 */
public class ReservationApplication {

    public static void main(String[] args) {
        // 创建 Spring 容器并启动内嵌 Web 服务器。
        SpringApplication.run(ReservationApplication.class, args);
    }
}
