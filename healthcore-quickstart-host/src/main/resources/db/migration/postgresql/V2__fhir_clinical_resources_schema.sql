-- =============================================================================
-- Opes HealthCore SDK — Flyway V2 FHIR R4 Clinical Resources Schema (PostgreSQL 16+)
-- Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
-- Website            : https://www.opeshealthsystems.com | Douala, Cameroon
-- =============================================================================

CREATE TABLE IF NOT EXISTS hc_patients (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    mrn VARCHAR(64) NOT NULL,
    name_prefix VARCHAR(24),
    family_name VARCHAR(120) NOT NULL,
    given_name VARCHAR(120) NOT NULL,
    middle_name VARCHAR(120),
    birth_date DATE NOT NULL,
    gender VARCHAR(16) NOT NULL,
    blood_group VARCHAR(8),
    primary_phone VARCHAR(64),
    secondary_phone VARCHAR(64),
    email VARCHAR(160),
    marital_status_code VARCHAR(16),
    preferred_language VARCHAR(16) DEFAULT 'en',
    golden_record BOOLEAN NOT NULL DEFAULT TRUE,
    surviving_patient_id UUID,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    deceased BOOLEAN NOT NULL DEFAULT FALSE,
    deceased_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_patients PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_hc_patient_fac_mrn ON hc_patients (facility_id, mrn);
CREATE INDEX IF NOT EXISTS idx_hc_patient_dob_gender ON hc_patients (birth_date, gender);
CREATE INDEX IF NOT EXISTS idx_hc_patient_fac_email ON hc_patients (facility_id, email);
CREATE INDEX IF NOT EXISTS idx_hc_patient_fac_active ON hc_patients (facility_id, active);

CREATE TABLE IF NOT EXISTS hc_patient_identifiers (
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    identifier_system VARCHAR(120) NOT NULL,
    identifier_use VARCHAR(24) DEFAULT 'OFFICIAL',
    type_code VARCHAR(32),
    assigner_name VARCHAR(120),
    encrypted_value VARCHAR(512) NOT NULL,
    blind_index VARCHAR(88) NOT NULL,
    biometric_template_hash VARCHAR(128),
    CONSTRAINT pk_hc_patient_identifiers PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_pat_ident_blind ON hc_patient_identifiers (identifier_system, blind_index);

CREATE TABLE IF NOT EXISTS hc_patient_demographics (
    id UUID NOT NULL,
    patient_id UUID NOT NULL,
    address_use VARCHAR(24) DEFAULT 'HOME',
    address_line VARCHAR(250),
    city_or_village VARCHAR(120),
    district VARCHAR(120),
    region_or_state VARCHAR(120),
    postal_code VARCHAR(32),
    country VARCHAR(64),
    emergency_contact_name VARCHAR(160),
    emergency_contact_phone VARCHAR(64),
    emergency_contact_email VARCHAR(160),
    emergency_relationship VARCHAR(40),
    communication_preferred BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_hc_patient_demographics PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_pat_demog_patient ON hc_patient_demographics (patient_id);

CREATE TABLE IF NOT EXISTS hc_patient_phonetics (
    patient_id UUID NOT NULL,
    family_metaphone VARCHAR(16) NOT NULL,
    given_metaphone VARCHAR(16) NOT NULL,
    birth_year INT NOT NULL,
    CONSTRAINT pk_hc_patient_phonetics PRIMARY KEY (patient_id)
);

CREATE INDEX IF NOT EXISTS idx_hc_phonetic_block ON hc_patient_phonetics (family_metaphone, birth_year);

CREATE TABLE IF NOT EXISTS hc_patient_merges (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    source_patient_id UUID NOT NULL,
    surviving_patient_id UUID NOT NULL,
    merged_by VARCHAR(120) NOT NULL,
    reason VARCHAR(300),
    reversed BOOLEAN NOT NULL DEFAULT FALSE,
    merged_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_patient_merges PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS hc_practitioners (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    identifier_system VARCHAR(120),
    identifier_value VARCHAR(120),
    identifier_use VARCHAR(24) DEFAULT 'OFFICIAL',
    assigner_display VARCHAR(160),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    name_prefix VARCHAR(32),
    family_name VARCHAR(120) NOT NULL,
    given_name VARCHAR(120) NOT NULL,
    name_suffix VARCHAR(32),
    gender VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    birth_date DATE,
    phone VARCHAR(64),
    email VARCHAR(160),
    address_use VARCHAR(24) DEFAULT 'WORK',
    address_line VARCHAR(250),
    city VARCHAR(120),
    district VARCHAR(120),
    state VARCHAR(120),
    postal_code VARCHAR(32),
    country VARCHAR(64),
    qualification_code VARCHAR(64),
    qualification_display VARCHAR(160),
    qualification_issuer VARCHAR(160),
    communication_code VARCHAR(24) DEFAULT 'en',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_practitioners PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_practitioners_fac_active ON hc_practitioners (facility_id, active);
CREATE INDEX IF NOT EXISTS idx_hc_practitioners_fac_ident ON hc_practitioners (facility_id, identifier_system, identifier_value);
CREATE INDEX IF NOT EXISTS idx_hc_practitioners_name ON hc_practitioners (family_name, given_name);

CREATE TABLE IF NOT EXISTS hc_encounters (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    patient_id UUID NOT NULL,
    encounter_class VARCHAR(24) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attending_id VARCHAR(120),
    department_code VARCHAR(64),
    primary_icd10 VARCHAR(32),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT pk_hc_encounters PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_encounters_patient_start ON hc_encounters (patient_id, started_at);

CREATE TABLE IF NOT EXISTS hc_observations (
    id UUID NOT NULL,
    facility_id UUID NOT NULL,
    patient_id UUID,
    encounter_id UUID,
    practitioner_id UUID,
    identifier_system VARCHAR(120),
    identifier_value VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'final',
    category_system VARCHAR(160),
    category_code VARCHAR(64),
    category_display VARCHAR(160),
    code_system VARCHAR(160),
    code VARCHAR(64) NOT NULL,
    code_display VARCHAR(200),
    code_text VARCHAR(250),
    subject_reference VARCHAR(160) NOT NULL,
    encounter_reference VARCHAR(160),
    performer_reference VARCHAR(160),
    performer_display VARCHAR(160),
    effective_at TIMESTAMPTZ,
    issued_at TIMESTAMPTZ,
    value_type VARCHAR(32),
    value_quantity DECIMAL(18,4),
    value_unit VARCHAR(48),
    value_unit_system VARCHAR(120),
    value_unit_code VARCHAR(48),
    value_string TEXT,
    value_boolean BOOLEAN,
    value_code_system VARCHAR(160),
    value_code VARCHAR(64),
    value_code_display VARCHAR(200),
    interpretation_code VARCHAR(32),
    interpretation_display VARCHAR(120),
    ref_range_low DECIMAL(18,4),
    ref_range_high DECIMAL(18,4),
    ref_range_text VARCHAR(160),
    note_text TEXT,
    components_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_hc_observations PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_hc_observations_fac_pat_eff ON hc_observations (facility_id, patient_id, effective_at);
CREATE INDEX IF NOT EXISTS idx_hc_observations_fac_code_st ON hc_observations (facility_id, code, status);
CREATE INDEX IF NOT EXISTS idx_hc_observations_fac_enc ON hc_observations (facility_id, encounter_id);
CREATE INDEX IF NOT EXISTS idx_hc_observations_fac_ident ON hc_observations (facility_id, identifier_system, identifier_value);
