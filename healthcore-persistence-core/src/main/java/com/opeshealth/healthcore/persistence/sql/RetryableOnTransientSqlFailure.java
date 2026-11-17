/*
 * =============================================================================
 * Opes HealthCore SDK — Transient SQL Deadlock Retry Annotation & Aspect
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sql;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declaratively retries idempotent service methods when PostgreSQL ({@code 40P01}),
 * Windows SQL Server ({@code 1205}), or MySQL ({@code 1213}) raises a transient deadlock
 * or serialization failure.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryableOnTransientSqlFailure {
    int maxAttempts() default 3;
    long initialBackoffMs() default 75L;
}
