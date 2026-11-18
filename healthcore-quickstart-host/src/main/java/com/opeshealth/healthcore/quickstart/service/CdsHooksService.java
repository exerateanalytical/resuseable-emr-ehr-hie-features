/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 CDS Hooks 2.0 Clinical Decision Support Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationRepository;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine.OrderSafetyRequest;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HL7 CDS Hooks 2.0 service implementing discovery, prefetch/context hydration,
 * card + suggestion + systemAction responses, feedback capture, and automatic
 * triggering during {@code Patient}, {@code Observation}, and {@code Encounter} interactions.
 */
@Service
public class CdsHooksService {

    public static final String SERVICE_PATIENT_CARE_GAPS = "opes-patient-care-gaps";
    public static final String SERVICE_OBSERVATION_ALERTS = "opes-observation-alerts";
    public static final String SERVICE_ENCOUNTER_TRIAGE = "opes-encounter-triage";
    public static final String SERVICE_MEDICATION_SAFETY = "opes-medication-safety";

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final Pattern UUID_TAIL_PATTERN = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsServiceDescriptor(
            String hook,
            String title,
            String description,
            String id,
            Map<String, String> prefetch,
            String usageRequirements
    ) {}

    public record ServiceDiscoveryResponse(List<CdsServiceDescriptor> services) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirAuthorization(
            String accessToken,
            String tokenType,
            Integer expiresIn,
            String scope,
            String subject
    ) {}

    public record CdsHookRequest(
            String hook,
            String hookInstance,
            String fhirServer,
            FhirAuthorization fhirAuthorization,
            Map<String, Object> context,
            Map<String, Object> prefetch
    ) {
        public CdsHookRequest {
            context = context != null ? context : Map.of();
            prefetch = prefetch != null ? prefetch : Map.of();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsSource(
            String label,
            String url,
            String icon,
            CdsCoding topic
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsCoding(
            String code,
            String system,
            String display
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsAction(
            String type, // create, update, delete
            String description,
            Map<String, Object> resource,
            String resourceId
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsSuggestion(
            String label,
            String uuid,
            Boolean isRecommended,
            List<CdsAction> actions
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsOverrideReason(
            String code,
            String system,
            String display
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsLink(
            String label,
            String url,
            String type, // absolute, smart
            String appContext
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsHookCard(
            String uuid,
            String summary,
            String detail,
            String indicator, // info, warning, critical
            CdsSource source,
            List<CdsSuggestion> suggestions,
            String selectionBehavior, // at-most-one, any
            List<CdsOverrideReason> overrideReasons,
            List<CdsLink> links
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CdsHookResponse(
            List<CdsHookCard> cards,
            List<CdsAction> systemActions
    ) {
        public CdsHookResponse {
            cards = cards != null ? List.copyOf(cards) : List.of();
            systemActions = systemActions != null ? List.copyOf(systemActions) : List.of();
        }

        public String highestIndicator() {
            boolean warn = false;
            for (CdsHookCard card : cards) {
                if ("critical".equalsIgnoreCase(card.indicator())) return "critical";
                if ("warning".equalsIgnoreCase(card.indicator())) warn = true;
            }
            if (warn) return "warning";
            return cards.isEmpty() ? "none" : "info";
        }
    }

    public record CdsFeedbackOverrideReason(CdsCoding reason, String userComment) {}

    public record CdsFeedbackItem(
            String card,
            String outcome, // accepted, overridden
            List<Map<String, String>> acceptedSuggestions,
            CdsFeedbackOverrideReason overrideReason,
            Instant outcomeTimestamp
    ) {}

    public record CdsFeedbackRequest(List<CdsFeedbackItem> feedback) {}

    public record CdsFeedbackReceipt(
            String serviceId,
            int recordedCount,
            Instant receivedAt
    ) {}

    public record CdsHookExecutionEvent(
            Instant timestamp,
            String serviceId,
            String hook,
            UUID patientId,
            UUID facilityId,
            int cardCount,
            String highestIndicator
    ) {}

    private final PatientService patientService;
    private final ObservationRepository observationRepository;
    private final CdsHooksEngine legacyCdssEngine;
    private final List<CdsHookExecutionEvent> executionHistory = new CopyOnWriteArrayList<>();
    private final List<CdsFeedbackItem> feedbackHistory = new CopyOnWriteArrayList<>();
    private final List<Consumer<CdsHookExecutionEvent>> listeners = new CopyOnWriteArrayList<>();

    public CdsHooksService() {
        this(null, null, null);
    }

    @Autowired
    public CdsHooksService(
            ObjectProvider<PatientService> patientServiceProvider,
            ObjectProvider<ObservationRepository> observationRepoProvider,
            ObjectProvider<CdsHooksEngine> cdsEngineProvider) {
        this(
                patientServiceProvider != null ? patientServiceProvider.getIfAvailable() : null,
                observationRepoProvider != null ? observationRepoProvider.getIfAvailable() : null,
                cdsEngineProvider != null ? cdsEngineProvider.getIfAvailable() : null
        );
    }

    public CdsHooksService(
            PatientService patientService,
            ObservationRepository observationRepository,
            CdsHooksEngine legacyCdssEngine) {
        this.patientService = patientService;
        this.observationRepository = observationRepository;
        this.legacyCdssEngine = legacyCdssEngine;
    }

    public void registerListener(Consumer<CdsHookExecutionEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public List<CdsHookExecutionEvent> getRecentEvaluations(int limit) {
        int size = executionHistory.size();
        int from = Math.max(0, size - Math.max(limit, 1));
        return List.copyOf(executionHistory.subList(from, size));
    }

    public List<CdsFeedbackItem> getRecordedFeedback() {
        return List.copyOf(feedbackHistory);
    }

    /** CDS Hooks 2.0 Discovery catalog. */
    public ServiceDiscoveryResponse discoverServices() {
        return new ServiceDiscoveryResponse(List.of(
                new CdsServiceDescriptor(
                        "patient-view",
                        "Opes HealthCore Patient Care Gaps & Risk Surveillance",
                        "Evaluates demographics, age-based cardiometabolic screening, chronic disease gaps, and recent vital/lab observations.",
                        SERVICE_PATIENT_CARE_GAPS,
                        Map.of(
                                "patient", "Patient/{{context.patientId}}",
                                "observations", "Observation?patient={{context.patientId}}&_count=20"
                        ),
                        "Requires read access to Patient and recent Observation resources"
                ),
                new CdsServiceDescriptor(
                        "patient-view",
                        "Opes HealthCore Observation & NEWS2 Clinical Alert Service",
                        "Analyzes incoming vital signs, blood pressure panels, SpO2, temperature, potassium, and glucose for early warning or panic alerts.",
                        SERVICE_OBSERVATION_ALERTS,
                        Map.of(
                                "patient", "Patient/{{context.patientId}}",
                                "observations", "Observation?patient={{context.patientId}}&_count=10"
                        ),
                        "Triggered automatically on Observation write/review or via CDS Hooks patient-view"
                ),
                new CdsServiceDescriptor(
                        "encounter-start",
                        "Opes HealthCore Encounter Preventive Care & Acuity Triage",
                        "Surfaces preventive screening, hypertension follow-up, and high-acuity emergency triage prompts at encounter check-in.",
                        SERVICE_ENCOUNTER_TRIAGE,
                        Map.of(
                                "patient", "Patient/{{context.patientId}}",
                                "encounter", "Encounter/{{context.encounterId}}"
                        ),
                        "Provides Patient and Encounter context at check-in"
                ),
                new CdsServiceDescriptor(
                        "order-sign",
                        "Opes HealthCore Medication, DDI & Allergy Safety (CDS Hooks 2.0)",
                        "Evaluates drug-drug interactions, allergy cross-reactivity, renal dosing, and pediatric dosing ceilings.",
                        SERVICE_MEDICATION_SAFETY,
                        Map.of("patient", "Patient/{{context.patientId}}"),
                        "Requires draft medication orders in hook context"
                )
        ));
    }

    /** Dispatches a CDS Hooks 2.0 invocation by registered service ID. */
    public CdsHookResponse invokeService(String serviceId, CdsHookRequest request, UUID facilityId) {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("CDS serviceId is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("CDS Hooks request payload must not be null");
        }
        if (request.hookInstance() != null && request.hookInstance().isBlank()) {
            throw new IllegalArgumentException("CDS Hooks hookInstance must not be blank when provided");
        }
        return switch (serviceId.trim()) {
            case SERVICE_PATIENT_CARE_GAPS -> evaluatePatientViewHook(request, facilityId);
            case SERVICE_OBSERVATION_ALERTS -> evaluateObservationHook(request, facilityId);
            case SERVICE_ENCOUNTER_TRIAGE -> evaluateEncounterStartHook(request, facilityId);
            case SERVICE_MEDICATION_SAFETY -> evaluateOrderSafetyHook(request, facilityId);
            default -> throw new NoSuchElementException("Unknown CDS Hooks 2.0 service: " + serviceId);
        };
    }

    /** Records CDS Hooks 2.0 card acceptance or override feedback. */
    public CdsFeedbackReceipt recordFeedback(String serviceId, CdsFeedbackRequest request) {
        if (request == null || request.feedback() == null || request.feedback().isEmpty()) {
            throw new IllegalArgumentException("CDS Hooks feedback list must not be empty");
        }
        int count = 0;
        for (CdsFeedbackItem item : request.feedback()) {
            if (item == null || isBlank(item.card()) || isBlank(item.outcome())) {
                throw new IllegalArgumentException("Each CDS feedback item requires card UUID and outcome");
            }
            String normalizedOutcome = item.outcome().trim().toLowerCase(Locale.ROOT);
            if (!"accepted".equals(normalizedOutcome) && !"overridden".equals(normalizedOutcome)) {
                throw new IllegalArgumentException("CDS feedback outcome must be 'accepted' or 'overridden'");
            }
            feedbackHistory.add(new CdsFeedbackItem(
                    item.card(),
                    normalizedOutcome,
                    item.acceptedSuggestions(),
                    item.overrideReason(),
                    item.outcomeTimestamp() != null ? item.outcomeTimestamp() : Instant.now()
            ));
            count++;
        }
        return new CdsFeedbackReceipt(serviceId, count, Instant.now());
    }

    /** Automatically triggered during Patient API interactions (create / read). */
    public CdsHookResponse triggerPatientInteraction(Patient fhirPatient, UUID facilityId) {
        if (fhirPatient == null) {
            return new CdsHookResponse(List.of(), List.of());
        }
        UUID patientId = extractUuidSuffix(fhirPatient.hasIdElement() ? fhirPatient.getIdElement().getIdPart() : null);
        List<Observation> recentObs = loadRecentObservationsForPatient(patientId, facilityId);
        CdsHookResponse response = evaluatePatientAndObservations(fhirPatient, recentObs, null);
        recordExecution(SERVICE_PATIENT_CARE_GAPS, "patient-view", patientId, facilityId, response);
        return response;
    }

    /** Automatically triggered during Observation API interactions (create / update / read). */
    public CdsHookResponse triggerObservationInteraction(Observation fhirObservation, UUID facilityId) {
        if (fhirObservation == null) {
            return new CdsHookResponse(List.of(), List.of());
        }
        UUID patientId = fhirObservation.hasSubject() && fhirObservation.getSubject().hasReference()
                ? extractUuidSuffix(fhirObservation.getSubject().getReference())
                : null;
        Patient patient = resolvePatient(patientId);
        CdsHookResponse response = evaluatePatientAndObservations(patient, List.of(fhirObservation), null);
        recordExecution(SERVICE_OBSERVATION_ALERTS, "observation-review", patientId, facilityId, response);
        return response;
    }

    /** Automatically triggered during Encounter API interactions (create / check-in). */
    public CdsHookResponse triggerEncounterInteraction(Encounter fhirEncounter, UUID facilityId) {
        if (fhirEncounter == null) {
            return new CdsHookResponse(List.of(), List.of());
        }
        UUID patientId = fhirEncounter.hasSubject() && fhirEncounter.getSubject().hasReference()
                ? extractUuidSuffix(fhirEncounter.getSubject().getReference())
                : null;
        Patient patient = resolvePatient(patientId);
        List<Observation> recentObs = loadRecentObservationsForPatient(patientId, facilityId);
        CdsHookResponse response = evaluatePatientAndObservations(patient, recentObs, fhirEncounter);
        recordExecution(SERVICE_ENCOUNTER_TRIAGE, "encounter-start", patientId, facilityId, response);
        return response;
    }

    private CdsHookResponse evaluatePatientViewHook(CdsHookRequest request, UUID facilityId) {
        UUID patientId = extractUuidFromContext(request.context(), "patientId");
        Patient patient = resolvePatientFromPrefetchOrRepo(request.prefetch(), patientId);
        List<Observation> observations = resolveObservationsFromPrefetchOrRepo(request.prefetch(), patientId, facilityId);
        CdsHookResponse response = evaluatePatientAndObservations(patient, observations, null);
        recordExecution(SERVICE_PATIENT_CARE_GAPS, "patient-view", patientId, facilityId, response);
        return response;
    }

    private CdsHookResponse evaluateObservationHook(CdsHookRequest request, UUID facilityId) {
        UUID patientId = extractUuidFromContext(request.context(), "patientId");
        Patient patient = resolvePatientFromPrefetchOrRepo(request.prefetch(), patientId);
        List<Observation> observations = new ArrayList<>(resolveObservationsFromPrefetchOrRepo(request.prefetch(), patientId, facilityId));
        observations.addAll(extractObservationsFromObject(request.context().get("observations")));
        observations.addAll(extractObservationsFromObject(request.context().get("observation")));
        CdsHookResponse response = evaluatePatientAndObservations(patient, observations, null);
        recordExecution(SERVICE_OBSERVATION_ALERTS, !isBlank(request.hook()) ? request.hook() : "patient-view", patientId, facilityId, response);
        return response;
    }

    private CdsHookResponse evaluateEncounterStartHook(CdsHookRequest request, UUID facilityId) {
        UUID patientId = extractUuidFromContext(request.context(), "patientId");
        Patient patient = resolvePatientFromPrefetchOrRepo(request.prefetch(), patientId);
        List<Observation> observations = resolveObservationsFromPrefetchOrRepo(request.prefetch(), patientId, facilityId);
        Encounter encounter = extractEncounterFromObject(request.prefetch().get("encounter"));
        if (encounter == null) {
            encounter = extractEncounterFromObject(request.context().get("encounter"));
        }
        CdsHookResponse response = evaluatePatientAndObservations(patient, observations, encounter);
        recordExecution(SERVICE_ENCOUNTER_TRIAGE, "encounter-start", patientId, facilityId, response);
        return response;
    }

    private CdsHookResponse evaluateOrderSafetyHook(CdsHookRequest request, UUID facilityId) {
        UUID patientId = extractUuidFromContext(request.context(), "patientId");
        List<CdsHookCard> cards = new ArrayList<>();
        if (legacyCdssEngine != null) {
            String proposedDrug = asString(request.context().get("proposedDrugCode"));
            BigDecimal weightKg = asBigDecimal(request.context().get("weightKg"));
            BigDecimal eGfr = asBigDecimal(request.context().get("eGfr"));
            BigDecimal doseMg = asBigDecimal(request.context().get("proposedSingleDoseMg"));
            boolean pregnant = Boolean.TRUE.equals(request.context().get("pregnant"));
            List<String> meds = asStringList(request.context().get("activeMedicationCodes"));
            List<String> allergies = asStringList(request.context().get("allergySubstanceCodes"));
            List<String> conditions = asStringList(request.context().get("conditionCodes"));
            if (proposedDrug != null) {
                var legacyCards = legacyCdssEngine.evaluateOrderSafety(new OrderSafetyRequest(
                        patientId, weightKg, eGfr, pregnant, meds, allergies, conditions, proposedDrug, doseMg));
                for (var lc : legacyCards) {
                    List<CdsSuggestion> suggs = lc.suggestions().stream()
                            .map(s -> new CdsSuggestion(s, UUID.randomUUID().toString(), true, List.of()))
                            .toList();
                    cards.add(new CdsHookCard(
                            lc.uuid(),
                            lc.summary(),
                            lc.detail(),
                            lc.indicator(),
                            defaultSource(lc.sourceLabel()),
                            suggs,
                            "at-most-one",
                            lc.overrideRequired() ? defaultOverrideReasons() : List.of(),
                            List.of()
                    ));
                }
            }
        }
        CdsHookResponse response = new CdsHookResponse(cards, List.of());
        recordExecution(SERVICE_MEDICATION_SAFETY, "order-sign", patientId, facilityId, response);
        return response;
    }

    private CdsHookResponse evaluatePatientAndObservations(
            Patient patient,
            List<Observation> observations,
            Encounter encounter) {
        List<CdsHookCard> cards = new ArrayList<>();
        List<CdsAction> systemActions = new ArrayList<>();
        String patientRef = patient != null && patient.hasIdElement() && !isBlank(patient.getIdElement().getIdPart())
                ? "Patient/" + patient.getIdElement().getIdPart()
                : "Patient/unknown";

        // 1. Patient demographic & preventive care evaluation
        if (patient != null && patient.hasBirthDate()) {
            LocalDate dob = patient.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            int ageYears = Period.between(dob, LocalDate.now()).getYears();
            if (ageYears >= 40) {
                cards.add(new CdsHookCard(
                        UUID.randomUUID().toString(),
                        "Preventive Cardiometabolic & Glycemic Screening Due",
                        "Patient is " + ageYears + " years old. Review annual HbA1c/fasting glucose, lipid profile, and blood pressure trend.",
                        "info",
                        defaultSource("Opes HealthCore Preventive CDSS"),
                        List.of(new CdsSuggestion(
                                "Order Fasting Glucose / HbA1c Screening (LOINC 2339-0)",
                                UUID.randomUUID().toString(),
                                true,
                                List.of(new CdsAction(
                                        "create",
                                        "Create FHIR ServiceRequest for fasting glucose screening",
                                        Map.of(
                                                "resourceType", "ServiceRequest",
                                                "status", "draft",
                                                "intent", "order",
                                                "code", Map.of("coding", List.of(Map.of(
                                                        "system", "http://loinc.org",
                                                        "code", "2339-0",
                                                        "display", "Glucose [Mass/volume] in Blood"))),
                                                "subject", Map.of("reference", patientRef)
                                        ),
                                        null
                                ))
                        )),
                        "at-most-one",
                        List.of(),
                        List.of()
                ));
            }
            if (ageYears < 5) {
                cards.add(new CdsHookCard(
                        UUID.randomUUID().toString(),
                        "Pediatric Growth & Immunization Verification",
                        "Patient is under 5 years (" + ageYears + "y). Verify MUAC/weight-for-age z-score and EPI immunization schedule.",
                        "info",
                        defaultSource("Opes HealthCore Pediatric CDSS"),
                        List.of(new CdsSuggestion(
                                "Record Anthropometric Weight & Immunization Check",
                                UUID.randomUUID().toString(),
                                true,
                                List.of()
                        )),
                        "at-most-one",
                        List.of(),
                        List.of()
                ));
            }
        }

        // 2. Encounter triage & reasonCode evaluation
        if (encounter != null) {
            boolean isEmergency = encounter.hasClass_() && "EMER".equalsIgnoreCase(encounter.getClass_().getCode());
            List<String> icdCodes = encounter.getReasonCode().stream()
                    .flatMap(cc -> cc.getCoding().stream())
                    .map(Coding::getCode)
                    .filter(Objects::nonNull)
                    .toList();

            if (isEmergency) {
                cards.add(new CdsHookCard(
                        UUID.randomUUID().toString(),
                        "Emergency Encounter Fast-Track Triage & NEWS2 Required",
                        "Emergency encounter registered. Complete ESI triage and baseline NEWS2 vital sign panel within 10 minutes.",
                        "warning",
                        defaultSource("Opes HealthCore ED Triage CDSS"),
                        List.of(new CdsSuggestion(
                                "Initiate Vital Signs & Sepsis Screening Bundle",
                                UUID.randomUUID().toString(),
                                true,
                                List.of()
                        )),
                        "at-most-one",
                        defaultOverrideReasons(),
                        List.of()
                ));
            }
            if (icdCodes.stream().anyMatch(c -> c.startsWith("I10"))) {
                cards.add(new CdsHookCard(
                        UUID.randomUUID().toString(),
                        "Hypertension Chronic Care Renal Panel Due",
                        "Encounter reason ICD-10 I10 detected. Verify blood pressure target (<130/80 mmHg) and annual serum creatinine/eGFR.",
                        "info",
                        defaultSource("Opes HealthCore Chronic Care CDSS"),
                        List.of(new CdsSuggestion(
                                "Order Renal Function Panel (LOINC 24362-6)",
                                UUID.randomUUID().toString(),
                                true,
                                List.of()
                        )),
                        "at-most-one",
                        List.of(),
                        List.of()
                ));
            }
        }

        // 3. Observation vital signs & laboratory panic threshold evaluation
        int news2 = 0;
        for (Observation obs : observations) {
            if (obs == null) continue;
            if (obs.hasStatus() && "entered-in-error".equalsIgnoreCase(obs.getStatus().toCode())) {
                continue;
            }
            String loincCode = extractPrimaryCode(obs);
            BigDecimal scalarVal = obs.hasValueQuantity() && obs.getValueQuantity().hasValue()
                    ? obs.getValueQuantity().getValue()
                    : null;

            // Check multi-component BP panel (85354-9)
            BigDecimal systolicBp = null;
            BigDecimal diastolicBp = null;
            for (Observation.ObservationComponentComponent comp : obs.getComponent()) {
                String compCode = comp.hasCode() && !comp.getCode().getCoding().isEmpty()
                        ? comp.getCode().getCodingFirstRep().getCode()
                        : null;
                BigDecimal compVal = comp.hasValueQuantity() && comp.getValueQuantity().hasValue()
                        ? comp.getValueQuantity().getValue()
                        : null;
                if ("8480-6".equals(compCode) && compVal != null) systolicBp = compVal;
                if ("8462-4".equals(compCode) && compVal != null) diastolicBp = compVal;
            }
            if ("8480-6".equals(loincCode) && scalarVal != null) systolicBp = scalarVal;
            if ("8462-4".equals(loincCode) && scalarVal != null) diastolicBp = scalarVal;

            if (systolicBp != null && systolicBp.compareTo(new BigDecimal("90")) <= 0) {
                news2 += 3;
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "Severe Hypotension Alert (SBP " + systolicBp + " mmHg)",
                        "Systolic blood pressure <= 90 mmHg indicates hemodynamic instability/shock risk. Immediate bedside assessment required.",
                        "critical",
                        "Escalate for Immediate Fluid & Shock Protocol"));
            } else if ((systolicBp != null && systolicBp.compareTo(new BigDecimal("180")) >= 0)
                    || (diastolicBp != null && diastolicBp.compareTo(new BigDecimal("120")) >= 0)) {
                CdsHookCard card = buildCriticalClinicalAlert(
                        patientRef,
                        "Hypertensive Crisis Alert (" + systolicBp + "/" + diastolicBp + " mmHg)",
                        "Blood pressure >= 180/120 mmHg. Assess immediately for acute target-organ damage (neurologic, cardiac, renal).",
                        "critical",
                        "Initiate Hypertensive Urgency/Emergency Workup");
                cards.add(card);
                systemActions.add(new CdsAction(
                        "create",
                        "Auto-flag high-risk blood pressure escalation for attending physician",
                        Map.of(
                                "resourceType", "Flag",
                                "status", "active",
                                "code", Map.of("text", "Hypertensive Crisis Alert"),
                                "subject", Map.of("reference", patientRef)
                        ),
                        null
                ));
            } else if ((systolicBp != null && systolicBp.compareTo(new BigDecimal("140")) >= 0)
                    || (diastolicBp != null && diastolicBp.compareTo(new BigDecimal("90")) >= 0)) {
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "Elevated Blood Pressure (" + systolicBp + "/" + diastolicBp + " mmHg)",
                        "Stage 2 hypertension reading detected. Repeat cuff measurement after 5 minutes rest and review antihypertensive adherence.",
                        "warning",
                        "Schedule Repeat BP Check & Hypertension Review"));
            }

            // SpO2 (2708-6)
            if ("2708-6".equals(loincCode) && scalarVal != null && scalarVal.compareTo(new BigDecimal("91")) <= 0) {
                news2 += 3;
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "Severe Hypoxemia Alert (SpO2 " + scalarVal + "%)",
                        "Oxygen saturation <= 91%. Initiate supplemental oxygen and arterial blood gas assessment immediately.",
                        "critical",
                        "Initiate Supplemental Oxygen & Respiratory Escalation"));
            }

            // Heart rate (8867-4)
            if ("8867-4".equals(loincCode) && scalarVal != null
                    && (scalarVal.compareTo(new BigDecimal("131")) >= 0 || scalarVal.compareTo(new BigDecimal("40")) <= 0)) {
                news2 += 3;
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "Critical Heart Rate Alert (" + scalarVal + " bpm)",
                        "Heart rate outside critical stability thresholds (<=40 or >=131 bpm). Obtain 12-lead ECG immediately.",
                        "critical",
                        "Order STAT 12-Lead ECG (LOINC 11524-6)"));
            }

            // Body Temperature (8310-5)
            if ("8310-5".equals(loincCode) && scalarVal != null && scalarVal.compareTo(new BigDecimal("39.1")) >= 0) {
                news2 += 2;
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "High Fever / Sepsis Screen Alert (" + scalarVal + " °C)",
                        "Temperature >= 39.1 °C. Evaluate for infectious etiology, malaria/blood cultures, and sepsis bundle criteria.",
                        "warning",
                        "Initiate Fever / Malaria & Sepsis Workup"));
            }

            // Serum Potassium panic (2823-3 / 6298-4)
            if (("2823-3".equals(loincCode) || "6298-4".equals(loincCode)) && scalarVal != null
                    && (scalarVal.compareTo(new BigDecimal("6.2")) >= 0 || scalarVal.compareTo(new BigDecimal("2.5")) <= 0)) {
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "PANIC LAB: Critical Serum Potassium (" + scalarVal + " mmol/L)",
                        "Life-threatening dyskalemia threshold reached. Continuous cardiac telemetry, 12-lead ECG, and electrolyte stabilization required.",
                        "critical",
                        "Log Critical Read-Back & Order STAT ECG"));
            }

            // Blood Glucose panic (2339-0 / 2345-7)
            if (("2339-0".equals(loincCode) || "2345-7".equals(loincCode)) && scalarVal != null
                    && scalarVal.compareTo(new BigDecimal("54")) <= 0) {
                cards.add(buildCriticalClinicalAlert(
                        patientRef,
                        "PANIC LAB: Severe Hypoglycemia (" + scalarVal + " mg/dL)",
                        "Blood glucose <= 54 mg/dL. Administer immediate oral glucose or IV dextrose 50% per clinical protocol.",
                        "critical",
                        "Administer Emergency Hypoglycemia Rescue Protocol"));
            }
        }

        if (news2 >= 5) {
            cards.add(buildCriticalClinicalAlert(
                    patientRef,
                    "NEWS2 High Clinical Deterioration Risk (Score=" + news2 + ")",
                    "Aggregate NEWS2 physiological score >= 5 across vital signs. Immediate clinician / rapid-response bedside review required.",
                    news2 >= 7 ? "critical" : "warning",
                    "Escalate to Attending Physician / Rapid Response Team"));
        }

        return new CdsHookResponse(cards, systemActions);
    }

    private CdsHookCard buildCriticalClinicalAlert(
            String patientRef,
            String summary,
            String detail,
            String indicator,
            String suggestionLabel) {
        return new CdsHookCard(
                UUID.randomUUID().toString(),
                summary,
                detail,
                indicator,
                defaultSource("Opes HealthCore Clinical Alert CDSS"),
                List.of(new CdsSuggestion(
                        suggestionLabel,
                        UUID.randomUUID().toString(),
                        true,
                        List.of(new CdsAction(
                                "create",
                                suggestionLabel,
                                Map.of(
                                        "resourceType", "Communication",
                                        "status", "preparation",
                                        "subject", Map.of("reference", patientRef),
                                        "payload", List.of(Map.of("contentString", summary))
                                ),
                                null
                        ))
                )),
                "at-most-one",
                "critical".equals(indicator) ? defaultOverrideReasons() : List.of(),
                List.of()
        );
    }

    private List<Observation> loadRecentObservationsForPatient(UUID patientId, UUID facilityId) {
        if (observationRepository == null || patientId == null) {
            return List.of();
        }
        List<ObservationEntity> entities = facilityId != null
                ? observationRepository.findByFacilityIdAndPatientIdOrderByEffectiveAtDesc(facilityId, patientId)
                : observationRepository.findAll().stream()
                        .filter(o -> Objects.equals(patientId, o.getPatientId()))
                        .toList();
        return entities.stream()
                .limit(20)
                .map(ObservationService::toFhirObservation)
                .toList();
    }

    private Patient resolvePatient(UUID patientId) {
        if (patientService == null || patientId == null) {
            return null;
        }
        return patientService.findPatientById(patientId)
                .map(p -> {
                    try {
                        return patientService.getFhirPatientById(p.getId());
                    } catch (Exception ignored) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private Patient resolvePatientFromPrefetchOrRepo(Map<String, Object> prefetch, UUID patientId) {
        Patient fromPrefetch = extractPatientFromObject(prefetch != null ? prefetch.get("patient") : null);
        return fromPrefetch != null ? fromPrefetch : resolvePatient(patientId);
    }

    private List<Observation> resolveObservationsFromPrefetchOrRepo(
            Map<String, Object> prefetch,
            UUID patientId,
            UUID facilityId) {
        List<Observation> fromPrefetch = prefetch != null
                ? extractObservationsFromObject(prefetch.get("observations"))
                : List.of();
        if (!fromPrefetch.isEmpty()) {
            return fromPrefetch;
        }
        return loadRecentObservationsForPatient(patientId, facilityId);
    }

    private Patient extractPatientFromObject(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Patient p) return p;
        String json = toJsonString(obj);
        if (json == null) return null;
        try {
            return FHIR_R4.newJsonParser().parseResource(Patient.class, json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Encounter extractEncounterFromObject(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Encounter e) return e;
        String json = toJsonString(obj);
        if (json == null) return null;
        try {
            return FHIR_R4.newJsonParser().parseResource(Encounter.class, json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Observation> extractObservationsFromObject(Object obj) {
        if (obj == null) return List.of();
        if (obj instanceof Observation o) return List.of(o);
        if (obj instanceof Bundle b) {
            return b.getEntry().stream()
                    .map(Bundle.BundleEntryComponent::getResource)
                    .filter(Observation.class::isInstance)
                    .map(Observation.class::cast)
                    .toList();
        }
        if (obj instanceof Collection<?> coll) {
            List<Observation> out = new ArrayList<>();
            for (Object item : coll) {
                out.addAll(extractObservationsFromObject(item));
            }
            return out;
        }
        String json = toJsonString(obj);
        if (json == null) return List.of();
        try {
            IParser parser = FHIR_R4.newJsonParser();
            var parsed = parser.parseResource(json);
            if (parsed instanceof Observation o) return List.of(o);
            if (parsed instanceof Bundle b) {
                return b.getEntry().stream()
                        .map(Bundle.BundleEntryComponent::getResource)
                        .filter(Observation.class::isInstance)
                        .map(Observation.class::cast)
                        .toList();
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return List.of();
    }

    private static String toJsonString(Object obj) {
        if (obj instanceof String s) {
            return s;
        }
        if (obj instanceof Map<?, ?> map) {
            return mapToJson(map);
        }
        return null;
    }

    private static String mapToJson(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escapeJson(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(escapeJson(String.valueOf(e.getKey()))).append("\":").append(mapToJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Collection<?> col) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : col) {
                if (!first) sb.append(',');
                first = false;
                sb.append(mapToJson(item));
            }
            return sb.append(']').toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void recordExecution(
            String serviceId,
            String hook,
            UUID patientId,
            UUID facilityId,
            CdsHookResponse response) {
        CdsHookExecutionEvent evt = new CdsHookExecutionEvent(
                Instant.now(),
                serviceId,
                hook,
                patientId,
                facilityId,
                response.cards().size(),
                response.highestIndicator()
        );
        executionHistory.add(evt);
        if (executionHistory.size() > 500) {
            executionHistory.remove(0);
        }
        for (Consumer<CdsHookExecutionEvent> listener : listeners) {
            try {
                listener.accept(evt);
            } catch (Exception ignored) {
                // Ignore listener diagnostics
            }
        }
    }

    private static String extractPrimaryCode(Observation obs) {
        if (obs == null || !obs.hasCode() || obs.getCode().getCoding().isEmpty()) {
            return null;
        }
        return obs.getCode().getCodingFirstRep().getCode();
    }

    private static CdsSource defaultSource(String label) {
        return new CdsSource(
                label,
                "https://www.opeshealthsystems.com/cdss",
                null,
                new CdsCoding("opes-cdss", "https://www.opeshealthsystems.com/fhir/CodeSystem/cdss", label)
        );
    }

    private static List<CdsOverrideReason> defaultOverrideReasons() {
        return List.of(
                new CdsOverrideReason("patient-declined", "https://www.opeshealthsystems.com/fhir/CodeSystem/cds-override", "Patient declined intervention"),
                new CdsOverrideReason("clinical-judgment", "https://www.opeshealthsystems.com/fhir/CodeSystem/cds-override", "Attending physician clinical judgment"),
                new CdsOverrideReason("already-managed", "https://www.opeshealthsystems.com/fhir/CodeSystem/cds-override", "Condition already monitored")
        );
    }

    private static UUID extractUuidFromContext(Map<String, Object> context, String key) {
        if (context == null) return null;
        Object raw = context.get(key);
        return raw != null ? extractUuidSuffix(raw.toString()) : null;
    }

    private static UUID extractUuidSuffix(String ref) {
        if (isBlank(ref)) return null;
        Matcher m = UUID_TAIL_PATTERN.matcher(ref.trim());
        if (!m.find()) return null;
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(o.toString().trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<String> asStringList(Object o) {
        if (o instanceof Collection<?> coll) {
            return coll.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
