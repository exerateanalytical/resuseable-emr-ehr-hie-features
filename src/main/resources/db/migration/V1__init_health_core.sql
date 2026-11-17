-- Reusable PostgreSQL schema for EMR / EHR / HIE Core
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. Multi-facility / Clinic tenant registry
CREATE TABLE facilities (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    facility_type   VARCHAR(50) NOT NULL, -- HOSPITAL, CLINIC, LAB, PHARMACY, HIE_NODE
    region          VARCHAR(100),
    city            VARCHAR(100),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Master Patient Index (MPI)
CREATE TABLE patients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    enterprise_id   VARCHAR(64) NOT NULL UNIQUE,
    given_name      VARCHAR(120) NOT NULL,
    family_name     VARCHAR(120) NOT NULL,
    date_of_birth   DATE NOT NULL,
    sex             VARCHAR(16) NOT NULL, -- MALE, FEMALE, OTHER, UNKNOWN
    primary_phone   VARCHAR(32),
    city            VARCHAR(100),
    address_line    VARCHAR(255),
    blood_group     VARCHAR(8),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patients_name_trgm
    ON patients USING GIN ((given_name || ' ' || family_name) gin_trgm_ops);
CREATE INDEX idx_patients_dob_phone ON patients (date_of_birth, primary_phone);

-- Cross-facility & national identifiers (CNI, Facility MRN, Insurance, NHIS)
CREATE TABLE patient_identifiers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id      UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    facility_id     UUID REFERENCES facilities(id),
    id_type         VARCHAR(40) NOT NULL, -- NATIONAL_ID, MRN, INSURANCE, PASSPORT
    id_value        VARCHAR(120) NOT NULL,
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (id_type, id_value, facility_id)
);

-- 3. HIE Consent Management (Opt-in / Opt-out + Scoped Sharing)
CREATE TABLE patient_consents (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id          UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    granting_facility_id UUID REFERENCES facilities(id),
    target_facility_id  UUID REFERENCES facilities(id), -- NULL = network-wide HIE
    status              VARCHAR(24) NOT NULL, -- ACTIVE, REVOKED, EXPIRED
    scope               VARCHAR(64) NOT NULL, -- FULL_SUMMARY, LABS_ONLY, MEDICATIONS_ONLY, EMERGENCY_ONLY
    purpose_of_use      VARCHAR(64) NOT NULL, -- TREATMENT, PAYMENT, PUBLIC_HEALTH
    effective_from      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    recorded_by         VARCHAR(120) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_consents_patient_status ON patient_consents (patient_id, status);

-- 4. EMR Clinical Encounters (Visits, Triage Vitals, SOAP Notes, ICD-10)
CREATE TABLE encounters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id      UUID NOT NULL REFERENCES patients(id),
    facility_id     UUID NOT NULL REFERENCES facilities(id),
    clinician_id    VARCHAR(120) NOT NULL,
    encounter_type  VARCHAR(40) NOT NULL, -- OUTPATIENT, INPATIENT, EMERGENCY, TELEMEDICINE
    status          VARCHAR(24) NOT NULL, -- PLANNED, IN_PROGRESS, COMPLETED, CANCELLED
    chief_complaint TEXT,
    soap_notes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    diagnosis_codes TEXT[] NOT NULL DEFAULT '{}', -- e.g. ICD-10 codes
    started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_encounters_patient_time ON encounters (patient_id, started_at DESC);
CREATE INDEX idx_encounters_facility ON encounters (facility_id, started_at DESC);

-- 5. Observations (LOINC-coded Vitals & Lab Results)
CREATE TABLE observations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id    UUID NOT NULL REFERENCES encounters(id) ON DELETE CASCADE,
    patient_id      UUID NOT NULL REFERENCES patients(id),
    facility_id     UUID NOT NULL REFERENCES facilities(id),
    loinc_code      VARCHAR(40) NOT NULL,
    display_name    VARCHAR(160) NOT NULL,
    value_numeric   NUMERIC(12, 4),
    value_text      TEXT,
    unit            VARCHAR(32),
    interpretation  VARCHAR(24), -- NORMAL, HIGH, LOW, CRITICAL
    observed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_observations_patient_loinc ON observations (patient_id, loinc_code, observed_at DESC);

-- 6. Transactional Outbox for Reliable HIE Sync (Offline / Flaky-Network Safe)
CREATE TABLE hie_outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    aggregate_type  VARCHAR(48) NOT NULL, -- PATIENT, ENCOUNTER, OBSERVATION
    aggregate_id    UUID NOT NULL,
    facility_id     UUID NOT NULL REFERENCES facilities(id),
    fhir_resource_type VARCHAR(48) NOT NULL,
    payload_json    JSONB NOT NULL,
    status          VARCHAR(24) NOT NULL DEFAULT 'PENDING', -- PENDING, SENT, FAILED, DEAD_LETTER
    attempt_count   INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at         TIMESTAMPTZ
);

CREATE INDEX idx_hie_outbox_pending ON hie_outbox_events (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- 7. Immutable Clinical Audit Trail (HIPAA / ATNA / Break-the-Glass)
CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id        VARCHAR(120) NOT NULL,
    actor_role      VARCHAR(64),
    facility_id     UUID,
    action          VARCHAR(64) NOT NULL, -- READ, CREATE, UPDATE, EXPORT_FHIR, BREAK_THE_GLASS
    resource_type   VARCHAR(64) NOT NULL,
    resource_id     VARCHAR(120),
    patient_id      UUID,
    outcome         VARCHAR(24) NOT NULL, -- SUCCESS, DENIED
    reason          TEXT,
    source_ip       VARCHAR(64),
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_patient_time ON audit_logs (patient_id, occurred_at DESC);
