/*
 * =============================================================================
 * Opes HealthCore SDK — Configurable Clinical Specialty Extension Contract
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.util.*;

/**
 * Configurable specialty extension contract supporting Pack 05 (EMR) and Pack 06/07 (EHR/HIS)
 * without hardcoding specialty columns into core encounter tables.
 */
public final class SpecialtyExtensionContract {

    private SpecialtyExtensionContract() {}

    public enum ClinicalSpecialty {
        PEDIATRICS,
        CARDIOLOGY,
        DENTISTRY,
        OPHTHALMOLOGY,
        DERMATOLOGY,
        OBSTETRICS
    }

    public record SpecialtyFieldDefinition(
            String fieldKey,
            String label,
            String dataType,
            String ucumUnit,
            String terminologyBinding,
            boolean required,
            String conditionalRule
    ) {}

    public record SpecialtyExtensionTemplate(
            String templateId,
            ClinicalSpecialty specialty,
            String version,
            boolean signatureRequired,
            List<SpecialtyFieldDefinition> fields
    ) {}

    public static Map<ClinicalSpecialty, SpecialtyExtensionTemplate> defaultTemplates() {
        Map<ClinicalSpecialty, SpecialtyExtensionTemplate> map = new EnumMap<>(ClinicalSpecialty.class);
        map.put(ClinicalSpecialty.PEDIATRICS, new SpecialtyExtensionTemplate(
                "HC-SPEC-PED-V1", ClinicalSpecialty.PEDIATRICS, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("head_circumference_cm", "Head Circumference", "DECIMAL", "cm", "LOINC:9843-4", false, "ageMonths <= 36"),
                        new SpecialtyFieldDefinition("weight_for_age_zscore", "WHO Weight-for-Age Z-Score", "DECIMAL", "{Zscore}", "LOINC:77606-2", true, null),
                        new SpecialtyFieldDefinition("immunization_status", "EPI Immunization Status", "ENUM", null, "SNOMED:33879002", true, null)
                )));
        map.put(ClinicalSpecialty.CARDIOLOGY, new SpecialtyExtensionTemplate(
                "HC-SPEC-CARD-V1", ClinicalSpecialty.CARDIOLOGY, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("lvef_pct", "Left Ventricular Ejection Fraction", "DECIMAL", "%", "LOINC:10230-1", false, null),
                        new SpecialtyFieldDefinition("nyha_class", "NYHA Functional Class", "ENUM", null, "SNOMED:420816009", true, null),
                        new SpecialtyFieldDefinition("ecg_rhythm", "12-Lead ECG Rhythm", "STRING", null, "LOINC:8884-9", true, null)
                )));
        map.put(ClinicalSpecialty.DENTISTRY, new SpecialtyExtensionTemplate(
                "HC-SPEC-DENT-V1", ClinicalSpecialty.DENTISTRY, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("fdi_tooth_number", "FDI Tooth Number", "STRING", null, "SNOMED:38199008", true, null),
                        new SpecialtyFieldDefinition("dmft_index", "DMFT Caries Index", "INTEGER", "{score}", "LOINC:72166-2", false, null),
                        new SpecialtyFieldDefinition("periodontal_probing_mm", "Probing Depth", "DECIMAL", "mm", "SNOMED:251348009", false, null)
                )));
        map.put(ClinicalSpecialty.OPHTHALMOLOGY, new SpecialtyExtensionTemplate(
                "HC-SPEC-OPHTH-V1", ClinicalSpecialty.OPHTHALMOLOGY, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("visual_acuity_od", "Visual Acuity Right Eye (OD)", "STRING", null, "LOINC:79880-1", true, null),
                        new SpecialtyFieldDefinition("visual_acuity_os", "Visual Acuity Left Eye (OS)", "STRING", null, "LOINC:79881-9", true, null),
                        new SpecialtyFieldDefinition("iop_mmhg", "Intraocular Pressure", "DECIMAL", "mm[Hg]", "LOINC:56844-4", true, null)
                )));
        map.put(ClinicalSpecialty.DERMATOLOGY, new SpecialtyExtensionTemplate(
                "HC-SPEC-DERM-V1", ClinicalSpecialty.DERMATOLOGY, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("anatomical_site", "Lesion Anatomical Site", "STRING", null, "SNOMED:363698007", true, null),
                        new SpecialtyFieldDefinition("morphology", "Lesion Morphology", "STRING", null, "SNOMED:49755003", true, null),
                        new SpecialtyFieldDefinition("abcde_risk_score", "ABCDE Melanoma Risk Flag", "BOOLEAN", null, "SNOMED:254701007", false, null)
                )));
        map.put(ClinicalSpecialty.OBSTETRICS, new SpecialtyExtensionTemplate(
                "HC-SPEC-OB-V1", ClinicalSpecialty.OBSTETRICS, "1.0.0", true, List.of(
                        new SpecialtyFieldDefinition("gestational_age_weeks", "Gestational Age", "DECIMAL", "wk", "LOINC:18185-9", true, null),
                        new SpecialtyFieldDefinition("fetal_heart_rate_bpm", "Fetal Heart Rate", "INTEGER", "/min", "LOINC:55283-6", true, "gestationalAgeWeeks >= 12"),
                        new SpecialtyFieldDefinition("fundal_height_cm", "Symphysis-Fundal Height", "DECIMAL", "cm", "LOINC:11881-0", false, "gestationalAgeWeeks >= 20")
                )));
        return Collections.unmodifiableMap(map);
    }

    public static List<String> validateSubmission(SpecialtyExtensionTemplate template, Map<String, Object> submittedFields) {
        List<String> errors = new ArrayList<>();
        for (SpecialtyFieldDefinition field : template.fields()) {
            if (field.required()) {
                Object val = submittedFields == null ? null : submittedFields.get(field.fieldKey());
                if (val == null || val.toString().isBlank()) {
                    errors.add("Required specialty field missing for " + template.templateId() + ": " + field.fieldKey());
                }
            }
        }
        return errors;
    }
}
