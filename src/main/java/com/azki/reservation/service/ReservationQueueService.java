package com.azki.reservation.service;

import com.azki.reservation.dto.reservation.ReservationRequestDto;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.exception.ReservationCapacityExceededException;
import com.azki.reservation.exception.ReservationNotAvailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

import java.util.UUID;

/**
 * 基于 Redis 的异步预约队列服务。
 *
 * <p>控制器在负载较高时调用 {@link #enqueueReservationRequest(Object)}，把请求序列化为 JSON
 * 放入 Redis List；后台轮询器批量取出消息并委托 {@link ReservationService} 完成数据库写入。
 * 请求方可使用返回的 requestId 查询 QUEUED、PROCESSING、SUCCESS、FAILED 状态。</p>
 *
 * <p>Redis 在这里保存的是临时工作流状态，PostgreSQL 中的 Reservation 才是最终业务记录。</p>
 */
@Service
public class ReservationQueueService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final ReservationService reservationService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RedisCleanupService redisCleanupService;
    private static final Logger logger = LoggerFactory.getLogger(ReservationQueueService.class);
    /** 等待处理的主队列：右侧入队、左侧出队，形成 FIFO。 */
    private static final String QUEUE_KEY = "reservation:queue";
    /** 达到最大重试次数后存放失败消息的死信队列。 */
    private static final String DLQ_KEY = "reservation:dlq";
    /** 记录仍在队列中的邮箱，用于 O(1) 判断同一邮箱是否重复入队。 */
    private static final String EMAIL_SET_KEY = "reservation:emails:queued";
    private static final int MAX_ATTEMPTS = 3;
    /** 每个请求状态键的前缀，完整键为 reservation:status:{requestId}。 */
    private static final String STATUS_KEY_PREFIX = "reservation:status:";
    @Value("${reservation.queue.batch-size:10}")
    private int batchSize;

    private volatile boolean running = true;

    /** 异步请求从入队到结束可能经历的状态。 */
    public enum RequestStatus {
        QUEUED, PROCESSING, SUCCESS, FAILED
    }

    /**
     * Redis 队列中的消息结构。
     * 原始请求、已尝试次数和 requestId 必须一起序列化，才能支持状态跟踪和死信处理。
     */
    private static class QueueItem {
        public ReservationRequestDto request;
        public int attempts;
        public String requestId;


        public QueueItem(ReservationRequestDto request, int attempts, String requestId) {
            this.request = request;
            this.attempts = attempts;
            this.requestId = requestId;
        }
    }

    public String enqueueReservationRequest(Object reservationRequest) {
        // requestId 在进入 Redis 前生成，之后贯穿队列消息和状态键。
        String requestId = UUID.randomUUID().toString();
        try {
            // 当前公开调用方传入 ReservationRequestDto；这里保留 Object 签名但立即进行强制转换。
            ReservationRequestDto req = (ReservationRequestDto) reservationRequest;
            if (isUserAlreadyInQueue(req.getEmail())) {
                throw new DuplicateReservationException("A reservation request for this email is already in queue");
            }

            // 1. 消息入主队列；2. 初始化状态；3. 设置 TTL；4. 标记邮箱已排队。
            String json = objectMapper.writeValueAsString(new QueueItem((ReservationRequestDto) reservationRequest, 0, requestId));
            redisTemplate.opsForList().rightPush(QUEUE_KEY, json);
            String statusKey = STATUS_KEY_PREFIX + requestId;
            redisTemplate.opsForValue().set(statusKey, RequestStatus.QUEUED.name());
            redisCleanupService.setExpiryOnStatusKey(statusKey);
            redisTemplate.opsForSet().add(EMAIL_SET_KEY, req.getEmail());
        } catch (DuplicateReservationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to serialize reservation request: {}", reservationRequest, e);
            throw new BusinessException("Failed to process reservation request: " + e.getMessage());
        }
        return requestId;
    }

    boolean isUserAlreadyInQueue(String email) {
        // Redis Set 的成员查询是常数时间，不需要扫描整个队列。
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(EMAIL_SET_KEY, email));
    }

    /** 根据 requestId 读取异步处理状态；键不存在或已过期时返回 null。 */
    public String getRequestStatus(String requestId) {
        Object status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + requestId);
        return status != null ? status.toString() : null;
    }

    private QueueItem dequeueQueueItem() {
        // 从列表左侧弹出最早进入的消息；leftPop 会立即把消息从主队列删除。
        Object jsonObj = redisTemplate.opsForList().leftPop(QUEUE_KEY);
        if (jsonObj instanceof String json) {
            try {
                return objectMapper.readValue(json, QueueItem.class);
            } catch (Exception e) {
                logger.error("Failed to deserialize queue item: {}", json, e);
            }
        }
        return null;
    }

    private void moveToDLQ(QueueItem item) {
        try {
            // DLQ 保留完整消息和 attempts，便于人工排查或后续重新投递。
            String json = objectMapper.writeValueAsString(item);
            redisTemplate.opsForList().rightPush(DLQ_KEY, json);
            meterRegistry.counter("reservation.dlq.moved").increment();
            logger.warn("Moved reservation request to DLQ: {}", item.request);
        } catch (Exception e) {
            logger.error("Failed to move reservation request to DLQ: {}", item, e);
        }
    }

    /** 应用关闭时停止领取新批次，让正在执行的方法自然结束。 */
    @PreDestroy
    public void shutdown() {
        running = false;
        logger.info("ReservationQueueService is shutting down. No new batches will be processed.");
    }

    /** @return 当前主队列中的待处理消息数，Redis 返回 null 时按 0 处理 */
    public long getQueueLength() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /** @return 当前死信队列中的消息数，供指标和健康检查使用 */
    public long getDLQLength() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        return size != null ? size : 0;
    }

    /**
     * 检查同一 requestId 是否已经成功，防止重复消息再次创建预约。
     * 这是请求级保护；业务层还会按用户未来预约进行第二层防重复。
     */
    private boolean isAlreadyProcessed(String requestId) {
        String status = getRequestStatus(requestId);
        return RequestStatus.SUCCESS.name().equals(status);
    }

    public ReservationQueueService(
        RedisTemplate<String, Object> redisTemplate,
        ReservationService reservationService,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        RedisCleanupService redisCleanupService
    ) {
        this.redisTemplate = redisTemplate;
        this.reservationService = reservationService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.redisCleanupService = redisCleanupService;
        // Gauge 保存对当前服务的引用，每次抓取指标时动态读取 Redis 队列长度。
        meterRegistry.gauge("reservation.queue.length", this, ReservationQueueService::getQueueLength);
        meterRegistry.gauge("reservation.dlq.length", this, ReservationQueueService::getDLQLength);
    }

    @Scheduled(fixedDelayString = "${reservation.queue.poll-interval-ms:100}")
    public void processReservationQueue() {
        // fixedDelay 表示上一次执行结束后再等待指定毫秒；每轮最多处理 batchSize 条。
        if (!running) return;
        for (int i = 0; i < batchSize; i++) {
            QueueItem item = dequeueQueueItem();
            if (item == null) break;

            String requestId = item.requestId;
            if (requestId != null) {
                if (isAlreadyProcessed(requestId)) {
                    // 消息已由 leftPop 移出，此处只需跳过重复处理。
                    continue;
                }
                // 在真正调用数据库服务前标记 PROCESSING，并刷新状态键 TTL。
                String statusKey = STATUS_KEY_PREFIX + requestId;
                redisTemplate.opsForValue().set(statusKey, RequestStatus.PROCESSING.name());
                redisCleanupService.setExpiryOnStatusKey(statusKey);
            }

            try {
                // 异步路径与同步路径共用同一领域服务，避免两套预约规则产生差异。
                reservationService.reserveNearestSlot(item.request.getEmail());
                meterRegistry.counter("reservation.queue.processed").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.SUCCESS.name());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // 请求已经结束，释放邮箱去重标记，允许该用户未来再次发起请求。
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (DuplicateReservationException e) {
                // 重复预约属于确定性的业务结果，重试不会改变结果，因此直接失败。
                logger.info("Skipping duplicate reservation: {}", item.request.getEmail());
                meterRegistry.counter("reservation.queue.duplicate").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.FAILED.name() + ": " + e.getMessage());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // 失败已经终结，同样需要释放邮箱去重标记。
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (ReservationNotAvailableException e) {
                // 没有可用时段也属于不可重试结果，直接记录 FAILED。
                logger.info("No slots available for reservation: {}", item.request.getEmail());
                meterRegistry.counter("reservation.queue.no_slots").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.FAILED.name() + ": " + e.getMessage());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // 请求终结后从 queued email 集合移除。
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (ReservationCapacityExceededException e) {
                // 高并发容量错误可能是暂时的，进入统一重试流程。
                handleRetryableError(item, requestId, e, "capacity_exceeded");
            } catch (BusinessException e) {
                // 其余业务异常按当前设计也会尝试重试。
                handleRetryableError(item, requestId, e, "business_rule");
            } catch (Exception e) {
                // 未分类的技术异常（数据库、序列化等）按技术错误记录指标。
                handleRetryableError(item, requestId, e, "technical");
            }
        }
    }

    private void handleRetryableError(QueueItem item, String requestId, Exception e, String errorType) {
        // attempts 表示该消息已经失败的次数，而不是还可重试的次数。
        item.attempts++;
        logger.error("Failed to process reservation request (attempt {}, type: {}): {}", item.attempts, errorType, item.request, e);
        meterRegistry.counter("reservation.queue.process.errors." + errorType).increment();
        if (item.attempts >= MAX_ATTEMPTS) {
            // 重试耗尽：保留到 DLQ、写最终状态并解除邮箱占用。
            moveToDLQ(item);
            if (requestId != null) {
                redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId,
                    RequestStatus.FAILED.name() + ": " + e.getMessage());
            }
            // 请求已终结，允许相同邮箱重新提交新请求。
            redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            // 注意：当前消息在 dequeueQueueItem 中已经 leftPop；这里再次 leftPop 会删除下一条消息。
            redisTemplate.opsForList().leftPop(QUEUE_KEY);
        } else {
            try {
                // 注意：当前消息已被弹出，set(0) 修改的是下一条消息，并非把当前消息重新入队。
                // 此处保留现有行为但明确标注风险，后续应改成原子重新入队或 Redis Streams ACK 模型。
                String updatedJson = objectMapper.writeValueAsString(item);
                redisTemplate.opsForList().set(QUEUE_KEY, 0, updatedJson);
            } catch (Exception ex) {
                logger.error("Failed to re-enqueue reservation request: {}", item, ex);
                moveToDLQ(item);
                if (requestId != null) {
                    redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, RequestStatus.FAILED.name());
                }
                // 无法重新序列化时只能终结请求并清理邮箱标记。
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
                // 当前实现会再次移除队首，存在误删下一条消息的风险。
                redisTemplate.opsForList().leftPop(QUEUE_KEY);
            }
        }
    }
}
