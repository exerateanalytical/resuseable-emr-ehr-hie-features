/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.ConsentService;
import com.opeshealth.healthcore.quickstart.service.ConsentService.BreakTheGlassAuditFlag;
import com.opeshealth.healthcore.quickstart.service.ConsentService.BreakTheGlassComplianceReviewRequest;
import com.opeshealth.healthcore.quickstart.service.ConsentService.BreakTheGlassInvocationResult;
import com.opeshealth.healthcore.quickstart.service.ConsentService.Ds4pClassificationRequest;
import com.opeshealth.healthcore.quickstart.service.ConsentService.Ds4pClassificationResult;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassSession;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentDirectiveRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.FhirConsentDirective;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SegmentedAccessEvaluationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * HL7 FHIR R4 {@code Consent} Lifecycle, Data Segmentation for Privacy (DS4P), and
 * Break-the-Glass ({@code BTG} / {@code ETREAT}) Emergency Access REST Controller.
 *
 * <p>Exposes endpoints for registering, searching, retrieving, and revoking HL7 FHIR R4
 * {@code Consent} directives, evaluating DS4P confidentiality segmentation rules, classifying
 * clinical content for DS4P sensitivity categories, invoking Break-the-Glass emergency overrides
 * with mandatory audit flagging, and adjudicating flagged emergency sessions.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@Tag(name = "FHIR R4 Consent, DS4P Privacy & Break-the-Glass",
        description = "HL7 FHIR R4 Consent lifecycle, DS4P Data Segmentation for Privacy (HIV, PSY, ETH, SDV, GEN, VIP), and Break-the-Glass (ETREAT) emergency access with mandatory HIPAA/GDPR audit flagging")
public class ConsentController {

    private static final String FHIR_JSON = "application/fhir+json";

    public record Ds4pEvaluationRequest(
            UUID patientId,
            String actorId,
            String purposeOfUse,
            String breakTheGlassToken,
            List<ClinicalSegmentItem> clinicalItems
    ) {}

    public record ConsentRevocationRequest(
            String actorId,
            String reason
    ) {}

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = Objects.requireNonNull(consentService, "ConsentService is required");
    }

    /**
     * Registers a structured HL7 FHIR R4 {@code Consent} privacy directive governing DS4P
     * confidentiality and sensitivity categories.
     */
    @PostMapping(
            value = {"/api/v1/consent/directives", "/api/v1/fhir/Consent"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Register an HL7 FHIR R4 Consent privacy directive with DS4P confidentiality categories",
            description = "Creates an active FHIR R4 Consent directive specifying PERMIT or DENY provisions across DS4P sensitivity categories (HIV, PSY, ETH, SDV, GEN, VIP), authorized actors, and purpose-of-use codes."
    )
    public ResponseEntity<FhirConsentDirective> registerConsentDirective(
            @RequestBody ConsentDirectiveRequest request) {
        return ResponseEntity.status(201).body(consentService.registerConsentDirective(request));
    }

    /**
     * Creates an HL7 FHIR R4 {@code Consent} directive from a raw HL7 FHIR R4 {@code Consent} JSON resource.
     */
    @PostMapping(
            value = "/api/v1/fhir/Consent/raw",
            consumes = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Create an HL7 FHIR R4 Consent directive from a raw FHIR R4 Consent JSON payload")
    public ResponseEntity<FhirConsentDirective> createConsentFromRawFhirJson(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestBody String rawFhirConsentJson) {
        return ResponseEntity.status(201).body(
                consentService.createConsentFromFhirJson(facilityHeader, rawFhirConsentJson));
    }

    /**
     * Lists HL7 FHIR R4 {@code Consent} directives for a patient (or all directives if {@code patientId} is omitted).
     */
    @GetMapping(value = "/api/v1/consent/directives", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List patient HL7 FHIR R4 Consent directives")
    public ResponseEntity<List<FhirConsentDirective>> listPatientConsents(
            @RequestParam(value = "patientId", required = false) UUID patientId) {
        return ResponseEntity.ok(consentService.listPatientConsents(patientId));
    }

    /**
     * Retrieves a specific {@link FhirConsentDirective} envelope by its UUID.
     */
    @GetMapping(value = "/api/v1/consent/directives/{consentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a specific FHIR Consent directive by consentId")
    public ResponseEntity<FhirConsentDirective> getConsentDirectiveById(
            @PathVariable("consentId") UUID consentId) {
        return ResponseEntity.ok(consentService.getConsentById(consentId));
    }

    /**
     * Retrieves the raw HL7 FHIR R4 {@code Consent} JSON resource by its UUID.
     */
    @GetMapping(
            value = "/api/v1/fhir/Consent/{consentId}",
            produces = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    @Operation(summary = "Read an HL7 FHIR R4 Consent resource in application/fhir+json format")
    public ResponseEntity<String> getFhirConsentResourceJson(
            @PathVariable("consentId") UUID consentId) {
        FhirConsentDirective directive = consentService.getConsentById(consentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FHIR_JSON))
                .body(directive.fhirConsentJson());
    }

    /**
     * Searches HL7 FHIR R4 {@code Consent} resources and returns an HL7 FHIR R4 {@code Bundle} ({@code searchset}).
     */
    @GetMapping(
            value = "/api/v1/fhir/Consent",
            produces = {FHIR_JSON, MediaType.APPLICATION_JSON_VALUE}
    )
    @Operation(summary = "Search HL7 FHIR R4 Consent resources and return a FHIR searchset Bundle")
    public ResponseEntity<String> searchFhirConsentBundle(
            @RequestParam(value = "patient", required = false) UUID patientId,
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FHIR_JSON))
                .body(consentService.searchFhirConsentBundleJson(patientId, status));
    }

    /**
     * Revokes an active HL7 FHIR R4 {@code Consent} directive and records a HIPAA/GDPR audit trail event.
     */
    @PostMapping(
            value = "/api/v1/consent/directives/{consentId}/revoke",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Revoke an active HL7 FHIR R4 Consent directive")
    public ResponseEntity<FhirConsentDirective> revokeConsentDirective(
            @PathVariable("consentId") UUID consentId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @RequestParam(value = "reason", required = false) String reasonParam,
            @RequestBody(required = false) ConsentRevocationRequest body) {
        String actor = (body != null && body.actorId() != null && !body.actorId().isBlank())
                ? body.actorId()
                : actorHeader;
        String reason = (body != null && body.reason() != null && !body.reason().isBlank())
                ? body.reason()
                : reasonParam;
        return ResponseEntity.ok(consentService.revokeConsentDirective(consentId, actor, reason));
    }

    /**
     * Standard FHIR {@code DELETE /api/v1/fhir/Consent/{consentId}} deactivation endpoint.
     */
    @DeleteMapping(value = "/api/v1/fhir/Consent/{consentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Deactivate (revoke) an HL7 FHIR R4 Consent resource via DELETE")
    public ResponseEntity<FhirConsentDirective> deleteFhirConsent(
            @PathVariable("consentId") UUID consentId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actorHeader,
            @RequestParam(value = "reason", required = false) String reasonParam) {
        return ResponseEntity.ok(consentService.revokeConsentDirective(consentId, actorHeader, reasonParam));
    }

    /**
     * Evaluates a set of clinical items under HL7 DS4P Data Segmentation for Privacy rules,
     * redacting restricted items unless permitted by an active {@code Consent} directive or
     * a valid Break-the-Glass emergency override token.
     */
    @PostMapping(
            value = {"/api/v1/consent/evaluate-ds4p", "/api/v1/fhir/Consent/$evaluate-ds4p"},
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Evaluate clinical items under HL7 DS4P Data Segmentation for Privacy rules",
            description = "Applies active patient FHIR Consent directives and Break-the-Glass tokens to filter or redact sensitive clinical segments (HIV, PSY, ETH, SDV, GEN, VIP)."
    )
    public ResponseEntity<SegmentedAccessEvaluationResult> evaluateDs4pSegmentation(
            @RequestHeader(value = "X-Break-The-Glass-Token", required = false) String btgHeader,
            @RequestBody Ds4pEvaluationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Ds4pEvaluationRequest is required");
        }
        String resolvedToken = (request.breakTheGlassToken() != null && !request.breakTheGlassToken().isBlank())
                ? request.breakTheGlassToken()
                : btgHeader;
        return ResponseEntity.ok(consentService.evaluateSegmentedAccess(
                request.patientId(),
                request.actorId(),
                request.purposeOfUse(),
                resolvedToken,
                request.clinicalItems()
        ));
    }

    /**
     * Classifies clinical text and diagnostic codes under HL7 DS4P sensitivity rules and
     * returns required security labels and privacy obligation codes.
     */
    @PostMapping(
            value = "/api/v1/consent/classify-ds4p",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Classify clinical text or diagnostic codes into HL7 DS4P confidentiality and sensitivity labels")
    public ResponseEntity<Ds4pClassificationResult> classifyClinicalContent(
            @RequestBody Ds4pClassificationRequest request) {
        return ResponseEntity.ok(consentService.classifyClinicalContent(request));
    }

    /**
     * Invokes a time-bound Break-the-Glass ({@code BTG} / {@code ETREAT}) emergency privacy override
     * and generates a mandatory compliance audit flag in the SHA-256 audit log chain.
     */
    @PostMapping(
            value = "/api/v1/consent/break-the-glass",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Invoke a time-bound Break-the-Glass (BTG / ETREAT) emergency privacy override",
            description = "Requires authenticated clinicianId and mandatory emergencyReason (>= 10 chars). Issues an ETREAT emergency token and creates a mandatory HIPAA/GDPR compliance audit flag."
    )
    public ResponseEntity<BreakTheGlassSession> invokeBreakTheGlass(
            @RequestBody BreakTheGlassRequest request) {
        return ResponseEntity.status(201).body(consentService.invokeBreakTheGlass(request));
    }

    /**
     * Invokes a Break-the-Glass emergency override and returns both the {@link BreakTheGlassSession}
     * and the created {@link BreakTheGlassAuditFlag} envelope.
     */
    @PostMapping(
            value = "/api/v1/fhir/Consent/$break-the-glass",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
            summary = "Invoke Break-the-Glass ($break-the-glass) and return both the session token and mandatory audit flag",
            description = "Returns the time-bound BreakTheGlassSession alongside the SHA-256 hash-chained BreakTheGlassAuditFlag queued for Privacy Officer review."
    )
    public ResponseEntity<BreakTheGlassInvocationResult> invokeBreakTheGlassWithAuditFlag(
            @RequestBody BreakTheGlassRequest request) {
        return ResponseEntity.status(201).body(consentService.invokeBreakTheGlassWithMandatoryAudit(request));
    }

    /**
     * Revokes an active Break-the-Glass emergency token before its scheduled expiration.
     */
    @PostMapping(
            value = "/api/v1/consent/break-the-glass/revoke",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Revoke an active Break-the-Glass emergency token")
    public ResponseEntity<BreakTheGlassSession> revokeBreakTheGlass(
            @RequestParam("token") String token) {
        return ResponseEntity.ok(consentService.revokeBreakTheGlassToken(token));
    }

    /**
     * Lists all mandatory Break-the-Glass audit flags queued or adjudicated by Privacy &amp; Compliance Officers.
     */
    @GetMapping(
            value = "/api/v1/consent/break-the-glass/audit-flags",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "List mandatory Break-the-Glass audit flags for HIPAA/GDPR compliance officer review")
    public ResponseEntity<List<BreakTheGlassAuditFlag>> listBreakTheGlassAuditFlags(
            @RequestParam(value = "patientId", required = false) UUID patientId,
            @RequestParam(value = "reviewStatus", required = false) String reviewStatus) {
        return ResponseEntity.ok(consentService.listBreakTheGlassAuditFlags(patientId, reviewStatus));
    }

    /**
     * Adjudicates a flagged Break-the-Glass emergency override session (`APPROVED_JUSTIFIED_EMERGENCY`
     * or `ESCALATED_UNAUTHORIZED_OVERRIDE`).
     */
    @PostMapping(
            value = "/api/v1/consent/break-the-glass/audit-flags/{flagId}/review",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Review and adjudicate a mandatory Break-the-Glass compliance audit flag")
    public ResponseEntity<BreakTheGlassAuditFlag> reviewBreakTheGlassAuditFlag(
            @PathVariable("flagId") UUID flagId,
            @RequestBody BreakTheGlassComplianceReviewRequest reviewRequest) {
        return ResponseEntity.ok(consentService.reviewBreakTheGlassAuditFlag(flagId, reviewRequest));
    }
}
