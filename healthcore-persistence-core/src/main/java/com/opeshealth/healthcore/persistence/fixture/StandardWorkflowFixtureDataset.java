/*
 * =============================================================================
 * Opes HealthCore SDK — Standardized Data-Driven Workflow Fixture Schema
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.fixture;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Strongly-typed, deterministic, zero-PII data model loaded from standardized JSON
 * fixture files to drive automated multi-module workflow population and verification.
 */
public record StandardWorkflowFixtureDataset(
        String datasetId,
        String version,
        boolean syntheticOnly,
        String description,
        TenantFixture tenant,
        List<PatientFixture> patients,
        SchedulingFixture scheduling,
        List<EncounterFixture> encounters,
        OrdersAndResultsFixture ordersAndResults,
        PharmacyAndLabFixture pharmacyAndLab,
        List<BillingChargeFixture> billingCharges,
        PatientPortalFixture patientPortal,
        List<RadiologyExamFixture> radiologyExams,
        EmergencyAndNursingFixture emergencyAndNursing,
        HospitalOpsFixture hospitalOps,
        IntegrationOpsFixture integrationOps,
        RevenueAndClaimsFixture revenueAndClaims,
        AnalyticsFixture analytics,
        Map<String, String> universalVerticalPacks
) {

    public record TenantFixture(
            String orgCode,
            String orgName,
            String orgType,
            String countryCode,
            String facilityCode,
            String facilityName,
            String facilityType,
            String timezone,
            String currency,
            String language,
            boolean active,
            List<DepartmentFixture> departments,
            List<FeatureFlagFixture> featureFlags
    ) {}

    public record DepartmentFixture(String code, String name, String kind, int bedCapacity) {}

    public record FeatureFlagFixture(String key, boolean enabled, boolean locked) {}

    public record PatientFixture(
            String mrn,
            String familyName,
            String givenName,
            LocalDate birthDate,
            String sex,
            String bloodGroup,
            String phone,
            String language,
            String nationalIdType,
            String nationalIdNumber,
            String city,
            String emergencyContactName,
            String emergencyContactPhone
    ) {}

    public record SchedulingFixture(
            List<CalendarFixture> calendars,
            List<AppointmentTypeFixture> appointmentTypes,
            List<AppointmentFixture> appointments
    ) {}

    public record CalendarFixture(
            String departmentCode,
            String resourceKind,
            String resourceCode,
            String displayName,
            int slotDurationMinutes,
            int concurrency
    ) {}

    public record AppointmentTypeFixture(
            String code,
            String name,
            String specialty,
            int durationMinutes,
            int bufferMinutes
    ) {}

    public record AppointmentFixture(
            String departmentCode,
            String patientMrn,
            String appointmentTypeCode,
            long offsetMinutesFromBase,
            long durationMinutes,
            String reason,
            boolean checkIn
    ) {}

    public record EncounterFixture(
            String encounterKey,
            String patientMrn,
            String encounterClass,
            String status,
            String attendingId,
            String departmentCode,
            String primaryIcd10
    ) {}

    public record OrdersAndResultsFixture(
            List<OrderSetFixture> orderSets,
            List<ReflexRuleFixture> reflexRules,
            List<ClinicalOrderFixture> orders
    ) {}

    public record OrderSetFixture(String code, String name, String indication, String itemsSpec) {}

    public record ReflexRuleFixture(
            String triggerLoinc,
            String triggerFlag,
            String reflexDomain,
            String reflexCode,
            String reflexName,
            String reflexPriority
    ) {}

    public record ClinicalOrderFixture(
            String orderKey,
            String patientMrn,
            String encounterKey,
            String domain,
            String codeSystem,
            String code,
            String displayName,
            String priority,
            String clinicalNotes,
            String orderingProvider,
            String accessionNumber,
            String specimenType,
            String collectedBy,
            BigDecimal numericValue,
            String unit,
            String referenceRange,
            String abnormalFlag,
            String enteredBy,
            String verifiedBy
    ) {}

    public record PharmacyAndLabFixture(
            List<MedicationBatchFixture> medicationBatches,
            List<LabSpecimenFixture> labSpecimens
    ) {}

    public record MedicationBatchFixture(
            String drugCode,
            String drugName,
            String lotNumber,
            int expiryDaysFromNow,
            int quantityOnHand,
            int reorderThreshold
    ) {}

    public record LabSpecimenFixture(
            String patientMrn,
            String specimenType,
            String loincPanelCode,
            String accessionBarcode
    ) {}

    public record BillingChargeFixture(
            String patientMrn,
            long amountMinorUnits,
            String currency,
            String invoiceRef,
            String description
    ) {}

    public record PatientPortalFixture(List<PortalEnrollmentFixture> enrollments) {}

    public record PortalEnrollmentFixture(
            String patientMrn,
            String language,
            String email,
            String phone,
            String proxyUsername,
            String proxyRelationship,
            List<String> proxyScopes,
            String threadSubject,
            String threadDepartment,
            String threadBody
    ) {}

    public record RadiologyExamFixture(
            String patientMrn,
            String encounterKey,
            String modality,
            String procedureCode,
            String procedureName,
            String bodyPart,
            String clinicalIndication,
            String aeTitle,
            String orderingProvider,
            boolean contrastRequired
    ) {}

    public record EmergencyAndNursingFixture(
            List<EdTriageFixture> edTriages,
            List<News2ObservationFixture> news2Observations
    ) {}

    public record EdTriageFixture(
            String patientMrn,
            String chiefComplaint,
            int esiLevel,
            String arrivalMode,
            String bayCode,
            String triageNurse
    ) {}

    public record News2ObservationFixture(
            String patientMrn,
            String encounterKey,
            int respiratoryRate,
            int spo2,
            boolean supplementalOxygen,
            int systolicBp,
            int heartRate,
            double temperatureCelsius,
            String consciousness,
            String recordedBy
    ) {}

    public record HospitalOpsFixture(
            List<BloodUnitFixture> bloodUnits,
            List<SurgicalScheduleFixture> surgicalSchedules
    ) {}

    public record BloodUnitFixture(
            String isbtUnitCode,
            String componentType,
            String aboRhd,
            int expiryDaysFromNow,
            String status
    ) {}

    public record SurgicalScheduleFixture(
            String patientMrn,
            String theaterRoom,
            String procedureName,
            String leadSurgeonId,
            boolean whoChecklistCompleted,
            String status
    ) {}

    public record IntegrationOpsFixture(
            List<IntegrationPartnerFixture> partners,
            List<OutboundMessageFixture> outboundMessages
    ) {}

    public record IntegrationPartnerFixture(
            String code,
            String name,
            String protocol,
            String endpointUrl,
            String authMode,
            String resourceTypes,
            int timeoutSeconds,
            boolean enabled
    ) {}

    public record OutboundMessageFixture(
            String partnerCode,
            String correlationId,
            String messageType,
            String payload,
            int maxRetries
    ) {}

    public record RevenueAndClaimsFixture(
            List<TariffFixture> tariffs,
            List<ClaimWorkflowFixture> claims
    ) {}

    public record TariffFixture(
            String payerCode,
            String serviceCode,
            String description,
            String category,
            long amountMinorUnits,
            String currency
    ) {}

    public record ClaimWorkflowFixture(
            String claimNumber,
            String patientMrn,
            String encounterKey,
            String codeSystem,
            String primaryCode,
            String secondaryCodes,
            String procedureCodes,
            String drgCode,
            String coderId,
            String approverId,
            String payerCode,
            String memberNumber,
            String serviceCode,
            boolean inNetwork,
            int approvedDays,
            String claimFormat,
            long billedAmountMinorUnits,
            String currency
    ) {}

    public record AnalyticsFixture(
            List<QualityMeasureFixture> qualityMeasures,
            List<CohortExportFixture> cohortExports
    ) {}

    public record QualityMeasureFixture(
            String measureCode,
            String title,
            String reportingPeriod,
            long numerator,
            long denominator,
            long exclusions,
            String sourceTables
    ) {}

    public record CohortExportFixture(
            String cohortName,
            String filterJson,
            String purpose,
            boolean deidentified,
            int kAnonymityThreshold,
            long estimatedRecordCount,
            String requestedBy
    ) {}

    /**
     * Validates structural and referential integrity across all sections before
     * populating core modules so broken test data fails fast with clear diagnostics.
     */
    public void validate() {
        List<String> violations = new ArrayList<>();
        if (!syntheticOnly) {
            violations.add("Fixture dataset must set syntheticOnly=true (real PII is prohibited in test fixtures).");
        }
        if (datasetId == null || datasetId.isBlank()) {
            violations.add("datasetId must be non-blank.");
        }
        if (tenant == null || tenant.orgCode() == null || tenant.orgCode().isBlank()
                || tenant.facilityCode() == null || tenant.facilityCode().isBlank()) {
            violations.add("tenant.orgCode and tenant.facilityCode are required.");
        }

        Set<String> mrns = new HashSet<>();
        if (patients != null) {
            for (PatientFixture p : patients) {
                if (p.mrn() == null || p.mrn().isBlank()) {
                    violations.add("Every patient fixture must declare a non-blank mrn.");
                } else if (!mrns.add(p.mrn())) {
                    violations.add("Duplicate patient MRN in fixture dataset: " + p.mrn());
                }
            }
        }

        Set<String> encounterKeys = new HashSet<>();
        if (encounters != null) {
            for (EncounterFixture e : encounters) {
                if (!mrns.contains(e.patientMrn())) {
                    violations.add("Encounter " + e.encounterKey() + " references unknown patientMrn=" + e.patientMrn());
                }
                if (!encounterKeys.add(e.encounterKey())) {
                    violations.add("Duplicate encounterKey: " + e.encounterKey());
                }
            }
        }

        if (scheduling != null && scheduling.appointments() != null) {
            for (AppointmentFixture a : scheduling.appointments()) {
                if (!mrns.contains(a.patientMrn())) {
                    violations.add("Appointment references unknown patientMrn=" + a.patientMrn());
                }
            }
        }

        if (ordersAndResults != null && ordersAndResults.orders() != null) {
            for (ClinicalOrderFixture o : ordersAndResults.orders()) {
                if (!mrns.contains(o.patientMrn())) {
                    violations.add("Order " + o.orderKey() + " references unknown patientMrn=" + o.patientMrn());
                }
                if (o.encounterKey() != null && !encounterKeys.contains(o.encounterKey())) {
                    violations.add("Order " + o.orderKey() + " references unknown encounterKey=" + o.encounterKey());
                }
            }
        }

        if (billingCharges != null) {
            for (BillingChargeFixture b : billingCharges) {
                if (!mrns.contains(b.patientMrn())) {
                    violations.add("Billing charge " + b.invoiceRef() + " references unknown patientMrn=" + b.patientMrn());
                }
            }
        }

        if (radiologyExams != null) {
            for (RadiologyExamFixture r : radiologyExams) {
                if (!mrns.contains(r.patientMrn())) {
                    violations.add("Radiology exam " + r.procedureCode() + " references unknown patientMrn=" + r.patientMrn());
                }
                if (r.encounterKey() != null && !encounterKeys.contains(r.encounterKey())) {
                    violations.add("Radiology exam references unknown encounterKey=" + r.encounterKey());
                }
            }
        }

        if (integrationOps != null && integrationOps.outboundMessages() != null) {
            Set<String> partnerCodes = new HashSet<>();
            if (integrationOps.partners() != null) {
                integrationOps.partners().forEach(p -> partnerCodes.add(p.code()));
            }
            for (OutboundMessageFixture m : integrationOps.outboundMessages()) {
                if (!partnerCodes.contains(m.partnerCode())) {
                    violations.add("Outbound message " + m.correlationId() + " references unknown partnerCode=" + m.partnerCode());
                }
            }
        }

        if (revenueAndClaims != null && revenueAndClaims.claims() != null) {
            for (ClaimWorkflowFixture c : revenueAndClaims.claims()) {
                if (!mrns.contains(c.patientMrn())) {
                    violations.add("Claim " + c.claimNumber() + " references unknown patientMrn=" + c.patientMrn());
                }
                if (c.encounterKey() != null && !encounterKeys.contains(c.encounterKey())) {
                    violations.add("Claim " + c.claimNumber() + " references unknown encounterKey=" + c.encounterKey());
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Invalid StandardWorkflowFixtureDataset: " + String.join("; ", violations));
        }
    }
}
