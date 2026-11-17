-- Patient Allergies & e-Prescribing (MedicationRequest) tables
CREATE TABLE patient_allergies (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id      UUID NOT NULL REFERENCES patients(id) ON DELETE CASCADE,
    substance_code  VARCHAR(64),
    substance_name  VARCHAR(160) NOT NULL,
    reaction        VARCHAR(200),
    severity        VARCHAR(24) NOT NULL, -- MILD, MODERATE, SEVERE, LIFE_THREATENING
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, RESOLVED
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_allergies_patient_active ON patient_allergies (patient_id, status);

CREATE TABLE prescriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    encounter_id    UUID NOT NULL REFERENCES encounters(id),
    patient_id      UUID NOT NULL REFERENCES patients(id),
    facility_id     UUID NOT NULL REFERENCES facilities(id),
    prescriber_id   VARCHAR(120) NOT NULL,
    medication_code VARCHAR(64), -- RxNorm / ATC / national formulary code
    medication_name VARCHAR(200) NOT NULL,
    dosage          VARCHAR(120) NOT NULL,
    frequency       VARCHAR(120) NOT NULL,
    duration_days   INT NOT NULL,
    quantity        INT NOT NULL,
    instructions    TEXT,
    status          VARCHAR(24) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, DISPENSED, CANCELLED
    allergy_override_reason TEXT,
    prescribed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prescriptions_patient ON prescriptions (patient_id, prescribed_at DESC);
