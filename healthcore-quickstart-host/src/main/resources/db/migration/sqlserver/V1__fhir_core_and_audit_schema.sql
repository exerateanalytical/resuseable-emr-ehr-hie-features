-- =============================================================================
-- Opes HealthCore SDK — Flyway V1 Core Persistence & Security Audit Schema (SQL Server 2019/2022)
-- Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
-- Website            : https://www.opeshealthsystems.com | Douala, Cameroon
-- =============================================================================

IF OBJECT_ID(N'dbo.hc_outbox_events', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_outbox_events (
        id UNIQUEIDENTIFIER NOT NULL,
        idempotency_key VARCHAR(140) NOT NULL,
        facility_id UNIQUEIDENTIFIER NULL,
        aggregate_type VARCHAR(64) NOT NULL,
        aggregate_id UNIQUEIDENTIFIER NOT NULL,
        event_type VARCHAR(120) NOT NULL,
        payload_json NVARCHAR(MAX) NOT NULL,
        correlation_id VARCHAR(64) NULL,
        status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
        attempt_count INT NOT NULL DEFAULT 0,
        next_attempt_at DATETIMEOFFSET NOT NULL,
        last_error NVARCHAR(MAX) NULL,
        created_at DATETIMEOFFSET NOT NULL,
        sent_at DATETIMEOFFSET NULL,
        CONSTRAINT pk_hc_outbox_events PRIMARY KEY (id),
        CONSTRAINT uq_hc_outbox_idempotency UNIQUE (idempotency_key)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_outbox_claim' AND object_id = OBJECT_ID(N'dbo.hc_outbox_events'))
    CREATE INDEX idx_hc_outbox_claim ON dbo.hc_outbox_events (status, next_attempt_at, created_at);

IF OBJECT_ID(N'dbo.hc_inbound_idempotency', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_inbound_idempotency (
        id UNIQUEIDENTIFIER NOT NULL,
        idempotency_key VARCHAR(140) NOT NULL,
        request_hash VARCHAR(64) NOT NULL,
        http_status INT NOT NULL,
        response_body NVARCHAR(MAX) NULL,
        created_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_inbound_idempotency PRIMARY KEY (id),
        CONSTRAINT uq_hc_inbound_idempotency UNIQUE (idempotency_key)
    );
END;

IF OBJECT_ID(N'dbo.hc_audit_chain_heads', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_audit_chain_heads (
        facility_key VARCHAR(64) NOT NULL,
        last_hash VARCHAR(64) NOT NULL,
        sequence_number BIGINT NOT NULL,
        CONSTRAINT pk_hc_audit_chain_heads PRIMARY KEY (facility_key)
    );
END;

IF OBJECT_ID(N'dbo.hc_audit_logs', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_audit_logs (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_key VARCHAR(64) NOT NULL,
        sequence_number BIGINT NOT NULL,
        actor_id VARCHAR(120) NOT NULL,
        actor_role VARCHAR(64) NULL,
        action VARCHAR(64) NOT NULL,
        resource_type VARCHAR(64) NOT NULL,
        resource_id VARCHAR(120) NULL,
        patient_id UNIQUEIDENTIFIER NULL,
        outcome VARCHAR(24) NOT NULL,
        reason VARCHAR(500) NULL,
        source_ip VARCHAR(64) NULL,
        correlation_id VARCHAR(64) NULL,
        prev_hash VARCHAR(64) NOT NULL,
        entry_hash VARCHAR(64) NOT NULL,
        occurred_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_audit_logs PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_audit_facility_seq' AND object_id = OBJECT_ID(N'dbo.hc_audit_logs'))
    CREATE UNIQUE INDEX idx_hc_audit_facility_seq ON dbo.hc_audit_logs (facility_key, sequence_number);

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_audit_patient' AND object_id = OBJECT_ID(N'dbo.hc_audit_logs'))
    CREATE INDEX idx_hc_audit_patient ON dbo.hc_audit_logs (facility_key, patient_id, sequence_number);

IF OBJECT_ID(N'dbo.hc_consent_policies', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_consent_policies (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_key VARCHAR(64) NOT NULL,
        patient_id UNIQUEIDENTIFIER NOT NULL,
        scope VARCHAR(40) NOT NULL,
        decision VARCHAR(16) NOT NULL,
        sensitivity_tag VARCHAR(32) NOT NULL,
        grantee_ref VARCHAR(120) NULL,
        granted_at DATETIMEOFFSET NOT NULL,
        expires_at DATETIMEOFFSET NULL,
        revoked BIT NOT NULL DEFAULT 0,
        witness_or_signature_ref VARCHAR(256) NULL,
        CONSTRAINT pk_hc_consent_policies PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_consent_patient' AND object_id = OBJECT_ID(N'dbo.hc_consent_policies'))
    CREATE INDEX idx_hc_consent_patient ON dbo.hc_consent_policies (facility_key, patient_id, revoked);
