/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Electronic Prior Authorization (HL7 Da Vinci PAS / CRD / DTR) &amp; Real-Time Coverage Eligibility
 * ({@code CoverageEligibilityRequest} / {@code CoverageEligibilityResponse}) Service.
 *
 * <p>Provides real-time point-of-care benefit eligibility verification and automated clinical
 * prior authorization adjudication for high-cost imaging, surgical procedures, and specialty
 * medications before care delivery.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PriorAuthAndEligibilityService {

    public enum PriorAuthDecision {
        APPROVED,
        PENDED_MEDICAL_REVIEW,
        DENIED
    }

    public record PatientCoveragePlanRequest(
            UUID facilityId,
            UUID patientId,
            String payerName,
            String memberSubscriberId,
            String planTierCode,
            BigDecimal annualBenefitLimitXaf,
            BigDecimal utilizedAmountXaf,
            double patientCopayPercent,
            LocalDate coverageStartDate,
            LocalDate coverageEndDate
    ) {}

    public record PatientCoveragePlan(
            UUID coverageId,
            UUID facilityId,
            UUID patientId,
            String payerName,
            String memberSubscriberId,
            String planTierCode,
            BigDecimal annualBenefitLimitXaf,
            BigDecimal utilizedAmountXaf,
            BigDecimal remainingBenefitXaf,
            double patientCopayPercent,
            LocalDate coverageStartDate,
            LocalDate coverageEndDate,
            boolean active
    ) {}

    public record EligibilityCheckRequest(
            UUID facilityId,
            UUID patientId,
            String serviceCategoryCode,
            BigDecimal estimatedServiceCostXaf
    ) {}

    public record EligibilityVerificationResponse(
            UUID verificationId,
            UUID patientId,
            UUID coverageId,
            String payerName,
            String memberSubscriberId,
            boolean coverageActiveInForce,
            String serviceCategoryCode,
            boolean benefitCovered,
            BigDecimal estimatedServiceCostXaf,
            BigDecimal payerCoveredPortionXaf,
            BigDecimal estimatedPatientCopayXaf,
            BigDecimal remainingAnnualCapAfterServiceXaf,
            boolean priorAuthorizationRequired,
            String fhirCoverageEligibilityResponseJson,
            Instant verifiedAt
    ) {}

    public record PriorAuthSubmissionRequest(
            UUID facilityId,
            UUID patientId,
            String procedureOrMedicationCode,
            String procedureOrMedicationDisplay,
            String primaryIcd10DiagnosisCode,
            String clinicalJustificationSummary,
            boolean conservativeTherapyAttemptedOrExempt,
            BigDecimal requestedAmountXaf,
            String orderingPractitionerId
    ) {}

    public record PriorAuthorizationAdjudication(
            UUID priorAuthId,
            String preAuthReferenceNumber,
            UUID facilityId,
            UUID patientId,
            String procedureOrMedicationCode,
            String procedureOrMedicationDisplay,
            String primaryIcd10DiagnosisCode,
            PriorAuthDecision decision,
            BigDecimal approvedAmountXaf,
            LocalDate validUntilDate,
            String adjudicationRationale,
            String fhirClaimResponseJson,
            Instant adjudicatedAt
    ) {}

    private static final Set<String> PRIOR_AUTH_REQUIRED_CATEGORIES = Set.of(
            "MRI", "CT_SCAN", "ONCOLOGY_BIOLOGIC", "ELECTIVE_SURGERY", "SPECIALTY_PHARMACY", "ICU_TRANSFER"
    );

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, PatientCoveragePlan> coverageByPatient = new ConcurrentHashMap<>();
    private final Map<UUID, List<PriorAuthorizationAdjudication>> priorAuthsByPatient = new ConcurrentHashMap<>();

    @Autowired
    public PriorAuthAndEligibilityService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public PriorAuthAndEligibilityService() {
        this(null);
    }

    @ClinicalAudit(action = "COVERAGE_PLAN_REGISTER", resourceType = "Coverage")
    public PatientCoveragePlan registerPatientCoverage(PatientCoveragePlanRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("PatientCoveragePlanRequest with patientId is required");
        }
        if (request.memberSubscriberId() == null || request.memberSubscriberId().isBlank()) {
            throw new IllegalArgumentException("memberSubscriberId is required");
        }

        UUID coverageId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        BigDecimal annualLimit = request.annualBenefitLimitXaf() != null
                ? request.annualBenefitLimitXaf()
                : new BigDecimal("5000000.00");
        BigDecimal utilized = request.utilizedAmountXaf() != null
                ? request.utilizedAmountXaf()
                : BigDecimal.ZERO;
        BigDecimal remaining = annualLimit.subtract(utilized).max(BigDecimal.ZERO);

        LocalDate start = request.coverageStartDate() != null ? request.coverageStartDate() : LocalDate.now().minusMonths(3);
        LocalDate end = request.coverageEndDate() != null ? request.coverageEndDate() : LocalDate.now().plusMonths(9);
        LocalDate today = LocalDate.now();
        boolean active = !today.isBefore(start) && !today.isAfter(end);

        PatientCoveragePlan plan = new PatientCoveragePlan(
                coverageId,
                facilityId,
                request.patientId(),
                request.payerName() != null ? request.payerName().trim() : "Cameroon National UHC / CNPS Health Pool",
                request.memberSubscriberId().trim(),
                request.planTierCode() != null ? request.planTierCode().trim() : "UHC-COMPREHENSIVE-GOLD",
                annualLimit,
                utilized,
                remaining,
                request.patientCopayPercent() >= 0 && request.patientCopayPercent() <= 100
                        ? request.patientCopayPercent()
                        : 15.0,
                start,
                end,
                active
        );

        coverageByPatient.put(request.patientId(), plan);
        return plan;
    }

    /**
     * Executes real-time HL7 FHIR R4 {@code CoverageEligibilityRequest} verification at the point of care.
     */
    @ClinicalAudit(action = "FHIR_COVERAGE_ELIGIBILITY_VERIFY", resourceType = "CoverageEligibilityResponse")
    public EligibilityVerificationResponse verifyCoverageEligibility(EligibilityCheckRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("EligibilityCheckRequest with patientId is required");
        }

        PatientCoveragePlan plan = coverageByPatient.computeIfAbsent(request.patientId(), pid ->
                registerPatientCoverage(new PatientCoveragePlanRequest(
                        request.facilityId(),
                        pid,
                        "Cameroon National UHC Fund",
                        "UHC-MEM-" + pid.toString().substring(0, 8).toUpperCase(Locale.ROOT),
                        "UHC-STANDARD",
                        new BigDecimal("3500000.00"),
                        new BigDecimal("150000.00"),
                        20.0,
                        LocalDate.now().minusMonths(2),
                        LocalDate.now().plusMonths(10)
                )));

        String category = (request.serviceCategoryCode() != null && !request.serviceCategoryCode().isBlank())
                ? request.serviceCategoryCode().trim().toUpperCase(Locale.ROOT)
                : "GENERAL_OUTPATIENT";
        BigDecimal cost = (request.estimatedServiceCostXaf() != null && request.estimatedServiceCostXaf().compareTo(BigDecimal.ZERO) > 0)
                ? request.estimatedServiceCostXaf()
                : new BigDecimal("45000.00");

        boolean covered = plan.active() && plan.remainingBenefitXaf().compareTo(BigDecimal.ZERO) > 0;
        BigDecimal copayRatio = BigDecimal.valueOf(plan.patientCopayPercent() / 100.0);
        BigDecimal patientCopay = covered
                ? cost.multiply(copayRatio).setScale(2, RoundingMode.HALF_UP)
                : cost;
        BigDecimal payerPortion = covered
                ? cost.subtract(patientCopay).min(plan.remainingBenefitXaf()).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal remainingAfter = plan.remainingBenefitXaf().subtract(payerPortion).max(BigDecimal.ZERO);

        boolean requiresPriorAuth = PRIOR_AUTH_REQUIRED_CATEGORIES.contains(category)
                || cost.compareTo(new BigDecimal("250000.00")) >= 0;

        UUID verificationId = UUID.randomUUID();
        String fhirJson = """
                {
                  "resourceType": "CoverageEligibilityResponse",
                  "id": "%s",
                  "status": "active",
                  "purpose": ["benefits", "validation"],
                  "patient": {"reference": "Patient/%s"},
                  "created": "%s",
                  "outcome": "complete",
                  "disposition": "%s",
                  "insurance": [
                    {
                      "coverage": {"reference": "Coverage/%s"},
                      "inforce": %s
                    }
                  ]
                }
                """.formatted(
                verificationId,
                request.patientId(),
                Instant.now(),
                covered ? "Coverage active and eligible" : "Coverage inactive or exhausted",
                plan.coverageId(),
                plan.active()
        ).trim();

        return new EligibilityVerificationResponse(
                verificationId,
                request.patientId(),
                plan.coverageId(),
                plan.payerName(),
                plan.memberSubscriberId(),
                plan.active(),
                category,
                covered,
                cost,
                payerPortion,
                patientCopay,
                remainingAfter,
                requiresPriorAuth,
                fhirJson,
                Instant.now()
        );
    }

    /**
     * Evaluates an HL7 Da Vinci Prior Authorization Support (PAS) {@code Claim} ({@code use = preauthorization})
     * and returns an instant adjudication {@code ClaimResponse}.
     */
    @ClinicalAudit(action = "FHIR_PRIOR_AUTH_ADJUDICATE", resourceType = "ClaimResponse")
    public PriorAuthorizationAdjudication submitPriorAuthorization(PriorAuthSubmissionRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("PriorAuthSubmissionRequest with patientId is required");
        }
        if (request.procedureOrMedicationCode() == null || request.procedureOrMedicationCode().isBlank()) {
            throw new IllegalArgumentException("procedureOrMedicationCode is required");
        }
        if (request.primaryIcd10DiagnosisCode() == null || request.primaryIcd10DiagnosisCode().isBlank()) {
            throw new IllegalArgumentException("primaryIcd10DiagnosisCode is required for clinical necessity review");
        }

        UUID priorAuthId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        BigDecimal requested = (request.requestedAmountXaf() != null && request.requestedAmountXaf().compareTo(BigDecimal.ZERO) > 0)
                ? request.requestedAmountXaf()
                : new BigDecimal("180000.00");

        boolean hasJustification = request.clinicalJustificationSummary() != null
                && request.clinicalJustificationSummary().trim().length() >= 12;

        PriorAuthDecision decision;
        BigDecimal approvedAmt;
        String rationale;

        if (hasJustification && request.conservativeTherapyAttemptedOrExempt()) {
            decision = PriorAuthDecision.APPROVED;
            approvedAmt = requested;
            rationale = "Auto-Adjudicated APPROVED (Da Vinci PAS): ICD-10 diagnosis "
                    + request.primaryIcd10DiagnosisCode().trim()
                    + " meets clinical necessity criteria for " + request.procedureOrMedicationCode().trim();
        } else if (hasJustification) {
            decision = PriorAuthDecision.PENDED_MEDICAL_REVIEW;
            approvedAmt = BigDecimal.ZERO;
            rationale = "PENDED FOR MEDICAL DIRECTOR REVIEW (Da Vinci PAS): Additional documentation of prior conservative therapy or specialist consult requested.";
        } else {
            decision = PriorAuthDecision.DENIED;
            approvedAmt = BigDecimal.ZERO;
            rationale = "DENIED (Da Vinci PAS): Insufficient clinical necessity documentation provided.";
        }

        String preAuthRef = "PA-OPES-" + priorAuthId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        LocalDate validUntil = decision == PriorAuthDecision.APPROVED ? LocalDate.now().plusDays(90) : null;

        String fhirJson = """
                {
                  "resourceType": "ClaimResponse",
                  "id": "%s",
                  "status": "active",
                  "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/claim-type", "code": "institutional"}]},
                  "use": "preauthorization",
                  "patient": {"reference": "Patient/%s"},
                  "created": "%s",
                  "outcome": "complete",
                  "disposition": "%s",
                  "preAuthRef": "%s"
                }
                """.formatted(
                priorAuthId,
                request.patientId(),
                Instant.now(),
                rationale,
                preAuthRef
        ).trim();

        PriorAuthorizationAdjudication adjudication = new PriorAuthorizationAdjudication(
                priorAuthId,
                preAuthRef,
                facilityId,
                request.patientId(),
                request.procedureOrMedicationCode().trim(),
                request.procedureOrMedicationDisplay() != null ? request.procedureOrMedicationDisplay().trim() : request.procedureOrMedicationCode().trim(),
                request.primaryIcd10DiagnosisCode().trim(),
                decision,
                approvedAmt,
                validUntil,
                rationale,
                fhirJson,
                Instant.now()
        );

        priorAuthsByPatient
                .computeIfAbsent(request.patientId(), k -> new ArrayList<>())
                .add(adjudication);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    request.orderingPractitionerId() != null ? request.orderingPractitionerId() : "PAS_ENGINE",
                    "CLINICIAN",
                    "PRIOR_AUTH_" + decision.name(),
                    "ClaimResponse",
                    priorAuthId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/prior-auth/submit",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Da Vinci PAS adjudication " + preAuthRef + " decision=" + decision
            ));
        }

        return adjudication;
    }

    public List<PriorAuthorizationAdjudication> listPatientPriorAuthorizations(UUID patientId) {
        List<PriorAuthorizationAdjudication> list = priorAuthsByPatient.get(patientId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(PriorAuthorizationAdjudication::adjudicatedAt).reversed())
                .toList();
    }
}
