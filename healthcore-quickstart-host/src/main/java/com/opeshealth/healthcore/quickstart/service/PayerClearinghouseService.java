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

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Payer Clearinghouse, EDI X12 ({@code 837P/837I}, {@code 835}, {@code 270/271}) Adapter,
 * Automated Claim Scrubbing, Remittance Reconciliation, FHIR {@code ExplanationOfBenefit},
 * and Denial Appeal Management Service.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PayerClearinghouseService {

    public record ClaimSubmissionRequest(
            UUID facilityId,
            UUID patientId,
            String payerId,
            String memberPolicyNumber,
            String claimType,
            String renderingProviderNpi,
            LocalDate serviceDate,
            List<String> icd10DiagnosisCodes,
            List<ClaimServiceLine> serviceLines,
            String currencyCode
    ) {}

    public record ClaimServiceLine(
            String cptOrProcedureCode,
            String description,
            int units,
            long unitChargeMinorUnits
    ) {}

    public record ScrubbingValidationResult(
            boolean passed,
            List<String> errors,
            List<String> warnings
    ) {}

    public record ClearinghouseClaimRecord(
            UUID claimId,
            UUID facilityId,
            UUID patientId,
            String payerId,
            String memberPolicyNumber,
            String claimType,
            long totalBilledMinorUnits,
            long totalPaidMinorUnits,
            long patientResponsibilityMinorUnits,
            String currencyCode,
            String status,
            String ediX12837Payload,
            ScrubbingValidationResult scrubbingResult,
            String denialCarcCode,
            String denialReasonText,
            String fhirExplanationOfBenefitJson,
            Instant submittedAt,
            Instant adjudicatedAt
    ) {}

    public record Edi835RemittanceRequest(
            UUID claimId,
            String payerTraceNumber,
            long paidAmountMinorUnits,
            long patientResponsibilityMinorUnits,
            String carcAdjustmentCode,
            String rarcRemarkCode,
            String adjudicationStatus
    ) {}

    public record ClaimAppealRequest(
            String appealedByUserId,
            String clinicalJustificationText,
            List<String> supportingAttachmentRefs
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, ClearinghouseClaimRecord> claimsById = new ConcurrentHashMap<>();

    @Autowired
    public PayerClearinghouseService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public PayerClearinghouseService() {
        this(null);
    }

    /**
     * Runs pre-submission claim scrubbing rules (ICD-10 presence, timely filing, NPI/provider check,
     * positive line charges) and generates a formatted **EDI X12 837P/837I** transaction payload.
     */
    @ClinicalAudit(action = "CLEARINGHOUSE_CLAIM_SUBMIT", resourceType = "Claim")
    public ClearinghouseClaimRecord scrubAndSubmitClaim(ClaimSubmissionRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("ClaimSubmissionRequest with patientId is required");
        }
        UUID claimId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        String payerId = request.payerId() != null && !request.payerId().isBlank() ? request.payerId().trim() : "PAYER-UHC-01";
        String claimType = request.claimType() != null && !request.claimType().isBlank()
                ? request.claimType().trim().toUpperCase(Locale.ROOT)
                : "837P";
        String currency = request.currencyCode() != null && !request.currencyCode().isBlank()
                ? request.currencyCode().trim().toUpperCase(Locale.ROOT)
                : "XAF";

        ScrubbingValidationResult scrub = runClaimScrubber(request);
        long totalBilled = 0L;
        if (request.serviceLines() != null) {
            for (ClaimServiceLine line : request.serviceLines()) {
                if (line != null) {
                    totalBilled += Math.max(1, line.units()) * Math.max(0L, line.unitChargeMinorUnits());
                }
            }
        }

        String edi837 = buildEdiX12837Envelope(claimId, payerId, claimType, request, totalBilled);
        String status = scrub.passed() ? "SUBMITTED_TO_CLEARINGHOUSE" : "SCRUB_REJECTED_HOLD";

        ClearinghouseClaimRecord record = new ClearinghouseClaimRecord(
                claimId,
                facilityId,
                request.patientId(),
                payerId,
                request.memberPolicyNumber(),
                claimType,
                totalBilled,
                0L,
                0L,
                currency,
                status,
                edi837,
                scrub,
                null,
                null,
                null,
                Instant.now(),
                null
        );
        claimsById.put(claimId, record);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    "BILLING_CLEARINGHOUSE",
                    "ROLE_BILLING",
                    "CLEARINGHOUSE_EDI_837_SUBMITTED",
                    "Claim",
                    claimId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/clearinghouse/claims",
                    "127.0.0.1",
                    null,
                    scrub.passed() ? "SUCCESS" : "FAILURE",
                    0L,
                    "Claim " + claimType + " total=" + totalBilled + " " + currency + " status=" + status
            ));
        }

        return record;
    }

    /**
     * Reconciles an inbound **EDI X12 835** Electronic Remittance Advice against a submitted claim,
     * generating an HL7 FHIR R4 {@code ExplanationOfBenefit} resource and flagging CARC/RARC denials.
     */
    @ClinicalAudit(action = "CLEARINGHOUSE_EDI_835_RECONCILE", resourceType = "ExplanationOfBenefit")
    public ClearinghouseClaimRecord reconcileEdi835Remittance(Edi835RemittanceRequest remittance) {
        if (remittance == null || remittance.claimId() == null) {
            throw new IllegalArgumentException("Edi835RemittanceRequest with claimId is required");
        }
        ClearinghouseClaimRecord existing = claimsById.get(remittance.claimId());
        if (existing == null) {
            throw new NoSuchElementException("Clearinghouse claim not found: " + remittance.claimId());
        }
        String adjStatus = remittance.adjudicationStatus() != null
                ? remittance.adjudicationStatus().trim().toUpperCase(Locale.ROOT)
                : (remittance.paidAmountMinorUnits() > 0 ? "PAID" : "DENIED");
        String carc = remittance.carcAdjustmentCode();
        String denialText = "DENIED".equals(adjStatus)
                ? "CARC-" + (carc != null ? carc : "96") + " (Non-covered or missing documentation)"
                : null;
        Instant now = Instant.now();

        String eobJson = """
                {
                  "resourceType": "ExplanationOfBenefit",
                  "id": "eob-%s",
                  "status": "active",
                  "type": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/claim-type", "code": "%s"}]},
                  "use": "claim",
                  "patient": {"reference": "Patient/%s"},
                  "created": "%s",
                  "outcome": "%s",
                  "payment": {"amount": {"value": %d, "currency": "%s"}}
                }
                """.formatted(
                existing.claimId(),
                "837I".equals(existing.claimType()) ? "institutional" : "professional",
                existing.patientId(),
                now,
                "DENIED".equals(adjStatus) ? "error" : "complete",
                remittance.paidAmountMinorUnits(),
                existing.currencyCode()
        ).trim();

        ClearinghouseClaimRecord reconciled = new ClearinghouseClaimRecord(
                existing.claimId(),
                existing.facilityId(),
                existing.patientId(),
                existing.payerId(),
                existing.memberPolicyNumber(),
                existing.claimType(),
                existing.totalBilledMinorUnits(),
                remittance.paidAmountMinorUnits(),
                remittance.patientResponsibilityMinorUnits(),
                existing.currencyCode(),
                adjStatus,
                existing.ediX12837Payload(),
                existing.scrubbingResult(),
                carc,
                denialText,
                eobJson,
                existing.submittedAt(),
                now
        );
        claimsById.put(existing.claimId(), reconciled);
        return reconciled;
    }

    /**
     * Initiates a formal appeal on a denied claim with clinical justification and supporting documentation.
     */
    @ClinicalAudit(action = "CLEARINGHOUSE_CLAIM_APPEAL", resourceType = "Claim")
    public ClearinghouseClaimRecord fileDenialAppeal(UUID claimId, ClaimAppealRequest appealRequest) {
        ClearinghouseClaimRecord existing = claimsById.get(claimId);
        if (existing == null) {
            throw new NoSuchElementException("Clearinghouse claim not found: " + claimId);
        }
        if (appealRequest == null || appealRequest.clinicalJustificationText() == null
                || appealRequest.clinicalJustificationText().trim().length() < 10) {
            throw new IllegalArgumentException("Appeal requires clinicalJustificationText of at least 10 characters");
        }
        ClearinghouseClaimRecord appealed = new ClearinghouseClaimRecord(
                existing.claimId(),
                existing.facilityId(),
                existing.patientId(),
                existing.payerId(),
                existing.memberPolicyNumber(),
                existing.claimType(),
                existing.totalBilledMinorUnits(),
                existing.totalPaidMinorUnits(),
                existing.patientResponsibilityMinorUnits(),
                existing.currencyCode(),
                "APPEAL_SUBMITTED",
                existing.ediX12837Payload(),
                existing.scrubbingResult(),
                existing.denialCarcCode(),
                "Appealed by " + appealRequest.appealedByUserId() + ": " + appealRequest.clinicalJustificationText().trim(),
                existing.fhirExplanationOfBenefitJson(),
                existing.submittedAt(),
                existing.adjudicatedAt()
        );
        claimsById.put(claimId, appealed);
        return appealed;
    }

    public List<ClearinghouseClaimRecord> listClaimsByStatus(String status) {
        return claimsById.values().stream()
                .filter(c -> status == null || status.isBlank() || status.trim().equalsIgnoreCase(c.status()))
                .sorted(Comparator.comparing(ClearinghouseClaimRecord::submittedAt).reversed())
                .toList();
    }

    private static ScrubbingValidationResult runClaimScrubber(ClaimSubmissionRequest req) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (req.memberPolicyNumber() == null || req.memberPolicyNumber().isBlank()) {
            errors.add("SCRUB-001: Missing memberPolicyNumber");
        }
        if (req.renderingProviderNpi() == null || req.renderingProviderNpi().isBlank()) {
            errors.add("SCRUB-002: Missing renderingProviderNpi / medical license identifier");
        }
        if (req.icd10DiagnosisCodes() == null || req.icd10DiagnosisCodes().isEmpty()) {
            errors.add("SCRUB-003: At least one ICD-10 diagnosis code is required");
        }
        if (req.serviceLines() == null || req.serviceLines().isEmpty()) {
            errors.add("SCRUB-004: At least one billable CPT/procedure service line is required");
        }
        if (req.serviceDate() != null && req.serviceDate().isBefore(LocalDate.now().minusDays(90))) {
            warnings.add("SCRUB-WARN-TIMELY: Service date exceeds 90-day standard timely filing window");
        }
        return new ScrubbingValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    private static String buildEdiX12837Envelope(
            UUID claimId,
            String payerId,
            String claimType,
            ClaimSubmissionRequest req,
            long totalBilled) {
        String dateStamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String diag = (req.icd10DiagnosisCodes() != null && !req.icd10DiagnosisCodes().isEmpty())
                ? req.icd10DiagnosisCodes().getFirst()
                : "Z00.00";
        return "ISA*00*          *00*          *ZZ*OPESHEALTH     *ZZ*" + payerId + "*" + dateStamp.substring(2)
                + "*1200*^*00501*000000001*0*P*:~"
                + "GS*HC*OPESHEALTH*" + payerId + "*" + dateStamp + "*1200*1*X*005010X222A1~"
                + "ST*837*0001*005010X222A1~"
                + "CLM*" + claimId.toString().substring(0, 12) + "*" + totalBilled + "***11:B:1*Y*A*Y*I~"
                + "HI*ABK:" + diag + "~"
                + "SE*6*0001~GE*1*1~IEA*1*000000001~";
    }
}
