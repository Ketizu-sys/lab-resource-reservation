package com.azki.reservation.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 基于令牌桶算法的 API 限流配置。
 * 仅当 reservation.rate-limiting.enabled=true 时创建全局 Bucket。
 */
@Configuration
@ConditionalOnProperty(value = "reservation.rate-limiting.enabled", havingValue = "true", matchIfMissing = false)
public class RateLimitConfig {

    // 桶最多容纳 20 个令牌，每分钟平滑补充 20 个；一次请求消费一个令牌。
    private static final int CAPACITY = 20;
    private static final int TOKENS_PER_MINUTE = 20;

    @Bean
    public Bucket tokenBucket() {
        Bandwidth limit = Bandwidth.classic(CAPACITY, Refill.greedy(TOKENS_PER_MINUTE, Duration.ofMinutes(1)));
        return Bucket4j.builder().addLimit(limit).build();
    }
}
