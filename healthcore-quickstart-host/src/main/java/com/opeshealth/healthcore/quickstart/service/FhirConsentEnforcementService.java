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

import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * HL7 FHIR R4 {@code Consent}, Data Segmentation for Privacy (DS4P), and Break-the-Glass ({@code BTG})
 * Emergency Access Service.
 *
 * <p>Enforces HL7 Confidentiality codes ({@code N} Normal, {@code R} Restricted, {@code V} Very Restricted)
 * across sensitive clinical categories ({@code HIV}, {@code PSY} Mental Health, {@code ETH} Substance Use,
 * {@code SDV} Sexual/Domestic Violence, {@code SICKLE_CELL_GENETICS}, {@code VIP}).</p>
 *
 * <p>Supports time-bound Break-the-Glass emergency override tokens requiring mandatory clinical
 * justification ({@code ETREAT}) and triggers real-time compliance officer alerts via
 * {@link FhirAuditLoggingService} and {@link FhirSubscriptionTopicService}.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class FhirConsentEnforcementService {

    private static final Logger log = LoggerFactory.getLogger(FhirConsentEnforcementService.class);

    public enum ConfidentialityLevel {
        N("N", "Normal"),
        R("R", "Restricted"),
        V("V", "Very Restricted");

        private final String code;
        private final String display;

        ConfidentialityLevel(String code, String display) {
            this.code = code;
            this.display = display;
        }

        public String code() {
            return code;
        }

        public String display() {
            return display;
        }
    }

    public enum SensitivityCategory {
        HIV("HIV", "HIV / AIDS & Antiretroviral Therapy Records", ConfidentialityLevel.V),
        PSY("PSY", "Psychiatry & Behavioral Mental Health Notes", ConfidentialityLevel.R),
        ETH("ETH", "Substance Use & Addiction Recovery", ConfidentialityLevel.R),
        SDV("SDV", "Sexual & Domestic Violence / Reproductive Health", ConfidentialityLevel.V),
        GEN("GEN", "Genetic & Pharmacogenomic Markers", ConfidentialityLevel.R),
        VIP("VIP", "High-Profile VIP Patient Confidentiality", ConfidentialityLevel.V);

        private final String actCode;
        private final String description;
        private final ConfidentialityLevel defaultConfidentiality;

        SensitivityCategory(String actCode, String description, ConfidentialityLevel defaultConfidentiality) {
            this.actCode = actCode;
            this.description = description;
            this.defaultConfidentiality = defaultConfidentiality;
        }

        public String actCode() {
            return actCode;
        }

        public String description() {
            return description;
        }

        public ConfidentialityLevel defaultConfidentiality() {
            return defaultConfidentiality;
        }
    }

    public enum ConsentProvisionType {
        PERMIT,
        DENY
    }

    public record ConsentDirectiveRequest(
            UUID facilityId,
            UUID patientId,
            ConsentProvisionType provisionType,
            Set<SensitivityCategory> restrictedCategories,
            Set<String> authorizedActorIds,
            Set<String> purposeOfUseCodes,
            Instant validFrom,
            Instant validUntil,
            String policyUri
    ) {}

    public record FhirConsentDirective(
            UUID consentId,
            UUID facilityId,
            UUID patientId,
            String status,
            ConsentProvisionType provisionType,
            Set<SensitivityCategory> restrictedCategories,
            Set<String> authorizedActorIds,
            Set<String> purposeOfUseCodes,
            Instant validFrom,
            Instant validUntil,
            String policyUri,
            String fhirConsentJson,
            Instant createdAt
    ) {}

    public record BreakTheGlassRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String clinicianRole,
            String emergencyReason,
            Set<SensitivityCategory> requestedCategories,
            Integer durationMinutes
    ) {}

    public record BreakTheGlassSession(
            UUID sessionId,
            String emergencyToken,
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String clinicianRole,
            String purposeOfUseCode,
            String emergencyReason,
            Set<SensitivityCategory> unlockedCategories,
            Instant issuedAt,
            Instant expiresAt,
            boolean active,
            boolean complianceOfficerAlerted
    ) {}

    public record ClinicalSegmentItem(
            String itemId,
            String resourceType,
            String displaySummary,
            SensitivityCategory sensitivityCategory,
            ConfidentialityLevel confidentialityLevel
    ) {}

    public record SegmentedAccessEvaluationResult(
            UUID patientId,
            String actorId,
            String purposeOfUse,
            boolean breakTheGlassApplied,
            UUID breakTheGlassSessionId,
            int totalItemsEvaluated,
            int permittedItemsCount,
            int redactedItemsCount,
            List<ClinicalSegmentItem> permittedItems,
            List<String> redactedItemNotices,
            List<String> appliedSecurityLabels
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final FhirSubscriptionTopicService subscriptionTopicService;

    private final Map<UUID, FhirConsentDirective> consentsById = new ConcurrentHashMap<>();
    private final Map<String, BreakTheGlassSession> btgSessionsByToken = new ConcurrentHashMap<>();

    @Autowired
    public FhirConsentEnforcementService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        this.subscriptionTopicService = subscriptionTopicService;
    }

    public FhirConsentEnforcementService() {
        this(null, null);
    }

    /**
     * Registers or updates an HL7 FHIR R4 {@code Consent} privacy directive for a patient.
     */
    @ClinicalAudit(action = "FHIR_CONSENT_REGISTER", resourceType = "Consent")
    public FhirConsentDirective registerConsentDirective(ConsentDirectiveRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("ConsentDirectiveRequest with patientId is required");
        }
        UUID consentId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        ConsentProvisionType provision = request.provisionType() != null
                ? request.provisionType()
                : ConsentProvisionType.DENY;
        Set<SensitivityCategory> categories = (request.restrictedCategories() != null && !request.restrictedCategories().isEmpty())
                ? Set.copyOf(request.restrictedCategories())
                : Set.of(SensitivityCategory.HIV, SensitivityCategory.PSY, SensitivityCategory.ETH, SensitivityCategory.SDV);
        Set<String> actors = request.authorizedActorIds() != null
                ? Set.copyOf(request.authorizedActorIds())
                : Set.of();
        Set<String> purposes = (request.purposeOfUseCodes() != null && !request.purposeOfUseCodes().isEmpty())
                ? Set.copyOf(request.purposeOfUseCodes())
                : Set.of("TREAT", "ETREAT");

        Instant now = Instant.now();
        Instant validFrom = request.validFrom() != null ? request.validFrom() : now;
        Instant validUntil = request.validUntil() != null ? request.validUntil() : now.plus(Duration.ofDays(365));
        String policyUri = (request.policyUri() != null && !request.policyUri().isBlank())
                ? request.policyUri().trim()
                : "https://opeshealthsystems.com/fhir/Policy/hipaa-gdpr-ds4p-privacy";

        String fhirJson = buildFhirConsentResourceJson(
                consentId, request.patientId(), provision, categories, actors, validFrom, validUntil, policyUri);

        FhirConsentDirective directive = new FhirConsentDirective(
                consentId,
                facilityId,
                request.patientId(),
                "active",
                provision,
                categories,
                actors,
                purposes,
                validFrom,
                validUntil,
                policyUri,
                fhirJson,
                now
        );

        consentsById.put(consentId, directive);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "PATIENT_CONSENT_PORTAL",
                    "PATIENT",
                    "FHIR_CONSENT_DIRECTIVE_CREATED",
                    "Consent",
                    consentId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/consent/directives",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Registered FHIR R4 Consent directive (" + provision + ") covering " + categories
            ));
        }

        return directive;
    }

    public List<FhirConsentDirective> listPatientConsents(UUID patientId) {
        return consentsById.values().stream()
                .filter(c -> patientId == null || Objects.equals(patientId, c.patientId()))
                .sorted(Comparator.comparing(FhirConsentDirective::createdAt).reversed())
                .toList();
    }

    public FhirConsentDirective getConsentById(UUID consentId) {
        if (consentId == null) {
            throw new IllegalArgumentException("consentId is required");
        }
        FhirConsentDirective found = consentsById.get(consentId);
        if (found == null) {
            throw new NoSuchElementException("FHIR Consent directive not found: " + consentId);
        }
        return found;
    }

    public FhirConsentDirective updateConsentDirective(FhirConsentDirective directive) {
        Objects.requireNonNull(directive, "FhirConsentDirective is required");
        consentsById.put(directive.consentId(), directive);
        return directive;
    }

    /**
     * Issues a time-bound Break-the-Glass ({@code BTG} / {@code ETREAT}) emergency override token
     * with mandatory clinical justification and dispatches an immediate compliance officer alert.
     */
    @ClinicalAudit(action = "FHIR_BREAK_THE_GLASS_OVERRIDE", resourceType = "AuditEvent")
    public BreakTheGlassSession invokeBreakTheGlass(BreakTheGlassRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("BreakTheGlassRequest with patientId is required");
        }
        if (request.clinicianId() == null || request.clinicianId().isBlank()) {
            throw new IllegalArgumentException("Authenticated clinicianId is required for Break-the-Glass override");
        }
        if (request.emergencyReason() == null || request.emergencyReason().trim().length() < 10) {
            throw new IllegalArgumentException(
                    "Break-the-Glass requires a clinical emergency justification of at least 10 characters");
        }

        int minutes = (request.durationMinutes() != null && request.durationMinutes() > 0)
                ? Math.min(request.durationMinutes(), 240)
                : 60;

        UUID sessionId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        String token = "BTG-" + sessionId.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(minutes));

        Set<SensitivityCategory> unlocked = (request.requestedCategories() != null && !request.requestedCategories().isEmpty())
                ? Set.copyOf(request.requestedCategories())
                : Set.of(SensitivityCategory.values());

        BreakTheGlassSession session = new BreakTheGlassSession(
                sessionId,
                token,
                facilityId,
                request.patientId(),
                request.clinicianId().trim(),
                request.clinicianRole() != null ? request.clinicianRole().trim() : "ROLE_PHYSICIAN",
                "ETREAT",
                request.emergencyReason().trim(),
                unlocked,
                now,
                expiresAt,
                true,
                true
        );

        btgSessionsByToken.put(token, session);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    session.clinicianId(),
                    session.clinicianRole(),
                    "BREAK_THE_GLASS_EMERGENCY_ACCESS",
                    "Patient",
                    request.patientId().toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/consent/break-the-glass",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "ETREAT Break-the-Glass invoked: " + session.emergencyReason()
                            + " (expires " + expiresAt + ")"
            ));
        }

        if (subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    facilityId,
                    FhirSubscriptionTopicService.TOPIC_BREAK_THE_GLASS_INVOKED,
                    "Patient",
                    request.patientId().toString(),
                    request.patientId(),
                    "CRITICAL",
                    "Break-the-Glass emergency override invoked by " + session.clinicianId()
                            + " for Patient/" + request.patientId() + ": " + session.emergencyReason(),
                    Map.of(
                            "sessionId", sessionId.toString(),
                            "clinicianId", session.clinicianId(),
                            "purposeOfUse", "ETREAT",
                            "expiresAt", expiresAt.toString()
                    )
            ));
        }

        log.warn("BREAK_THE_GLASS invoked by clinician={} for patient={} sessionId={} expiresAt={}",
                session.clinicianId(), request.patientId(), sessionId, expiresAt);
        return session;
    }

    /**
     * Revokes an active Break-the-Glass emergency token before its expiration time.
     */
    public BreakTheGlassSession revokeBreakTheGlassToken(String emergencyToken) {
        if (emergencyToken == null || emergencyToken.isBlank()) {
            throw new IllegalArgumentException("emergencyToken is required");
        }
        BreakTheGlassSession existing = btgSessionsByToken.get(emergencyToken.trim());
        if (existing == null) {
            throw new NoSuchElementException("Break-the-Glass session not found for token");
        }
        BreakTheGlassSession revoked = new BreakTheGlassSession(
                existing.sessionId(),
                existing.emergencyToken(),
                existing.facilityId(),
                existing.patientId(),
                existing.clinicianId(),
                existing.clinicianRole(),
                existing.purposeOfUseCode(),
                existing.emergencyReason(),
                existing.unlockedCategories(),
                existing.issuedAt(),
                Instant.now(),
                false,
                existing.complianceOfficerAlerted()
        );
        btgSessionsByToken.put(emergencyToken.trim(), revoked);
        return revoked;
    }

    /**
     * Evaluates a list of clinical items under HL7 DS4P Data Segmentation for Privacy rules,
     * redacting restricted categories unless explicitly permitted by an active FHIR {@code Consent}
     * directive or a valid, non-expired Break-the-Glass emergency token.
     */
    public SegmentedAccessEvaluationResult evaluateSegmentedAccess(
            UUID patientId,
            String actorId,
            String purposeOfUse,
            String breakTheGlassToken,
            List<ClinicalSegmentItem> items) {

        Objects.requireNonNull(patientId, "patientId is required");
        String resolvedActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "ANONYMOUS";
        String resolvedPurpose = (purposeOfUse != null && !purposeOfUse.isBlank()) ? purposeOfUse.trim() : "TREAT";

        BreakTheGlassSession activeBtg = validateBreakTheGlassToken(patientId, breakTheGlassToken);
        List<FhirConsentDirective> activeConsents = listPatientConsents(patientId).stream()
                .filter(c -> "active".equalsIgnoreCase(c.status()))
                .filter(c -> !Instant.now().isAfter(c.validUntil()))
                .toList();

        List<ClinicalSegmentItem> permitted = new ArrayList<>();
        List<String> redactedNotices = new ArrayList<>();
        Set<String> securityLabels = new LinkedHashSet<>();
        securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|N");

        List<ClinicalSegmentItem> safeItems = items != null ? items : List.of();
        for (ClinicalSegmentItem item : safeItems) {
            if (item == null) {
                continue;
            }
            if (item.sensitivityCategory() == null || item.confidentialityLevel() == ConfidentialityLevel.N) {
                permitted.add(item);
                continue;
            }

            SensitivityCategory cat = item.sensitivityCategory();
            if (activeBtg != null && activeBtg.unlockedCategories().contains(cat)) {
                permitted.add(item);
                securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-ActReason|ETREAT");
                securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|"
                        + item.confidentialityLevel().code());
                continue;
            }

            if (isCategoryPermittedByConsent(cat, resolvedActor, activeConsents)) {
                permitted.add(item);
                securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|"
                        + item.confidentialityLevel().code());
            } else {
                redactedNotices.add("[DS4P REDACTED — Category=" + cat.actCode()
                        + " (" + item.confidentialityLevel().code() + "): "
                        + item.resourceType() + "/" + item.itemId()
                        + " requires patient FHIR Consent or Break-the-Glass ETREAT override]");
                securityLabels.add("http://terminology.hl7.org/CodeSystem/v3-ActCode|REDACTED");
            }
        }

        return new SegmentedAccessEvaluationResult(
                patientId,
                resolvedActor,
                resolvedPurpose,
                activeBtg != null,
                activeBtg != null ? activeBtg.sessionId() : null,
                safeItems.size(),
                permitted.size(),
                redactedNotices.size(),
                List.copyOf(permitted),
                List.copyOf(redactedNotices),
                List.copyOf(securityLabels)
        );
    }

    public BreakTheGlassSession validateBreakTheGlassToken(UUID patientId, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        BreakTheGlassSession session = btgSessionsByToken.get(token.trim());
        if (session == null || !session.active()) {
            return null;
        }
        if (!Objects.equals(patientId, session.patientId())) {
            return null;
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            return null;
        }
        return session;
    }

    private static boolean isCategoryPermittedByConsent(
            SensitivityCategory category,
            String actorId,
            List<FhirConsentDirective> activeConsents) {

        for (FhirConsentDirective directive : activeConsents) {
            if (!directive.restrictedCategories().contains(category)) {
                continue;
            }
            if (directive.provisionType() == ConsentProvisionType.PERMIT) {
                if (directive.authorizedActorIds().isEmpty()
                        || directive.authorizedActorIds().contains(actorId)
                        || directive.authorizedActorIds().contains("*")) {
                    return true;
                }
            } else if (directive.provisionType() == ConsentProvisionType.DENY) {
                if (directive.authorizedActorIds().contains(actorId)) {
                    return true;
                }
                return false;
            }
        }
        // By default under HIPAA/GDPR DS4P, Very Restricted (V) and Restricted (R) categories require explicit permit
        return false;
    }

    private static String buildFhirConsentResourceJson(
            UUID consentId,
            UUID patientId,
            ConsentProvisionType provision,
            Set<SensitivityCategory> categories,
            Set<String> actors,
            Instant validFrom,
            Instant validUntil,
            String policyUri) {
        List<String> secLabelTokens = categories.stream()
                .map(c -> "{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ActCode\",\"code\":\""
                        + c.actCode() + "\",\"display\":\"" + c.description() + "\"}")
                .toList();
        return """
                {
                  "resourceType": "Consent",
                  "id": "%s",
                  "status": "active",
                  "scope": {
                    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentscope", "code": "patient-privacy", "display": "Privacy Consent"}]
                  },
                  "category": [
                    {"coding": [{"system": "http://loinc.org", "code": "59284-0", "display": "Patient Consent"}]}
                  ],
                  "patient": {"reference": "Patient/%s"},
                  "dateTime": "%s",
                  "policyRule": {
                    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/consentpolicycodes", "code": "hipaa-auth"}]
                  },
                  "policy": [{"uri": "%s"}],
                  "provision": {
                    "type": "%s",
                    "period": {"start": "%s", "end": "%s"},
                    "securityLabel": [%s],
                    "authorizedActorsCount": %d
                  }
                }
                """.formatted(
                consentId,
                patientId,
                Instant.now(),
                policyUri,
                provision.name().toLowerCase(Locale.ROOT),
                validFrom,
                validUntil,
                String.join(",", secLabelTokens),
                actors.size()
        ).trim();
    }
}
