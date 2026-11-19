/*
 * =============================================================================
 * Opes HealthCore SDK — ClinicalPredictiveEngine (Readmission & Disease Progression)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.predictive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.DecisionEvaluationResult;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.DecisionQuestion;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that forecasts
 * hospital readmission risks (30-day / 90-day) and chronic disease progression trajectories
 * from longitudinal HL7 FHIR R4 clinical history ({@code Patient}, {@code Encounter},
 * {@code Condition}, {@code Observation}, {@code MedicationRequest}, {@code MedicationStatement},
 * {@code Procedure}, {@code CarePlan}, or full FHIR {@code Bundle}s).
 *
 * <p><strong>Clinical Privacy &amp; Governance Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Mandatory HIPAA Safe-Harbor Redaction:</strong> Harvests all direct patient
 *       identifiers from FHIR {@code Patient} / {@code RelatedPerson} resources, strips PII blocks
 *       (retaining only gender and birth year), and scrubs all 18 Safe-Harbor identifier classes
 *       before invoking Gemini models.</li>
 *   <li><strong>Deterministic Longitudinal FHIR Feature Extraction:</strong> Computes a validated
 *       LACE-index readmission baseline, serial biomarker progression slopes (eGFR, HbA1c, Systolic
 *       BP, BNP/NT-proBNP, Serum Creatinine, SpO2), polypharmacy burden, and acute utilization counts
 *       directly from historical FHIR R4 records.</li>
 *   <li><strong>Human-in-the-Loop Attestation &amp; Strict Error Propagation:</strong> Quarantines
 *       every forecast in {@link ClinicalAiGovernanceService} under {@code DRAFT_PENDING_ATTESTATION}
 *       and propagates upstream Gemini API errors cleanly without fabricated predictions.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalPredictiveEngine {

    public enum ForecastHorizon {
        DAYS_30(30),
        DAYS_90(90),
        MONTHS_6(180);

        private final int days;

        ForecastHorizon(int days) {
            this.days = days;
        }

        public int days() {
            return days;
        }
    }

    public enum RiskTier {
        LOW,
        MODERATE,
        HIGH,
        VERY_HIGH
    }

    public enum TrendDirection {
        IMPROVING,
        STABLE,
        WORSENING,
        INSUFFICIENT_DATA
    }

    public record PredictiveForecastRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            ForecastHorizon horizon,
            List<String> historicalFhirResourcesJson,
            List<String> socialDeterminantsOfHealth,
            boolean includeCalibratedRiskGate
    ) {}

    public record BiomarkerTrend(
            String biomarkerName,
            String unit,
            int observationCount,
            double earliestValue,
            double latestValue,
            double absoluteDelta,
            TrendDirection direction,
            String clinicalInterpretation
    ) {}

    public record LongitudinalFhirSignals(
            int totalEncounters,
            int inpatientOrEmergencyEncounters,
            int maxInpatientLengthOfStayDays,
            int activeChronicConditionCount,
            int activeMedicationCount,
            boolean polypharmacyFlag,
            int baselineLaceScore,
            RiskTier baselineReadmissionTier,
            List<BiomarkerTrend> biomarkerTrends
    ) {}

    public record RedactedLongitudinalContext(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> resourceCountsByType,
            LongitudinalFhirSignals longitudinalSignals,
            int totalResourcesProcessed
    ) {}

    public record ClinicalPredictiveForecastResult(
            UUID draftId,
            String attestationStatus,
            ForecastHorizon horizon,
            String forecastModelId,
            String calibratedDecisionModelId,
            Map<String, Integer> processedResourceCountsByType,
            LongitudinalFhirSignals baselineLongitudinalSignals,
            JsonNode aiPredictiveForecast,
            Map<String, JsonNode> calibratedReadmissionGate,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long totalLatencyMs
    ) {}

    private record TimedObservation(String timestampKey, double value, String unit) {}

    private static final Set<String> CHRONIC_DISEASE_TOKENS = Set.of(
            "diabetes", "hypertension", "heart failure", "chf", "ckd", "chronic kidney",
            "copd", "asthma", "coronary", "atrial fibrillation", "stroke", "cirrhosis",
            "cancer", "malignancy", "hiv", "sickle cell", "epilepsy", "renal failure"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public ClinicalPredictiveEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Forecasts patient hospital readmission risk and chronic disease progression trends from
     * longitudinal HL7 FHIR R4 history with mandatory HIPAA Safe-Harbor PHI redaction, local
     * LACE + biomarker trend extraction, structured Gemini synthesis ({@code gemini-4-hydrogen}),
     * optional calibrated risk gating ({@code gemini-decision-1}), and attestation quarantine.
     */
    public ClinicalPredictiveForecastResult forecastReadmissionAndDiseaseProgression(
            PredictiveForecastRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PredictiveForecastRequest is required.");
        }
        governanceService.assertTenantAiAllowed(
                request.facilityId(), AiCapabilityType.PREDICTIVE_RISK_AND_PROGRESSION);

        ForecastHorizon horizon = request.horizon() != null ? request.horizon() : ForecastHorizon.DAYS_30;
        RedactedLongitudinalContext context = prepareRedactedLongitudinalContext(
                request.historicalFhirResourcesJson(),
                request.socialDeterminantsOfHealth()
        );
        if (context.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one historical HL7 FHIR R4 resource or Bundle JSON payload is required.");
        }

        String systemInstruction = """
                You are a clinical predictive analytics and population-health risk stratification engine
                embedded inside an HL7 FHIR R4 EMR/EHR platform.
                Using ONLY the de-identified longitudinal FHIR clinical history, baseline LACE score, and
                serial biomarker trajectories provided:
                1. Forecast unplanned hospital readmission risk over the requested horizon (0-100 score,
                   risk tier, primary clinical/utilization drivers, modifiable factors, and evidence-based
                   transitional care interventions such as TCM follow-up, med reconciliation, or RPM).
                2. Forecast chronic disease progression trajectories (e.g., CKD/eGFR decline, glycemic
                   drift, heart failure decompensation, hypertension escalation, pulmonary decline) with
                   explicit grounding in the longitudinal FHIR observations.
                3. Never invent labs, comorbidities, encounters, or medications absent from the input.
                4. Preserve all HIPAA Safe-Harbor placeholders verbatim.
                Output strictly valid JSON matching responseSchema.
                """;

        String prompt = "Forecast Horizon: " + horizon.name() + " (" + horizon.days() + " days)\n"
                + "Processed Historical FHIR Resource Counts: " + context.resourceCountsByType() + "\n"
                + "Baseline LACE Score: " + context.longitudinalSignals().baselineLaceScore()
                + " (" + context.longitudinalSignals().baselineReadmissionTier() + ")\n\n"
                + "De-identified Longitudinal FHIR Clinical History:\n"
                + context.redactedPayload().redactedText();

        long totalLatencyMs = 0L;
        TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildPredictiveForecastSchema(),
                "MEDIUM"
        );
        totalLatencyMs += aiResult.latencyMs();

        String calibratedModelId = null;
        Map<String, JsonNode> calibratedGateAnswers = Map.of();
        if (request.includeCalibratedRiskGate()) {
            DecisionEvaluationResult gate = geminiClient.evaluateClinicalDecisions(
                    context.redactedPayload().redactedText(),
                    List.of(),
                    buildCalibratedReadmissionQuestions(horizon)
            );
            calibratedModelId = gate.modelId();
            calibratedGateAnswers = gate.answers();
            totalLatencyMs += gate.latencyMs();
        }

        JsonNode parsedForecast = parseJsonSafely(aiResult.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.PREDICTIVE_RISK_AND_PROGRESSION,
                aiResult.modelId(),
                context.redactedPayload().redactedSha256Hex(),
                context.redactedPayload().redactionCountsByCategory(),
                parsedForecast.toString(),
                aiResult.tokenUsage(),
                totalLatencyMs,
                request.clinicianId()
        );

        return new ClinicalPredictiveForecastResult(
                draft.draftId(),
                draft.status().name(),
                horizon,
                aiResult.modelId(),
                calibratedModelId,
                context.resourceCountsByType(),
                context.longitudinalSignals(),
                parsedForecast,
                calibratedGateAnswers,
                context.redactedPayload().totalRedactions(),
                context.redactedPayload().redactionCountsByCategory(),
                totalLatencyMs
        );
    }

    /**
     * Unwraps historical HL7 FHIR R4 resources/Bundles, extracts deterministic longitudinal LACE
     * readmission signals and serial biomarker trajectories, harvests direct patient identifiers,
     * strips PII blocks, and applies mandatory HIPAA Safe-Harbor redaction.
     */
    public RedactedLongitudinalContext prepareRedactedLongitudinalContext(
            List<String> historicalFhirResourcesJson,
            List<String> socialDeterminantsOfHealth) {
        List<JsonNode> resources = new ArrayList<>();
        List<String> supplementalTexts = new ArrayList<>();
        parseFhirInputList(historicalFhirResourcesJson, resources, supplementalTexts);

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIds = new LinkedHashSet<>();
        String harvestedDob = null;
        Map<String, Integer> countsByType = new LinkedHashMap<>();

        for (JsonNode node : resources) {
            String type = node.path("resourceType").asText("UnknownResource");
            countsByType.merge(type, 1, Integer::sum);
            if ("Patient".equals(type) || "RelatedPerson".equals(type)) {
                harvestPatientIdentifiers(node, harvestedNames, harvestedIds);
                if (harvestedDob == null && node.hasNonNull("birthDate")) {
                    String dob = node.path("birthDate").asText("").trim();
                    if (!dob.isBlank()) {
                        harvestedDob = dob;
                    }
                }
            }
        }

        LongitudinalFhirSignals signals = extractLongitudinalFhirSignals(resources);

        StringBuilder digest = new StringBuilder();
        if (!resources.isEmpty()) {
            digest.append("=== LONGITUDINAL FHIR HISTORY ===\n");
            for (JsonNode node : resources) {
                digest.append(formatSafeFhirLine(node)).append("\n");
            }
        }
        if (socialDeterminantsOfHealth != null && !socialDeterminantsOfHealth.isEmpty()) {
            digest.append("=== SOCIAL DETERMINANTS OF HEALTH (SDOH) ===\n")
                    .append(String.join("; ", socialDeterminantsOfHealth)).append("\n");
        }
        digest.append("=== DETERMINISTIC LONGITUDINAL SIGNALS ===\n")
                .append("encounters=").append(signals.totalEncounters())
                .append(", inpatientOrEmergency=").append(signals.inpatientOrEmergencyEncounters())
                .append(", maxInpatientLosDays=").append(signals.maxInpatientLengthOfStayDays())
                .append(", activeChronicConditions=").append(signals.activeChronicConditionCount())
                .append(", activeMedications=").append(signals.activeMedicationCount())
                .append(", polypharmacy=").append(signals.polypharmacyFlag())
                .append(", baselineLaceScore=").append(signals.baselineLaceScore())
                .append(" (").append(signals.baselineReadmissionTier()).append(")\n");

        for (BiomarkerTrend trend : signals.biomarkerTrends()) {
            digest.append("[BiomarkerTrend] ").append(trend.biomarkerName())
                    .append(": earliest=").append(trend.earliestValue()).append(" ").append(trend.unit())
                    .append(", latest=").append(trend.latestValue()).append(" ").append(trend.unit())
                    .append(", delta=").append(String.format(Locale.ROOT, "%+.2f", trend.absoluteDelta()))
                    .append(", direction=").append(trend.direction())
                    .append(" — ").append(trend.clinicalInterpretation()).append("\n");
        }
        for (String extra : supplementalTexts) {
            digest.append("[Supplemental Clinical History] ").append(extra).append("\n");
        }

        RedactionContext ctx = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digest.toString().trim(), ctx);

        return new RedactedLongitudinalContext(
                redacted,
                Collections.unmodifiableMap(countsByType),
                signals,
                resources.size()
        );
    }

    /**
     * Deterministically extracts longitudinal utilization, LACE-index readmission baseline,
     * polypharmacy count, and serial biomarker progression slopes from parsed FHIR R4 resources.
     */
    public LongitudinalFhirSignals extractLongitudinalFhirSignals(List<JsonNode> fhirResources) {
        if (fhirResources == null || fhirResources.isEmpty()) {
            return new LongitudinalFhirSignals(0, 0, 0, 0, 0, false, 0, RiskTier.LOW, List.of());
        }

        int totalEncounters = 0;
        int acuteEncounters = 0;
        int edEncounters = 0;
        int maxLosDays = 0;
        boolean hasEmergencyAdmission = false;
        Set<String> chronicConditions = new LinkedHashSet<>();
        Set<String> activeMedications = new LinkedHashSet<>();
        Map<String, List<TimedObservation>> serialBiomarkers = new LinkedHashMap<>();

        for (JsonNode res : fhirResources) {
            String type = res.path("resourceType").asText("");
            switch (type) {
                case "Encounter" -> {
                    totalEncounters++;
                    String encClass = extractEncounterClassCode(res).toUpperCase(Locale.ROOT);
                    if ("EMER".equals(encClass) || "EMERGENCY".equals(encClass)) {
                        acuteEncounters++;
                        edEncounters++;
                        hasEmergencyAdmission = true;
                    } else if ("IMP".equals(encClass) || "ACUTE".equals(encClass) || "INPATIENT".equals(encClass)) {
                        acuteEncounters++;
                    }
                    int los = computeEncounterLengthOfStayDays(res);
                    if (los > maxLosDays) {
                        maxLosDays = los;
                    }
                }
                case "Condition" -> {
                    String status = extractCodeableDisplay(res.path("clinicalStatus")).toLowerCase(Locale.ROOT);
                    if (!status.contains("resolved") && !status.contains("inactive")) {
                        String condDisplay = extractCodeableDisplay(res.path("code")).toLowerCase(Locale.ROOT);
                        if (!condDisplay.isBlank() && !"unspecified".equals(condDisplay)) {
                            boolean chronic = CHRONIC_DISEASE_TOKENS.stream().anyMatch(condDisplay::contains);
                            if (chronic) {
                                chronicConditions.add(condDisplay);
                            }
                        }
                    }
                }
                case "MedicationRequest", "MedicationStatement" -> {
                    String medStatus = res.path("status").asText("active").toLowerCase(Locale.ROOT);
                    if ("active".equals(medStatus) || "intended".equals(medStatus) || "on-hold".equals(medStatus)) {
                        String med = extractCodeableDisplay(res.path("medicationCodeableConcept")).toLowerCase(Locale.ROOT);
                        if (!med.isBlank() && !"unspecified".equals(med)) {
                            activeMedications.add(med);
                        }
                    }
                }
                case "Observation" -> {
                    Double numericValue = extractNumericObservationValue(res);
                    if (numericValue != null) {
                        String codeText = extractCodeableDisplay(res.path("code")).toLowerCase(Locale.ROOT);
                        String unit = res.path("valueQuantity").path("unit").asText("").trim();
                        String ts = extractObservationTimestampKey(res);
                        String canonicalMarker = classifyBiomarker(codeText);
                        if (canonicalMarker != null) {
                            serialBiomarkers.computeIfAbsent(canonicalMarker, k -> new ArrayList<>())
                                    .add(new TimedObservation(ts, numericValue, unit));
                        }
                    }
                }
                default -> {
                    // Included in redacted digest for Gemini contextual synthesis
                }
            }
        }

        // LACE Index approximation (0-19): L (Length of stay 0-7) + A (Acuity 0 or 3) + C (Comorbidity 0-5) + E (ED visits 0-4)
        int scoreL = mapLosToLacePoints(maxLosDays);
        int scoreA = hasEmergencyAdmission ? 3 : 0;
        int scoreC = Math.min(5, chronicConditions.size());
        int scoreE = Math.min(4, edEncounters);
        int laceScore = scoreL + scoreA + scoreC + scoreE;
        RiskTier tier = classifyLaceRiskTier(laceScore);

        List<BiomarkerTrend> trends = new ArrayList<>();
        for (Map.Entry<String, List<TimedObservation>> entry : serialBiomarkers.entrySet()) {
            trends.add(computeBiomarkerTrend(entry.getKey(), entry.getValue()));
        }

        return new LongitudinalFhirSignals(
                totalEncounters,
                acuteEncounters,
                maxLosDays,
                chronicConditions.size(),
                activeMedications.size(),
                activeMedications.size() >= 5,
                laceScore,
                tier,
                Collections.unmodifiableList(trends)
        );
    }

    private BiomarkerTrend computeBiomarkerTrend(String markerName, List<TimedObservation> points) {
        List<TimedObservation> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(TimedObservation::timestampKey));

        TimedObservation earliest = sorted.get(0);
        TimedObservation latest = sorted.get(sorted.size() - 1);
        double delta = latest.value() - earliest.value();
        String unit = !latest.unit().isBlank() ? latest.unit() : earliest.unit();

        if (sorted.size() < 2) {
            return new BiomarkerTrend(
                    markerName, unit, sorted.size(), earliest.value(), latest.value(), 0.0,
                    TrendDirection.INSUFFICIENT_DATA,
                    "Single longitudinal measurement recorded (" + latest.value() + " " + unit + ")."
            );
        }

        TrendDirection dir;
        String interpretation;
        switch (markerName) {
            case "eGFR" -> {
                if (delta <= -5.0 || latest.value() < 30.0) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Declining renal filtration trajectory; evaluate CKD progression and nephrotoxin exposure.";
                } else if (delta >= 5.0) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Improving estimated glomerular filtration rate across serial labs.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Renal filtration relatively stable across recorded measurements.";
                }
            }
            case "HbA1c" -> {
                if (delta >= 0.5 || latest.value() >= 8.0) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Worsening or uncontrolled glycemic trend; review adherence and antihyperglycemic intensification.";
                } else if (delta <= -0.5) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Clinically meaningful HbA1c reduction over serial measurements.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Glycemic control stable across serial HbA1c observations.";
                }
            }
            case "Systolic Blood Pressure" -> {
                if (delta >= 10.0 || latest.value() >= 140.0) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Escalating or uncontrolled systolic hypertension; elevated cardiovascular/readmission risk.";
                } else if (delta <= -10.0 && latest.value() >= 100.0) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Improved systolic blood pressure trend toward guideline targets.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Systolic blood pressure stable across recorded visits.";
                }
            }
            case "BNP / NT-proBNP" -> {
                if (delta >= 150.0 || latest.value() >= 400.0) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Rising natriuretic peptide trend consistent with heart failure volume overload/decompensation.";
                } else if (delta <= -150.0) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Decreasing natriuretic peptide trend suggesting improved volume status.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Natriuretic peptide level stable.";
                }
            }
            case "Serum Creatinine" -> {
                if (delta >= 0.3 || latest.value() >= 1.8) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Rising serum creatinine indicating potential AKI or CKD progression.";
                } else if (delta <= -0.3) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Declining serum creatinine suggesting renal recovery.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Serum creatinine stable.";
                }
            }
            case "SpO2" -> {
                if (delta <= -3.0 || latest.value() < 92.0) {
                    dir = TrendDirection.WORSENING;
                    interpretation = "Declining oxygen saturation; evaluate pulmonary decompensation or COPD/HF exacerbation.";
                } else if (delta >= 3.0) {
                    dir = TrendDirection.IMPROVING;
                    interpretation = "Improving arterial oxygen saturation.";
                } else {
                    dir = TrendDirection.STABLE;
                    interpretation = "Oxygen saturation stable.";
                }
            }
            default -> {
                dir = TrendDirection.STABLE;
                interpretation = "Serial biomarker measurements tracked.";
            }
        }

        return new BiomarkerTrend(
                markerName, unit, sorted.size(), earliest.value(), latest.value(), delta, dir, interpretation);
    }

    private String classifyBiomarker(String codeText) {
        if (codeText.contains("egfr") || codeText.contains("33914-3") || codeText.contains("62238-1")) {
            return "eGFR";
        }
        if (codeText.contains("hba1c") || codeText.contains("hemoglobin a1c") || codeText.contains("4548-4")) {
            return "HbA1c";
        }
        if (codeText.contains("systolic") || codeText.contains("8480-6")) {
            return "Systolic Blood Pressure";
        }
        if (codeText.contains("bnp") || codeText.contains("30934-4") || codeText.contains("33762-6")) {
            return "BNP / NT-proBNP";
        }
        if (codeText.contains("creatinine") || codeText.contains("2160-0")) {
            return "Serum Creatinine";
        }
        if (codeText.contains("spo2") || codeText.contains("oxygen saturation")
                || codeText.contains("59408-5") || codeText.contains("2708-6")) {
            return "SpO2";
        }
        return null;
    }

    private int mapLosToLacePoints(int losDays) {
        if (losDays <= 0) return 0;
        if (losDays == 1) return 1;
        if (losDays == 2) return 2;
        if (losDays == 3) return 3;
        if (losDays <= 6) return 4;
        if (losDays <= 13) return 5;
        return 7;
    }

    private RiskTier classifyLaceRiskTier(int laceScore) {
        if (laceScore >= 12) return RiskTier.VERY_HIGH;
        if (laceScore >= 10) return RiskTier.HIGH;
        if (laceScore >= 5) return RiskTier.MODERATE;
        return RiskTier.LOW;
    }

    private String extractEncounterClassCode(JsonNode encounter) {
        JsonNode cls = encounter.path("class");
        if (cls.isObject()) {
            return cls.path("code").asText("");
        }
        if (cls.isArray() && !cls.isEmpty()) {
            return cls.get(0).path("coding").path(0).path("code").asText("");
        }
        return "";
    }

    private int computeEncounterLengthOfStayDays(JsonNode encounter) {
        String startStr = encounter.path("period").path("start").asText("").trim();
        String endStr = encounter.path("period").path("end").asText("").trim();
        if (startStr.isBlank() || endStr.isBlank()) {
            return 0;
        }
        try {
            Instant s = parseInstantFlexible(startStr);
            Instant e = parseInstantFlexible(endStr);
            if (s != null && e != null && !e.isBefore(s)) {
                long hours = Duration.between(s, e).toHours();
                return (int) Math.max(1L, (hours + 23L) / 24L);
            }
        } catch (Exception ignored) {
            return 0;
        }
        return 0;
    }

    private Instant parseInstantFlexible(String iso) {
        try {
            return Instant.parse(iso);
        } catch (Exception ignored) {
            // Try offset or date-only ISO formats
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception ignored) {
            // Fall through
        }
        try {
            if (iso.length() >= 10) {
                return LocalDate.parse(iso.substring(0, 10)).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String extractObservationTimestampKey(JsonNode obs) {
        String eff = obs.path("effectiveDateTime").asText("").trim();
        if (!eff.isBlank()) {
            return eff;
        }
        String issued = obs.path("issued").asText("").trim();
        return !issued.isBlank() ? issued : "0000-00-00";
    }

    private void parseFhirInputList(List<String> rawList, List<JsonNode> nodeSink, List<String> textSink) {
        if (rawList == null) {
            return;
        }
        for (String raw : rawList) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                JsonNode parsed = objectMapper.readTree(raw);
                flattenFhirNode(parsed, nodeSink);
            } catch (Exception ex) {
                textSink.add(raw.trim());
            }
        }
    }

    private void flattenFhirNode(JsonNode node, List<JsonNode> sink) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                flattenFhirNode(item, sink);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if ("Bundle".equals(node.path("resourceType").asText("")) && node.path("entry").isArray()) {
            for (JsonNode entry : node.path("entry")) {
                if (entry.path("resource").isObject()) {
                    flattenFhirNode(entry.path("resource"), sink);
                }
            }
            return;
        }
        sink.add(node);
    }

    private void harvestPatientIdentifiers(JsonNode patientNode, Set<String> names, Set<String> ids) {
        if (patientNode.path("name").isArray()) {
            for (JsonNode hn : patientNode.path("name")) {
                String text = hn.path("text").asText("").trim();
                if (text.length() >= 2) {
                    names.add(text);
                }
                String family = hn.path("family").asText("").trim();
                List<String> givens = new ArrayList<>();
                if (hn.path("given").isArray()) {
                    for (JsonNode g : hn.path("given")) {
                        String gv = g.asText("").trim();
                        if (gv.length() >= 2) {
                            givens.add(gv);
                            names.add(gv);
                        }
                    }
                }
                if (family.length() >= 2) {
                    names.add(family);
                    if (!givens.isEmpty()) {
                        names.add(String.join(" ", givens) + " " + family);
                    }
                }
            }
        }
        if (patientNode.path("identifier").isArray()) {
            for (JsonNode id : patientNode.path("identifier")) {
                String v = id.path("value").asText("").trim();
                if (v.length() >= 3) {
                    ids.add(v);
                }
            }
        }
        if (patientNode.path("telecom").isArray()) {
            for (JsonNode tel : patientNode.path("telecom")) {
                String v = tel.path("value").asText("").trim();
                if (v.length() >= 4) {
                    ids.add(v);
                }
            }
        }
        if (patientNode.path("address").isArray()) {
            for (JsonNode addr : patientNode.path("address")) {
                if (addr.path("line").isArray()) {
                    for (JsonNode line : addr.path("line")) {
                        String lv = line.asText("").trim();
                        if (lv.length() >= 4) {
                            ids.add(lv);
                        }
                    }
                }
            }
        }
    }

    private String formatSafeFhirLine(JsonNode resource) {
        String type = resource.path("resourceType").asText("Resource");
        return switch (type) {
            case "Patient" -> {
                String dob = resource.path("birthDate").asText("");
                String birthYear = dob.length() >= 4 ? dob.substring(0, 4) : "unspecified";
                yield "[Patient] gender=" + resource.path("gender").asText("unknown") + ", birthYear=" + birthYear;
            }
            case "Encounter" -> {
                int los = computeEncounterLengthOfStayDays(resource);
                String year = safeYearPrefix(resource.path("period").path("start").asText(""));
                yield "[Encounter] class=" + extractEncounterClassCode(resource)
                        + ", status=" + resource.path("status").asText("finished")
                        + ", year=" + year
                        + ", lengthOfStayDays=" + los
                        + ", reason=" + extractCodeableDisplay(resource.path("reasonCode").path(0));
            }
            case "Condition" -> "[Condition] condition=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + extractCodeableDisplay(resource.path("clinicalStatus"))
                    + ", onsetYear=" + safeYearPrefix(resource.path("onsetDateTime").asText(""))
                    + extractNotesSuffix(resource);
            case "Observation" -> "[Observation] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", value=" + extractObservationDisplayValue(resource)
                    + ", year=" + safeYearPrefix(extractObservationTimestampKey(resource))
                    + ", status=" + resource.path("status").asText("final")
                    + extractNotesSuffix(resource);
            case "MedicationRequest", "MedicationStatement" -> "[" + type + "] medication="
                    + extractCodeableDisplay(resource.path("medicationCodeableConcept"))
                    + ", status=" + resource.path("status").asText("active")
                    + extractNotesSuffix(resource);
            case "Procedure" -> "[Procedure] procedure=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + resource.path("status").asText("completed")
                    + ", year=" + safeYearPrefix(resource.path("performedDateTime").asText(""));
            case "CarePlan" -> "[CarePlan] title=" + resource.path("title").asText("unspecified")
                    + ", status=" + resource.path("status").asText("active");
            default -> "[" + type + "] " + resource.toString();
        };
    }

    private String safeYearPrefix(String isoDate) {
        if (isoDate != null && isoDate.length() >= 4 && Character.isDigit(isoDate.charAt(0))) {
            return isoDate.substring(0, 4);
        }
        return "unspecified";
    }

    private Double extractNumericObservationValue(JsonNode obs) {
        JsonNode valNode = obs.path("valueQuantity").path("value");
        if (valNode.isNumber()) {
            return valNode.asDouble();
        }
        if (valNode.isTextual()) {
            try {
                return Double.parseDouble(valNode.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractObservationDisplayValue(JsonNode obs) {
        if (obs.has("valueQuantity")) {
            JsonNode q = obs.path("valueQuantity");
            return q.path("value").asText("") + " " + q.path("unit").asText("");
        }
        if (obs.has("valueString")) {
            return obs.path("valueString").asText("");
        }
        if (obs.has("valueCodeableConcept")) {
            return extractCodeableDisplay(obs.path("valueCodeableConcept"));
        }
        return "unspecified";
    }

    private String extractCodeableDisplay(JsonNode concept) {
        if (concept == null || concept.isMissingNode() || concept.isNull()) {
            return "unspecified";
        }
        String text = concept.path("text").asText("").trim();
        JsonNode coding = concept.path("coding").path(0);
        String code = coding.path("code").asText("").trim();
        String display = coding.path("display").asText("").trim();
        String label = !text.isBlank() ? text : (!display.isBlank() ? display : "unspecified");
        return !code.isBlank() ? label + " (" + code + ")" : label;
    }

    private String extractNotesSuffix(JsonNode resource) {
        JsonNode notes = resource.path("note");
        if (notes.isArray() && !notes.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode n : notes) {
                String t = n.path("text").asText("").trim();
                if (!t.isBlank()) {
                    texts.add(t);
                }
            }
            if (!texts.isEmpty()) {
                return ", notes=" + String.join(" | ", texts);
            }
        }
        return "";
    }

    private Map<String, DecisionQuestion> buildCalibratedReadmissionQuestions(ForecastHorizon horizon) {
        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        questions.put("high_readmission_risk", new DecisionQuestion(
                "noul",
                "Based on the longitudinal FHIR history, does this patient have high risk of unplanned hospital readmission within "
                        + horizon.days() + " days requiring proactive transitional care management?",
                Map.of(
                        "yes", "Multiple acute encounters, high LACE score, polypharmacy, or worsening organ-function trends",
                        "no", "Clinically stable with low utilization and controlled chronic markers"
                )
        ));
        questions.put("progression_severity_tier", new DecisionQuestion(
                "choice",
                "Classify the overall chronic disease progression risk tier.",
                Map.of(
                        "LOW", "Stable chronic markers with routine outpatient follow-up",
                        "MODERATE", "Mild drift requiring guideline medication optimization",
                        "HIGH", "Clear worsening biomarker trajectory requiring specialist/care-manager intervention",
                        "CRITICAL", "Rapid multi-organ or decompensating trajectory requiring urgent clinical review"
                )
        ));
        return questions;
    }

    private ObjectNode buildPredictiveForecastSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode readmissionObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode readmissionProps = objectMapper.createObjectNode();
        readmissionProps.set("horizonDays", objectMapper.createObjectNode().put("type", "INTEGER"));
        readmissionProps.set("riskScore0To100", objectMapper.createObjectNode().put("type", "INTEGER"));
        readmissionProps.set("riskTier", objectMapper.createObjectNode().put("type", "STRING"));
        readmissionProps.set("primaryRiskDrivers", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        readmissionProps.set("modifiableRiskFactors", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        readmissionProps.set("transitionalCareInterventions", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        readmissionObj.set("properties", readmissionProps);
        props.set("readmissionForecast", readmissionObj);

        ObjectNode progItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode progProps = objectMapper.createObjectNode();
        progProps.set("conditionName", objectMapper.createObjectNode().put("type", "STRING"));
        progProps.set("currentStageOrStatus", objectMapper.createObjectNode().put("type", "STRING"));
        progProps.set("trajectoryDirection", objectMapper.createObjectNode().put("type", "STRING"));
        progProps.set("projected6MonthOutlook", objectMapper.createObjectNode().put("type", "STRING"));
        progProps.set("supportingBiomarkerTrends", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        progProps.set("guidelineRecommendedActions", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        progItem.set("properties", progProps);
        props.set("diseaseProgressionTrends", objectMapper.createObjectNode().put("type", "ARRAY").set("items", progItem));

        props.set("careManagementPriority", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("dataCompletenessNotes", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("clinicianAttestationRequired", objectMapper.createObjectNode().put("type", "BOOLEAN"));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("readmissionForecast")
                .add("diseaseProgressionTrends")
                .add("careManagementPriority")
                .add("clinicianAttestationRequired");
        root.set("required", req);
        return root;
    }

    private JsonNode parseJsonSafely(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("rawOutput", text);
        }
    }
}
