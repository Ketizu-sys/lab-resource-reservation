package com.azki.reservation.aspect;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

/**
 * 数据库访问专用日志切面。
 * 负责记录 Repository 耗时、事务提交/回滚以及数据访问异常。
 */
@Aspect
@Component
@Order(2)
public class DatabaseLoggingAspect {

    private static final Logger dbLogger = LoggerFactory.getLogger("DB_OPERATIONS");

    /** 匹配 repository 包中任意方法。 */
    @Pointcut("execution(* com.azki.reservation.repository.*.*(..))")
    public void repositoryMethods() {
        // 仅声明切点，不执行实际逻辑。
    }

    /** 匹配带 Transactional 注解的方法。 */
    @Pointcut("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void transactionalMethods() {
        // 仅声明切点，不执行实际逻辑。
    }

    /** 统计 Repository 方法耗时，并根据耗时选择不同日志级别。 */
    @Around("repositoryMethods()")
    public Object logQueryPerformance(ProceedingJoinPoint joinPoint) throws Throwable {
        String repoName = joinPoint.getSignature().getDeclaringTypeName();
        String methodName = joinPoint.getSignature().getName();
        StopWatch stopWatch = new StopWatch();

        dbLogger.debug("Database operation starting: {}.{}", repoName, methodName);
        stopWatch.start();

        Object result = null;
        try {
            result = joinPoint.proceed();
            return result;
        } finally {
            stopWatch.stop();
            long executionTime = stopWatch.getTotalTimeMillis();

            // 超过 1 秒视为慢查询，100ms～1 秒作为普通信息，其余只在 DEBUG 输出。
            if (executionTime > 1000) {
                dbLogger.warn("SLOW QUERY: {}.{} - execution time: {}ms",
                    repoName, methodName, executionTime);
            } else if (executionTime > 100) {
                dbLogger.info("Database operation completed: {}.{} - execution time: {}ms",
                    repoName, methodName, executionTime);
            } else {
                dbLogger.debug("Database operation completed: {}.{} - execution time: {}ms",
                    repoName, methodName, executionTime);
            }
            logAffectedRows(methodName, result);
        }
    }

    /** 捕获 Repository 或事务方法抛出的异常，数据库异常会额外提取 SQLState。 */
    @AfterThrowing(pointcut = "repositoryMethods() || transactionalMethods()", throwing = "exception")
    public void logDatabaseException(JoinPoint joinPoint, Exception exception) {
        String methodName = joinPoint.getSignature().getName();
        String typeName = joinPoint.getSignature().getDeclaringTypeName();

        if (exception instanceof DataAccessException) {
            dbLogger.error("Database error in {}.{}: {} - SQL state: {}",
                typeName,
                methodName,
                exception.getMessage(),
                extractSqlState((DataAccessException) exception));
        } else {
            dbLogger.error("Error during database operation {}.{}: {}",
                typeName, methodName, exception.getMessage());
        }
    }

    /** 在事务方法外围记录开始、提交和回滚边界。 */
    @Around("transactionalMethods()")
    public Object logTransactionBoundary(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        String typeName = joinPoint.getSignature().getDeclaringTypeName();

        dbLogger.debug("Transaction starting: {}.{}", typeName, methodName);

        try {
            Object result = joinPoint.proceed();
            dbLogger.debug("Transaction committed: {}.{}", typeName, methodName);
            return result;
        } catch (Exception e) {
            dbLogger.warn("Transaction rolled back: {}.{} due to: {}",
                typeName, methodName, e.getMessage());
            throw e;
        }
    }

    /** 根据方法名和返回值粗略记录数据修改或查询条数。 */
    private void logAffectedRows(String methodName, Object result) {
        if (methodName.startsWith("save") || methodName.startsWith("update")) {
            dbLogger.debug("Data modified: 1 row affected");
        } else if (methodName.startsWith("delete")) {
            dbLogger.debug("Data deleted");
        } else if (result instanceof Iterable) {
            int count = 0;
            for (Object ignored : (Iterable<?>) result) {
                count++;
            }
            if (count > 0) {
                dbLogger.debug("Data retrieved: {} rows", count);
            }
        }
    }

    /** 尝试从 Spring 数据访问异常的底层 SQLException 中提取 SQLState。 */
    private String extractSqlState(DataAccessException ex) {
        try {
            java.sql.SQLException sqlEx = (java.sql.SQLException) ex.getCause();
            return sqlEx != null ? sqlEx.getSQLState() : "Unknown";
        } catch (Exception e) {
            return "Unknown";
        }
    }
}
