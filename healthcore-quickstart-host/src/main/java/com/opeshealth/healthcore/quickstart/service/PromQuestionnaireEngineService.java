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

import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Patient-Reported Outcome Measures (PROMs) &amp; Adaptive HL7 FHIR R4 {@code Questionnaire} /
 * {@code QuestionnaireResponse} Clinical Assessment Service.
 *
 * <p>Provides standardized clinical psychometric instruments (**PHQ-9** Depression 0–27 with
 * Item 9 suicide/self-harm risk detection, **GAD-7** Anxiety 0–21, **AUDIT-C** Alcohol Use
 * Disorder screening 0–12, and **WHO-5** Well-Being Index 0–100%), persists derived LOINC
 * {@code Observation} resources via {@link ObservationService}, and dispatches urgent alerts via
 * {@link FhirSubscriptionTopicService}.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PromQuestionnaireEngineService {

    public enum PromInstrumentCode {
        PHQ_9("44249-1", "PHQ-9 Quick Depression Assessment Panel", 9, 27),
        GAD_7("69737-5", "GAD-7 Generalized Anxiety Disorder Assessment", 7, 21),
        AUDIT_C("75626-2", "AUDIT-C Alcohol Use Disorders Identification Test", 3, 12),
        WHO_5("71954-2", "WHO-5 Well-Being Index Percentage Score", 5, 100);

        private final String loincCode;
        private final String displayTitle;
        private final int itemCount;
        private final int maxScore;

        PromInstrumentCode(String loincCode, String displayTitle, int itemCount, int maxScore) {
            this.loincCode = loincCode;
            this.displayTitle = displayTitle;
            this.itemCount = itemCount;
            this.maxScore = maxScore;
        }

        public String loincCode() {
            return loincCode;
        }

        public String displayTitle() {
            return displayTitle;
        }

        public int itemCount() {
            return itemCount;
        }

        public int maxScore() {
            return maxScore;
        }
    }

    public record QuestionnaireDefinitionSummary(
            PromInstrumentCode instrument,
            String canonicalUrl,
            String loincCode,
            String title,
            int itemCount,
            int maxPossibleScore,
            List<String> itemPrompts
    ) {}

    public record PromSubmissionRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            PromInstrumentCode instrument,
            Map<String, Integer> itemScoresByLinkId
    ) {}

    public record PromAssessmentResult(
            UUID responseId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            PromInstrumentCode instrument,
            String loincCode,
            int rawTotalScore,
            int normalizedScore,
            String severityClassification,
            boolean suicideOrSelfHarmRiskFlagged,
            String clinicalFollowUpRecommendation,
            UUID derivedObservationId,
            String fhirQuestionnaireResponseJson,
            Instant submittedAt
    ) {}

    private final ObservationService observationService;
    private final FhirSubscriptionTopicService subscriptionTopicService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final Map<PromInstrumentCode, QuestionnaireDefinitionSummary> definitions = new LinkedHashMap<>();
    private final Map<UUID, List<PromAssessmentResult>> assessmentsByPatient = new ConcurrentHashMap<>();

    @Autowired
    public PromQuestionnaireEngineService(
            @Autowired(required = false) ObservationService observationService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.observationService = observationService;
        this.subscriptionTopicService = subscriptionTopicService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        registerStandardQuestionnaires();
    }

    public PromQuestionnaireEngineService(ObservationService observationService) {
        this(observationService, null, null);
    }

    private void registerStandardQuestionnaires() {
        definitions.put(PromInstrumentCode.PHQ_9, new QuestionnaireDefinitionSummary(
                PromInstrumentCode.PHQ_9,
                "https://opeshealthsystems.com/fhir/Questionnaire/phq-9",
                PromInstrumentCode.PHQ_9.loincCode(),
                PromInstrumentCode.PHQ_9.displayTitle(),
                9,
                27,
                List.of(
                        "q1: Little interest or pleasure in doing things (0-3)",
                        "q2: Feeling down, depressed, or hopeless (0-3)",
                        "q3: Trouble falling or staying asleep, or sleeping too much (0-3)",
                        "q4: Feeling tired or having little energy (0-3)",
                        "q5: Poor appetite or overeating (0-3)",
                        "q6: Feeling bad about yourself or that you are a failure (0-3)",
                        "q7: Trouble concentrating on things (0-3)",
                        "q8: Moving or speaking slowly or being fidgety/restless (0-3)",
                        "q9: Thoughts that you would be better off dead, or of hurting yourself (0-3)"
                )
        ));
        definitions.put(PromInstrumentCode.GAD_7, new QuestionnaireDefinitionSummary(
                PromInstrumentCode.GAD_7,
                "https://opeshealthsystems.com/fhir/Questionnaire/gad-7",
                PromInstrumentCode.GAD_7.loincCode(),
                PromInstrumentCode.GAD_7.displayTitle(),
                7,
                21,
                List.of(
                        "q1: Feeling nervous, anxious, or on edge (0-3)",
                        "q2: Not being able to stop or control worrying (0-3)",
                        "q3: Worrying too much about different things (0-3)",
                        "q4: Trouble relaxing (0-3)",
                        "q5: Being so restless that it is hard to sit still (0-3)",
                        "q6: Becoming easily annoyed or irritable (0-3)",
                        "q7: Feeling afraid as if something awful might happen (0-3)"
                )
        ));
        definitions.put(PromInstrumentCode.AUDIT_C, new QuestionnaireDefinitionSummary(
                PromInstrumentCode.AUDIT_C,
                "https://opeshealthsystems.com/fhir/Questionnaire/audit-c",
                PromInstrumentCode.AUDIT_C.loincCode(),
                PromInstrumentCode.AUDIT_C.displayTitle(),
                3,
                12,
                List.of(
                        "q1: How often do you have a drink containing alcohol? (0-4)",
                        "q2: How many standard drinks containing alcohol do you have on a typical day? (0-4)",
                        "q3: How often do you have six or more drinks on one occasion? (0-4)"
                )
        ));
        definitions.put(PromInstrumentCode.WHO_5, new QuestionnaireDefinitionSummary(
                PromInstrumentCode.WHO_5,
                "https://opeshealthsystems.com/fhir/Questionnaire/who-5",
                PromInstrumentCode.WHO_5.loincCode(),
                PromInstrumentCode.WHO_5.displayTitle(),
                5,
                100,
                List.of(
                        "q1: I have felt cheerful and in good spirits (0-5)",
                        "q2: I have felt calm and relaxed (0-5)",
                        "q3: I have felt active and vigorous (0-5)",
                        "q4: I woke up feeling fresh and rested (0-5)",
                        "q5: My daily life has been filled with things that interest me (0-5)"
                )
        ));
    }

    public List<QuestionnaireDefinitionSummary> listAvailableQuestionnaires() {
        return List.copyOf(definitions.values());
    }

    /**
     * Scores a submitted HL7 FHIR R4 {@code QuestionnaireResponse}, persists a LOINC-coded
     * {@code Observation}, and triggers immediate suicide/self-harm alerts if PHQ-9 Item 9 &gt; 0.
     */
    @ClinicalAudit(action = "PROM_QUESTIONNAIRE_SUBMIT", resourceType = "QuestionnaireResponse")
    public PromAssessmentResult submitAndScoreQuestionnaire(PromSubmissionRequest request) {
        if (request == null || request.patientId() == null || request.instrument() == null) {
            throw new IllegalArgumentException("PromSubmissionRequest with patientId and instrument is required");
        }
        if (request.itemScoresByLinkId() == null || request.itemScoresByLinkId().isEmpty()) {
            throw new IllegalArgumentException("itemScoresByLinkId answers must not be empty");
        }

        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        PromInstrumentCode instrument = request.instrument();
        int maxPerItem = switch (instrument) {
            case PHQ_9, GAD_7 -> 3;
            case AUDIT_C -> 4;
            case WHO_5 -> 5;
        };

        int rawSum = 0;
        for (Map.Entry<String, Integer> entry : request.itemScoresByLinkId().entrySet()) {
            Integer val = entry.getValue();
            if (val == null || val < 0 || val > maxPerItem) {
                throw new IllegalArgumentException(
                        "Invalid score " + val + " for item '" + entry.getKey()
                                + "' in " + instrument + " (allowed 0.." + maxPerItem + ")");
            }
            rawSum += val;
        }

        int normalized = instrument == PromInstrumentCode.WHO_5 ? (rawSum * 4) : rawSum;
        int item9Score = request.itemScoresByLinkId().getOrDefault("q9",
                request.itemScoresByLinkId().getOrDefault("item9", 0));
        boolean suicideRisk = instrument == PromInstrumentCode.PHQ_9 && item9Score > 0;

        String severity;
        String recommendation;

        switch (instrument) {
            case PHQ_9 -> {
                if (normalized >= 20) {
                    severity = "SEVERE_DEPRESSION";
                    recommendation = "Immediate psychiatric evaluation, pharmacotherapy + psychotherapy initiation, and safety planning.";
                } else if (normalized >= 15) {
                    severity = "MODERATELY_SEVERE_DEPRESSION";
                    recommendation = "Active treatment with antidepressant pharmacotherapy and/or evidence-based psychotherapy.";
                } else if (normalized >= 10) {
                    severity = "MODERATE_DEPRESSION";
                    recommendation = "Develop treatment plan considering counseling, follow-up, and pharmacotherapy.";
                } else if (normalized >= 5) {
                    severity = "MILD_DEPRESSION";
                    recommendation = "Watchful waiting; repeat PHQ-9 at follow-up visit.";
                } else {
                    severity = "MINIMAL_OR_NONE";
                    recommendation = "No active depression intervention required.";
                }
                if (suicideRisk) {
                    recommendation = "URGENT SUICIDE / SELF-HARM RISK (PHQ-9 Item 9 = " + item9Score
                            + "): Conduct immediate Columbia-Suicide Severity Rating Scale (C-SSRS) safety assessment. " + recommendation;
                }
            }
            case GAD_7 -> {
                if (normalized >= 15) {
                    severity = "SEVERE_ANXIETY";
                    recommendation = "Active CBT and/or SSRI/SNRI pharmacotherapy with mental health specialist referral.";
                } else if (normalized >= 10) {
                    severity = "MODERATE_ANXIETY";
                    recommendation = "Clinical evaluation for Generalized Anxiety Disorder and structured intervention.";
                } else if (normalized >= 5) {
                    severity = "MILD_ANXIETY";
                    recommendation = "Monitor symptoms and re-evaluate in 4 weeks.";
                } else {
                    severity = "MINIMAL_ANXIETY";
                    recommendation = "Routine care.";
                }
            }
            case AUDIT_C -> {
                if (normalized >= 8) {
                    severity = "HIGH_RISK_ALCOHOL_DEPENDENCE";
                    recommendation = "Full AUDIT assessment, brief intervention, and addiction medicine referral.";
                } else if (normalized >= 4) {
                    severity = "HAZARDOUS_DRINKING_SCREEN_POSITIVE";
                    recommendation = "Provide brief structured alcohol reduction counseling.";
                } else {
                    severity = "LOW_RISK";
                    recommendation = "Reinforce healthy lifestyle.";
                }
            }
            case WHO_5 -> {
                if (normalized <= 28) {
                    severity = "POOR_WELLBEING_DEPRESSION_SCREEN_INDICATED";
                    recommendation = "WHO-5 score <= 28 indicates likely depression; administer PHQ-9 immediately.";
                } else if (normalized <= 50) {
                    severity = "REDUCED_PSYCHOLOGICAL_WELLBEING";
                    recommendation = "Screen for clinical stressors and follow up with PHQ-9 / GAD-7.";
                } else {
                    severity = "OPTIMAL_WELLBEING";
                    recommendation = "Continue supportive preventive care.";
                }
            }
            default -> {
                severity = "SCORED";
                recommendation = "Clinical review recommended.";
            }
        }

        UUID responseId = UUID.randomUUID();
        UUID derivedObsId = null;

        if (observationService != null) {
            ObservationEntity obs = new ObservationEntity();
            obs.setFacilityId(facilityId);
            obs.setStatus("final");
            obs.setCategoryCode("survey");
            obs.setCategoryDisplay("Survey / PROMs");
            obs.setCodeSystem("http://loinc.org");
            obs.setCode(instrument.loincCode());
            obs.setCodeDisplay(instrument.displayTitle());
            obs.setSubjectReference("Patient/" + request.patientId());
            obs.setPatientId(request.patientId());
            if (request.encounterId() != null) {
                obs.setEncounterReference("Encounter/" + request.encounterId());
                obs.setEncounterId(request.encounterId());
            }
            obs.setValueType("Quantity");
            obs.setValueQuantity(BigDecimal.valueOf(normalized));
            obs.setValueUnit("{score}");
            obs.setValueCode("http://unitsofmeasure.org|{score}");
            obs.setEffectiveAt(Instant.now());
            derivedObsId = observationService.createObservation(obs).getId();
        }

        String fhirJson = """
                {
                  "resourceType": "QuestionnaireResponse",
                  "id": "%s",
                  "questionnaire": "https://opeshealthsystems.com/fhir/Questionnaire/%s",
                  "status": "completed",
                  "subject": {"reference": "Patient/%s"},
                  "authored": "%s",
                  "totalScore": %d,
                  "severityClassification": "%s"
                }
                """.formatted(
                responseId,
                instrument.name().toLowerCase(),
                request.patientId(),
                Instant.now(),
                normalized,
                severity
        ).trim();

        PromAssessmentResult result = new PromAssessmentResult(
                responseId,
                facilityId,
                request.patientId(),
                request.encounterId(),
                instrument,
                instrument.loincCode(),
                rawSum,
                normalized,
                severity,
                suicideRisk,
                recommendation,
                derivedObsId,
                fhirJson,
                Instant.now()
        );

        assessmentsByPatient
                .computeIfAbsent(request.patientId(), k -> new ArrayList<>())
                .add(result);

        if ((suicideRisk || (instrument == PromInstrumentCode.PHQ_9 && normalized >= 20))
                && subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    facilityId,
                    FhirSubscriptionTopicService.TOPIC_PROM_SUICIDE_RISK_ALERT,
                    "QuestionnaireResponse",
                    responseId.toString(),
                    request.patientId(),
                    "CRITICAL",
                    "PROMs High-Risk Alert (" + instrument + " score=" + normalized
                            + ", suicideRisk=" + suicideRisk + ") for Patient/" + request.patientId(),
                    Map.of(
                            "responseId", responseId.toString(),
                            "instrument", instrument.name(),
                            "score", normalized,
                            "suicideRisk", suicideRisk
                    )
            ));
        }

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "PROM_ASSESSMENT_ENGINE",
                    "PATIENT_OR_CLINICIAN",
                    "PROM_SCORED_" + instrument.name(),
                    "QuestionnaireResponse",
                    responseId.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/proms/submit",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Scored " + instrument + " total=" + normalized + " severity=" + severity
            ));
        }

        return result;
    }

    public List<PromAssessmentResult> listPatientPromAssessments(UUID patientId) {
        List<PromAssessmentResult> list = assessmentsByPatient.get(patientId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(PromAssessmentResult::submittedAt).reversed())
                .toList();
    }
}
