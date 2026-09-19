package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.dto.reservation.ReservationMode;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.exception.ReservationCapacityExceededException;
import com.azki.reservation.exception.ReservationNotAvailableException;
import com.azki.reservation.exception.RequestAlreadyProcessedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的异步预约队列服务。
 *
 * <p>等待消息保存在 List 中；消息被消费者领取后会原子移动到带时间戳的处理中 Sorted Set。
 * 只有业务处理结束后才会确认删除；处理超时的消息会重新进入等待队列，因此进程异常退出时
 * 不会因为消息已经从等待队列弹出而永久丢失。</p>
 */
@Service
public class ReservationQueueService {

    private static final Logger logger = LoggerFactory.getLogger(ReservationQueueService.class);

    static final String QUEUE_KEY = "reservation:queue";
    static final String PROCESSING_KEY = "reservation:queue:processing";
    static final String DLQ_KEY = "reservation:dlq";
    static final String USER_SET_KEY = "reservation:users:queued";
    static final String STATUS_KEY_PREFIX = "reservation:status:";
    static final String STATUS_OWNER_KEY_PREFIX = "reservation:status-owner:";
    private static final int MAX_ATTEMPTS = 3;

    /** 原子完成用户占位、消息入队、初始状态和状态所有者写入。 */
    private static final RedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('SADD', KEYS[2], ARGV[1]) == 0 then
            return 0
        end
        redis.call('RPUSH', KEYS[1], ARGV[2])
        redis.call('SET', KEYS[3], ARGV[3])
        redis.call('EXPIRE', KEYS[3], ARGV[4])
        redis.call('SET', KEYS[4], ARGV[1])
        redis.call('EXPIRE', KEYS[4], ARGV[4])
        return 1
        """, Long.class);

    /** 从等待队列领取一条消息，并记录领取时间。 */
    private static final RedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
        local item = redis.call('LPOP', KEYS[1])
        if item then
            redis.call('ZADD', KEYS[2], ARGV[1], item)
        end
        return item
        """, String.class);

    /** 确认终态，同时清理处理中记录和用户占位。 */
    private static final RedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then
            return 0
        end
        redis.call('SET', KEYS[2], ARGV[2])
        redis.call('EXPIRE', KEYS[2], ARGV[4])
        redis.call('SREM', KEYS[3], ARGV[3])
        redis.call('SET', KEYS[4], ARGV[3])
        redis.call('EXPIRE', KEYS[4], ARGV[4])
        return 1
        """, Long.class);

    /** 将失败的同一条消息原子地从处理中集合移回等待队列。 */
    private static final RedisScript<Long> REQUEUE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then
            return 0
        end
        redis.call('RPUSH', KEYS[2], ARGV[2])
        redis.call('SET', KEYS[3], ARGV[3])
        redis.call('EXPIRE', KEYS[3], ARGV[4])
        redis.call('SET', KEYS[4], ARGV[5])
        redis.call('EXPIRE', KEYS[4], ARGV[4])
        return 1
        """, Long.class);

    /** 将达到重试上限的同一条消息原子地转移到 DLQ。 */
    private static final RedisScript<Long> DLQ_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then
            return 0
        end
        redis.call('RPUSH', KEYS[2], ARGV[2])
        redis.call('SET', KEYS[3], ARGV[3])
        redis.call('EXPIRE', KEYS[3], ARGV[5])
        redis.call('SREM', KEYS[4], ARGV[4])
        redis.call('SET', KEYS[5], ARGV[4])
        redis.call('EXPIRE', KEYS[5], ARGV[5])
        return 1
        """, Long.class);

    /** 无法被 Java 解析的消息仍要进入 DLQ；若 JSON 元数据可读，则同步清理占位与状态。 */
    private static final RedisScript<Long> MALFORMED_TO_DLQ_SCRIPT = new DefaultRedisScript<>("""
        local function decodeItem(raw)
            local ok, value = pcall(cjson.decode, raw)
            if not ok then return nil end
            if type(value) == 'string' then
                ok, value = pcall(cjson.decode, value)
                if not ok then return nil end
            end
            if type(value) ~= 'table' then return nil end
            return value
        end
        if redis.call('ZREM', KEYS[1], ARGV[1]) == 0 then
            return 0
        end
        redis.call('RPUSH', KEYS[2], ARGV[1])
        local item = decodeItem(ARGV[1])
        if item and item.requestId and item.request and item.request.userId then
            local userId = cjson.encode(tostring(item.request.userId))
            local statusKey = cjson.decode(ARGV[2]) .. item.requestId
            local ownerKey = cjson.decode(ARGV[3]) .. item.requestId
            redis.call('SET', statusKey, ARGV[4])
            redis.call('EXPIRE', statusKey, ARGV[5])
            redis.call('SET', ownerKey, userId)
            redis.call('EXPIRE', ownerKey, ARGV[5])
            redis.call('SREM', KEYS[3], userId)
        end
        return 1
        """, Long.class);

    /** 回收超时消息时原子恢复等待状态、所有者和用户去重占位。 */
    private static final RedisScript<Long> RECOVER_STALE_SCRIPT = new DefaultRedisScript<>("""
        local function decodeItem(raw)
            local ok, value = pcall(cjson.decode, raw)
            if not ok then return nil end
            if type(value) == 'string' then
                ok, value = pcall(cjson.decode, value)
                if not ok then return nil end
            end
            if type(value) ~= 'table' then return nil end
            return value
        end
        local items = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
        local recovered = 0
        for _, item in ipairs(items) do
            if redis.call('ZREM', KEYS[1], item) == 1 then
                redis.call('RPUSH', KEYS[2], item)
                local decoded = decodeItem(item)
                if decoded and decoded.requestId and decoded.request and decoded.request.userId then
                    local userId = cjson.encode(tostring(decoded.request.userId))
                    local statusKey = cjson.decode(ARGV[2]) .. decoded.requestId
                    local ownerKey = cjson.decode(ARGV[3]) .. decoded.requestId
                    redis.call('SET', statusKey, ARGV[4])
                    redis.call('EXPIRE', statusKey, ARGV[5])
                    redis.call('SET', ownerKey, userId)
                    redis.call('EXPIRE', ownerKey, ARGV[5])
                    redis.call('SADD', KEYS[3], userId)
                end
                recovered = recovered + 1
            end
        end
        return recovered
        """, Long.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Value("${reservation.queue.batch-size:10}")
    private int batchSize = 10;

    @Value("${reservation.queue.claim-timeout-ms:300000}")
    private long claimTimeoutMs = 300_000;

    @Value("${reservation.status.expiry-hours:24}")
    private int statusExpiryHours = 24;

    private volatile boolean running = true;

    public enum RequestStatus {
        QUEUED, PROCESSING, SUCCESS, FAILED
    }

    /** 可被 Jackson 稳定反序列化的队列消息。 */
    static class QueueItem {
        public ReservationRequestDto request;
        public int attempts;
        public String requestId;

        public QueueItem() {
        }

        QueueItem(ReservationRequestDto request, int attempts, String requestId) {
            this.request = request;
            this.attempts = attempts;
            this.requestId = requestId;
        }
    }

    /** 同时保存解析后的对象和 Redis 中的原始 JSON，以便精确确认同一条消息。 */
    private record ClaimedQueueItem(QueueItem item, String rawJson) {
    }

    public ReservationQueueService(
        RedisTemplate<String, Object> redisTemplate,
        ReservationService reservationService,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.reservationService = reservationService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;

        meterRegistry.gauge("reservation.queue.length", this, ReservationQueueService::getQueueLength);
        meterRegistry.gauge("reservation.queue.processing.length", this, ReservationQueueService::getProcessingLength);
        meterRegistry.gauge("reservation.dlq.length", this, ReservationQueueService::getDLQLength);
    }

    /** 原子加入队列，避免并发请求同时通过“先查询、后写入”的去重检查。 */
    public String enqueueReservationRequest(ReservationRequestDto request) {
        if (request == null || request.getUserId() == null || request.getMode() == null
                || (request.getMode() == ReservationMode.MANUAL && request.getSlotId() == null)) {
            throw new BusinessException("Invalid reservation queue request");
        }
        String requestId = UUID.randomUUID().toString();
        String statusKey = STATUS_KEY_PREFIX + requestId;
        String ownerKey = STATUS_OWNER_KEY_PREFIX + requestId;

        try {
            String json = objectMapper.writeValueAsString(new QueueItem(request, 0, requestId));
            Long result = redisTemplate.execute(
                ENQUEUE_SCRIPT,
                List.of(QUEUE_KEY, USER_SET_KEY, statusKey, ownerKey),
                request.getUserId().toString(), json, RequestStatus.QUEUED.name(), statusExpirySeconds()
            );

            if (!Long.valueOf(1).equals(result)) {
                throw new DuplicateReservationException("A reservation request for this user is already in queue");
            }
            return requestId;
        } catch (DuplicateReservationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to enqueue reservation request for user {}", request.getUserId(), e);
            throw new BusinessException("Failed to process reservation request");
        }
    }

    boolean isUserAlreadyInQueue(Long userId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(USER_SET_KEY, userId.toString()));
    }

    /** 仅向请求所有者返回队列状态；不匹配时按不存在处理，避免泄露他人请求。 */
    public String getRequestStatus(String requestId, Long userId) {
        Object owner = redisTemplate.opsForValue().get(STATUS_OWNER_KEY_PREFIX + requestId);
        if (owner == null || userId == null || !owner.toString().equals(userId.toString())) {
            return null;
        }
        return getRawRequestStatus(requestId);
    }

    private String getRawRequestStatus(String requestId) {
        Object status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + requestId);
        return status != null ? status.toString() : null;
    }

    private ClaimedQueueItem claimNextItem() {
        String rawJson = redisTemplate.execute(
            CLAIM_SCRIPT, List.of(QUEUE_KEY, PROCESSING_KEY), System.currentTimeMillis()
        );
        if (rawJson == null) {
            return null;
        }

        try {
            return new ClaimedQueueItem(objectMapper.readValue(rawJson, QueueItem.class), rawJson);
        } catch (Exception e) {
            logger.error("Failed to deserialize queue item; moving raw message to DLQ: {}", rawJson, e);
            moveMalformedClaimToDLQ(rawJson);
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        logger.info("ReservationQueueService is shutting down. No new batches will be processed.");
    }

    public long getQueueLength() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    public long getProcessingLength() {
        Long size = redisTemplate.opsForZSet().size(PROCESSING_KEY);
        return size != null ? size : 0;
    }

    public long getDLQLength() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        return size != null ? size : 0;
    }

    /**
     * 判断该请求是否已经产生业务效果。
     *
     * <p>Redis 只是状态缓存层，PostgreSQL 才是最终事实源。
     * 当 Redis 的 SUCCESS 状态因进程崩溃或写入失败而丢失时，
     * 这里会以数据库中的 request_id 幂等键为准进行判定。</p>
     */
    private boolean isAlreadyProcessed(String requestId) {
        if (RequestStatus.SUCCESS.name().equals(getRawRequestStatus(requestId))) {
            return true;
        }
        return reservationService.hasProcessedRequest(requestId);
    }

    @Scheduled(fixedDelayString = "${reservation.queue.poll-interval-ms:100}")
    public void processReservationQueue() {
        if (!running) {
            return;
        }

        recoverStaleClaims();
        for (int i = 0; i < batchSize; i++) {
            ClaimedQueueItem claimed = claimNextItem();
            if (claimed == null) {
                break;
            }

            QueueItem item = claimed.item();
            if (!isValid(item)) {
                moveMalformedClaimToDLQ(claimed.rawJson());
                continue;
            }
            if (isAlreadyProcessed(item.requestId)) {
                complete(claimed, RequestStatus.SUCCESS);
                continue;
            }

            updateStatus(item.requestId, RequestStatus.PROCESSING);
            try {
                if (item.request.getMode() == ReservationMode.MANUAL) {
                    reservationService.reserveSlot(item.request.getUserId(), item.request.getSlotId(), item.requestId);
                } else {
                    reservationService.reserveNearestSlot(item.request.getUserId(), item.requestId);
                }
                meterRegistry.counter("reservation.queue.processed").increment();
                complete(claimed, RequestStatus.SUCCESS);
            } catch (RequestAlreadyProcessedException e) {
                // 数据库唯一约束已经证明该 requestId 的业务效果存在，按幂等成功处理。
                logger.info("Async request {} already persisted; treating as idempotent success", item.requestId);
                meterRegistry.counter("reservation.queue.idempotent").increment();
                if (reservationService.hasProcessedRequest(item.requestId)) {
                    complete(claimed, RequestStatus.SUCCESS);
                } else {
                    handleRetryableError(claimed, e, "idempotency");
                }
            } catch (DuplicateReservationException e) {
                logger.info("Skipping duplicate reservation for user: {}", item.request.getUserId());
                meterRegistry.counter("reservation.queue.duplicate").increment();
                complete(claimed, RequestStatus.FAILED);
            } catch (ReservationNotAvailableException e) {
                logger.info("No slots available for user: {}", item.request.getUserId());
                meterRegistry.counter("reservation.queue.no_slots").increment();
                complete(claimed, RequestStatus.FAILED);
            } catch (ReservationCapacityExceededException e) {
                handleRetryableError(claimed, e, "capacity_exceeded");
            } catch (BusinessException e) {
                handleRetryableError(claimed, e, "business_rule");
            } catch (Exception e) {
                handleRetryableError(claimed, e, "technical");
            }
        }
    }

    private boolean isValid(QueueItem item) {
        return item != null && item.requestId != null && item.request != null
            && item.request.getUserId() != null && item.request.getMode() != null
            && (item.request.getMode() != ReservationMode.MANUAL || item.request.getSlotId() != null);
    }

    private void updateStatus(String requestId, RequestStatus status) {
        String statusKey = STATUS_KEY_PREFIX + requestId;
        redisTemplate.opsForValue().set(statusKey, status.name());
        redisTemplate.expire(statusKey, statusExpirySeconds(), TimeUnit.SECONDS);
    }

    private void complete(ClaimedQueueItem claimed, RequestStatus status) {
        QueueItem item = claimed.item();
        Long completed = redisTemplate.execute(
            COMPLETE_SCRIPT,
            List.of(PROCESSING_KEY, STATUS_KEY_PREFIX + item.requestId, USER_SET_KEY,
                STATUS_OWNER_KEY_PREFIX + item.requestId),
            claimed.rawJson(), status.name(), item.request.getUserId().toString(), statusExpirySeconds()
        );
        if (!Long.valueOf(1).equals(completed)) {
            logger.warn("Completion skipped because processing claim no longer exists: {}", item.requestId);
        }
    }

    private void handleRetryableError(ClaimedQueueItem claimed, Exception error, String errorType) {
        QueueItem item = claimed.item();
        item.attempts++;
        logger.error("Failed to process reservation request (attempt {}, type: {}, requestId: {})",
            item.attempts, errorType, item.requestId, error);
        meterRegistry.counter("reservation.queue.process.errors." + errorType).increment();

        if (item.attempts >= MAX_ATTEMPTS) {
            moveToDLQ(claimed);
            return;
        }

        try {
            String updatedJson = objectMapper.writeValueAsString(item);
            Long requeued = redisTemplate.execute(
                REQUEUE_SCRIPT,
                List.of(PROCESSING_KEY, QUEUE_KEY, STATUS_KEY_PREFIX + item.requestId,
                    STATUS_OWNER_KEY_PREFIX + item.requestId),
                claimed.rawJson(), updatedJson, RequestStatus.QUEUED.name(), statusExpirySeconds(),
                item.request.getUserId().toString()
            );
            if (!Long.valueOf(1).equals(requeued)) {
                logger.warn("Retry skipped because processing claim no longer exists: {}", item.requestId);
            }
        } catch (Exception serializationError) {
            logger.error("Failed to serialize retry message: {}", item.requestId, serializationError);
            moveToDLQ(claimed);
        }
    }

    private void moveToDLQ(ClaimedQueueItem claimed) {
        QueueItem item = claimed.item();
        String dlqJson = claimed.rawJson();
        try {
            dlqJson = objectMapper.writeValueAsString(item);
        } catch (Exception e) {
            logger.error("Failed to serialize final DLQ message; preserving original payload: {}", item.requestId, e);
        }

        Long moved = redisTemplate.execute(
            DLQ_SCRIPT,
            List.of(PROCESSING_KEY, DLQ_KEY, STATUS_KEY_PREFIX + item.requestId, USER_SET_KEY,
                STATUS_OWNER_KEY_PREFIX + item.requestId),
            claimed.rawJson(), dlqJson, RequestStatus.FAILED.name(), item.request.getUserId().toString(),
            statusExpirySeconds()
        );
        if (Long.valueOf(1).equals(moved)) {
            meterRegistry.counter("reservation.dlq.moved").increment();
            logger.warn("Moved reservation request to DLQ: {}", item.requestId);
        }
    }

    private void moveMalformedClaimToDLQ(String rawJson) {
        Long moved = redisTemplate.execute(
            MALFORMED_TO_DLQ_SCRIPT, List.of(PROCESSING_KEY, DLQ_KEY, USER_SET_KEY),
            rawJson, STATUS_KEY_PREFIX, STATUS_OWNER_KEY_PREFIX, RequestStatus.FAILED.name(),
            statusExpirySeconds()
        );
        if (Long.valueOf(1).equals(moved)) {
            meterRegistry.counter("reservation.dlq.malformed").increment();
        }
    }

    long recoverStaleClaims() {
        long cutoff = System.currentTimeMillis() - claimTimeoutMs;
        Long recovered = redisTemplate.execute(
            RECOVER_STALE_SCRIPT, List.of(PROCESSING_KEY, QUEUE_KEY, USER_SET_KEY),
            cutoff, STATUS_KEY_PREFIX, STATUS_OWNER_KEY_PREFIX, RequestStatus.QUEUED.name(),
            statusExpirySeconds()
        );
        long count = recovered != null ? recovered : 0;
        if (count > 0) {
            meterRegistry.counter("reservation.queue.claims.recovered").increment(count);
            logger.warn("Recovered {} stale reservation queue claims", count);
        }
        return count;
    }

    private long statusExpirySeconds() {
        return TimeUnit.HOURS.toSeconds(statusExpiryHours);
    }
}
