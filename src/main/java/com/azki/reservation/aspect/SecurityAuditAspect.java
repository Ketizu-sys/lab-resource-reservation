package com.azki.reservation.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 安全审计日志切面。
 * 独立记录登录/注册尝试、成功结果、失败原因以及鉴权过滤器中的拒绝事件。
 */
@Aspect
@Component
@Order(1)
public class SecurityAuditAspect {

    private static final Logger securityLogger = LoggerFactory.getLogger("SECURITY_AUDIT");

    /** 匹配 AuthController 的所有认证接口。 */
    @Pointcut("execution(* com.azki.reservation.controller.AuthController.*(..))")
    public void authenticationPointcut() {
        // 仅声明切点。
    }

    /** 匹配 JwtFilter.doFilter，用于观察令牌鉴权事件。 */
    @Pointcut("execution(* com.azki.reservation.security.JwtFilter.doFilter(..))")
    public void authorizationPointcut() {
        // 仅声明切点。
    }

    /** 调用认证接口前记录尝试的用户名/邮箱。 */
    @Before("authenticationPointcut() && args(request,..)")
    public void logAuthenticationAttempt(JoinPoint joinPoint, Object request) {
        String methodName = joinPoint.getSignature().getName();

        // 尝试从不同认证 DTO 中提取邮箱，提取失败时降级为 unknown。
        String username = extractUsername(request);

        if (methodName.toLowerCase().contains("login")) {
            securityLogger.info("Authentication attempt for user: {}", username);
        } else if (methodName.toLowerCase().contains("register")) {
            securityLogger.info("Registration attempt for user: {}", username);
        }
    }

    /** 认证接口正常返回且响应为 2xx 时记录成功审计日志。 */
    @AfterReturning(pointcut = "authenticationPointcut() && args(request,..)", returning = "result", argNames = "joinPoint,request,result")
    public void logSuccessfulAuthentication(JoinPoint joinPoint, Object request, Object result) {
        String methodName = joinPoint.getSignature().getName();
        String username = extractUsername(request);

        if (methodName.toLowerCase().contains("login") && isSuccessful(result)) {
            securityLogger.info("Successful authentication for user: {}", username);
        } else if (methodName.toLowerCase().contains("register") && isSuccessful(result)) {
            securityLogger.info("Successful registration for user: {}", username);
        }
    }

    /** 认证接口抛异常时记录失败原因。 */
    @AfterThrowing(pointcut = "authenticationPointcut() && args(request,..)", throwing = "exception")
    public void logFailedAuthentication(JoinPoint joinPoint, Object request, Exception exception) {
        String methodName = joinPoint.getSignature().getName();
        String username = extractUsername(request);
        String failureReason = exception.getMessage();

        if (methodName.toLowerCase().contains("login")) {
            securityLogger.warn("Authentication failed for user: {}. Reason: {}", username, failureReason);
        } else if (methodName.toLowerCase().contains("register")) {
            securityLogger.warn("Registration failed for user: {}. Reason: {}", username, failureReason);
        }
    }

    /** JWT 过滤器抛异常时记录当前 SecurityContext 中的用户。 */
    @AfterThrowing(pointcut = "authorizationPointcut()", throwing = "exception")
    public void logAccessDenial(JoinPoint joinPoint, Exception exception) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : "anonymous";

        securityLogger.warn("Access denied for user: {}. Reason: {}", username, exception.getMessage());
    }

    /**
     * 通过反射调用请求对象的 getEmail，兼容多个具有相同属性的认证 DTO。
     * @return 请求中的邮箱；无法取得时返回 unknown
     */
    private String extractUsername(Object request) {
        try {
            // 反射避免让审计切面依赖某个具体 DTO 类型。
            return request.getClass().getMethod("getEmail").invoke(request).toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 判断返回对象是否代表成功；ResponseEntity 以 2xx 状态为准。 */
    private boolean isSuccessful(Object result) {
        if (result == null) {
            return false;
        }

        // 非 ResponseEntity 的正常返回暂按成功处理。
        try {
            if (result instanceof org.springframework.http.ResponseEntity) {
                org.springframework.http.ResponseEntity<?> response = (org.springframework.http.ResponseEntity<?>) result;
                return response.getStatusCode().is2xxSuccessful();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
