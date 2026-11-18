-- =============================================================================
-- Opes HealthCore SDK — Flyway V1 Core Persistence & Security Audit Schema (MySQL 8.4+)
-- Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
-- Website            : https://www.opeshealthsystems.com | Douala, Cameroon
-- =============================================================================

CREATE TABLE IF NOT EXISTS hc_outbox_events (
    id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(140) NOT NULL,
    facility_id BINARY(16),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    correlation_id VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6),
    CONSTRAINT pk_hc_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_hc_outbox_idempotency UNIQUE (idempotency_key),
    INDEX idx_hc_outbox_claim (status, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hc_inbound_idempotency (
    id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(140) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    http_status INT NOT NULL,
    response_body LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_hc_inbound_idempotency PRIMARY KEY (id),
    CONSTRAINT uq_hc_inbound_idempotency UNIQUE (idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hc_audit_chain_heads (
    facility_key VARCHAR(64) NOT NULL,
    last_hash VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    CONSTRAINT pk_hc_audit_chain_heads PRIMARY KEY (facility_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hc_audit_logs (
    id BINARY(16) NOT NULL,
    facility_key VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    actor_id VARCHAR(120) NOT NULL,
    actor_role VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(120),
    patient_id BINARY(16),
    outcome VARCHAR(24) NOT NULL,
    reason VARCHAR(500),
    source_ip VARCHAR(64),
    correlation_id VARCHAR(64),
    prev_hash VARCHAR(64) NOT NULL,
    entry_hash VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_hc_audit_logs PRIMARY KEY (id),
    UNIQUE INDEX idx_hc_audit_facility_seq (facility_key, sequence_number),
    INDEX idx_hc_audit_patient (facility_key, patient_id, sequence_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS hc_consent_policies (
    id BINARY(16) NOT NULL,
    facility_key VARCHAR(64) NOT NULL,
    patient_id BINARY(16) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    sensitivity_tag VARCHAR(32) NOT NULL,
    grantee_ref VARCHAR(120),
    granted_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6),
    revoked TINYINT(1) NOT NULL DEFAULT 0,
    witness_or_signature_ref VARCHAR(256),
    CONSTRAINT pk_hc_consent_policies PRIMARY KEY (id),
    INDEX idx_hc_consent_patient (facility_key, patient_id, revoked)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
