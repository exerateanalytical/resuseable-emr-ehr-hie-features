/*
 * =============================================================================
 * Opes HealthCore SDK — Standardized ISO / HL7 Compliant Synthetic Sample Data
 * =============================================================================
 */
package com.opeshealth.healthcore.testing.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Canonical, zero-PII, ISO &amp; HL7 standards-aligned sample data constants for
 * isolated and full-suite workflow verification across OPES HealthCore.
 *
 * <ul>
 *   <li><b>ISO 3166-1 alpha-2</b>: Country codes ({@code CM})</li>
 *   <li><b>ISO 4217</b>: Currency codes ({@code XAF})</li>
 *   <li><b>ISO 639-1</b>: Language tags ({@code fr}, {@code en})</li>
 *   <li><b>ISO 8601</b>: Dates and UTC timestamps</li>
 *   <li><b>HL7 FHIR R4</b>: Administrative gender, encounter class ({@code AMB}/{@code IMP}), IPS Bundle JSON, Claim</li>
 *   <li><b>HL7 v2.5.1</b>: Wire-format {@code ORM^O01} laboratory order message</li>
 *   <li><b>LOINC / UCUM</b>: {@code 2823-3} (Serum Potassium, {@code mmol/L}), {@code 718-7} (Hemoglobin, {@code g/dL}), {@code 24323-8}</li>
 *   <li><b>DICOM / CPT / ICD-10 / DRG / ISBT-128</b>: {@code CT}, {@code 71260}, {@code I10}, {@code E87.5}, {@code DRG-304}, {@code W000026990001}</li>
 * </ul>
 */
public final class IsoHl7StandardSampleData {

    private IsoHl7StandardSampleData() {}

    // ISO 3166-1 / ISO 4217 / ISO 639-1 Tenant Defaults
    public static final String ORG_CODE = "ISO-CM-NET-001";
    public static final String ORG_NAME = "Synthetic ISO Reference Health Network";
    public static final String FACILITY_CODE = "ISO-DLA-REF-001";
    public static final String FACILITY_NAME = "Synthetic Douala Reference Hospital";
    public static final String ISO_COUNTRY_CM = "CM";
    public static final String ISO_CURRENCY_XAF = "XAF";
    public static final String ISO_LANG_FR = "fr";
    public static final String ISO_LANG_EN = "en";
    public static final String IANA_TZ_DOUALA = "Africa/Douala";

    // HL7 FHIR R4 Synthetic Patients (zero real PII)
    public static final String PATIENT_AMB_MRN = "SYN-HL7-MRN-1001";
    public static final String PATIENT_IMP_MRN = "SYN-HL7-MRN-1002";
    public static final LocalDate PATIENT_AMB_DOB = LocalDate.of(1988, 4, 14);
    public static final LocalDate PATIENT_IMP_DOB = LocalDate.of(1961, 2, 19);

    // LOINC + UCUM + ICD-10 + CPT + DRG
    public static final String LOINC_SYSTEM = "http://loinc.org";
    public static final String LOINC_POTASSIUM = "2823-3";
    public static final String LOINC_ECG_12_LEAD = "11524-6";
    public static final String LOINC_COMP_METABOLIC_PANEL = "24323-8";
    public static final String UCUM_MMOL_L = "mmol/L";
    public static final BigDecimal CRITICAL_POTASSIUM_VALUE = new BigDecimal("6.4");
    public static final String POTASSIUM_REF_RANGE = "3.5-5.1";

    public static final String ICD10_HYPERTENSION = "I10";
    public static final String ICD10_HYPERKALEMIA = "E87.5";
    public static final String ICD10_FEVER = "R50.9";
    public static final String CPT_CT_CHEST_CONTRAST = "71260";
    public static final String CPT_INPATIENT_AND_PANEL = "99222,80051";
    public static final String DRG_HYPERTENSION = "DRG-304";

    // DICOM / ISBT 128 Standard Identifiers
    public static final String DICOM_STUDY_INSTANCE_UID = "1.2.826.0.1.3680043.8.498.20261120001";
    public static final String ISBT_128_PRBC_UNIT = "W000026990001";

    // HL7 FHIR R4 Document Bundle & HL7 v2.5.1 ORM^O01 Payloads
    public static final String FHIR_R4_IPS_BUNDLE_JSON =
            "{\"resourceType\":\"Bundle\",\"id\":\"syn-ips-bundle-001\",\"type\":\"document\","
            + "\"meta\":{\"profile\":[\"http://hl7.org/fhir/uv/ips/StructureDefinition/Bundle-uv-ips\"]},"
            + "\"timestamp\":\"2026-11-20T10:00:00Z\",\"entry\":[]}";

    public static final String HL7_V251_ORM_O01_MESSAGE =
            "MSH|^~\\&|HEALTHCORE_HIS|ISO-DLA-REF-001|REF_LIS|SYN_LAB|20261120100000||ORM^O01|SYNORM0001|P|2.5.1\r"
            + "PID|1||SYN-HL7-MRN-1002^^^ISO-DLA-REF-001^MR||SYNTHETIQUE^JEAN-LUC||19610219|M\r"
            + "ORC|NW|ORD-SYN-2823|||||^^^20261120100000^^S\r"
            + "OBR|1|ORD-SYN-2823||2823-3^Potassium [Moles/volume] in Serum or Plasma^LN|||20261120100000\r";

    // Clinical AI SHA-256 Placeholder Digest (synthetic prompt hash, zero PHI)
    public static final String SYNTHETIC_PROMPT_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public static final String SYNTHETIC_AI_DRAFT_JSON =
            "{\"icd10Primary\":\"I10\",\"icd10Secondary\":\"E87.5\",\"summary\":\"Synthetic hypertension and hyperkalemia clinical draft.\"}";

    // Analytics CMS eCQM & Revenue Payer Codes
    public static final String ECQM_CMS165 = "CMS165v12";
    public static final String PAYER_CNPS_UHC = "CM-CNPS-UHC";
    public static final String CLAIM_CONTROL_NUMBER = "CLM-ISO-2026-0001";
    public static final String ERA_TRACE_NUMBER = "ERA-835-2026-0001";

    // SNOMED-CT, Additional LOINC/UCUM, HL7 v2.5.1 ORU^R01 & Canonical Patient Journey Constants
    public static final String SNOMED_SYSTEM = "http://snomed.info/sct";
    public static final String SNOMED_CHEST_PAIN = "29857009";
    public static final String LOINC_HEMOGLOBIN = "718-7";
    public static final String UCUM_G_DL = "g/dL";
    public static final String OPD_CANONICAL_WORKFLOW_ID = "OPD_CONSULTATION_FLOW";
    public static final String OPD_CANONICAL_WORKFLOW_VERSION = "2.0.0";

    public static final String HL7_V251_ORU_R01_CRITICAL_POTASSIUM =
            "MSH|^~\\&|REF_LIS|SYN_LAB|HEALTHCORE_HIS|ISO-DLA-REF-001|20261120102500||ORU^R01|SYNORU0001|P|2.5.1\r"
            + "PID|1||SYN-HL7-MRN-1002^^^ISO-DLA-REF-001^MR||SYNTHETIQUE^JEAN-LUC||19610219|M\r"
            + "OBR|1|ORD-SYN-2823|ACC-SYN-2823|2823-3^Potassium [Moles/volume] in Serum or Plasma^LN|||20261120102000\r"
            + "OBX|1|NM|2823-3^Potassium^LN||6.4|mmol/L|3.5-5.1|HH|||F\r";
}
