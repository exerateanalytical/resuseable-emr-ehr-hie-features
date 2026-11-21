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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Social Determinants of Health (SDoH — HL7 Gravity Project) Screening &amp; Closed-Loop
 * Community Health Worker (CHW) Referral ({@code ServiceRequest} / {@code Task}) Service.
 *
 * <p>Evaluates LOINC-standardized SDoH risk domains ({@code FOOD_INSECURITY},
 * {@code HOUSING_INSTABILITY}, {@code TRANSPORTATION_BARRIER}, {@code CLEAN_WATER_SANITATION},
 * {@code UTILITY_INSECURITY}, {@code INTERPERSONAL_SAFETY}), maps positive findings to
 * ICD-10 Z55–Z65 SDoH {@code Condition} codes, and tracks closed-loop community referrals
 * from issuance through CHW fulfillment.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class SdohGravityReferralService {

    public enum SdohDomain {
        FOOD_INSECURITY(
                "88122-7",
                "Hunger Vital Sign [HVS] Food Insecurity Screening",
                "Z59.41",
                "Food insecurity",
                "710925007",
                "Provision of community nutrition & therapeutic food package"
        ),
        HOUSING_INSTABILITY(
                "71802-3",
                "Housing status & stability assessment",
                "Z59.0",
                "Homelessness / Severe housing instability",
                "410346003",
                "Emergency shelter & municipal social housing assistance referral"
        ),
        TRANSPORTATION_BARRIER(
                "93030-5",
                "Lack of transportation kept you from medical appointments",
                "Z59.82",
                "Transportation insecurity",
                "410366001",
                "Medical transport voucher & mobile CHW home-visit arrangement"
        ),
        CLEAN_WATER_SANITATION(
                "96778-6",
                "Household access to safe drinking water and sanitation (WASH)",
                "Z58.6",
                "Inadequate drinking-water supply",
                "410352008",
                "Community WASH clean water filtration & hygiene kit dispatch"
        ),
        UTILITY_INSECURITY(
                "96779-4",
                "Electric, gas, oil, or water utility shutoff threat in past 12 months",
                "Z59.1",
                "Inadequate housing utilities",
                "410347007",
                "Household energy & cold-chain medication storage support referral"
        ),
        INTERPERSONAL_SAFETY(
                "76501-6",
                "HARK Interpersonal & Domestic Safety Screening",
                "Z63.0",
                "Problems in relationship with spouse or partner / safety risk",
                "410360007",
                "Confidential social worker & safe-haven protection referral"
        );

        private final String screeningLoincCode;
        private final String screeningDisplay;
        private final String icd10ZCode;
        private final String icd10Display;
        private final String referralSnomedCode;
        private final String defaultInterventionSummary;

        SdohDomain(
                String screeningLoincCode,
                String screeningDisplay,
                String icd10ZCode,
                String icd10Display,
                String referralSnomedCode,
                String defaultInterventionSummary) {
            this.screeningLoincCode = screeningLoincCode;
            this.screeningDisplay = screeningDisplay;
            this.icd10ZCode = icd10ZCode;
            this.icd10Display = icd10Display;
            this.referralSnomedCode = referralSnomedCode;
            this.defaultInterventionSummary = defaultInterventionSummary;
        }

        public String screeningLoincCode() {
            return screeningLoincCode;
        }

        public String screeningDisplay() {
            return screeningDisplay;
        }

        public String icd10ZCode() {
            return icd10ZCode;
        }

        public String icd10Display() {
            return icd10Display;
        }

        public String referralSnomedCode() {
            return referralSnomedCode;
        }

        public String defaultInterventionSummary() {
            return defaultInterventionSummary;
        }
    }

    public enum SdohRiskLevel {
        NO_RISK_IDENTIFIED,
        MODERATE_RISK,
        HIGH_URGENT_RISK
    }

    public enum ReferralLoopStatus {
        ISSUED,
        ACCEPTED_BY_CHW,
        IN_PROGRESS,
        FULFILLED_CLOSED,
        DECLINED_BY_PATIENT
    }

    public record SdohScreeningRequest(
            UUID facilityId,
            UUID patientId,
            SdohDomain domain,
            SdohRiskLevel riskLevel,
            String patientReportedNarrative,
            String targetCommunityOrganization,
            String assignedChwWorkerId,
            boolean autoCreateClosedLoopReferral
    ) {}

    public record SdohCommunityReferral(
            UUID referralId,
            UUID screeningId,
            UUID facilityId,
            UUID patientId,
            SdohDomain domain,
            String icd10ConditionZCode,
            String referralSnomedCode,
            String interventionSummary,
            String targetCommunityOrganization,
            String assignedChwWorkerId,
            ReferralLoopStatus status,
            String outcomeResolutionNotes,
            String fhirServiceRequestAndTaskJson,
            Instant issuedAt,
            Instant updatedAt,
            Instant closedAt
    ) {}

    public record SdohScreeningEvaluation(
            UUID screeningId,
            UUID facilityId,
            UUID patientId,
            SdohDomain domain,
            String screeningLoincCode,
            SdohRiskLevel riskLevel,
            boolean positiveRiskDetected,
            String generatedIcd10ZCode,
            String generatedIcd10Display,
            UUID closedLoopReferralId,
            SdohCommunityReferral referral,
            Instant screenedAt
    ) {}

    public record ReferralStatusUpdateRequest(
            ReferralLoopStatus nextStatus,
            String assignedChwWorkerId,
            String outcomeResolutionNotes
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, List<SdohScreeningEvaluation>> screeningsByPatient = new ConcurrentHashMap<>();
    private final Map<UUID, SdohCommunityReferral> referralsById = new ConcurrentHashMap<>();

    @Autowired
    public SdohGravityReferralService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public SdohGravityReferralService() {
        this(null);
    }

    /**
     * Records a Gravity Project SDoH screening, maps positive screens to ICD-10 Z55–Z65
     * {@code Condition} diagnoses, and initiates a closed-loop CHW / community social care referral.
     */
    @ClinicalAudit(action = "SDOH_GRAVITY_SCREEN_EVALUATE", resourceType = "ServiceRequest")
    public SdohScreeningEvaluation evaluateSdohScreening(SdohScreeningRequest request) {
        if (request == null || request.patientId() == null || request.domain() == null) {
            throw new IllegalArgumentException("SdohScreeningRequest with patientId and domain is required");
        }

        UUID screeningId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        SdohDomain domain = request.domain();
        SdohRiskLevel risk = request.riskLevel() != null ? request.riskLevel() : SdohRiskLevel.MODERATE_RISK;
        boolean positive = risk != SdohRiskLevel.NO_RISK_IDENTIFIED;

        SdohCommunityReferral referral = null;
        if (positive && request.autoCreateClosedLoopReferral()) {
            UUID referralId = UUID.randomUUID();
            Instant now = Instant.now();
            String org = (request.targetCommunityOrganization() != null && !request.targetCommunityOrganization().isBlank())
                    ? request.targetCommunityOrganization().trim()
                    : "Douala Community Health & Social Support Network";
            String chw = (request.assignedChwWorkerId() != null && !request.assignedChwWorkerId().isBlank())
                    ? request.assignedChwWorkerId().trim()
                    : "CHW-LITTORAL-01";

            String fhirJson = buildFhirServiceRequestTaskJson(
                    referralId, request.patientId(), domain, risk, org, chw, ReferralLoopStatus.ISSUED);

            referral = new SdohCommunityReferral(
                    referralId,
                    screeningId,
                    facilityId,
                    request.patientId(),
                    domain,
                    domain.icd10ZCode(),
                    domain.referralSnomedCode(),
                    domain.defaultInterventionSummary(),
                    org,
                    chw,
                    ReferralLoopStatus.ISSUED,
                    request.patientReportedNarrative(),
                    fhirJson,
                    now,
                    now,
                    null
            );
            referralsById.put(referralId, referral);
        }

        SdohScreeningEvaluation evaluation = new SdohScreeningEvaluation(
                screeningId,
                facilityId,
                request.patientId(),
                domain,
                domain.screeningLoincCode(),
                risk,
                positive,
                positive ? domain.icd10ZCode() : null,
                positive ? domain.icd10Display() : null,
                referral != null ? referral.referralId() : null,
                referral,
                Instant.now()
        );

        screeningsByPatient
                .computeIfAbsent(request.patientId(), k -> new ArrayList<>())
                .add(evaluation);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "SDOH_GRAVITY_ENGINE",
                    "SOCIAL_WORKER_CHW",
                    "SDOH_SCREENING_" + domain.name(),
                    "ServiceRequest",
                    referral != null ? referral.referralId().toString() : screeningId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/sdoh/screenings",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Evaluated SDoH domain " + domain + " risk=" + risk
                            + (positive ? " -> ICD-10 " + domain.icd10ZCode() : "")
            ));
        }

        return evaluation;
    }

    /**
     * Advances or closes the loop on an SDoH community/CHW referral (`ISSUED` -&gt; `ACCEPTED_BY_CHW`
     * -&gt; `IN_PROGRESS` -&gt; `FULFILLED_CLOSED`).
     */
    @ClinicalAudit(action = "SDOH_REFERRAL_LOOP_UPDATE", resourceType = "Task")
    public SdohCommunityReferral updateReferralStatus(UUID referralId, ReferralStatusUpdateRequest update) {
        if (referralId == null || update == null || update.nextStatus() == null) {
            throw new IllegalArgumentException("referralId and ReferralLoopStatus nextStatus are required");
        }
        SdohCommunityReferral existing = referralsById.get(referralId);
        if (existing == null) {
            throw new NoSuchElementException("SDoH Community Referral not found: " + referralId);
        }

        Instant now = Instant.now();
        String chw = (update.assignedChwWorkerId() != null && !update.assignedChwWorkerId().isBlank())
                ? update.assignedChwWorkerId().trim()
                : existing.assignedChwWorkerId();
        String notes = (update.outcomeResolutionNotes() != null && !update.outcomeResolutionNotes().isBlank())
                ? update.outcomeResolutionNotes().trim()
                : existing.outcomeResolutionNotes();

        boolean closed = update.nextStatus() == ReferralLoopStatus.FULFILLED_CLOSED
                || update.nextStatus() == ReferralLoopStatus.DECLINED_BY_PATIENT;

        String fhirJson = buildFhirServiceRequestTaskJson(
                existing.referralId(),
                existing.patientId(),
                existing.domain(),
                SdohRiskLevel.HIGH_URGENT_RISK,
                existing.targetCommunityOrganization(),
                chw,
                update.nextStatus()
        );

        SdohCommunityReferral updated = new SdohCommunityReferral(
                existing.referralId(),
                existing.screeningId(),
                existing.facilityId(),
                existing.patientId(),
                existing.domain(),
                existing.icd10ConditionZCode(),
                existing.referralSnomedCode(),
                existing.interventionSummary(),
                existing.targetCommunityOrganization(),
                chw,
                update.nextStatus(),
                notes,
                fhirJson,
                existing.issuedAt(),
                now,
                closed ? now : null
        );

        referralsById.put(referralId, updated);
        return updated;
    }

    public List<SdohScreeningEvaluation> listPatientScreenings(UUID patientId) {
        List<SdohScreeningEvaluation> list = screeningsByPatient.get(patientId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(SdohScreeningEvaluation::screenedAt).reversed())
                .toList();
    }

    public List<SdohCommunityReferral> listPatientReferrals(UUID patientId) {
        return referralsById.values().stream()
                .filter(r -> patientId == null || Objects.equals(patientId, r.patientId()))
                .sorted(Comparator.comparing(SdohCommunityReferral::issuedAt).reversed())
                .toList();
    }

    private static String buildFhirServiceRequestTaskJson(
            UUID referralId,
            UUID patientId,
            SdohDomain domain,
            SdohRiskLevel risk,
            String targetOrg,
            String chwId,
            ReferralLoopStatus status) {
        String taskStatus = switch (status) {
            case ISSUED -> "requested";
            case ACCEPTED_BY_CHW -> "accepted";
            case IN_PROGRESS -> "in-progress";
            case FULFILLED_CLOSED -> "completed";
            case DECLINED_BY_PATIENT -> "rejected";
        };
        return """
                {
                  "resourceType": "ServiceRequest",
                  "id": "%s",
                  "meta": {"profile": ["http://hl7.org/fhir/us/sdoh-clinicalcare/StructureDefinition/SDOHCC-ServiceRequest"]},
                  "status": "%s",
                  "intent": "order",
                  "category": [{"coding": [{"system": "http://snomed.info/sct", "code": "410606002", "display": "Social service procedure"}]}],
                  "priority": "%s",
                  "code": {"coding": [{"system": "http://snomed.info/sct", "code": "%s", "display": "%s"}]},
                  "subject": {"reference": "Patient/%s"},
                  "reasonCode": [{"coding": [{"system": "http://hl7.org/fhir/sid/icd-10", "code": "%s", "display": "%s"}]}],
                  "performer": [{"display": "%s (CHW: %s)"}],
                  "taskLoopStatus": "%s"
                }
                """.formatted(
                referralId,
                status == ReferralLoopStatus.FULFILLED_CLOSED ? "completed" : "active",
                risk == SdohRiskLevel.HIGH_URGENT_RISK ? "urgent" : "routine",
                domain.referralSnomedCode(),
                domain.defaultInterventionSummary(),
                patientId,
                domain.icd10ZCode(),
                domain.icd10Display(),
                targetOrg,
                chwId,
                taskStatus
        ).trim();
    }
}
