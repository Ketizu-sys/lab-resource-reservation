package com.azki.reservation.exception;

/**
 * 数据库唯一约束已经拒绝过同一个 requestId 时抛出。
 *
 * <p>Redis 队列采用 at-least-once 投递，极端并发下两个消费者可能同时处理同一条消息。
 * 此时 uk_reservation_request_id 会拒绝第二次写入；该异常把这种“冲突”标识为
 * “另一个消费者已经完成”，由调用方按数据库结果恢复为幂等成功，而不是当成业务失败。</p>
 */
public class RequestAlreadyProcessedException extends RuntimeException {

    private final String requestId;

    public RequestAlreadyProcessedException(String requestId) {
        super("Reservation request has already been processed: " + requestId);
        this.requestId = requestId;
    }

    public String getRequestId() {
        return requestId;
    }
}
