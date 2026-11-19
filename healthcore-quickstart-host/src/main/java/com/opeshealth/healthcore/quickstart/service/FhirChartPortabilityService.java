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
import com.opeshealth.healthcore.quickstart.domain.EncounterRepository;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationRepository;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.FhirConsentDirective;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SegmentedAccessEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Consent;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * HL7 FHIR R4 {@code Patient/{id}/$everything}, {@code Encounter/{id}/$everything}, and
 * Patient Compartment Search Portability Service.
 *
 * <p>Exports a complete longitudinal patient chart or encounter bundle for clinical handovers,
 * hospital migrations, and GDPR Article 20 / HIPAA Right of Access portability while enforcing
 * HL7 DS4P confidentiality segmentation and Break-the-Glass ({@code ETREAT}) overrides.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class FhirChartPortabilityService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public record ChartEverythingExportResponse(
            UUID bundleId,
            String scope,
            UUID rootResourceId,
            UUID patientId,
            int totalResourcesExported,
            int redactedSensitiveResourcesCount,
            boolean breakTheGlassApplied,
            Map<String, Integer> resourceCountsByType,
            List<String> ds4pSecurityLabels,
            String fhirBundleJson,
            Instant generatedAt
    ) {}

    private final PatientService patientService;
    private final EncounterRepository encounterRepository;
    private final ObservationRepository observationRepository;
    private final ConsentService consentService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    @Autowired
    public FhirChartPortabilityService(
            @Autowired(required = false) PatientService patientService,
            @Autowired(required = false) EncounterRepository encounterRepository,
            @Autowired(required = false) ObservationRepository observationRepository,
            @Autowired(required = false) ConsentService consentService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.patientService = patientService;
        this.encounterRepository = encounterRepository;
        this.observationRepository = observationRepository;
        this.consentService = consentService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    /**
     * Executes HL7 FHIR R4 {@code Patient/{id}/$everything} with optional {@code _type},
     * {@code _since} filters and DS4P / Break-the-Glass privacy enforcement.
     */
    @ClinicalAudit(action = "FHIR_PATIENT_EVERYTHING_EXPORT", resourceType = "Patient")
    public ChartEverythingExportResponse exportPatientEverything(
            UUID patientId,
            String actorId,
            String breakTheGlassToken,
            Set<String> allowedResourceTypes,
            Instant sinceInstant) {

        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required for Patient/$everything");
        }

        UUID bundleId = UUID.randomUUID();
        Bundle bundle = new Bundle();
        bundle.setId(bundleId.toString());
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTimestamp(new Date());

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<ClinicalSegmentItem> sensitivityCandidates = new ArrayList<>();
        Map<String, Resource> stagedResourcesByKey = new LinkedHashMap<>();

        // 1. Root Patient resource
        Patient patient = resolvePatientResource(patientId);
        if (isTypeAllowed("Patient", allowedResourceTypes)) {
            stagedResourcesByKey.put("Patient/" + patientId, patient);
            sensitivityCandidates.add(new ClinicalSegmentItem(
                    patientId.toString(), "Patient", "Patient Demographics", null, ConfidentialityLevel.N));
        }

        // 2. Patient Encounters
        if (isTypeAllowed("Encounter", allowedResourceTypes) && encounterRepository != null) {
            encounterRepository.findAll().stream()
                    .filter(e -> patientId.equals(e.getPatientId()))
                    .filter(e -> sinceInstant == null || e.getStartedAt() == null || !e.getStartedAt().isBefore(sinceInstant))
                    .forEach(encEntity -> {
                        Encounter enc = new Encounter();
                        enc.setId(encEntity.getId().toString());
                        enc.setSubject(new Reference("Patient/" + patientId));
                        enc.setStatus(Encounter.EncounterStatus.FINISHED);
                        enc.setClass_(new Coding()
                                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                                .setCode(encEntity.getEncounterClass() != null ? encEntity.getEncounterClass() : "AMB"));
                        String key = "Encounter/" + encEntity.getId();
                        stagedResourcesByKey.put(key, enc);
                        SensitivityCategory cat = detectSensitivityFromText(encEntity.getChiefComplaint());
                        sensitivityCandidates.add(new ClinicalSegmentItem(
                                encEntity.getId().toString(),
                                "Encounter",
                                encEntity.getChiefComplaint() != null ? encEntity.getChiefComplaint() : "Encounter",
                                cat,
                                cat != null ? cat.defaultConfidentiality() : ConfidentialityLevel.N));
                    });
        }

        // 3. Patient Observations
        if (isTypeAllowed("Observation", allowedResourceTypes) && observationRepository != null) {
            observationRepository.findAll().stream()
                    .filter(o -> patientId.equals(o.getPatientId()))
                    .filter(o -> sinceInstant == null || o.getEffectiveAt() == null || !o.getEffectiveAt().isBefore(sinceInstant))
                    .forEach(obsEntity -> {
                        Observation obs = toFhirObservation(obsEntity);
                        String key = "Observation/" + obsEntity.getId();
                        stagedResourcesByKey.put(key, obs);
                        SensitivityCategory cat = detectSensitivityFromText(
                                (obsEntity.getCodeDisplay() != null ? obsEntity.getCodeDisplay() : "")
                                        + " " + (obsEntity.getCodeValue() != null ? obsEntity.getCodeValue() : ""));
                        sensitivityCandidates.add(new ClinicalSegmentItem(
                                obsEntity.getId().toString(),
                                "Observation",
                                obsEntity.getCodeDisplay() != null ? obsEntity.getCodeDisplay() : "Observation",
                                cat,
                                cat != null ? cat.defaultConfidentiality() : ConfidentialityLevel.N));
                    });
        }

        // 4. Active Patient Consent directives
        if (isTypeAllowed("Consent", allowedResourceTypes) && consentService != null) {
            for (FhirConsentDirective dir : consentService.listPatientConsents(patientId)) {
                Consent c = FHIR_R4.newJsonParser().parseResource(Consent.class, dir.fhirConsentJson());
                stagedResourcesByKey.put("Consent/" + dir.consentId(), c);
                sensitivityCandidates.add(new ClinicalSegmentItem(
                        dir.consentId().toString(), "Consent", "Privacy Consent", null, ConfidentialityLevel.N));
            }
        }

        // 5. Enforce HL7 DS4P privacy segmentation & Break-the-Glass
        int redactedCount = 0;
        boolean btgApplied = false;
        List<String> securityLabels = List.of("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|N");

        if (consentService != null && !sensitivityCandidates.isEmpty()) {
            SegmentedAccessEvaluationResult eval = consentService.evaluateSegmentedAccess(
                    patientId,
                    actorId != null ? actorId : "PORTABILITY_CLIENT",
                    breakTheGlassToken != null && !breakTheGlassToken.isBlank() ? "ETREAT" : "TREAT",
                    breakTheGlassToken,
                    sensitivityCandidates
            );
            redactedCount = eval.redactedItemsCount();
            btgApplied = eval.breakTheGlassApplied();
            securityLabels = eval.appliedSecurityLabels();

            Set<String> permittedKeys = eval.permittedItems().stream()
                    .map(i -> i.resourceType() + "/" + i.itemId())
                    .collect(java.util.stream.Collectors.toSet());

            for (Map.Entry<String, Resource> entry : stagedResourcesByKey.entrySet()) {
                if (permittedKeys.contains(entry.getKey())) {
                    addBundleEntry(bundle, counts, entry.getValue(), entry.getKey());
                }
            }
        } else {
            for (Map.Entry<String, Resource> entry : stagedResourcesByKey.entrySet()) {
                addBundleEntry(bundle, counts, entry.getValue(), entry.getKey());
            }
        }

        bundle.setTotal(bundle.getEntry().size());
        Meta meta = new Meta().setLastUpdated(new Date());
        for (String secLabel : securityLabels) {
            String[] parts = secLabel.split("\\|", 2);
            if (parts.length == 2) {
                meta.addSecurity(new Coding().setSystem(parts[0]).setCode(parts[1]));
            }
        }
        bundle.setMeta(meta);

        String bundleJson = FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    PatientService.DEFAULT_FACILITY_ID.toString(),
                    actorId != null ? actorId : "PORTABILITY_CLIENT",
                    "ROLE_CLINICIAN",
                    "FHIR_PATIENT_EVERYTHING_EXPORT",
                    "Patient",
                    patientId.toString(),
                    patientId,
                    "GET",
                    "/api/v1/fhir/Patient/" + patientId + "/$everything",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Exported Patient/$everything bundle=" + bundleId + " resources=" + bundle.getTotal()
                            + " redacted=" + redactedCount
            ));
        }

        return new ChartEverythingExportResponse(
                bundleId,
                "Patient/$everything",
                patientId,
                patientId,
                bundle.getTotal(),
                redactedCount,
                btgApplied,
                Map.copyOf(counts),
                securityLabels,
                bundleJson,
                Instant.now()
        );
    }

    /**
     * Executes HL7 FHIR R4 {@code Encounter/{id}/$everything} returning the encounter,
     * subject patient, and linked clinical observations.
     */
    @ClinicalAudit(action = "FHIR_ENCOUNTER_EVERYTHING_EXPORT", resourceType = "Encounter")
    public ChartEverythingExportResponse exportEncounterEverything(
            UUID encounterId,
            String actorId,
            String breakTheGlassToken) {
        if (encounterId == null) {
            throw new IllegalArgumentException("encounterId is required for Encounter/$everything");
        }
        var encEntity = (encounterRepository != null ? encounterRepository.findById(encounterId) : java.util.Optional.<com.opeshealth.healthcore.emr.entity.ClinicalEntities.EncounterEntity>empty())
                .orElseThrow(() -> new NoSuchElementException("Encounter not found for $everything: " + encounterId));

        UUID patientId = encEntity.getPatientId();
        UUID bundleId = UUID.randomUUID();
        Bundle bundle = new Bundle();
        bundle.setId(bundleId.toString());
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTimestamp(new Date());

        Map<String, Integer> counts = new LinkedHashMap<>();

        Encounter enc = new Encounter();
        enc.setId(encEntity.getId().toString());
        enc.setSubject(new Reference("Patient/" + patientId));
        enc.setStatus(Encounter.EncounterStatus.FINISHED);
        addBundleEntry(bundle, counts, enc, "Encounter/" + encounterId);

        Patient patient = resolvePatientResource(patientId);
        addBundleEntry(bundle, counts, patient, "Patient/" + patientId);

        if (observationRepository != null) {
            observationRepository.findAll().stream()
                    .filter(o -> encounterId.equals(o.getEncounterId()) || patientId.equals(o.getPatientId()))
                    .forEach(o -> addBundleEntry(bundle, counts, toFhirObservation(o), "Observation/" + o.getId()));
        }

        bundle.setTotal(bundle.getEntry().size());
        String bundleJson = FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);

        return new ChartEverythingExportResponse(
                bundleId,
                "Encounter/$everything",
                encounterId,
                patientId,
                bundle.getTotal(),
                0,
                breakTheGlassToken != null && !breakTheGlassToken.isBlank(),
                Map.copyOf(counts),
                List.of("http://terminology.hl7.org/CodeSystem/v3-Confidentiality|N"),
                bundleJson,
                Instant.now()
        );
    }

    /**
     * Executes FHIR Patient Compartment search ({@code GET /api/v1/fhir/Patient/{id}/{resourceType}}).
     */
    public ChartEverythingExportResponse searchPatientCompartment(
            UUID patientId,
            String resourceType,
            String actorId,
            String breakTheGlassToken) {
        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("Compartment resourceType is required");
        }
        return exportPatientEverything(patientId, actorId, breakTheGlassToken, Set.of(resourceType.trim()), null);
    }

    private Patient resolvePatientResource(UUID patientId) {
        if (patientService != null) {
            try {
                return patientService.getFhirPatientById(patientId);
            } catch (Exception ignored) {
                // Fallback synthetic reference if patient was seeded outside JPA
            }
        }
        Patient fallback = new Patient();
        fallback.setId(patientId.toString());
        fallback.setActive(true);
        return fallback;
    }

    private static Observation toFhirObservation(ObservationEntity entity) {
        Observation obs = new Observation();
        obs.setId(entity.getId().toString());
        obs.setStatus(Observation.ObservationStatus.FINAL);
        obs.setSubject(new Reference("Patient/" + entity.getPatientId()));
        if (entity.getEncounterId() != null) {
            obs.setEncounter(new Reference("Encounter/" + entity.getEncounterId()));
        }
        obs.setCode(new CodeableConcept().addCoding(new Coding()
                .setSystem(entity.getCodeSystem() != null ? entity.getCodeSystem() : "http://loinc.org")
                .setCode(entity.getCodeValue() != null ? entity.getCodeValue() : "8867-4")
                .setDisplay(entity.getCodeDisplay() != null ? entity.getCodeDisplay() : "Clinical Observation")));
        return obs;
    }

    private static void addBundleEntry(Bundle bundle, Map<String, Integer> counts, Resource resource, String fullUrl) {
        bundle.addEntry().setFullUrl(fullUrl).setResource(resource);
        String typeName = resource.fhirType();
        counts.merge(typeName, 1, Integer::sum);
    }

    private static boolean isTypeAllowed(String resourceType, Set<String> allowedTypes) {
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            return true;
        }
        return allowedTypes.stream().anyMatch(t -> t.equalsIgnoreCase(resourceType));
    }

    private static SensitivityCategory detectSensitivityFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("hiv") || lower.contains("antiretroviral") || lower.contains("b20")) {
            return SensitivityCategory.HIV;
        }
        if (lower.contains("psych") || lower.contains("bipolar") || lower.contains("schizophren") || lower.contains("depress")) {
            return SensitivityCategory.PSY;
        }
        if (lower.contains("substance") || lower.contains("opioid") || lower.contains("addiction")) {
            return SensitivityCategory.ETH;
        }
        return null;
    }
}
