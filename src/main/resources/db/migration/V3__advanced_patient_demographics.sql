-- Advanced FHIR R4 Patient Demographics & Multi-Valued Sub-Resources
ALTER TABLE patients
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS middle_names TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS name_prefix VARCHAR(32),
    ADD COLUMN IF NOT EXISTS name_suffix VARCHAR(32),
    ADD COLUMN IF NOT EXISTS marital_status_code VARCHAR(16),
    ADD COLUMN IF NOT EXISTS deceased_boolean BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deceased_date_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS multiple_birth_boolean BOOLEAN,
    ADD COLUMN IF NOT EXISTS multiple_birth_integer INT,
    ADD COLUMN IF NOT EXISTS preferred_language VARCHAR(16),
    ADD COLUMN IF NOT EXISTS general_practitioner_ref VARCHAR(120),
    ADD COLUMN IF NOT EXISTS managing_organization_id UUID REFERENCES facilities(id),
    ADD COLUMN IF NOT EXISTS fhir_extensions JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE patient_identifiers
    ADD COLUMN IF NOT EXISTS system_uri VARCHAR(255),
    ADD COLUMN IF NOT EXISTS use_code VARCHAR(24);

CREATE INDEX IF NOT EXISTS idx_patient_identifiers_system_val
    ON patient_identifiers (system_uri, id_value);

CREATE TABLE patient_telecoms (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id  UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    system      VARCHAR(24) NOT NULL, -- phone, email, sms, fax, url, other
    value       VARCHAR(160) NOT NULL,
    use_code    VARCHAR(24),          -- home, work, temp, old, mobile
    rank        INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patient_telecoms_patient ON patient_telecoms (patient_id);
CREATE INDEX idx_patient_telecoms_value ON patient_telecoms (value);

CREATE TABLE patient_addresses (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id   UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    use_code     VARCHAR(24), -- home, work, temp, old, billing
    address_type VARCHAR(24), -- postal, physical, both
    line1        VARCHAR(255),
    line2        VARCHAR(255),
    city         VARCHAR(100),
    district     VARCHAR(100),
    state_region VARCHAR(100),
    postal_code  VARCHAR(32),
    country      VARCHAR(64),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patient_addresses_patient ON patient_addresses (patient_id);
CREATE INDEX idx_patient_addresses_city ON patient_addresses (city);

CREATE TABLE patient_contacts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id           UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    relationship_code    VARCHAR(32) NOT NULL, -- e.g. C (Emergency), N (Next-of-Kin), PRN (Parent)
    relationship_display VARCHAR(100),
    given_name           VARCHAR(120),
    family_name          VARCHAR(120),
    gender               VARCHAR(16),
    phone                VARCHAR(32),
    email                VARCHAR(160),
    address_summary      VARCHAR(255),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patient_contacts_patient ON patient_contacts (patient_id);

CREATE TABLE patient_communications (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id       UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    language_code    VARCHAR(24) NOT NULL, -- BCP-47 e.g. fr, en, fr-CM, en-CM
    language_display VARCHAR(80),
    preferred        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patient_communications_patient ON patient_communications (patient_id);
