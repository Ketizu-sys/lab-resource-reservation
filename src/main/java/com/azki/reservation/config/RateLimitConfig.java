package com.azki.reservation.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Refill;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 基于令牌桶算法的 API 限流参数与 Bucket 工厂。
 * 每个客户端会获得独立 Bucket，避免一个调用方耗尽所有人的额度。
 */
@Configuration
@ConfigurationProperties(prefix = "reservation.rate-limiting")
public class RateLimitConfig {

    private long capacity = 20;
    private long refillTokens = 20;
    private Duration refillPeriod = Duration.ofMinutes(1);
    private int maxTrackedClients = 10_000;

    @PostConstruct
    public void validate() {
        if (capacity <= 0 || refillTokens <= 0 || refillPeriod.isZero() || refillPeriod.isNegative()) {
            throw new IllegalArgumentException("Rate limit capacity, refill tokens and period must be positive");
        }
        if (maxTrackedClients <= 0) {
            throw new IllegalArgumentException("Rate limit max tracked clients must be positive");
        }
    }

    /** 为一个新的“客户端 + 接口组”创建独立令牌桶。 */
    public Bucket createBucket() {
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillTokens, refillPeriod));
        return Bucket4j.builder().addLimit(limit).build();
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public long getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(long refillTokens) {
        this.refillTokens = refillTokens;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }
}
