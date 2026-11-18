-- =============================================================================
-- Opes HealthCore SDK — Flyway V1 Core Persistence & Security Audit Schema (PostgreSQL 16+)
-- Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
-- Website            : https://www.opeshealthsystems.com | Douala, Cameroon
-- =============================================================================

CREATE TABLE IF NOT EXISTS hc_outbox_events (
    id UUID NOT NULL,
    idempotency_key VARCHAR(140) NOT NULL,
    facility_id UUID,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload_json TEXT NOT NULL,
    correlation_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ,
    CONSTRAINT pk_hc_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_hc_outbox_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_hc_outbox_claim
    ON hc_outbox_events (status, next_attempt_at, created_at);

CREATE TABLE IF NOT EXISTS hc_inbound_idempotency (
    id UUID NOT NULL,
    idempotency_key VARCHAR(140) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_inbound_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_hc_inbound_idempotency UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS hc_audit_chain_heads (
    facility_key VARCHAR(64) NOT NULL,
    last_hash VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    CONSTRAINT pk_hc_audit_chain_heads PRIMARY KEY (facility_key)
);

CREATE TABLE IF NOT EXISTS hc_audit_logs (
    id UUID NOT NULL,
    facility_key VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    actor_id VARCHAR(120) NOT NULL,
    actor_role VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(120),
    patient_id UUID,
    outcome VARCHAR(24) NOT NULL,
    reason VARCHAR(500),
    source_ip VARCHAR(64),
    correlation_id VARCHAR(64),
    prev_hash VARCHAR(64) NOT NULL,
    entry_hash VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_audit_logs PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_hc_audit_facility_seq
    ON hc_audit_logs (facility_key, sequence_number);

CREATE INDEX IF NOT EXISTS idx_hc_audit_patient
    ON hc_audit_logs (facility_key, patient_id, sequence_number);

CREATE TABLE IF NOT EXISTS hc_consent_policies (
    id UUID NOT NULL,
    facility_key VARCHAR(64) NOT NULL,
    patient_id UUID NOT NULL,
    scope VARCHAR(40) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    sensitivity_tag VARCHAR(32) NOT NULL,
    grantee_ref VARCHAR(120),
    granted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    witness_or_signature_ref VARCHAR(256),
    CONSTRAINT pk_hc_consent_policies PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_consent_patient
    ON hc_consent_policies (facility_key, patient_id, revoked);
