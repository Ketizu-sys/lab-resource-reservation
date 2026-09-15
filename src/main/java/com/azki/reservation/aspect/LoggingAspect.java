package com.azki.reservation.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * 应用层通用日志切面。
 * 统一记录 Controller、Service、Repository 的异常和执行耗时。
 * 不输出方法参数和返回值，避免密码、令牌等敏感字段进入普通日志。
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

    /** ERROR 级别只记录异常类型，DEBUG 级别才追加完整堆栈。 */
    @AfterThrowing(pointcut = "applicationPackagePointcut() && springBeanPointcut()", throwing = "e")
    public void logAfterThrowing(JoinPoint joinPoint, Throwable e) {
        Logger log = logger(joinPoint);
        log.error("Exception in {}.{}(): {}",
            joinPoint.getSignature().getDeclaringTypeName(),
            joinPoint.getSignature().getName(),
            e.getClass().getSimpleName());

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
            log.trace("Enter: {}.{}()",
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName());
        }

        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        try {
            return joinPoint.proceed();
        } finally {
            stopWatch.stop();
            if (log.isTraceEnabled()) {
                log.trace("Exit: {}.{}() in {}ms",
                    joinPoint.getSignature().getDeclaringTypeName(),
                    joinPoint.getSignature().getName(),
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
