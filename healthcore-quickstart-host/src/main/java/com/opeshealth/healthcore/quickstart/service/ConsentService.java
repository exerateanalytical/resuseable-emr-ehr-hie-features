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

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirResourceAccessEvent;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassSession;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentDirectiveRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentProvisionType;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.FhirConsentDirective;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SegmentedAccessEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Consent.ConsentState;
import org.hl7.fhir.r4.model.Consent.ProvisionComponent;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HL7 FHIR R4 {@code Consent} Lifecycle, Data Segmentation for Privacy (DS4P), and
 * Break-the-Glass ({@code BTG} / {@code ETREAT}) Emergency Access Service with Mandatory Audit Flagging.
 *
 * <p>Manages HL7 FHIR R4 {@code Consent} resources supporting 42 CFR Part 2, HIPAA § 164.508,
 * GDPR Article 9, and HL7 DS4P confidentiality and sensitivity segmentation ({@code HIV},
 * {@code PSY}, {@code ETH}, {@code SDV}, {@code GEN}, {@code VIP}).</p>
 *
 * <p>Every Break-the-Glass emergency override invocation generates an immutable
 * {@link BreakTheGlassAuditFlag} linked to a SHA-256 hash-chained {@link FhirAuditLoggingService}
 * entry and queues the session for mandatory Privacy &amp; Compliance Officer review.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);
    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public static final String DS4P_POLICY_URI = "https://opeshealthsystems.com/fhir/Policy/hipaa-gdpr-ds4p-privacy";
    public static final String ACT_REASON_ETREAT = "ETREAT";
    public static final String AUDIT_FLAG_PENDING_REVIEW = "PENDING_COMPLIANCE_REVIEW";
    public static final String AUDIT_FLAG_APPROVED = "APPROVED_JUSTIFIED_EMERGENCY";
    public static final String AUDIT_FLAG_ESCALATED = "ESCALATED_UNAUTHORIZED_OVERRIDE";

    /**
     * Mandatory compliance audit flag generated whenever Break-the-Glass ({@code ETREAT})
     * emergency access is invoked or rejected.
     */
    public record BreakTheGlassAuditFlag(
            UUID flagId,
            UUID sessionId,
            String emergencyToken,
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String clinicianRole,
            String purposeOfUseCode,
            String emergencyReason,
            Set<SensitivityCategory> unlockedCategories,
            UUID auditEventId,
            String auditEntrySha256Hash,
            String severity,
            String reviewStatus,
            boolean mandatoryAuditFlagged,
            boolean complianceOfficerAlerted,
            Instant flaggedAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewNotes
    ) {}

    /**
     * Result of invoking a Break-the-Glass emergency override, pairing the active time-bound
     * {@link BreakTheGlassSession} with its mandatory {@link BreakTheGlassAuditFlag}.
     */
    public record BreakTheGlassInvocationResult(
            BreakTheGlassSession session,
            BreakTheGlassAuditFlag mandatoryAuditFlag
    ) {}

    /**
     * Request for a Privacy / Compliance Officer to adjudicate a flagged Break-the-Glass emergency override.
     */
    public record BreakTheGlassComplianceReviewRequest(
            String complianceOfficerId,
            String decision,
            String reviewNotes
    ) {}

    /**
     * Request to classify clinical text or diagnostic codes under HL7 DS4P sensitivity rules.
     */
    public record Ds4pClassificationRequest(
            String resourceType,
            String resourceId,
            String clinicalText,
            String clinicalCode
    ) {}

    /**
     * Result of classifying clinical content under HL7 DS4P privacy rules.
     */
    public record Ds4pClassificationResult(
            String resourceType,
            String resourceId,
            ConfidentialityLevel assignedConfidentiality,
            Set<SensitivityCategory> detectedCategories,
            List<String> securityLabelUris,
            List<String> obligationCodes,
            boolean requiresExplicitConsentOrBreakTheGlass
    ) {}

    private final FhirConsentEnforcementService enforcementService;
    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final FhirSubscriptionTopicService subscriptionTopicService;

    private final Map<UUID, FhirConsentDirective> directivesById = new ConcurrentHashMap<>();
    private final Map<UUID, BreakTheGlassAuditFlag> auditFlagsById = new ConcurrentHashMap<>();
    private final Map<String, UUID> auditFlagIdByToken = new ConcurrentHashMap<>();

    @Autowired
    public ConsentService(
            @Autowired(required = false) FhirConsentEnforcementService enforcementService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService != null
                ? fhirAuditLoggingService
                : new FhirAuditLoggingService();
        this.subscriptionTopicService = subscriptionTopicService;
        this.enforcementService = enforcementService != null
                ? enforcementService
                : new FhirConsentEnforcementService(this.fhirAuditLoggingService, this.subscriptionTopicService);
    }

    public ConsentService(FhirAuditLoggingService fhirAuditLoggingService) {
        this(null, fhirAuditLoggingService, null);
    }

    public ConsentService() {
        this(null, new FhirAuditLoggingService(), null);
    }

    /**
     * Registers a new HL7 FHIR R4 {@code Consent} directive governing DS4P sensitivity categories
     * and persists a SHA-256 hash-chained audit record.
     */
    @ClinicalAudit(action = "FHIR_CONSENT_CREATE", resourceType = "Consent")
    public FhirConsentDirective registerConsentDirective(ConsentDirectiveRequest request) {
        FhirConsentDirective directive = enforcementService.registerConsentDirective(request);
        directivesById.put(directive.consentId(), directive);
        return directive;
    }

    /**
     * Parses a raw HL7 FHIR R4 {@code Consent} JSON payload, extracts DS4P security labels,
     * provision type, and patient reference, and registers an active {@link FhirConsentDirective}.
     */
    @ClinicalAudit(action = "FHIR_CONSENT_CREATE_RAW", resourceType = "Consent")
    public FhirConsentDirective createConsentFromFhirJson(UUID facilityId, String rawFhirConsentJson) {
        if (rawFhirConsentJson == null || rawFhirConsentJson.isBlank()) {
            throw new IllegalArgumentException("FHIR R4 Consent JSON payload must not be blank");
        }
        Consent parsed;
        try {
            parsed = FHIR_R4.newJsonParser().parseResource(Consent.class, rawFhirConsentJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid HL7 FHIR R4 Consent JSON: " + ex.getMessage(), ex);
        }
        if (parsed.getPatient() == null || parsed.getPatient().getReference() == null
                || parsed.getPatient().getReference().isBlank()) {
            throw new IllegalArgumentException("FHIR Consent.patient.reference (Patient/{id}) is required");
        }
        UUID patientId = extractUuidFromReference(parsed.getPatient().getReference());

        ConsentProvisionType provisionType = ConsentProvisionType.DENY;
        Set<SensitivityCategory> categories = EnumSet.noneOf(SensitivityCategory.class);
        Set<String> actors = new LinkedHashSet<>();
        Set<String> purposes = new LinkedHashSet<>();
        Instant validFrom = Instant.now();
        Instant validUntil = validFrom.plusSeconds(365L * 24 * 3600);

        if (parsed.hasProvision()) {
            ProvisionComponent prov = parsed.getProvision();
            if (prov.getType() == Consent.ConsentProvisionType.PERMIT) {
                provisionType = ConsentProvisionType.PERMIT;
            }
            if (prov.hasSecurityLabel()) {
                for (Coding label : prov.getSecurityLabel()) {
                    if (label.hasCode()) {
                        try {
                            categories.add(SensitivityCategory.valueOf(label.getCode().trim().toUpperCase(Locale.ROOT)));
                        } catch (IllegalArgumentException ignored) {
                            // Ignore non-category confidentiality codes such as R or V
                        }
                    }
                }
            }
            if (prov.hasActor()) {
                for (Consent.provisionActorComponent actor : prov.getActor()) {
                    if (actor.hasReference() && actor.getReference().hasReference()) {
                        actors.add(actor.getReference().getReference());
                    }
                }
            }
            if (prov.hasPurpose()) {
                for (Coding purpose : prov.getPurpose()) {
                    if (purpose.hasCode()) {
                        purposes.add(purpose.getCode());
                    }
                }
            }
            if (prov.hasPeriod()) {
                if (prov.getPeriod().hasStart()) {
                    validFrom = prov.getPeriod().getStart().toInstant();
                }
                if (prov.getPeriod().hasEnd()) {
                    validUntil = prov.getPeriod().getEnd().toInstant();
                }
            }
        }

        if (categories.isEmpty()) {
            categories.addAll(List.of(
                    SensitivityCategory.HIV,
                    SensitivityCategory.PSY,
                    SensitivityCategory.ETH,
                    SensitivityCategory.SDV));
        }

        String policyUri = (parsed.hasPolicy() && !parsed.getPolicy().isEmpty() && parsed.getPolicyFirstRep().hasUri())
                ? parsed.getPolicyFirstRep().getUri()
                : DS4P_POLICY_URI;

        return registerConsentDirective(new ConsentDirectiveRequest(
                facilityId != null ? facilityId : PatientService.DEFAULT_FACILITY_ID,
                patientId,
                provisionType,
                categories,
                actors,
                purposes.isEmpty() ? Set.of("TREAT", ACT_REASON_ETREAT) : purposes,
                validFrom,
                validUntil,
                policyUri
        ));
    }

    /**
     * Retrieves a registered {@link FhirConsentDirective} by its UUID.
     */
    public FhirConsentDirective getConsentById(UUID consentId) {
        if (consentId == null) {
            throw new IllegalArgumentException("consentId is required");
        }
        FhirConsentDirective found = directivesById.get(consentId);
        if (found != null) {
            return found;
        }
        return enforcementService.listPatientConsents(null).stream()
                .filter(c -> consentId.equals(c.consentId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("FHIR Consent directive not found: " + consentId));
    }

    /**
     * Lists all {@link FhirConsentDirective} records for a patient (or all directives if {@code patientId} is null).
     */
    public List<FhirConsentDirective> listPatientConsents(UUID patientId) {
        List<FhirConsentDirective> fromEnforcement = enforcementService.listPatientConsents(patientId);
        Map<UUID, FhirConsentDirective> merged = new LinkedHashMap<>();
        for (FhirConsentDirective dir : fromEnforcement) {
            merged.put(dir.consentId(), dir);
        }
        for (FhirConsentDirective dir : directivesById.values()) {
            if (patientId == null || Objects.equals(patientId, dir.patientId())) {
                merged.put(dir.consentId(), dir);
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(FhirConsentDirective::createdAt).reversed())
                .toList();
    }

    /**
     * Revokes an active HL7 FHIR R4 {@code Consent} directive and records a mandatory audit log entry.
     */
    @ClinicalAudit(action = "FHIR_CONSENT_REVOKE", resourceType = "Consent")
    public FhirConsentDirective revokeConsentDirective(UUID consentId, String actorId, String revocationReason) {
        FhirConsentDirective existing = getConsentById(consentId);
        String resolvedActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "PATIENT_CONSENT_PORTAL";
        String resolvedReason = (revocationReason != null && !revocationReason.isBlank())
                ? revocationReason.trim()
                : "Patient revoked FHIR Consent directive";

        String updatedJson = existing.fhirConsentJson().replace("\"status\": \"active\"", "\"status\": \"inactive\"");
        FhirConsentDirective revoked = new FhirConsentDirective(
                existing.consentId(),
                existing.facilityId(),
                existing.patientId(),
                "inactive",
                existing.provisionType(),
                existing.restrictedCategories(),
                existing.authorizedActorIds(),
                existing.purposeOfUseCodes(),
                existing.validFrom(),
                Instant.now(),
                existing.policyUri(),
                updatedJson,
                existing.createdAt()
        );
        directivesById.put(consentId, revoked);
        enforcementService.updateConsentDirective(revoked);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    existing.facilityId().toString(),
                    resolvedActor,
                    "PATIENT",
                    "FHIR_CONSENT_DIRECTIVE_REVOKED",
                    "Consent",
                    consentId.toString(),
                    existing.patientId(),
                    "POST",
                    "/api/v1/consent/directives/" + consentId + "/revoke",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    resolvedReason
            ));
        }

        return revoked;
    }

    /**
     * Searches HL7 FHIR R4 {@code Consent} directives and encodes the results as a standards-compliant
     * HL7 FHIR R4 {@code Bundle} ({@code searchset}) JSON string.
     */
    public String searchFhirConsentBundleJson(UUID patientId, String status) {
        List<FhirConsentDirective> directives = listPatientConsents(patientId).stream()
                .filter(d -> status == null || status.isBlank() || status.trim().equalsIgnoreCase(d.status()))
                .toList();

        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTotal(directives.size());
        bundle.setTimestamp(new Date());

        for (FhirConsentDirective directive : directives) {
            Consent fhirConsent = toHapiFhirConsent(directive);
            bundle.addEntry()
                    .setFullUrl("urn:uuid:" + directive.consentId())
                    .setResource(fhirConsent);
        }

        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
    }

    /**
     * Evaluates a list of clinical items under HL7 DS4P Data Segmentation for Privacy rules,
     * applying active FHIR {@code Consent} directives or a valid Break-the-Glass token, and
     * recording a mandatory DS4P audit log entry whenever items are redacted or overridden.
     */
    @ClinicalAudit(action = "FHIR_DS4P_SEGMENTATION_EVALUATE", resourceType = "Consent")
    public SegmentedAccessEvaluationResult evaluateSegmentedAccess(
            UUID patientId,
            String actorId,
            String purposeOfUse,
            String breakTheGlassToken,
            List<ClinicalSegmentItem> items) {

        SegmentedAccessEvaluationResult result = enforcementService.evaluateSegmentedAccess(
                patientId, actorId, purposeOfUse, breakTheGlassToken, items);

        if (fhirAuditLoggingService != null) {
            String auditAction = result.breakTheGlassApplied()
                    ? "DS4P_BREAK_THE_GLASS_OVERRIDE_ACCESS"
                    : (result.redactedItemsCount() > 0 ? "DS4P_SEGMENTED_REDACTED_ACCESS" : "DS4P_PERMITTED_ACCESS");
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    PatientService.DEFAULT_FACILITY_ID.toString(),
                    result.actorId(),
                    "ROLE_CLINICIAN",
                    auditAction,
                    "Consent",
                    result.breakTheGlassSessionId() != null
                            ? result.breakTheGlassSessionId().toString()
                            : patientId.toString(),
                    patientId,
                    "POST",
                    "/api/v1/consent/evaluate-ds4p",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "DS4P evaluation: total=" + result.totalItemsEvaluated()
                            + ", permitted=" + result.permittedItemsCount()
                            + ", redacted=" + result.redactedItemsCount()
                            + ", breakTheGlassApplied=" + result.breakTheGlassApplied()
            ));
        }

        return result;
    }

    /**
     * Automatically classifies clinical text and codes into HL7 DS4P sensitivity categories
     * ({@code HIV}, {@code PSY}, {@code ETH}, {@code SDV}, {@code GEN}, {@code VIP}),
     * assigns HL7 v3 Confidentiality codes ({@code N}, {@code R}, {@code V}), and returns
     * required DS4P privacy obligation tags.
     */
    public Ds4pClassificationResult classifyClinicalContent(Ds4pClassificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Ds4pClassificationRequest is required");
        }
        String combined = ((request.clinicalText() != null ? request.clinicalText() : "")
                + " " + (request.clinicalCode() != null ? request.clinicalCode() : ""))
                .toLowerCase(Locale.ROOT);

        Set<SensitivityCategory> detected = EnumSet.noneOf(SensitivityCategory.class);
        if (combined.contains("hiv") || combined.contains("antiretroviral") || combined.contains("b20")
                || combined.contains("viral load cd4")) {
            detected.add(SensitivityCategory.HIV);
        }
        if (combined.contains("psych") || combined.contains("schizophren") || combined.contains("bipolar")
                || combined.contains("major depressive") || combined.contains("suicid") || combined.contains("f20")
                || combined.contains("f32") || combined.contains("f33")) {
            detected.add(SensitivityCategory.PSY);
        }
        if (combined.contains("substance") || combined.contains("opioid") || combined.contains("alcohol dependence")
                || combined.contains("addiction") || combined.contains("buprenorphine") || combined.contains("methadone")
                || combined.contains("f10") || combined.contains("f11")) {
            detected.add(SensitivityCategory.ETH);
        }
        if (combined.contains("domestic violence") || combined.contains("sexual assault")
                || combined.contains("abuse victim") || combined.contains("t74")) {
            detected.add(SensitivityCategory.SDV);
        }
        if (combined.contains("cyp2c19") || combined.contains("cyp2d6") || combined.contains("HLA-B")
                || combined.contains("pharmacogenom") || combined.contains("genetic")) {
            detected.add(SensitivityCategory.GEN);
        }
        if (combined.contains("vip") || combined.contains("head of state") || combined.contains("dignitary")) {
            detected.add(SensitivityCategory.VIP);
        }

        ConfidentialityLevel level = ConfidentialityLevel.N;
        for (SensitivityCategory category : detected) {
            if (category.defaultConfidentiality() == ConfidentialityLevel.V) {
                level = ConfidentialityLevel.V;
                break;
            }
            if (category.defaultConfidentiality() == ConfidentialityLevel.R) {
                level = ConfidentialityLevel.R;
            }
        }

        List<String> securityLabels = new ArrayList<>();
        securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|" + level.code());
        for (SensitivityCategory category : detected) {
            securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-ActCode|" + category.actCode());
        }

        List<String> obligations = detected.isEmpty()
                ? List.of("NONE")
                : List.of("REDACT", "MASK", "NODISCL", "NOREUSE");

        return new Ds4pClassificationResult(
                request.resourceType() != null ? request.resourceType() : "ClinicalResource",
                request.resourceId() != null ? request.resourceId() : "unassigned",
                level,
                Set.copyOf(detected),
                List.copyOf(securityLabels),
                obligations,
                level != ConfidentialityLevel.N
        );
    }

    /**
     * Invokes a time-bound Break-the-Glass ({@code BTG} / {@code ETREAT}) emergency access override
     * and generates a **mandatory HIPAA/GDPR audit flag** ({@link BreakTheGlassAuditFlag}) linked
     * to a tamper-evident SHA-256 hash-chained {@link FhirAuditLoggingService} event.
     *
     * <p>If the request lacks a valid clinician ID or sufficient clinical emergency justification,
     * a mandatory {@code FAILURE} audit flag is recorded before throwing {@link IllegalArgumentException}.</p>
     */
    @ClinicalAudit(action = "FHIR_BREAK_THE_GLASS_OVERRIDE", resourceType = "AuditEvent")
    public BreakTheGlassInvocationResult invokeBreakTheGlassWithMandatoryAudit(BreakTheGlassRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("BreakTheGlassRequest with patientId is required");
        }
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;

        if (request.clinicianId() == null || request.clinicianId().isBlank()
                || request.emergencyReason() == null || request.emergencyReason().trim().length() < 10) {
            // Mandatory audit flagging of rejected/invalid Break-the-Glass attempt
            if (fhirAuditLoggingService != null) {
                fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                        facilityId.toString(),
                        request.clinicianId() != null && !request.clinicianId().isBlank()
                                ? request.clinicianId().trim()
                                : "UNIDENTIFIED_CLINICIAN",
                        request.clinicianRole() != null ? request.clinicianRole() : "UNKNOWN",
                        "BREAK_THE_GLASS_REJECTED_MANDATORY_AUDIT_FLAG",
                        "Patient",
                        request.patientId().toString(),
                        request.patientId(),
                        "POST",
                        "/api/v1/consent/break-the-glass",
                        "127.0.0.1",
                        null,
                        "FAILURE",
                        0L,
                        "MANDATORY_AUDIT_FLAG: Rejected Break-the-Glass attempt due to missing clinicianId or insufficient emergencyReason"
                ));
            }
        }

        BreakTheGlassSession session = enforcementService.invokeBreakTheGlass(request);

        // Persist a dedicated mandatory audit flag entry with SHA-256 hash proof
        FhirResourceAccessEvent auditEvent = fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                session.facilityId().toString(),
                session.clinicianId(),
                session.clinicianRole(),
                "BREAK_THE_GLASS_MANDATORY_AUDIT_FLAG",
                "Consent",
                session.sessionId().toString(),
                session.patientId(),
                "POST",
                "/api/v1/consent/break-the-glass",
                "127.0.0.1",
                null,
                "SUCCESS",
                0L,
                "[MANDATORY_BTG_AUDIT_FLAG] Purpose=ETREAT Token=" + session.emergencyToken()
                        + " Categories=" + session.unlockedCategories()
                        + " Justification=" + session.emergencyReason()
        ));

        UUID flagId = UUID.randomUUID();
        BreakTheGlassAuditFlag flag = new BreakTheGlassAuditFlag(
                flagId,
                session.sessionId(),
                session.emergencyToken(),
                session.facilityId(),
                session.patientId(),
                session.clinicianId(),
                session.clinicianRole(),
                ACT_REASON_ETREAT,
                session.emergencyReason(),
                session.unlockedCategories(),
                auditEvent.id(),
                auditEvent.entryHash(),
                "CRITICAL_PRIVACY_OVERRIDE",
                AUDIT_FLAG_PENDING_REVIEW,
                true,
                session.complianceOfficerAlerted(),
                Instant.now(),
                null,
                null,
                null
        );

        auditFlagsById.put(flagId, flag);
        auditFlagIdByToken.put(session.emergencyToken(), flagId);

        log.warn("MANDATORY_BTG_AUDIT_FLAG created flagId={} sessionId={} patientId={} clinicianId={} sha256={}",
                flagId, session.sessionId(), session.patientId(), session.clinicianId(), auditEvent.entryHash());

        return new BreakTheGlassInvocationResult(session, flag);
    }

    /**
     * Convenience method returning the {@link BreakTheGlassSession} while still recording the
     * mandatory {@link BreakTheGlassAuditFlag}.
     */
    public BreakTheGlassSession invokeBreakTheGlass(BreakTheGlassRequest request) {
        return invokeBreakTheGlassWithMandatoryAudit(request).session();
    }

    /**
     * Validates whether a Break-the-Glass emergency token is currently active and non-expired for a patient.
     */
    public BreakTheGlassSession validateBreakTheGlassToken(UUID patientId, String emergencyToken) {
        return enforcementService.validateBreakTheGlassToken(patientId, emergencyToken);
    }

    /**
     * Revokes an active Break-the-Glass emergency override token and records the revocation in the audit trail.
     */
    @ClinicalAudit(action = "FHIR_BREAK_THE_GLASS_REVOKE", resourceType = "AuditEvent")
    public BreakTheGlassSession revokeBreakTheGlassToken(String emergencyToken) {
        BreakTheGlassSession revoked = enforcementService.revokeBreakTheGlassToken(emergencyToken);
        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    revoked.facilityId().toString(),
                    revoked.clinicianId(),
                    revoked.clinicianRole(),
                    "BREAK_THE_GLASS_TOKEN_REVOKED",
                    "Patient",
                    revoked.patientId().toString(),
                    revoked.patientId(),
                    "POST",
                    "/api/v1/consent/break-the-glass/revoke",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Revoked Break-the-Glass emergency token " + revoked.emergencyToken()
            ));
        }
        return revoked;
    }

    /**
     * Lists all mandatory Break-the-Glass audit flags, optionally filtered by {@code patientId}
     * and {@code reviewStatus} ({@code PENDING_COMPLIANCE_REVIEW}, {@code APPROVED_JUSTIFIED_EMERGENCY},
     * or {@code ESCALATED_UNAUTHORIZED_OVERRIDE}).
     */
    public List<BreakTheGlassAuditFlag> listBreakTheGlassAuditFlags(UUID patientId, String reviewStatus) {
        return auditFlagsById.values().stream()
                .filter(f -> patientId == null || Objects.equals(patientId, f.patientId()))
                .filter(f -> reviewStatus == null || reviewStatus.isBlank()
                        || reviewStatus.trim().equalsIgnoreCase(f.reviewStatus()))
                .sorted(Comparator.comparing(BreakTheGlassAuditFlag::flaggedAt).reversed())
                .toList();
    }

    /**
     * Retrieves a specific Break-the-Glass mandatory audit flag by its {@code flagId}.
     */
    public BreakTheGlassAuditFlag getBreakTheGlassAuditFlag(UUID flagId) {
        if (flagId == null) {
            throw new IllegalArgumentException("flagId is required");
        }
        BreakTheGlassAuditFlag flag = auditFlagsById.get(flagId);
        if (flag == null) {
            throw new NoSuchElementException("Break-the-Glass audit flag not found: " + flagId);
        }
        return flag;
    }

    /**
     * Allows a Privacy / HIPAA Compliance Officer to review and adjudicate a mandatory
     * Break-the-Glass audit flag, recording the review outcome in the SHA-256 audit log chain.
     */
    @ClinicalAudit(action = "FHIR_BREAK_THE_GLASS_COMPLIANCE_REVIEW", resourceType = "AuditEvent")
    public BreakTheGlassAuditFlag reviewBreakTheGlassAuditFlag(
            UUID flagId,
            BreakTheGlassComplianceReviewRequest reviewRequest) {

        BreakTheGlassAuditFlag existing = getBreakTheGlassAuditFlag(flagId);
        if (reviewRequest == null || reviewRequest.complianceOfficerId() == null
                || reviewRequest.complianceOfficerId().isBlank()) {
            throw new IllegalArgumentException("complianceOfficerId is required to review a Break-the-Glass audit flag");
        }
        String rawDecision = reviewRequest.decision() != null
                ? reviewRequest.decision().trim().toUpperCase(Locale.ROOT)
                : AUDIT_FLAG_APPROVED;
        String normalizedStatus = (rawDecision.contains("ESCALATE") || rawDecision.contains("REJECT")
                || rawDecision.contains("VIOLATION") || rawDecision.contains("UNAUTHORIZED"))
                ? AUDIT_FLAG_ESCALATED
                : AUDIT_FLAG_APPROVED;

        Instant reviewedAt = Instant.now();
        BreakTheGlassAuditFlag updated = new BreakTheGlassAuditFlag(
                existing.flagId(),
                existing.sessionId(),
                existing.emergencyToken(),
                existing.facilityId(),
                existing.patientId(),
                existing.clinicianId(),
                existing.clinicianRole(),
                existing.purposeOfUseCode(),
                existing.emergencyReason(),
                existing.unlockedCategories(),
                existing.auditEventId(),
                existing.auditEntrySha256Hash(),
                existing.severity(),
                normalizedStatus,
                true,
                existing.complianceOfficerAlerted(),
                existing.flaggedAt(),
                reviewRequest.complianceOfficerId().trim(),
                reviewedAt,
                reviewRequest.reviewNotes() != null ? reviewRequest.reviewNotes().trim() : "Compliance review completed"
        );
        auditFlagsById.put(flagId, updated);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    existing.facilityId().toString(),
                    updated.reviewedBy(),
                    "ROLE_COMPLIANCE_OFFICER",
                    "BREAK_THE_GLASS_AUDIT_FLAG_REVIEWED",
                    "AuditEvent",
                    flagId.toString(),
                    existing.patientId(),
                    "POST",
                    "/api/v1/consent/break-the-glass/audit-flags/" + flagId + "/review",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Break-the-Glass audit flag " + flagId + " adjudicated as " + normalizedStatus
                            + " by " + updated.reviewedBy() + ": " + updated.reviewNotes()
            ));
        }

        if (AUDIT_FLAG_ESCALATED.equals(normalizedStatus) && subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    existing.facilityId(),
                    FhirSubscriptionTopicService.TOPIC_BREAK_THE_GLASS_INVOKED,
                    "AuditEvent",
                    flagId.toString(),
                    existing.patientId(),
                    "CRITICAL",
                    "ESCALATED Break-the-Glass privacy violation flagged by compliance officer "
                            + updated.reviewedBy() + " for session " + existing.sessionId(),
                    Map.of(
                            "flagId", flagId.toString(),
                            "sessionId", existing.sessionId().toString(),
                            "clinicianId", existing.clinicianId(),
                            "reviewStatus", normalizedStatus
                    )
            ));
        }

        return updated;
    }

    public FhirConsentEnforcementService getEnforcementService() {
        return enforcementService;
    }

    public FhirAuditLoggingService getFhirAuditLoggingService() {
        return fhirAuditLoggingService;
    }

    private static Consent toHapiFhirConsent(FhirConsentDirective directive) {
        Consent consent = new Consent();
        consent.setId(directive.consentId().toString());
        consent.setMeta(new Meta()
                .setLastUpdated(Date.from(directive.createdAt()))
                .addProfile("http://hl7.org/fhir/StructureDefinition/Consent"));
        consent.setStatus("active".equalsIgnoreCase(directive.status())
                ? ConsentState.ACTIVE
                : ConsentState.INACTIVE);
        consent.setScope(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/consentscope")
                .setCode("patient-privacy")
                .setDisplay("Privacy Consent")));
        consent.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://loinc.org")
                .setCode("59284-0")
                .setDisplay("Patient Consent")));
        consent.setPatient(new Reference("Patient/" + directive.patientId()));
        consent.setDateTimeElement(new DateTimeType(Date.from(directive.createdAt())));
        consent.addPolicy().setUri(directive.policyUri());
        consent.setPolicyRule(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/consentpolicycodes")
                .setCode("hipaa-auth")));

        ProvisionComponent provision = new ProvisionComponent();
        provision.setType(directive.provisionType() == ConsentProvisionType.PERMIT
                ? Consent.ConsentProvisionType.PERMIT
                : Consent.ConsentProvisionType.DENY);
        provision.setPeriod(new Period()
                .setStart(Date.from(directive.validFrom()))
                .setEnd(Date.from(directive.validUntil())));
        for (SensitivityCategory category : directive.restrictedCategories()) {
            provision.addSecurityLabel(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                    .setCode(category.actCode())
                    .setDisplay(category.description()));
        }
        for (String purpose : directive.purposeOfUseCodes()) {
            provision.addPurpose(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActReason")
                    .setCode(purpose));
        }
        consent.setProvision(provision);
        return consent;
    }

    private static UUID extractUuidFromReference(String reference) {
        String trimmed = reference.trim();
        int slashIdx = trimmed.lastIndexOf('/');
        String idPart = slashIdx >= 0 ? trimmed.substring(slashIdx + 1) : trimmed;
        return UUID.fromString(idPart);
    }
}
