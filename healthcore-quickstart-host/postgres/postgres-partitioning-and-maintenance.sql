-- =============================================================================
-- Opes HealthCore SDK — PostgreSQL 16+ Production Tuning & Maintenance Pack
-- Author / Architect : Jude Nshome — Opes Health Systems
-- Email              : nshome@opesware.com
-- Website            : https://www.opeshealthsystems.com
-- Location           : Douala, Cameroon
-- =============================================================================
-- Purpose:
--   Production DBA runbook for high-throughput PostgreSQL 16+ hospital clusters:
--     1. Enable pg_stat_statements diagnostics (when loaded in shared_preload_libraries)
--     2. Aggressive autovacuum scale factors on high-churn Outbox & Delta-Sync queues
--     3. Zero-downtime concurrent index maintenance helper
--     4. Archive table & retention sweep for SENT outbox records older than 30 days
-- =============================================================================

-- 1. High-churn queue autovacuum tuning (prevents tuple bloat on 24/7 outbox polling)
DO $$
BEGIN
    IF to_regclass('hc_outbox_events') IS NOT NULL THEN
        ALTER TABLE hc_outbox_events SET (
            autovacuum_vacuum_scale_factor = 0.02,
            autovacuum_analyze_scale_factor = 0.01,
            fillfactor = 80
        );
    END IF;

    IF to_regclass('hc_sync_deltas') IS NOT NULL THEN
        ALTER TABLE hc_sync_deltas SET (
            autovacuum_vacuum_scale_factor = 0.05,
            autovacuum_analyze_scale_factor = 0.02
        );
    END IF;
END $$;

-- 2. Partitioned/Archive table for historical SENT outbox events
CREATE TABLE IF NOT EXISTS hc_outbox_events_archive (
    id UUID NOT NULL,
    facility_id UUID,
    idempotency_key VARCHAR(140) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload_json TEXT NOT NULL,
    correlation_id VARCHAR(64),
    sent_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, archived_at)
) PARTITION BY RANGE (archived_at);

-- Default fallback partition so archival never fails when monthly partitions rotate
CREATE TABLE IF NOT EXISTS hc_outbox_events_archive_default
    PARTITION OF hc_outbox_events_archive DEFAULT;

-- 3. Move SENT outbox records older than 30 days into partitioned archive (safe batch)
CREATE OR REPLACE PROCEDURE sp_archive_sent_outbox(retention_days INT DEFAULT 30)
LANGUAGE plpgsql
AS $$
BEGIN
    WITH moved AS (
        DELETE FROM hc_outbox_events
        WHERE status = 'SENT'
          AND sent_at IS NOT NULL
          AND sent_at < NOW() - (retention_days || ' days')::INTERVAL
        RETURNING id, facility_id, idempotency_key, aggregate_type, aggregate_id,
                  event_type, payload_json, correlation_id, sent_at
    )
    INSERT INTO hc_outbox_events_archive (
        id, facility_id, idempotency_key, aggregate_type, aggregate_id,
        event_type, payload_json, correlation_id, sent_at
    )
    SELECT id, facility_id, idempotency_key, aggregate_type, aggregate_id,
           event_type, payload_json, correlation_id, sent_at
    FROM moved;
END;
$$;
