package com.azki.reservation.controller;

import com.azki.reservation.dto.ApiError;
import com.azki.reservation.exception.BusinessException;
import com.azki.reservation.exception.DuplicateReservationException;
import com.azki.reservation.exception.ReservationCapacityExceededException;
import com.azki.reservation.exception.ReservationNotAvailableException;
import com.azki.reservation.exception.ResourceNotFoundException;
import com.azki.reservation.exception.SlotNotFoundException;
import com.azki.reservation.exception.UserRegistrationConflictException;
import com.azki.reservation.exception.ReservationNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器。
 *
 * <p>把控制器抛出的业务异常、基础设施异常和未知异常转换成统一的
 * {@link ApiError} JSON 结构，并给出符合语义的 HTTP 状态码。</p>
 *
 * <p><strong>信息分级：</strong>ApiError 的 {@code error} 字段只使用本类定义的固定枚举式文案，
 * {@code message} 字段只放置面向调用方的可读说明。原始异常（含 SQL、约束名、
 * Hibernate/JDBC 内部信息、堆栈）只写入服务端日志，绝不下发给客户端。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String ERROR_BAD_REQUEST = "Bad Request";
    private static final String ERROR_NOT_FOUND = "Not Found";
    private static final String ERROR_FORBIDDEN = "Forbidden";
    private static final String ERROR_CONFLICT = "Conflict";
    private static final String ERROR_SERVICE_UNAVAILABLE = "Service Unavailable";
    private static final String ERROR_DATA_INTEGRITY = "Data Integrity Violation";
    private static final String ERROR_INTERNAL = "Internal Server Error";

    private static final String MESSAGE_DATA_INTEGRITY =
            "The request conflicts with existing data, please refresh and try again";
    private static final String MESSAGE_INTERNAL = "An unexpected error occurred";

    @ExceptionHandler(UserRegistrationConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleUserRegistrationConflict(
            UserRegistrationConflictException ex, HttpServletRequest request) {
        logger.warn("User registration conflict: {}", ex.getMessage());
        return buildErrorResponse(ERROR_CONFLICT, ex.getMessage(), HttpStatus.CONFLICT, request.getRequestURI());
    }

    @ExceptionHandler(DuplicateReservationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleDuplicateReservation(DuplicateReservationException ex, HttpServletRequest request) {
        logger.warn("Duplicate reservation attempt: {}", ex.getMessage());
        return buildErrorResponse(ERROR_CONFLICT, "A reservation already exists for this user",
                HttpStatus.CONFLICT, request.getRequestURI());
    }

    @ExceptionHandler(ReservationNotAvailableException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleReservationNotAvailable(ReservationNotAvailableException ex, HttpServletRequest request) {
        logger.warn("No available slots found: {}", ex.getMessage());
        return buildErrorResponse(ERROR_NOT_FOUND, "No available time slots found",
                HttpStatus.NOT_FOUND, request.getRequestURI());
    }

    @ExceptionHandler(ReservationCapacityExceededException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ResponseEntity<ApiError> handleCapacityExceeded(ReservationCapacityExceededException ex, HttpServletRequest request) {
        logger.error("System capacity exceeded: {}", ex.getMessage());
        return buildErrorResponse(ERROR_SERVICE_UNAVAILABLE, "System is currently at full capacity, please try again later",
                HttpStatus.SERVICE_UNAVAILABLE, request.getRequestURI());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiError> handleBusinessException(BusinessException ex, HttpServletRequest request) {
        logger.warn("Business rule violation: {}", ex.getMessage());
        return buildErrorResponse(ERROR_BAD_REQUEST, ex.getMessage(),
                HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(ERROR_NOT_FOUND, ex.getMessage(), HttpStatus.NOT_FOUND, request.getRequestURI());
    }

    @ExceptionHandler(SlotNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleSlotNotFound(SlotNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(ERROR_NOT_FOUND, ex.getMessage(), HttpStatus.NOT_FOUND, request.getRequestURI());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<ApiError> handleReservationNotFound(ReservationNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(ERROR_NOT_FOUND, ex.getMessage(), HttpStatus.NOT_FOUND, request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return buildErrorResponse(ERROR_FORBIDDEN, "Access denied", HttpStatus.FORBIDDEN, request.getRequestURI());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleOptimisticLockingFailure(OptimisticLockingFailureException ex, HttpServletRequest request) {
        logger.warn("Concurrent modification detected: {}", ex.getMessage());
        return buildErrorResponse(ERROR_CONFLICT, "The resource was modified by another request, please try again",
                HttpStatus.CONFLICT, request.getRequestURI());
    }

    /**
     * 数据库完整性异常专用出口。
     *
     * <p>唯一约束、外键约束、非空约束等都属于数据冲突，语义上更接近 409。
     * 这里必须使用固定文案，因为原始异常消息会包含 SQL、约束名和 JDBC 内部信息。</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        logger.error("Database integrity violation at {}", request.getRequestURI(), ex);
        return buildErrorResponse(ERROR_DATA_INTEGRITY, MESSAGE_DATA_INTEGRITY,
                HttpStatus.CONFLICT, request.getRequestURI());
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ResponseEntity<ApiError> handleRedisConnectionFailure(RedisConnectionFailureException ex, HttpServletRequest request) {
        logger.error("Redis connection failure: {}", ex.getMessage());
        return buildErrorResponse(ERROR_SERVICE_UNAVAILABLE, "Service temporarily unavailable",
                HttpStatus.SERVICE_UNAVAILABLE, request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiError> handleValidationErrors(MethodArgumentNotValidException ex, HttpServletRequest request) {
        logger.warn("Validation failure: {}", ex.getMessage());

        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            validationErrors.put(error.getField(), error.getDefaultMessage())
        );

        ApiError apiError = new ApiError(
            HttpStatus.BAD_REQUEST.value(),
            "Validation error",
            validationErrors.toString(),
            request.getRequestURI());

        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<ApiError> handleInvalidSortProperty(
            PropertyReferenceException ex, HttpServletRequest request) {
        logger.warn("Invalid sort property: {}", ex.getPropertyName());
        return buildErrorResponse(ERROR_BAD_REQUEST, "Invalid sort property: " + ex.getPropertyName(),
                HttpStatus.BAD_REQUEST, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<ApiError> handleGenericException(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled exception occurred", ex);
        return buildErrorResponse(ERROR_INTERNAL, MESSAGE_INTERNAL,
                HttpStatus.INTERNAL_SERVER_ERROR, request.getRequestURI());
    }

    /**
     * 统一组装错误响应。
     *
     * @param error 面向客户端的固定错误分类，禁止传入 {@code ex.getMessage()}
     * @param message 面向客户端的可读说明，由各 handler 自己保证不含内部实现细节
     */
    private ResponseEntity<ApiError> buildErrorResponse(String error, String message,
                                                       HttpStatus status, String path) {
        ApiError apiError = new ApiError(
            status.value(),
            error,
            message,
            path);

        return new ResponseEntity<>(apiError, status);
    }
}
