package com.azki.reservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 状态键的过期管理服务。
 *
 * <p>新建状态键时立即设置 TTL；每天凌晨还会检查历史遗留的、没有 TTL 的状态键，
 * 防止请求状态永久占用 Redis 内存。</p>
 */
@Service
public class RedisCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(RedisCleanupService.class);
    private static final String STATUS_KEY_PREFIX = "reservation:status:";

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${reservation.status.expiry-hours:24}")
    private int statusExpiryHours;

    public RedisCleanupService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 为新建的请求状态键设置存活时间。
     * @param key 完整的 Redis 状态键
     */
    public void setExpiryOnStatusKey(String key) {
        redisTemplate.expire(key, statusExpiryHours, TimeUnit.HOURS);
    }

    /** 每天凌晨 2 点为历史遗留的无 TTL 状态键补设过期时间。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupOldStatusKeys() {
        logger.info("Starting scheduled cleanup of old reservation status keys");
        try {
            // 找到所有预约状态键。注意：RedisTemplate.keys 实际执行 KEYS，大数据量时可能阻塞 Redis。
            Set<String> keys = redisTemplate.keys(STATUS_KEY_PREFIX + "*");

            if (!keys.isEmpty()) {
                int count = 0;
                for (String key : keys) {
                    // getExpire 大于 0 说明已有有效 TTL；否则补设默认过期时间。
                    Duration ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS) > 0
                        ? null : Duration.ofHours(statusExpiryHours);

                    if (ttl != null) {
                        redisTemplate.expire(key, statusExpiryHours, TimeUnit.HOURS);
                        count++;
                    }
                }
                logger.info("Applied TTL to {} status keys", count);
            } else {
                logger.info("No status keys found to clean up");
            }
        } catch (Exception e) {
            logger.error("Error during status keys cleanup", e);
        }
    }
}
