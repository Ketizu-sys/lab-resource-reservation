package com.azki.reservation.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

import java.util.Arrays;

/**
 * 应用层通用日志切面。
 * 统一记录 Controller、Service、Repository 的入参、返回值、异常和执行耗时，
 * 从而避免在每个业务方法中重复编写相同的诊断代码。
 */
@Aspect
@Component
public class LoggingAspect {

    /** 匹配所有带 Repository、Service 或 RestController 注解的 Spring Bean。 */
    @Pointcut("within(@org.springframework.stereotype.Repository *)" +
            " || within(@org.springframework.stereotype.Service *)" +
            " || within(@org.springframework.web.bind.annotation.RestController *)")
    public void springBeanPointcut() {
        // 切点方法只用于声明匹配范围，不需要方法体。
    }

    /** 匹配本项目包下的类型，并排除切面自身，避免日志切面递归拦截。 */
    @Pointcut("within(com.azki.reservation..*)" +
            " && !within(com.azki.reservation.aspect..*)")
    public void applicationPackagePointcut() {
        // 切点的实际行为由下面的通知方法实现。
    }

    /** 使用被拦截类的名称创建 Logger，便于按业务类检索日志。 */
    private Logger logger(JoinPoint joinPoint) {
        return LoggerFactory.getLogger(joinPoint.getSignature().getDeclaringTypeName());
    }

    /** DEBUG 级别记录方法进入和参数。 */
    @Before("applicationPackagePointcut() && springBeanPointcut()")
    public void logBefore(JoinPoint joinPoint) {
        Logger log = logger(joinPoint);
        if (log.isDebugEnabled()) {
            log.debug("Enter: {}.{}() with arguments = {}",
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName(),
                Arrays.toString(joinPoint.getArgs()));
        }
    }

    /** DEBUG 级别记录正常返回的方法及结果。 */
    @AfterReturning(pointcut = "applicationPackagePointcut() && springBeanPointcut()", returning = "result")
    public void logAfterReturning(JoinPoint joinPoint, Object result) {
        Logger log = logger(joinPoint);
        if (log.isDebugEnabled()) {
            log.debug("Exit: {}.{}() with result = {}",
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName(),
                result);
        }
    }

    /** ERROR 级别记录异常摘要，DEBUG 级别追加完整堆栈。 */
    @AfterThrowing(pointcut = "applicationPackagePointcut() && springBeanPointcut()", throwing = "e")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable e) {
        Logger log = logger(joinPoint);
        log.error("Exception in {}.{}() with cause = '{}'",
            joinPoint.getSignature().getDeclaringTypeName(),
            joinPoint.getSignature().getName(),
            e.getCause() != null ? e.getCause() : "NULL");

        if (log.isDebugEnabled()) {
            log.debug("Exception details: ", e);
        }
    }

    /**
     * 环绕执行目标方法并统计耗时。
     * TRACE 下记录全部调用；非 TRACE 时只把超过 500ms 的调用记为慢调用。
     */
    @Around("applicationPackagePointcut() && springBeanPointcut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Logger log = logger(joinPoint);
        if (log.isTraceEnabled()) {
            log.trace("Enter: {}.{}() with arguments = {}",
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName(),
                Arrays.toString(joinPoint.getArgs()));
        }

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        // 提前初始化，确保 finally 中即使发生异常也能安全引用。
        Object result = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (IllegalArgumentException e) {
            log.error("Illegal argument: {} in {}.{}()",
                Arrays.toString(joinPoint.getArgs()),
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName());
            throw e;
        } finally {
            stopWatch.stop();
            if (log.isTraceEnabled()) {
                log.trace("Exit: {}.{}() with result = {} in {}ms",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(),
                    result,
                    stopWatch.getTotalTimeMillis());
            } else if (log.isInfoEnabled() && stopWatch.getTotalTimeMillis() > 500) {
                log.info("Long execution time: {}.{}() took {}ms",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(),
                    stopWatch.getTotalTimeMillis());
            }
        }
    }
}
