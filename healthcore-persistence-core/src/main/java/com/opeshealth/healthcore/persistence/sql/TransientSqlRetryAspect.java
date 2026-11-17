/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-DB Deadlock Retry AOP Aspect
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sql;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Executes BEFORE Spring's {@code @Transactional} proxy ({@link Ordered#HIGHEST_PRECEDENCE})
 * so each retry opens a fresh database transaction cleanly after a deadlock rollback.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TransientSqlRetryAspect {

    @Around("@annotation(retryMeta)")
    public Object retryOnDeadlock(ProceedingJoinPoint pjp, RetryableOnTransientSqlFailure retryMeta) throws Throwable {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return pjp.proceed();
            } catch (Throwable ex) {
                if (attempt >= retryMeta.maxAttempts() || !isTransientDeadlock(ex)) {
                    throw ex;
                }
                long delay = retryMeta.initialBackoffMs() * (1L << (attempt - 1))
                        + ThreadLocalRandom.current().nextLong(5, 35);
                Thread.sleep(delay);
            }
        }
    }

    private boolean isTransientDeadlock(Throwable ex) {
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor instanceof DeadlockLoserDataAccessException
                    || cursor instanceof CannotAcquireLockException
                    || cursor instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (cursor instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                int code = sqlEx.getErrorCode();
                // PG: 40P01 / 40001 | MSSQL: 1205 | MySQL: 1213 / 1205
                if ("40P01".equals(state) || "40001".equals(state) || code == 1205 || code == 1213) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
