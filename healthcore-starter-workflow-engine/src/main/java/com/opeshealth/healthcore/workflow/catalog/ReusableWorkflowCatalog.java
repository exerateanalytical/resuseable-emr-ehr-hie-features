/*
 * =============================================================================
 * Opes HealthCore SDK — Reusable Clinical, EHR & HIE Workflow Catalog
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.workflow.catalog;

import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.WorkflowDomainCategory;

import java.util.*;

/**
 * Production-ready library of 8 reusable healthcare state-machine blueprints designed so
 * independent EMR, EHR, HIE, PIS, LIS, Specialty Clinic, and Community Health projects
 * can bootstrap battle-tested clinical journeys in seconds and customize per facility.
 */
public final class ReusableWorkflowCatalog {

    private ReusableWorkflowCatalog() {}

    /** Canonical definition blueprint for one reusable state machine. */
    public record WorkflowTemplate(
            String workflowCode,
            WorkflowDomainCategory domainCategory,
            String initialStage,
            Set<String> terminalStages,
            int defaultSlaMinutes,
            Map<String, Set<String>> allowedTransitions,
            String description
    ) {
        /** Checks whether transitioning {@code fromStage -> toStage} is permitted by this blueprint. */
        public boolean isTransitionAllowed(String fromStage, String toStage) {
            if (fromStage == null || toStage == null) {
                return false;
            }
            return allowedTransitions.getOrDefault(fromStage, Set.of()).contains(toStage);
        }
    }

    public static final String OPD_CONSULTATION_FLOW = "OPD_CONSULTATION_FLOW";
    public static final String INPATIENT_ADT_CARE_FLOW = "INPATIENT_ADT_CARE_FLOW";
    public static final String DIAGNOSTIC_LAB_FLOW = "DIAGNOSTIC_LAB_FLOW";
    public static final String PHARMACY_DISPENSE_FLOW = "PHARMACY_DISPENSE_FLOW";
    public static final String EHR_HIE_EXCHANGE_FLOW = "EHR_HIE_EXCHANGE_FLOW";
    public static final String CLOSED_LOOP_REFERRAL_FLOW = "CLOSED_LOOP_REFERRAL_FLOW";
    public static final String SURGICAL_OT_FLOW = "SURGICAL_OT_FLOW";
    public static final String CHW_OUTBREAK_ESCALATION_FLOW = "CHW_OUTBREAK_ESCALATION_FLOW";

    private static final Map<String, WorkflowTemplate> TEMPLATES = buildCatalog();

    /** Returns an unmodifiable view of all 8 standard HealthCore workflow blueprints. */
    public static Map<String, WorkflowTemplate> allTemplates() {
        return TEMPLATES;
    }

    /** Looks up a standard blueprint by code, throwing if unknown. */
    public static WorkflowTemplate requireTemplate(String workflowCode) {
        WorkflowTemplate t = TEMPLATES.get(workflowCode);
        if (t == null) {
            throw new NoSuchElementException("Unknown standard workflow template: " + workflowCode);
        }
        return t;
    }

    private static Map<String, WorkflowTemplate> buildCatalog() {
        Map<String, WorkflowTemplate> m = new LinkedHashMap<>();

        // 1. Ambulatory / Specialty Clinic OPD Journey
        m.put(OPD_CONSULTATION_FLOW, new WorkflowTemplate(
                OPD_CONSULTATION_FLOW,
                WorkflowDomainCategory.EMR,
                "APPOINTMENT_CHECKED_IN",
                Set.of("DISCHARGED_WITH_AVS", "ADMITTED_TO_WARD", "CANCELLED"),
                120,
                Map.of(
                        "APPOINTMENT_CHECKED_IN", Set.of("TRIAGE_ESI_SCORED", "CANCELLED"),
                        "TRIAGE_ESI_SCORED", Set.of("VITALS_LOINC_CAPTURED", "CONSULTATION_IN_PROGRESS", "CANCELLED"),
                        "VITALS_LOINC_CAPTURED", Set.of("CONSULTATION_IN_PROGRESS", "CANCELLED"),
                        "CONSULTATION_IN_PROGRESS", Set.of("ORDERS_AND_CDSS_SIGNED", "DISCHARGED_WITH_AVS", "ADMITTED_TO_WARD"),
                        "ORDERS_AND_CDSS_SIGNED", Set.of("RESULTS_REVIEWED", "BILLING_UHC_SETTLED", "ADMITTED_TO_WARD"),
                        "RESULTS_REVIEWED", Set.of("BILLING_UHC_SETTLED", "ADMITTED_TO_WARD"),
                        "BILLING_UHC_SETTLED", Set.of("DISCHARGED_WITH_AVS")
                ),
                "Outpatient & Specialty Clinic end-to-end journey: Check-in -> ESI Triage -> Vitals -> SOAP/Orders -> Billing -> Discharge AVS"
        ));

        // 2. Inpatient Admission, ADT Bed Transfers & Ward Care
        m.put(INPATIENT_ADT_CARE_FLOW, new WorkflowTemplate(
                INPATIENT_ADT_CARE_FLOW,
                WorkflowDomainCategory.EMR,
                "ADMISSION_ADT_A01",
                Set.of("DISCHARGED_ADT_A03", "EXPIRED_CRVS_CERTIFIED"),
                1440,
                Map.of(
                        "ADMISSION_ADT_A01", Set.of("WARD_BED_ASSIGNED"),
                        "WARD_BED_ASSIGNED", Set.of("ACTIVE_WARD_CARE", "BED_TRANSFERRED_ADT_A02"),
                        "BED_TRANSFERRED_ADT_A02", Set.of("ACTIVE_WARD_CARE"),
                        "ACTIVE_WARD_CARE", Set.of("BED_TRANSFERRED_ADT_A02", "SBAR_SHIFT_HANDOVER", "DISCHARGE_PLANNING", "EXPIRED_CRVS_CERTIFIED"),
                        "SBAR_SHIFT_HANDOVER", Set.of("ACTIVE_WARD_CARE", "DISCHARGE_PLANNING"),
                        "DISCHARGE_PLANNING", Set.of("MEDICATION_RECONCILED", "ACTIVE_WARD_CARE"),
                        "MEDICATION_RECONCILED", Set.of("DISCHARGED_ADT_A03")
                ),
                "Inpatient ward lifecycle supporting ADT^A01/A02/A03, 5-rights eMAR, fluid I&O, SBAR handovers, and discharge med reconciliation"
        ));

        // 3. Diagnostic LIS Specimen-to-Result Verification
        m.put(DIAGNOSTIC_LAB_FLOW, new WorkflowTemplate(
                DIAGNOSTIC_LAB_FLOW,
                WorkflowDomainCategory.LIS,
                "ORDER_RECEIVED",
                Set.of("FINAL_FHIR_PUBLISHED", "SPECIMEN_REJECTED"),
                90,
                Map.of(
                        "ORDER_RECEIVED", Set.of("SPECIMEN_COLLECTED", "SPECIMEN_REJECTED"),
                        "SPECIMEN_COLLECTED", Set.of("BARCODE_ACCESSIONED", "SPECIMEN_REJECTED"),
                        "BARCODE_ACCESSIONED", Set.of("ANALYZER_ASTM_RUN", "SPECIMEN_REJECTED"),
                        "ANALYZER_ASTM_RUN", Set.of("WESTGARD_QC_PASSED", "REPEAT_ANALYSIS_HOLD"),
                        "REPEAT_ANALYSIS_HOLD", Set.of("ANALYZER_ASTM_RUN", "SPECIMEN_REJECTED"),
                        "WESTGARD_QC_PASSED", Set.of("SCIENTIST_VERIFIED", "PANIC_ALERT_ESCALATED"),
                        "PANIC_ALERT_ESCALATED", Set.of("SCIENTIST_VERIFIED"),
                        "SCIENTIST_VERIFIED", Set.of("FINAL_FHIR_PUBLISHED")
                ),
                "Standalone LIS / Hospital Lab journey enforcing barcode accession, Westgard QC gate, panic escalation, and FHIR DiagnosticReport publication"
        ));

        // 4. Pharmacy FEFO & Controlled Substance Dispense
        m.put(PHARMACY_DISPENSE_FLOW, new WorkflowTemplate(
                PHARMACY_DISPENSE_FLOW,
                WorkflowDomainCategory.PIS,
                "PRESCRIPTION_RECEIVED",
                Set.of("DISPENSED_AND_CLAIMED", "PRESCRIPTION_REJECTED"),
                45,
                Map.of(
                        "PRESCRIPTION_RECEIVED", Set.of("CDSS_DDI_ALLERGY_CLEARED", "PHARMACIST_CLARIFICATION_HOLD", "PRESCRIPTION_REJECTED"),
                        "PHARMACIST_CLARIFICATION_HOLD", Set.of("CDSS_DDI_ALLERGY_CLEARED", "PRESCRIPTION_REJECTED"),
                        "CDSS_DDI_ALLERGY_CLEARED", Set.of("FEFO_LOT_PICKED", "PRESCRIPTION_REJECTED"),
                        "FEFO_LOT_PICKED", Set.of("DUAL_WITNESS_VERIFIED", "PATIENT_COUNSELED"),
                        "DUAL_WITNESS_VERIFIED", Set.of("PATIENT_COUNSELED"),
                        "PATIENT_COUNSELED", Set.of("DISPENSED_AND_CLAIMED")
                ),
                "Standalone PIS / Dispensary workflow enforcing LOINC/RxNorm CDSS checks, FEFO batch picking, dual-witness narcotic verification, and UHC claim"
        ));

        // 5. Longitudinal EHR & National HIE Federation Dispatch
        m.put(EHR_HIE_EXCHANGE_FLOW, new WorkflowTemplate(
                EHR_HIE_EXCHANGE_FLOW,
                WorkflowDomainCategory.HIE,
                "CONSENT_AND_ABAC_VERIFIED",
                Set.of("ATNA_AUDIT_SEALED", "EXCHANGE_DENIED"),
                30,
                Map.of(
                        "CONSENT_AND_ABAC_VERIFIED", Set.of("MPI_PIXM_RESOLVED", "EXCHANGE_DENIED"),
                        "MPI_PIXM_RESOLVED", Set.of("IPS_OR_MHD_ASSEMBLED", "EXCHANGE_DENIED"),
                        "IPS_OR_MHD_ASSEMBLED", Set.of("SIGNED_FHIR_OR_MLLP_DISPATCHED"),
                        "SIGNED_FHIR_OR_MLLP_DISPATCHED", Set.of("REMOTE_ACK_CONFIRMED", "RETRY_BACKOFF_QUEUED"),
                        "RETRY_BACKOFF_QUEUED", Set.of("SIGNED_FHIR_OR_MLLP_DISPATCHED", "EXCHANGE_DENIED"),
                        "REMOTE_ACK_CONFIRMED", Set.of("ATNA_AUDIT_SEALED")
                ),
                "National/Regional HIE workflow binding patient consent, IHE PIXm matching, IPS/$export packaging, MLLP/HTTPS dispatch, and ATNA audit sealing"
        ));

        // 6. Closed-Loop Inter-Facility Clinical Referral
        m.put(CLOSED_LOOP_REFERRAL_FLOW, new WorkflowTemplate(
                CLOSED_LOOP_REFERRAL_FLOW,
                WorkflowDomainCategory.EHR,
                "REFERRAL_INITIATED_QR",
                Set.of("OUTCOME_SUMMARY_RETURNED", "REFERRAL_EXPIRED_OR_CANCELLED"),
                4320, // 72h default inter-facility loop SLA
                Map.of(
                        "REFERRAL_INITIATED_QR", Set.of("TARGET_FACILITY_TRIAGED", "REFERRAL_EXPIRED_OR_CANCELLED"),
                        "TARGET_FACILITY_TRIAGED", Set.of("REFERRAL_ACCEPTED", "REFERRAL_EXPIRED_OR_CANCELLED"),
                        "REFERRAL_ACCEPTED", Set.of("SPECIALIST_CONSULT_COMPLETED"),
                        "SPECIALIST_CONSULT_COMPLETED", Set.of("OUTCOME_SUMMARY_RETURNED")
                ),
                "Closed-loop EMR/EHR referral flow tracking QR-coded referrals through receiving facility triage and counter-referral outcome return"
        ));

        // 7. Surgical Operating Theater (OT) WHO Checklist Flow
        m.put(SURGICAL_OT_FLOW, new WorkflowTemplate(
                SURGICAL_OT_FLOW,
                WorkflowDomainCategory.EMR,
                "PREOP_CONSENT_AND_CLEARANCE",
                Set.of("TRANSFERRED_TO_WARD_OR_ICU", "SURGERY_POSTPONED"),
                360,
                Map.of(
                        "PREOP_CONSENT_AND_CLEARANCE", Set.of("BLOOD_CROSSMATCH_RESERVED", "WHO_SIGN_IN_COMPLETED", "SURGERY_POSTPONED"),
                        "BLOOD_CROSSMATCH_RESERVED", Set.of("WHO_SIGN_IN_COMPLETED", "SURGERY_POSTPONED"),
                        "WHO_SIGN_IN_COMPLETED", Set.of("INTRA_OP_ACTIVE"),
                        "INTRA_OP_ACTIVE", Set.of("WHO_TIME_OUT_AND_SIGN_OUT"),
                        "WHO_TIME_OUT_AND_SIGN_OUT", Set.of("PACU_ALDRETE_RECOVERY"),
                        "PACU_ALDRETE_RECOVERY", Set.of("TRANSFERRED_TO_WARD_OR_ICU")
                ),
                "Operating theater workflow enforcing pre-op clearance, blood crossmatch, WHO Sign-In/Time-Out/Sign-Out safety gates, and PACU Aldrete score"
        ));

        // 8. CHW Household Visit & IDSR Disease Watch Escalation
        m.put(CHW_OUTBREAK_ESCALATION_FLOW, new WorkflowTemplate(
                CHW_OUTBREAK_ESCALATION_FLOW,
                WorkflowDomainCategory.PUBLIC_HEALTH,
                "CHW_HOUSEHOLD_SCREENING",
                Set.of("DHIS2_ADX_AND_RESPONSE_CLOSED", "CLEARED_NON_NOTIFIABLE"),
                120,
                Map.of(
                        "CHW_HOUSEHOLD_SCREENING", Set.of("SUSPECT_IDSR_CASE_FLAGGED", "CLEARED_NON_NOTIFIABLE"),
                        "SUSPECT_IDSR_CASE_FLAGGED", Set.of("IDSR_CIF_SUBMITTED", "CLEARED_NON_NOTIFIABLE"),
                        "IDSR_CIF_SUBMITTED", Set.of("CONTACT_TRACING_AND_LAB_SWAB"),
                        "CONTACT_TRACING_AND_LAB_SWAB", Set.of("DHIS2_ADX_AND_RESPONSE_CLOSED")
                ),
                "Community-to-Ministry surveillance flow bridging offline CHW household MUAC/fever flags to IDSR Case Investigation Forms and DHIS2 ADX export"
        ));

        return Collections.unmodifiableMap(m);
    }
}
