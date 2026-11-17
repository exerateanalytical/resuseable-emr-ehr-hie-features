/*
 * =============================================================================
 * Opes HealthCore SDK — Dialect-Aware Outbox Claim SPI & Implementations
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.outbox;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;

/**
 * Service Provider Interface (SPI) for claiming pending outbox rows safely across
 * concurrent application instances without lock contention or duplicate processing.
 */
public interface OutboxClaimStrategy {

    /**
     * Claims up to {@code batchSize} eligible outbox events due at or before {@code now}.
     * Must execute inside an active read-write transaction.
     */
    List<TransactionalOutboxEntity> claimPendingBatch(EntityManager em, Instant now, int batchSize);

    /** PostgreSQL &amp; MySQL 8+ strategy using ANSI-adjacent {@code FOR UPDATE SKIP LOCKED}. */
    final class SkipLockedClaimStrategy implements OutboxClaimStrategy {
        @Override
        @SuppressWarnings("unchecked")
        public List<TransactionalOutboxEntity> claimPendingBatch(EntityManager em, Instant now, int batchSize) {
            return em.createNativeQuery("""
                    SELECT * FROM hc_outbox_events
                    WHERE status IN ('PENDING', 'FAILED')
                      AND next_attempt_at <= :now
                    ORDER BY created_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """, TransactionalOutboxEntity.class)
                    .setParameter("now", now)
                    .setParameter("batchSize", batchSize)
                    .getResultList();
        }
    }

    /**
     * Windows SQL Server (MSSQL) strategy using {@code TOP (:batchSize)} paired with
     * {@code WITH (UPDLOCK, READPAST, ROWLOCK)} so parallel pods skip locked rows cleanly.
     */
    final class SqlServerReadPastClaimStrategy implements OutboxClaimStrategy {
        @Override
        @SuppressWarnings("unchecked")
        public List<TransactionalOutboxEntity> claimPendingBatch(EntityManager em, Instant now, int batchSize) {
            return em.createNativeQuery("""
                    SELECT TOP (:batchSize) * FROM hc_outbox_events WITH (UPDLOCK, READPAST, ROWLOCK)
                    WHERE status IN ('PENDING', 'FAILED')
                      AND next_attempt_at <= :now
                    ORDER BY created_at ASC
                    """, TransactionalOutboxEntity.class)
                    .setParameter("now", now)
                    .setParameter("batchSize", batchSize)
                    .getResultList();
        }
    }
}
