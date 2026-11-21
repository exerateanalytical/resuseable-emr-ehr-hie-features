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
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Consent.ConsentState;
import org.hl7.fhir.r4.model.Consent.ProvisionComponent;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
    public static final String AI_PROCESSING_POLICY_URI = "https://opeshealthsystems.com/fhir/Policy/ai-assisted-clinical-processing-consent";
    public static final String AI_PROCESSING_CATEGORY_CODE = "AI-ASSISTED-PROCESSING";
    public static final String AI_CONSENT_TAG_SYSTEM = "https://opeshealthsystems.com/fhir/CodeSystem/ai-processing-scope";
    public static final String AI_EXTENSION_URL = "https://opeshealthsystems.com/fhir/StructureDefinition/ai-assisted-processing-consent";
    public static final String AI_OPT_OUT_SECURITY_CODE = "NOAI";
    public static final String AI_OPT_IN_SECURITY_CODE = "AIPERMIT";
    public static final String ACT_REASON_ETREAT = "ETREAT";
    public static final String AUDIT_FLAG_PENDING_REVIEW = "PENDING_COMPLIANCE_REVIEW";
    public static final String AUDIT_FLAG_APPROVED = "APPROVED_JUSTIFIED_EMERGENCY";
    public static final String AUDIT_FLAG_ESCALATED = "ESCALATED_UNAUTHORIZED_OVERRIDE";

    /**
     * Granular automated AI clinical processing scopes that patients can individually opt-in to
     * or opt-out from under HIPAA, GDPR Article 22, and HL7 FHIR R4 Consent governance.
     */
    public enum AiProcessingScope {
        ALL_AI_PROCESSING("ALL_AI", "All Automated AI-Assisted Clinical Processing"),
        CLINICAL_SUMMARIZATION("AI_SUMMARIZE", "AI Clinical & IPS Chart Summarization"),
        CLINICAL_DECISION_SUPPORT("AI_CDS", "AI Real-Time Clinical Decision Support & Alerts"),
        AMBIENT_TRANSCRIPTION("AI_SCRIBE", "AI Ambient Voice Transcription & SOAP Note Generation"),
        PREDICTIVE_ANALYTICS("AI_PREDICT", "AI Readmission & Disease Progression Forecasting"),
        PATIENT_HEALTH_COACHING("AI_COACH", "AI Personalized Wellness & Medication Adherence Coaching"),
        AUTOMATED_MEDICAL_CODING("AI_CODING", "AI ICD-10 / CPT Automated Medical Coding"),
        MEDICATION_RECONCILIATION("AI_MEDREC", "AI Three-Way Medication Reconciliation"),
        MULTIMODAL_IMAGE_TRIAGE("AI_IMAGING", "AI Radiology, Dermatology & Lab Image Triage"),
        SEMANTIC_CLINICAL_SEARCH("AI_SEARCH", "AI Semantic Chart & Clinical Trial Search");

        private final String code;
        private final String display;

        AiProcessingScope(String code, String display) {
            this.code = code;
            this.display = display;
        }

        public String code() {
            return code;
        }

        public String display() {
            return display;
        }

        public static Optional<AiProcessingScope> fromCode(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (AiProcessingScope scope : values()) {
                if (scope.name().equalsIgnoreCase(normalized) || scope.code.equalsIgnoreCase(normalized)) {
                    return Optional.of(scope);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Request to register or update a patient's granular AI-Assisted Processing consent flags.
     */
    public record AiProcessingConsentRequest(
            UUID facilityId,
            UUID patientId,
            boolean globalAiAssistedProcessingOptIn,
            Map<AiProcessingScope, Boolean> scopeFlags,
            String recordedBy,
            String consentChannel,
            Instant validFrom,
            Instant validUntil,
            String notes
    ) {}

    /**
     * Persisted patient AI-Assisted Processing consent state with generated HL7 FHIR R4 Consent JSON.
     */
    public record PatientAiProcessingConsent(
            UUID consentRecordId,
            UUID facilityId,
            UUID patientId,
            boolean globalAiAssistedProcessingOptIn,
            Map<AiProcessingScope, Boolean> scopeFlags,
            String status,
            Instant validFrom,
            Instant validUntil,
            String recordedBy,
            String consentChannel,
            String notes,
            String fhirConsentJson,
            Instant updatedAt
    ) {}

    /**
     * Outcome of validating an HL7 FHIR R4 resource or Bundle against patient AI-Assisted
     * Processing consent flags and embedded resource-level AI privacy tags.
     */
    public record AiConsentValidationOutcome(
            UUID patientId,
            String resourceType,
            String resourceId,
            Set<AiProcessingScope> requestedScopes,
            boolean allowed,
            Set<AiProcessingScope> permittedScopes,
            Set<AiProcessingScope> deniedScopes,
            String reasonCode,
            String diagnosticMessage
    ) {}

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
    private final Map<UUID, PatientAiProcessingConsent> aiConsentsByPatientId = new ConcurrentHashMap<>();

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

        UUID resolvedFacilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(facilityId);
        syncAiConsentFromParsedFhirConsent(resolvedFacilityId, patientId, parsed, validFrom, validUntil);

        return registerConsentDirective(new ConsentDirectiveRequest(
                resolvedFacilityId,
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
                    com.opeshealth.healthcore.security.tenant.FacilityContext.requireFacilityId().toString(),
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
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());

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

    // =========================================================================
    // AI-Assisted Clinical Processing Patient Consent Flags & FHIR Validation
    // =========================================================================

    /**
     * Registers or updates a patient's granular 'AI-Assisted Processing' consent flags, allowing
     * opt-in or opt-out globally and per automated clinical AI workflow (summarization, CDS,
     * ambient transcription, predictive analytics, coding, medication reconciliation, coaching).
     */
    @ClinicalAudit(action = "FHIR_AI_PROCESSING_CONSENT_UPSERT", resourceType = "Consent")
    public PatientAiProcessingConsent upsertAiProcessingConsent(AiProcessingConsentRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("AiProcessingConsentRequest with patientId is required");
        }
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        Instant now = Instant.now();
        Instant validFrom = request.validFrom() != null ? request.validFrom() : now;
        Instant validUntil = request.validUntil() != null
                ? request.validUntil()
                : validFrom.plusSeconds(365L * 24 * 3600);
        if (validUntil.isBefore(validFrom)) {
            throw new IllegalArgumentException("AI processing consent validUntil cannot precede validFrom");
        }

        EnumMap<AiProcessingScope, Boolean> normalizedFlags = new EnumMap<>(AiProcessingScope.class);
        if (request.scopeFlags() != null) {
            for (Map.Entry<AiProcessingScope, Boolean> entry : request.scopeFlags().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    normalizedFlags.put(entry.getKey(), entry.getValue());
                }
            }
        }
        normalizedFlags.put(AiProcessingScope.ALL_AI_PROCESSING, request.globalAiAssistedProcessingOptIn());

        PatientAiProcessingConsent existing = aiConsentsByPatientId.get(request.patientId());
        UUID recordId = existing != null ? existing.consentRecordId() : UUID.randomUUID();
        String recordedBy = (request.recordedBy() != null && !request.recordedBy().isBlank())
                ? request.recordedBy().trim()
                : "PATIENT_PORTAL";
        String channel = (request.consentChannel() != null && !request.consentChannel().isBlank())
                ? request.consentChannel().trim()
                : "PATIENT_PORTAL";
        String notes = request.notes() != null ? request.notes().trim() : "";

        Map<AiProcessingScope, Boolean> immutableFlags = Collections.unmodifiableMap(normalizedFlags);
        String fhirConsentJson = encodeAiProcessingConsentAsFhirJson(
                recordId, request.patientId(), request.globalAiAssistedProcessingOptIn(),
                immutableFlags, "active", validFrom, validUntil, now);

        PatientAiProcessingConsent saved = new PatientAiProcessingConsent(
                recordId,
                facilityId,
                request.patientId(),
                request.globalAiAssistedProcessingOptIn(),
                immutableFlags,
                "active",
                validFrom,
                validUntil,
                recordedBy,
                channel,
                notes,
                fhirConsentJson,
                now
        );
        aiConsentsByPatientId.put(request.patientId(), saved);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    recordedBy,
                    "PATIENT_OR_CLINICIAN",
                    "FHIR_AI_PROCESSING_CONSENT_UPDATED",
                    "Consent",
                    recordId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/consent/ai-processing",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "globalAiOptIn=" + request.globalAiAssistedProcessingOptIn()
                            + " scopeFlags=" + immutableFlags
                            + " channel=" + channel
            ));
        }

        return saved;
    }

    /**
     * Toggles a patient's opt-in or opt-out flag for a specific {@link AiProcessingScope}
     * (or {@link AiProcessingScope#ALL_AI_PROCESSING} for global opt-in/out).
     */
    @ClinicalAudit(action = "FHIR_AI_PROCESSING_SCOPE_TOGGLE", resourceType = "Consent")
    public PatientAiProcessingConsent setAiProcessingScopeFlag(
            UUID facilityId,
            UUID patientId,
            AiProcessingScope scope,
            boolean optIn,
            String actorId,
            String reason) {
        if (patientId == null || scope == null) {
            throw new IllegalArgumentException("patientId and AiProcessingScope are required");
        }
        PatientAiProcessingConsent existing = aiConsentsByPatientId.get(patientId);
        EnumMap<AiProcessingScope, Boolean> merged = new EnumMap<>(AiProcessingScope.class);
        if (existing != null && existing.scopeFlags() != null) {
            merged.putAll(existing.scopeFlags());
        }
        boolean globalOptIn;
        if (scope == AiProcessingScope.ALL_AI_PROCESSING) {
            globalOptIn = optIn;
            merged.put(AiProcessingScope.ALL_AI_PROCESSING, optIn);
        } else {
            merged.put(scope, optIn);
            // If opting in to a specific workflow while previously having no record, enable global opt-in
            // only if explicitly opting in or keep existing global flag when already configured.
            globalOptIn = existing != null ? existing.globalAiAssistedProcessingOptIn() : optIn;
        }
        return upsertAiProcessingConsent(new AiProcessingConsentRequest(
                facilityId != null ? facilityId : (existing != null ? existing.facilityId() : com.opeshealth.healthcore.security.tenant.FacilityContext.requireFacilityId()),
                patientId,
                globalOptIn,
                merged,
                actorId,
                existing != null ? existing.consentChannel() : "CLINICAL_PORTAL",
                existing != null ? existing.validFrom() : Instant.now(),
                existing != null ? existing.validUntil() : Instant.now().plusSeconds(365L * 24 * 3600),
                reason
        ));
    }

    /**
     * Immediately revokes all AI-Assisted Processing consent flags for a patient, marking
     * the consent record {@code inactive} and opting out of every automated AI scope.
     */
    @ClinicalAudit(action = "FHIR_AI_PROCESSING_CONSENT_REVOKE", resourceType = "Consent")
    public PatientAiProcessingConsent revokeAllAiProcessingConsent(
            UUID facilityId,
            UUID patientId,
            String actorId,
            String reason) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        PatientAiProcessingConsent existing = aiConsentsByPatientId.get(patientId);
        UUID resolvedFacility = facilityId != null
                ? facilityId
                : (existing != null ? existing.facilityId() : com.opeshealth.healthcore.security.tenant.FacilityContext.requireFacilityId());
        UUID recordId = existing != null ? existing.consentRecordId() : UUID.randomUUID();
        Instant now = Instant.now();

        EnumMap<AiProcessingScope, Boolean> allDenied = new EnumMap<>(AiProcessingScope.class);
        for (AiProcessingScope s : AiProcessingScope.values()) {
            allDenied.put(s, false);
        }
        Map<AiProcessingScope, Boolean> immutableDenied = Collections.unmodifiableMap(allDenied);
        String resolvedActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "PATIENT_PORTAL";
        String resolvedReason = (reason != null && !reason.isBlank())
                ? reason.trim()
                : "Patient revoked AI-Assisted Processing consent";

        String fhirJson = encodeAiProcessingConsentAsFhirJson(
                recordId, patientId, false, immutableDenied, "inactive",
                existing != null ? existing.validFrom() : now, now, now);

        PatientAiProcessingConsent revoked = new PatientAiProcessingConsent(
                recordId,
                resolvedFacility,
                patientId,
                false,
                immutableDenied,
                "inactive",
                existing != null ? existing.validFrom() : now,
                now,
                resolvedActor,
                existing != null ? existing.consentChannel() : "PATIENT_PORTAL",
                resolvedReason,
                fhirJson,
                now
        );
        aiConsentsByPatientId.put(patientId, revoked);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    resolvedFacility.toString(),
                    resolvedActor,
                    "PATIENT",
                    "FHIR_AI_PROCESSING_CONSENT_REVOKED",
                    "Consent",
                    recordId.toString(),
                    patientId,
                    "POST",
                    "/api/v1/consent/ai-processing/" + patientId + "/revoke",
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
     * Retrieves a patient's current AI-Assisted Processing consent flags if configured.
     */
    public Optional<PatientAiProcessingConsent> getPatientAiProcessingConsent(UUID patientId) {
        if (patientId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(aiConsentsByPatientId.get(patientId));
    }

    /**
     * Evaluates whether a patient currently permits automated AI-Assisted Processing for the given
     * {@link AiProcessingScope}. Requires an active, non-expired consent record where neither the
     * global flag nor the specific scope flag is opted out.
     */
    public boolean isAiProcessingPermitted(UUID patientId, AiProcessingScope scope) {
        if (patientId == null || scope == null) {
            return false;
        }
        PatientAiProcessingConsent consent = aiConsentsByPatientId.get(patientId);
        if (consent == null || !"active".equalsIgnoreCase(consent.status())) {
            return false;
        }
        Instant now = Instant.now();
        if (consent.validFrom() != null && now.isBefore(consent.validFrom())) {
            return false;
        }
        if (consent.validUntil() != null && now.isAfter(consent.validUntil())) {
            return false;
        }
        if (scope == AiProcessingScope.ALL_AI_PROCESSING) {
            return consent.globalAiAssistedProcessingOptIn();
        }
        Boolean explicitScopeDecision = consent.scopeFlags() != null ? consent.scopeFlags().get(scope) : null;
        if (explicitScopeDecision != null) {
            // An explicit per-scope flag governs unless the patient revoked global AI processing
            // AND did not explicitly opt in to this specific workflow.
            return explicitScopeDecision;
        }
        return consent.globalAiAssistedProcessingOptIn();
    }

    /**
     * Validates an HL7 FHIR R4 resource (or {@link Bundle}) against patient 'AI-Assisted Processing'
     * consent flags and embedded resource-level AI privacy tags/extensions.
     *
     * <p>Resources that do not carry AI-Assisted Processing tags/extensions and are validated without
     * explicit requested scopes pass cleanly ({@code NO_AI_PROCESSING_REQUESTED}) so standard EHR
     * persistence is unaffected. When AI processing scopes are present—either on the resource's
     * {@code meta.tag}/{@code meta.security}/extensions or explicitly requested by the caller—this
     * validator enforces patient opt-in/opt-out flags, detects contradictory {@code NOAI} labels,
     * and records an audit event.</p>
     */
    public AiConsentValidationOutcome validateFhirResourceAiConsent(
            IBaseResource resource,
            Set<AiProcessingScope> explicitRequestedScopes) {
        if (resource == null) {
            throw new IllegalArgumentException("FHIR resource must not be null for AI consent validation");
        }

        String resourceType = resource.fhirType();
        String resourceId = (resource instanceof Resource r && r.hasId()) ? r.getIdElement().getIdPart() : "unassigned";

        // If Bundle, evaluate bundle-level tags AND each contained entry resource
        if (resource instanceof Bundle bundle) {
            Set<AiProcessingScope> bundleLevelScopes = new LinkedHashSet<>();
            if (explicitRequestedScopes != null) {
                bundleLevelScopes.addAll(explicitRequestedScopes);
            }
            ExtractedResourceAiMetadata bundleMeta = extractResourceAiMetadata(bundle);
            if (!bundleMeta.invalidScopeCodes().isEmpty()) {
                return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                        null, "Bundle", resourceId, Set.copyOf(bundleLevelScopes), false,
                        Set.of(), Set.copyOf(bundleLevelScopes), "INVALID_AI_SCOPE_TAG",
                        "FHIR Bundle contains unrecognized ai-processing-scope tag(s): " + bundleMeta.invalidScopeCodes()
                ));
            }
            bundleLevelScopes.addAll(bundleMeta.requestedScopes());

            if (bundleMeta.explicitNoAiFlag() && !bundleLevelScopes.isEmpty()) {
                return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                        null, "Bundle", resourceId, Set.copyOf(bundleLevelScopes), false,
                        Set.of(), Set.copyOf(bundleLevelScopes), "RESOURCE_TAGGED_NOAI_OPT_OUT",
                        "FHIR Bundle is marked NOAI (opt-out) and cannot be submitted for AI-Assisted Processing scopes: " + bundleLevelScopes
                ));
            }

            Set<AiProcessingScope> aggregatedRequested = new LinkedHashSet<>(bundleLevelScopes);
            Set<AiProcessingScope> aggregatedPermitted = new LinkedHashSet<>();
            UUID lastEvaluatedPatient = null;

            for (BundleEntryComponent entry : bundle.getEntry()) {
                if (!entry.hasResource()) {
                    continue;
                }
                Resource entryRes = entry.getResource();
                AiConsentValidationOutcome entryOutcome = validateSingleFhirResourceAiConsent(
                        entryRes, bundleLevelScopes);
                if (entryOutcome.patientId() != null) {
                    lastEvaluatedPatient = entryOutcome.patientId();
                }
                aggregatedRequested.addAll(entryOutcome.requestedScopes());
                aggregatedPermitted.addAll(entryOutcome.permittedScopes());
                if (!entryOutcome.allowed()) {
                    return entryOutcome;
                }
            }

            if (aggregatedRequested.isEmpty()) {
                return new AiConsentValidationOutcome(
                        lastEvaluatedPatient, "Bundle", resourceId, Set.of(), true,
                        Set.of(), Set.of(), "NO_AI_PROCESSING_REQUESTED",
                        "FHIR Bundle does not request automated AI-Assisted Processing."
                );
            }
            return new AiConsentValidationOutcome(
                    lastEvaluatedPatient, "Bundle", resourceId, Set.copyOf(aggregatedRequested), true,
                    Set.copyOf(aggregatedPermitted), Set.of(), "AI_CONSENT_PERMITTED",
                    "All Bundle entries satisfied patient AI-Assisted Processing consent flags."
            );
        }

        if (resource instanceof Resource r) {
            return validateSingleFhirResourceAiConsent(r,
                    explicitRequestedScopes != null ? explicitRequestedScopes : Set.of());
        }

        return new AiConsentValidationOutcome(
                null, resourceType, resourceId, Set.of(), true,
                Set.of(), Set.of(), "NO_AI_PROCESSING_REQUESTED",
                "Resource does not carry AI-Assisted Processing metadata."
        );
    }

    /**
     * Parses a raw HL7 FHIR R4 JSON payload and validates it against patient 'AI-Assisted Processing'
     * consent flags and requested scopes.
     */
    public AiConsentValidationOutcome validateFhirJsonAiConsent(
            String rawFhirJson,
            Set<AiProcessingScope> explicitRequestedScopes) {
        if (rawFhirJson == null || rawFhirJson.isBlank()) {
            throw new IllegalArgumentException("Raw FHIR R4 JSON payload must not be blank");
        }
        IBaseResource parsed;
        try {
            parsed = FHIR_R4.newJsonParser().parseResource(rawFhirJson);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid HL7 FHIR R4 JSON payload: " + ex.getMessage(), ex);
        }
        return validateFhirResourceAiConsent(parsed, explicitRequestedScopes);
    }

    /**
     * Enforces AI-Assisted Processing consent validation for an HL7 FHIR R4 resource during
     * FHIR schema/domain validation pipelines (throws on invalid AI tags or opted-out consent).
     */
    public void assertFhirResourceAiConsentValid(IBaseResource resource) {
        assertFhirResourceAiConsentValid(resource, Set.of());
    }

    /**
     * Enforces AI-Assisted Processing consent validation for an HL7 FHIR R4 resource and explicit
     * automated AI processing scopes (throws {@link IllegalArgumentException} for invalid/contradictory
     * FHIR AI tags or {@link IllegalStateException} when patient consent denies AI processing).
     */
    public void assertFhirResourceAiConsentValid(
            IBaseResource resource,
            Set<AiProcessingScope> explicitRequestedScopes) {
        AiConsentValidationOutcome outcome = validateFhirResourceAiConsent(resource, explicitRequestedScopes);
        if (!outcome.allowed()) {
            if ("INVALID_AI_SCOPE_TAG".equals(outcome.reasonCode())
                    || "MISSING_PATIENT_REFERENCE_FOR_AI_CONSENT".equals(outcome.reasonCode())) {
                throw new IllegalArgumentException("FHIR AI-Assisted Processing validation error ["
                        + outcome.reasonCode() + "]: " + outcome.diagnosticMessage());
            }
            throw new IllegalStateException("FHIR AI-Assisted Processing consent denied ["
                    + outcome.reasonCode() + "]: " + outcome.diagnosticMessage());
        }
    }

    private record ExtractedResourceAiMetadata(
            Set<AiProcessingScope> requestedScopes,
            List<String> invalidScopeCodes,
            boolean explicitNoAiFlag,
            Boolean extensionOptInFlag
    ) {}

    private AiConsentValidationOutcome validateSingleFhirResourceAiConsent(
            Resource resource,
            Set<AiProcessingScope> inheritedScopes) {
        String resourceType = resource.fhirType();
        String resourceId = resource.hasId() ? resource.getIdElement().getIdPart() : "unassigned";
        ExtractedResourceAiMetadata meta = extractResourceAiMetadata(resource);

        Set<AiProcessingScope> requestedScopes = new LinkedHashSet<>(inheritedScopes);
        requestedScopes.addAll(meta.requestedScopes());

        if (!meta.invalidScopeCodes().isEmpty()) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    extractPatientIdFromFhirResource(resource), resourceType, resourceId,
                    Set.copyOf(requestedScopes), false, Set.of(), Set.copyOf(requestedScopes),
                    "INVALID_AI_SCOPE_TAG",
                    "FHIR " + resourceType + " contains unrecognized ai-processing-scope code(s): " + meta.invalidScopeCodes()
            ));
        }

        // Standard non-AI FHIR resource with no requested AI scopes passes cleanly
        if (requestedScopes.isEmpty()) {
            return new AiConsentValidationOutcome(
                    extractPatientIdFromFhirResource(resource), resourceType, resourceId,
                    Set.of(), true, Set.of(), Set.of(), "NO_AI_PROCESSING_REQUESTED",
                    "FHIR " + resourceType + " does not request automated AI-Assisted Processing."
            );
        }

        UUID patientId = extractPatientIdFromFhirResource(resource);
        if (meta.explicitNoAiFlag()) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    patientId, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.of(), Set.copyOf(requestedScopes), "RESOURCE_TAGGED_NOAI_OPT_OUT",
                    "FHIR " + resourceType + " carries security label 'NOAI' prohibiting AI-Assisted Processing for scopes: " + requestedScopes
            ));
        }
        if (Boolean.FALSE.equals(meta.extensionOptInFlag())) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    patientId, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.of(), Set.copyOf(requestedScopes), "RESOURCE_EXTENSION_AI_OPT_OUT",
                    "FHIR " + resourceType + " carries ai-assisted-processing-consent=false extension prohibiting AI scopes: " + requestedScopes
            ));
        }
        if (patientId == null) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    null, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.of(), Set.copyOf(requestedScopes), "MISSING_PATIENT_REFERENCE_FOR_AI_CONSENT",
                    "FHIR " + resourceType + " requests AI-Assisted Processing scopes " + requestedScopes
                            + " but lacks a resolvable Patient UUID reference for consent verification."
            ));
        }

        PatientAiProcessingConsent consent = aiConsentsByPatientId.get(patientId);
        if (consent == null) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    patientId, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.of(), Set.copyOf(requestedScopes), "AI_CONSENT_NOT_GRANTED",
                    "Patient/" + patientId + " has no active AI-Assisted Processing opt-in consent for requested scopes: " + requestedScopes
            ));
        }

        Instant now = Instant.now();
        if (!"active".equalsIgnoreCase(consent.status())
                || (consent.validFrom() != null && now.isBefore(consent.validFrom()))
                || (consent.validUntil() != null && now.isAfter(consent.validUntil()))) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    patientId, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.of(), Set.copyOf(requestedScopes), "AI_CONSENT_INACTIVE_OR_EXPIRED",
                    "Patient/" + patientId + " AI-Assisted Processing consent is inactive or outside its validity period."
            ));
        }

        Set<AiProcessingScope> permitted = new LinkedHashSet<>();
        Set<AiProcessingScope> denied = new LinkedHashSet<>();
        for (AiProcessingScope scope : requestedScopes) {
            if (isAiProcessingPermitted(patientId, scope)) {
                permitted.add(scope);
            } else {
                denied.add(scope);
            }
        }

        if (!denied.isEmpty()) {
            return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                    patientId, resourceType, resourceId, Set.copyOf(requestedScopes), false,
                    Set.copyOf(permitted), Set.copyOf(denied), "AI_CONSENT_OPTED_OUT",
                    "Patient/" + patientId + " opted out of AI-Assisted Processing for scope(s): " + denied
            ));
        }

        return recordAiConsentValidationAudit(new AiConsentValidationOutcome(
                patientId, resourceType, resourceId, Set.copyOf(requestedScopes), true,
                Set.copyOf(permitted), Set.of(), "AI_CONSENT_PERMITTED",
                "Patient/" + patientId + " has active AI-Assisted Processing opt-in consent for scope(s): " + permitted
        ));
    }

    private ExtractedResourceAiMetadata extractResourceAiMetadata(Resource resource) {
        Set<AiProcessingScope> scopes = new LinkedHashSet<>();
        List<String> invalidCodes = new ArrayList<>();
        boolean noAiFlag = false;
        Boolean extensionOptIn = null;

        if (resource.hasMeta()) {
            Meta meta = resource.getMeta();
            for (Coding tag : meta.getTag()) {
                if (AI_CONSENT_TAG_SYSTEM.equalsIgnoreCase(tag.getSystem()) && tag.hasCode()) {
                    Optional<AiProcessingScope> parsed = AiProcessingScope.fromCode(tag.getCode());
                    if (parsed.isPresent()) {
                        scopes.add(parsed.get());
                    } else if (AI_OPT_OUT_SECURITY_CODE.equalsIgnoreCase(tag.getCode())) {
                        noAiFlag = true;
                    } else {
                        invalidCodes.add(tag.getCode());
                    }
                }
            }
            for (Coding sec : meta.getSecurity()) {
                if (sec.hasCode()) {
                    if (AI_OPT_OUT_SECURITY_CODE.equalsIgnoreCase(sec.getCode())) {
                        noAiFlag = true;
                    } else if (AI_CONSENT_TAG_SYSTEM.equalsIgnoreCase(sec.getSystem())) {
                        Optional<AiProcessingScope> parsed = AiProcessingScope.fromCode(sec.getCode());
                        if (parsed.isPresent()) {
                            scopes.add(parsed.get());
                        } else if (!AI_OPT_IN_SECURITY_CODE.equalsIgnoreCase(sec.getCode())) {
                            invalidCodes.add(sec.getCode());
                        }
                    }
                }
            }
        }

        if (resource instanceof DomainResource domainResource && domainResource.hasExtension()) {
            for (Extension ext : domainResource.getExtension()) {
                if (AI_EXTENSION_URL.equalsIgnoreCase(ext.getUrl())) {
                    if (ext.getValue() instanceof BooleanType boolVal && boolVal.hasValue()) {
                        extensionOptIn = boolVal.getValue();
                        if (Boolean.TRUE.equals(extensionOptIn) && scopes.isEmpty()) {
                            scopes.add(AiProcessingScope.ALL_AI_PROCESSING);
                        }
                    } else if (ext.getValue() instanceof StringType strVal && strVal.hasValue()) {
                        Optional<AiProcessingScope> parsed = AiProcessingScope.fromCode(strVal.getValue());
                        if (parsed.isPresent()) {
                            scopes.add(parsed.get());
                        } else {
                            invalidCodes.add(strVal.getValue());
                        }
                    }
                }
            }
        }

        return new ExtractedResourceAiMetadata(scopes, invalidCodes, noAiFlag, extensionOptIn);
    }

    private UUID extractPatientIdFromFhirResource(Resource resource) {
        try {
            if (resource instanceof Patient patient && patient.hasId()) {
                return extractUuidFromReference(patient.getIdElement().getIdPart());
            }
            if (resource instanceof Observation obs && obs.hasSubject() && obs.getSubject().hasReference()) {
                return extractUuidFromReference(obs.getSubject().getReference());
            }
            if (resource instanceof Encounter enc && enc.hasSubject() && enc.getSubject().hasReference()) {
                return extractUuidFromReference(enc.getSubject().getReference());
            }
            if (resource instanceof MedicationRequest medReq && medReq.hasSubject() && medReq.getSubject().hasReference()) {
                return extractUuidFromReference(medReq.getSubject().getReference());
            }
            if (resource instanceof Consent consent && consent.hasPatient() && consent.getPatient().hasReference()) {
                return extractUuidFromReference(consent.getPatient().getReference());
            }
            // Generic fallback for Condition, DiagnosticReport, CarePlan, DocumentReference, etc.
            String json = FHIR_R4.newJsonParser().encodeResourceToString(resource);
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "\"reference\"\\s*:\\s*\"Patient/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"")
                    .matcher(json);
            if (matcher.find()) {
                return UUID.fromString(matcher.group(1));
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private void syncAiConsentFromParsedFhirConsent(
            UUID facilityId,
            UUID patientId,
            Consent parsed,
            Instant validFrom,
            Instant validUntil) {
        boolean isAiConsent = false;
        if (parsed.hasCategory()) {
            for (CodeableConcept cat : parsed.getCategory()) {
                for (Coding c : cat.getCoding()) {
                    if (AI_PROCESSING_CATEGORY_CODE.equalsIgnoreCase(c.getCode())) {
                        isAiConsent = true;
                    }
                }
            }
        }
        if (parsed.hasPolicy()) {
            for (Consent.ConsentPolicyComponent p : parsed.getPolicy()) {
                if (AI_PROCESSING_POLICY_URI.equalsIgnoreCase(p.getUri())) {
                    isAiConsent = true;
                }
            }
        }

        EnumMap<AiProcessingScope, Boolean> extractedFlags = new EnumMap<>(AiProcessingScope.class);
        boolean rootPermit = parsed.hasProvision()
                && parsed.getProvision().getType() == Consent.ConsentProvisionType.PERMIT;

        if (parsed.hasProvision()) {
            ProvisionComponent rootProv = parsed.getProvision();
            for (Coding label : rootProv.getSecurityLabel()) {
                if (AI_CONSENT_TAG_SYSTEM.equalsIgnoreCase(label.getSystem()) && label.hasCode()) {
                    isAiConsent = true;
                    AiProcessingScope.fromCode(label.getCode())
                            .ifPresent(scope -> extractedFlags.put(scope, rootPermit));
                } else if (AI_OPT_OUT_SECURITY_CODE.equalsIgnoreCase(label.getCode())) {
                    isAiConsent = true;
                    extractedFlags.put(AiProcessingScope.ALL_AI_PROCESSING, false);
                } else if (AI_OPT_IN_SECURITY_CODE.equalsIgnoreCase(label.getCode())) {
                    isAiConsent = true;
                    extractedFlags.put(AiProcessingScope.ALL_AI_PROCESSING, true);
                }
            }
            for (ProvisionComponent sub : rootProv.getProvision()) {
                boolean subPermit = sub.getType() == Consent.ConsentProvisionType.PERMIT;
                for (Coding label : sub.getSecurityLabel()) {
                    if (AI_CONSENT_TAG_SYSTEM.equalsIgnoreCase(label.getSystem()) && label.hasCode()) {
                        isAiConsent = true;
                        AiProcessingScope.fromCode(label.getCode())
                                .ifPresent(scope -> extractedFlags.put(scope, subPermit));
                    }
                }
            }
        }

        if (!isAiConsent) {
            return;
        }

        boolean globalOptIn = extractedFlags.getOrDefault(AiProcessingScope.ALL_AI_PROCESSING, rootPermit);
        if (parsed.getStatus() == ConsentState.INACTIVE || parsed.getStatus() == ConsentState.REJECTED) {
            revokeAllAiProcessingConsent(facilityId, patientId, "FHIR_CONSENT_SYNC",
                    "Synced inactive/rejected FHIR Consent resource");
            return;
        }
        upsertAiProcessingConsent(new AiProcessingConsentRequest(
                facilityId,
                patientId,
                globalOptIn,
                extractedFlags,
                "FHIR_CONSENT_SYNC",
                "FHIR_R4_API",
                validFrom,
                validUntil,
                "Synchronized from raw HL7 FHIR R4 Consent resource"
        ));
    }

    private AiConsentValidationOutcome recordAiConsentValidationAudit(AiConsentValidationOutcome outcome) {
        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    com.opeshealth.healthcore.security.tenant.FacilityContext.requireFacilityId().toString(),
                    "FHIR_AI_CONSENT_VALIDATOR",
                    "ROLE_SYSTEM_VALIDATOR",
                    outcome.allowed() ? "FHIR_AI_CONSENT_VALIDATION_PERMITTED" : "FHIR_AI_CONSENT_VALIDATION_DENIED",
                    outcome.resourceType(),
                    outcome.resourceId(),
                    outcome.patientId(),
                    "VALIDATE",
                    "/api/v1/consent/ai-processing/validate-fhir",
                    "127.0.0.1",
                    null,
                    outcome.allowed() ? "SUCCESS" : "DENIED",
                    0L,
                    "reason=" + outcome.reasonCode() + " detail=" + outcome.diagnosticMessage()
            ));
        }
        return outcome;
    }

    private static String encodeAiProcessingConsentAsFhirJson(
            UUID recordId,
            UUID patientId,
            boolean globalOptIn,
            Map<AiProcessingScope, Boolean> scopeFlags,
            String status,
            Instant validFrom,
            Instant validUntil,
            Instant updatedAt) {
        Consent consent = new Consent();
        consent.setId(recordId.toString());
        consent.setMeta(new Meta()
                .setLastUpdated(Date.from(updatedAt))
                .addProfile("http://hl7.org/fhir/StructureDefinition/Consent"));
        consent.setStatus("active".equalsIgnoreCase(status) ? ConsentState.ACTIVE : ConsentState.INACTIVE);
        consent.setScope(new CodeableConcept().addCoding(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/consentscope")
                .setCode("patient-privacy")
                .setDisplay("Privacy Consent")));
        consent.addCategory(new CodeableConcept().addCoding(new Coding()
                .setSystem(AI_CONSENT_TAG_SYSTEM)
                .setCode(AI_PROCESSING_CATEGORY_CODE)
                .setDisplay("AI-Assisted Clinical Processing Consent")));
        consent.setPatient(new Reference("Patient/" + patientId));
        consent.setDateTimeElement(new DateTimeType(Date.from(updatedAt)));
        consent.addPolicy().setUri(AI_PROCESSING_POLICY_URI);

        ProvisionComponent rootProvision = new ProvisionComponent();
        rootProvision.setType(globalOptIn ? Consent.ConsentProvisionType.PERMIT : Consent.ConsentProvisionType.DENY);
        rootProvision.setPeriod(new Period()
                .setStart(Date.from(validFrom))
                .setEnd(Date.from(validUntil)));
        rootProvision.addSecurityLabel(new Coding()
                .setSystem(AI_CONSENT_TAG_SYSTEM)
                .setCode(globalOptIn ? AI_OPT_IN_SECURITY_CODE : AI_OPT_OUT_SECURITY_CODE)
                .setDisplay(globalOptIn ? "AI-Assisted Processing Permitted" : "AI-Assisted Processing Opt-Out"));

        for (Map.Entry<AiProcessingScope, Boolean> entry : scopeFlags.entrySet()) {
            if (entry.getKey() == AiProcessingScope.ALL_AI_PROCESSING) {
                continue;
            }
            ProvisionComponent sub = new ProvisionComponent();
            sub.setType(Boolean.TRUE.equals(entry.getValue())
                    ? Consent.ConsentProvisionType.PERMIT
                    : Consent.ConsentProvisionType.DENY);
            sub.addSecurityLabel(new Coding()
                    .setSystem(AI_CONSENT_TAG_SYSTEM)
                    .setCode(entry.getKey().code())
                    .setDisplay(entry.getKey().display()));
            rootProvision.addProvision(sub);
        }
        consent.setProvision(rootProvision);
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(consent);
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
