-- =============================================================================
-- Opes HealthCore SDK — Flyway V2 FHIR R4 Clinical Resources Schema (SQL Server 2019/2022)
-- Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
-- Website            : https://www.opeshealthsystems.com | Douala, Cameroon
-- =============================================================================

IF OBJECT_ID(N'dbo.hc_patients', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_patients (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_id UNIQUEIDENTIFIER NOT NULL,
        mrn VARCHAR(64) NOT NULL,
        name_prefix VARCHAR(24) NULL,
        family_name VARCHAR(120) NOT NULL,
        given_name VARCHAR(120) NOT NULL,
        middle_name VARCHAR(120) NULL,
        birth_date DATE NOT NULL,
        gender VARCHAR(16) NOT NULL,
        blood_group VARCHAR(8) NULL,
        primary_phone VARCHAR(64) NULL,
        secondary_phone VARCHAR(64) NULL,
        email VARCHAR(160) NULL,
        marital_status_code VARCHAR(16) NULL,
        preferred_language VARCHAR(16) NULL DEFAULT 'en',
        golden_record BIT NOT NULL DEFAULT 1,
        surviving_patient_id UNIQUEIDENTIFIER NULL,
        active BIT NOT NULL DEFAULT 1,
        deceased BIT NOT NULL DEFAULT 0,
        deceased_at DATETIMEOFFSET NULL,
        version BIGINT NOT NULL DEFAULT 0,
        created_at DATETIMEOFFSET NOT NULL,
        updated_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_patients PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_patient_fac_mrn' AND object_id = OBJECT_ID(N'dbo.hc_patients'))
    CREATE UNIQUE INDEX idx_hc_patient_fac_mrn ON dbo.hc_patients (facility_id, mrn);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_patient_dob_gender' AND object_id = OBJECT_ID(N'dbo.hc_patients'))
    CREATE INDEX idx_hc_patient_dob_gender ON dbo.hc_patients (birth_date, gender);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_patient_fac_email' AND object_id = OBJECT_ID(N'dbo.hc_patients'))
    CREATE INDEX idx_hc_patient_fac_email ON dbo.hc_patients (facility_id, email);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_patient_fac_active' AND object_id = OBJECT_ID(N'dbo.hc_patients'))
    CREATE INDEX idx_hc_patient_fac_active ON dbo.hc_patients (facility_id, active);

IF OBJECT_ID(N'dbo.hc_patient_identifiers', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_patient_identifiers (
        id UNIQUEIDENTIFIER NOT NULL,
        patient_id UNIQUEIDENTIFIER NOT NULL,
        identifier_system VARCHAR(120) NOT NULL,
        identifier_use VARCHAR(24) NULL DEFAULT 'OFFICIAL',
        type_code VARCHAR(32) NULL,
        assigner_name VARCHAR(120) NULL,
        encrypted_value VARCHAR(512) NOT NULL,
        blind_index VARCHAR(88) NOT NULL,
        biometric_template_hash VARCHAR(128) NULL,
        CONSTRAINT pk_hc_patient_identifiers PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_pat_ident_blind' AND object_id = OBJECT_ID(N'dbo.hc_patient_identifiers'))
    CREATE INDEX idx_hc_pat_ident_blind ON dbo.hc_patient_identifiers (identifier_system, blind_index);

IF OBJECT_ID(N'dbo.hc_patient_demographics', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_patient_demographics (
        id UNIQUEIDENTIFIER NOT NULL,
        patient_id UNIQUEIDENTIFIER NOT NULL,
        address_use VARCHAR(24) NULL DEFAULT 'HOME',
        address_line VARCHAR(250) NULL,
        city_or_village VARCHAR(120) NULL,
        district VARCHAR(120) NULL,
        region_or_state VARCHAR(120) NULL,
        postal_code VARCHAR(32) NULL,
        country VARCHAR(64) NULL,
        emergency_contact_name VARCHAR(160) NULL,
        emergency_contact_phone VARCHAR(64) NULL,
        emergency_contact_email VARCHAR(160) NULL,
        emergency_relationship VARCHAR(40) NULL,
        communication_preferred BIT NOT NULL DEFAULT 1,
        CONSTRAINT pk_hc_patient_demographics PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_pat_demog_patient' AND object_id = OBJECT_ID(N'dbo.hc_patient_demographics'))
    CREATE INDEX idx_hc_pat_demog_patient ON dbo.hc_patient_demographics (patient_id);

IF OBJECT_ID(N'dbo.hc_patient_phonetics', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_patient_phonetics (
        patient_id UNIQUEIDENTIFIER NOT NULL,
        family_metaphone VARCHAR(16) NOT NULL,
        given_metaphone VARCHAR(16) NOT NULL,
        birth_year INT NOT NULL,
        CONSTRAINT pk_hc_patient_phonetics PRIMARY KEY (patient_id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_phonetic_block' AND object_id = OBJECT_ID(N'dbo.hc_patient_phonetics'))
    CREATE INDEX idx_hc_phonetic_block ON dbo.hc_patient_phonetics (family_metaphone, birth_year);

IF OBJECT_ID(N'dbo.hc_patient_merges', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_patient_merges (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_id UNIQUEIDENTIFIER NOT NULL,
        source_patient_id UNIQUEIDENTIFIER NOT NULL,
        surviving_patient_id UNIQUEIDENTIFIER NOT NULL,
        merged_by VARCHAR(120) NOT NULL,
        reason VARCHAR(300) NULL,
        reversed BIT NOT NULL DEFAULT 0,
        merged_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_patient_merges PRIMARY KEY (id)
    );
END;

IF OBJECT_ID(N'dbo.hc_practitioners', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_practitioners (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_id UNIQUEIDENTIFIER NOT NULL,
        identifier_system VARCHAR(120) NULL,
        identifier_value VARCHAR(120) NULL,
        identifier_use VARCHAR(24) NULL DEFAULT 'OFFICIAL',
        assigner_display VARCHAR(160) NULL,
        active BIT NOT NULL DEFAULT 1,
        name_prefix VARCHAR(32) NULL,
        family_name VARCHAR(120) NOT NULL,
        given_name VARCHAR(120) NOT NULL,
        name_suffix VARCHAR(32) NULL,
        gender VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
        birth_date DATE NULL,
        phone VARCHAR(64) NULL,
        email VARCHAR(160) NULL,
        address_use VARCHAR(24) NULL DEFAULT 'WORK',
        address_line VARCHAR(250) NULL,
        city VARCHAR(120) NULL,
        district VARCHAR(120) NULL,
        state VARCHAR(120) NULL,
        postal_code VARCHAR(32) NULL,
        country VARCHAR(64) NULL,
        qualification_code VARCHAR(64) NULL,
        qualification_display VARCHAR(160) NULL,
        qualification_issuer VARCHAR(160) NULL,
        communication_code VARCHAR(24) NULL DEFAULT 'en',
        version BIGINT NOT NULL DEFAULT 0,
        created_at DATETIMEOFFSET NOT NULL,
        updated_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_practitioners PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_practitioners_fac_active' AND object_id = OBJECT_ID(N'dbo.hc_practitioners'))
    CREATE INDEX idx_hc_practitioners_fac_active ON dbo.hc_practitioners (facility_id, active);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_practitioners_fac_ident' AND object_id = OBJECT_ID(N'dbo.hc_practitioners'))
    CREATE INDEX idx_hc_practitioners_fac_ident ON dbo.hc_practitioners (facility_id, identifier_system, identifier_value);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_practitioners_name' AND object_id = OBJECT_ID(N'dbo.hc_practitioners'))
    CREATE INDEX idx_hc_practitioners_name ON dbo.hc_practitioners (family_name, given_name);

IF OBJECT_ID(N'dbo.hc_encounters', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_encounters (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_id UNIQUEIDENTIFIER NOT NULL,
        patient_id UNIQUEIDENTIFIER NOT NULL,
        encounter_class VARCHAR(24) NOT NULL,
        status VARCHAR(32) NOT NULL,
        attending_id VARCHAR(120) NULL,
        department_code VARCHAR(64) NULL,
        primary_icd10 VARCHAR(32) NULL,
        started_at DATETIMEOFFSET NOT NULL,
        ended_at DATETIMEOFFSET NULL,
        CONSTRAINT pk_hc_encounters PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_encounters_patient_start' AND object_id = OBJECT_ID(N'dbo.hc_encounters'))
    CREATE INDEX idx_hc_encounters_patient_start ON dbo.hc_encounters (patient_id, started_at);

IF OBJECT_ID(N'dbo.hc_observations', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.hc_observations (
        id UNIQUEIDENTIFIER NOT NULL,
        facility_id UNIQUEIDENTIFIER NOT NULL,
        patient_id UNIQUEIDENTIFIER NULL,
        encounter_id UNIQUEIDENTIFIER NULL,
        practitioner_id UNIQUEIDENTIFIER NULL,
        identifier_system VARCHAR(120) NULL,
        identifier_value VARCHAR(120) NULL,
        status VARCHAR(32) NOT NULL DEFAULT 'final',
        category_system VARCHAR(160) NULL,
        category_code VARCHAR(64) NULL,
        category_display VARCHAR(160) NULL,
        code_system VARCHAR(160) NULL,
        code VARCHAR(64) NOT NULL,
        code_display VARCHAR(200) NULL,
        code_text VARCHAR(250) NULL,
        subject_reference VARCHAR(160) NOT NULL,
        encounter_reference VARCHAR(160) NULL,
        performer_reference VARCHAR(160) NULL,
        performer_display VARCHAR(160) NULL,
        effective_at DATETIMEOFFSET NULL,
        issued_at DATETIMEOFFSET NULL,
        value_type VARCHAR(32) NULL,
        value_quantity DECIMAL(18,4) NULL,
        value_unit VARCHAR(48) NULL,
        value_unit_system VARCHAR(120) NULL,
        value_unit_code VARCHAR(48) NULL,
        value_string NVARCHAR(MAX) NULL,
        value_boolean BIT NULL,
        value_code_system VARCHAR(160) NULL,
        value_code VARCHAR(64) NULL,
        value_code_display VARCHAR(200) NULL,
        interpretation_code VARCHAR(32) NULL,
        interpretation_display VARCHAR(120) NULL,
        ref_range_low DECIMAL(18,4) NULL,
        ref_range_high DECIMAL(18,4) NULL,
        ref_range_text VARCHAR(160) NULL,
        note_text NVARCHAR(MAX) NULL,
        components_json NVARCHAR(MAX) NULL,
        version BIGINT NOT NULL DEFAULT 0,
        created_at DATETIMEOFFSET NOT NULL,
        updated_at DATETIMEOFFSET NOT NULL,
        CONSTRAINT pk_hc_observations PRIMARY KEY (id)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_observations_fac_pat_eff' AND object_id = OBJECT_ID(N'dbo.hc_observations'))
    CREATE INDEX idx_hc_observations_fac_pat_eff ON dbo.hc_observations (facility_id, patient_id, effective_at);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_observations_fac_code_st' AND object_id = OBJECT_ID(N'dbo.hc_observations'))
    CREATE INDEX idx_hc_observations_fac_code_st ON dbo.hc_observations (facility_id, code, status);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_observations_fac_enc' AND object_id = OBJECT_ID(N'dbo.hc_observations'))
    CREATE INDEX idx_hc_observations_fac_enc ON dbo.hc_observations (facility_id, encounter_id);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_hc_observations_fac_ident' AND object_id = OBJECT_ID(N'dbo.hc_observations'))
    CREATE INDEX idx_hc_observations_fac_ident ON dbo.hc_observations (facility_id, identifier_system, identifier_value);
