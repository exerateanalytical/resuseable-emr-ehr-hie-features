/*
 * =============================================================================
 * Opes HealthCore SDK — Real-Time FHIR ClinicalDecisionEngine (Gemini + Safe-Harbor)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.cds;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} providing real-time,
 * evidence-based clinical alerts and drug-interaction warnings triggered by HL7 FHIR R4 resources
 * ({@code MedicationRequest}, {@code AllergyIntolerance}, {@code Observation}, {@code Condition},
 * {@code Encounter}, or full FHIR {@code Bundle}s).
 *
 * <p><strong>Clinical Safety &amp; Privacy Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Mandatory HIPAA Safe-Harbor Redaction:</strong> Harvests direct patient identifiers
 *       from FHIR {@code Patient} / {@code RelatedPerson} payloads, strips PII blocks, and scrubs all
 *       18 Safe-Harbor identifier classes across the clinical trigger digest before invoking Gemini.</li>
 *   <li><strong>Deterministic FHIR Safety Guardrails:</strong> Evaluates real-time drug-allergy,
 *       high-risk drug-drug, renal dosing (eGFR), critical lab panic, and qSOFA sepsis rules locally
 *       on FHIR trigger resources and passes those structured cues into Gemini for deeper evidence-based
 *       clinical synthesis and alternative therapy guidance.</li>
 *   <li><strong>Human-in-the-Loop Attestation &amp; Strict Error Propagation:</strong> Quarantines
 *       all AI-generated alerts in {@link ClinicalAiGovernanceService} under
 *       {@code DRAFT_PENDING_ATTESTATION} and propagates upstream Gemini API errors cleanly without
 *       fabricated clinical outputs.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalDecisionEngine {

    public enum FhirTriggerEvent {
        ORDER_SELECT,
        ORDER_SIGN,
        OBSERVATION_RECORDED,
        ENCOUNTER_START,
        PATIENT_VIEW,
        MEDICATION_ADMINISTRATION
    }

    public enum AlertSeverity {
        CRITICAL,
        WARNING,
        INFO
    }

    public record FhirCdsTriggerRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            FhirTriggerEvent triggerEvent,
            List<String> triggerFhirResourcesJson,
            List<String> patientContextFhirResourcesJson,
            List<String> pharmacogenomicsPhenotypes
    ) {}

    public record DeterministicClinicalAlert(
            String ruleCode,
            AlertSeverity severity,
            String category,
            String summary,
            String clinicalDetail,
            String recommendedAction
    ) {}

    public record RedactedFhirTriggerContext(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> resourceCountsByType,
            List<DeterministicClinicalAlert> deterministicRuleAlerts,
            int totalResourcesProcessed
    ) {}

    public record ClinicalDecisionEvaluationResult(
            UUID draftId,
            String attestationStatus,
            FhirTriggerEvent triggerEvent,
            String modelId,
            Map<String, Integer> processedResourceCountsByType,
            List<DeterministicClinicalAlert> deterministicRuleAlerts,
            JsonNode evidenceBasedAiAlerts,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long latencyMs
    ) {}

    private static final Set<String> NSAID_TOKENS = Set.of(
            "ibuprofen", "naproxen", "diclofenac", "ketorolac", "meloxicam", "celecoxib", "indomethacin", "piroxicam"
    );
    private static final Set<String> ACE_ARB_TOKENS = Set.of(
            "lisinopril", "enalapril", "ramipril", "captopril", "perindopril", "losartan", "valsartan", "candesartan", "telmisartan"
    );
    private static final Set<String> POTASSIUM_SPARING_TOKENS = Set.of(
            "spironolactone", "eplerenone", "amiloride", "triamterene", "potassium chloride"
    );
    private static final Set<String> QT_PROLONGING_TOKENS = Set.of(
            "azithromycin", "clarithromycin", "erythromycin", "ciprofloxacin", "levofloxacin", "moxifloxacin",
            "amiodarone", "haloperidol", "ondansetron", "fluconazole", "methadone"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public ClinicalDecisionEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Evaluates an HL7 FHIR R4 clinical trigger event (e.g., {@code ORDER_SIGN}, {@code ORDER_SELECT},
     * {@code OBSERVATION_RECORDED}) with mandatory HIPAA Safe-Harbor redaction, local deterministic
     * safety screening, and real-time Gemini evidence-based alert synthesis.
     */
    public ClinicalDecisionEvaluationResult evaluateFhirTrigger(FhirCdsTriggerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("FhirCdsTriggerRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CLINICAL_DECISION_SUPPORT);

        FhirTriggerEvent event = request.triggerEvent() != null
                ? request.triggerEvent() : FhirTriggerEvent.ORDER_SIGN;

        RedactedFhirTriggerContext triggerCtx = prepareRedactedTriggerContext(
                request.triggerFhirResourcesJson(),
                request.patientContextFhirResourcesJson(),
                request.pharmacogenomicsPhenotypes()
        );
        if (triggerCtx.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one trigger or patient-context FHIR R4 resource JSON payload is required.");
        }

        String systemInstruction = """
                You are a real-time, evidence-based Clinical Decision Support (CDS) engine embedded inside
                an HL7 FHIR R4 EMR/EHR platform (aligned with HL7 CDS Hooks 2.0, WHO, NICE, IDSA, ACC/AHA, and CPIC).
                Analyze the de-identified FHIR trigger resources, longitudinal patient context, and deterministic
                safety rule cues to produce concise, actionable clinical alerts and drug-interaction warnings.
                Prioritize:
                1. Severe/contraindicated drug-drug, drug-allergy, drug-disease, and drug-gene (CPIC) interactions.
                2. Renal/hepatic dose adjustments (e.g., eGFR thresholds) and electrolyte/vital panic escalations.
                3. Evidence-based safer therapeutic alternatives and guideline citations.
                Never fabricate labs, allergies, or medications absent from the FHIR context.
                Preserve Safe-Harbor placeholders verbatim. Output strictly valid JSON matching responseSchema.
                """;

        String prompt = "FHIR CDS Trigger Event: " + event.name()
                + "\nProcessed FHIR Resource Counts: " + triggerCtx.resourceCountsByType()
                + "\nDeterministic Pre-Screen Alerts Count: " + triggerCtx.deterministicRuleAlerts().size()
                + "\n\nDe-identified FHIR Clinical Context & Trigger Digest:\n"
                + triggerCtx.redactedPayload().redactedText();

        TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildCdsEngineSchema(),
                "MEDIUM"
        );

        JsonNode parsedAlerts = parseJsonSafely(aiResult.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.CLINICAL_DECISION_SUPPORT,
                aiResult.modelId(),
                triggerCtx.redactedPayload().redactedSha256Hex(),
                triggerCtx.redactedPayload().redactionCountsByCategory(),
                parsedAlerts.toString(),
                aiResult.tokenUsage(),
                aiResult.latencyMs(),
                request.clinicianId()
        );

        return new ClinicalDecisionEvaluationResult(
                draft.draftId(),
                draft.status().name(),
                event,
                aiResult.modelId(),
                triggerCtx.resourceCountsByType(),
                triggerCtx.deterministicRuleAlerts(),
                parsedAlerts,
                triggerCtx.redactedPayload().totalRedactions(),
                triggerCtx.redactedPayload().redactionCountsByCategory(),
                aiResult.latencyMs()
        );
    }

    /**
     * Unwraps trigger and context FHIR R4 resources (including {@code Bundle}s), evaluates
     * deterministic high-risk clinical safety rules locally, harvests all explicit patient direct
     * identifiers, strips PII blocks, and applies mandatory HIPAA Safe-Harbor PHI redaction.
     */
    public RedactedFhirTriggerContext prepareRedactedTriggerContext(
            List<String> triggerFhirResourcesJson,
            List<String> contextFhirResourcesJson,
            List<String> pharmacogenomicsPhenotypes) {
        List<JsonNode> triggerNodes = new ArrayList<>();
        List<JsonNode> contextNodes = new ArrayList<>();
        List<String> supplementalTexts = new ArrayList<>();

        parseFhirInputList(triggerFhirResourcesJson, triggerNodes, supplementalTexts);
        parseFhirInputList(contextFhirResourcesJson, contextNodes, supplementalTexts);

        List<JsonNode> allNodes = new ArrayList<>(triggerNodes.size() + contextNodes.size());
        allNodes.addAll(triggerNodes);
        allNodes.addAll(contextNodes);

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIds = new LinkedHashSet<>();
        String harvestedDob = null;
        Map<String, Integer> countsByType = new LinkedHashMap<>();

        for (JsonNode node : allNodes) {
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

        List<DeterministicClinicalAlert> deterministicAlerts = evaluateDeterministicFhirSafetyRules(allNodes);

        StringBuilder digest = new StringBuilder();
        if (!triggerNodes.isEmpty()) {
            digest.append("=== TRIGGER FHIR RESOURCES ===\n");
            for (JsonNode n : triggerNodes) {
                digest.append(formatSafeFhirLine(n)).append("\n");
            }
        }
        if (!contextNodes.isEmpty()) {
            digest.append("=== PATIENT CLINICAL CONTEXT ===\n");
            for (JsonNode n : contextNodes) {
                digest.append(formatSafeFhirLine(n)).append("\n");
            }
        }
        if (pharmacogenomicsPhenotypes != null && !pharmacogenomicsPhenotypes.isEmpty()) {
            digest.append("=== PHARMACOGENOMICS (CPIC) ===\n")
                    .append(String.join("; ", pharmacogenomicsPhenotypes)).append("\n");
        }
        if (!deterministicAlerts.isEmpty()) {
            digest.append("=== DETERMINISTIC RULE PRE-SCREEN ALERTS ===\n");
            for (DeterministicClinicalAlert a : deterministicAlerts) {
                digest.append("[").append(a.severity()).append("] ")
                        .append(a.ruleCode()).append(": ").append(a.summary())
                        .append(" — ").append(a.clinicalDetail()).append("\n");
            }
        }
        for (String extra : supplementalTexts) {
            digest.append("[Supplemental Clinical Note] ").append(extra).append("\n");
        }

        RedactionContext ctx = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digest.toString().trim(), ctx);

        return new RedactedFhirTriggerContext(
                redacted,
                Collections.unmodifiableMap(countsByType),
                Collections.unmodifiableList(deterministicAlerts),
                allNodes.size()
        );
    }

    /**
     * Evaluates deterministic, evidence-based local safety rules across parsed FHIR R4 resources:
     * direct drug-allergy cross-matches, major drug-drug interactions (anticoagulant + NSAID,
     * ACEi/ARB + potassium-sparing diuretic, dual QT-prolonging agents), renal dosing gates
     * (Metformin with eGFR &lt; 30), critical potassium panic values, and qSOFA sepsis cues.
     */
    public List<DeterministicClinicalAlert> evaluateDeterministicFhirSafetyRules(List<JsonNode> fhirResources) {
        List<DeterministicClinicalAlert> alerts = new ArrayList<>();
        if (fhirResources == null || fhirResources.isEmpty()) {
            return alerts;
        }

        List<String> medications = new ArrayList<>();
        List<String> allergies = new ArrayList<>();
        Double latestEgfr = null;
        Double latestPotassium = null;
        Double latestSystolicBp = null;
        Double latestRespRate = null;

        for (JsonNode res : fhirResources) {
            String type = res.path("resourceType").asText("");
            switch (type) {
                case "MedicationRequest", "MedicationStatement", "MedicationAdministration" -> {
                    String medName = extractCodeableDisplay(res.path("medicationCodeableConcept"));
                    if (!medName.isBlank() && !"unspecified".equalsIgnoreCase(medName)) {
                        medications.add(medName);
                    }
                }
                case "AllergyIntolerance" -> {
                    String substance = extractCodeableDisplay(res.path("code"));
                    if (!substance.isBlank() && !"unspecified".equalsIgnoreCase(substance)) {
                        allergies.add(substance);
                    }
                }
                case "Observation" -> {
                    String codeText = extractCodeableDisplay(res.path("code")).toLowerCase(Locale.ROOT);
                    Double numericVal = extractNumericObservationValue(res);
                    if (numericVal != null) {
                        if (codeText.contains("egfr") || codeText.contains("33914-3") || codeText.contains("62238-1")) {
                            latestEgfr = numericVal;
                        } else if (codeText.contains("potassium") || codeText.contains("2823-3") || codeText.contains("6298-4")) {
                            latestPotassium = numericVal;
                        } else if (codeText.contains("systolic") || codeText.contains("8480-6")) {
                            latestSystolicBp = numericVal;
                        } else if (codeText.contains("respiratory rate") || codeText.contains("9279-1")) {
                            latestRespRate = numericVal;
                        }
                    }
                }
                default -> {
                    // Other resources are included in the redacted Gemini context digest
                }
            }
        }

        // 1. Direct Drug-Allergy Contraindication Screening
        for (String med : medications) {
            String medLow = med.toLowerCase(Locale.ROOT);
            for (String allergy : allergies) {
                String algLow = allergy.toLowerCase(Locale.ROOT);
                String algRoot = algLow.split("[\\s(]+")[0];
                if (algRoot.length() >= 4 && medLow.contains(algRoot)) {
                    alerts.add(new DeterministicClinicalAlert(
                            "CDS-ALLERGY-001",
                            AlertSeverity.CRITICAL,
                            "DRUG_ALLERGY",
                            "Potential Drug-Allergy Contraindication: " + med,
                            "Ordered/active medication matches documented allergy (" + allergy + ").",
                            "Hold order immediately and verify allergy reaction history or select a non-cross-reactive alternative."
                    ));
                }
            }
        }

        // 2. Major Drug-Drug Interactions
        boolean hasWarfarin = containsAnyToken(medications, Set.of("warfarin", "acenocoumarol"));
        boolean hasNsaid = containsAnyToken(medications, NSAID_TOKENS);
        if (hasWarfarin && hasNsaid) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-DDI-001",
                    AlertSeverity.CRITICAL,
                    "DRUG_DRUG_INTERACTION",
                    "Major Bleeding Risk: Anticoagulant (Warfarin) + NSAID",
                    "Concurrent Warfarin and systemic NSAID markedly increases GI hemorrhage risk and elevates INR.",
                    "Avoid concurrent NSAID; consider acetaminophen/topical therapy or add PPI gastroprotection with close INR monitoring if unavoidable."
            ));
        }

        boolean hasAceOrArb = containsAnyToken(medications, ACE_ARB_TOKENS);
        boolean hasKsparing = containsAnyToken(medications, POTASSIUM_SPARING_TOKENS);
        if (hasAceOrArb && hasKsparing) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-DDI-002",
                    AlertSeverity.WARNING,
                    "DRUG_DRUG_INTERACTION",
                    "Hyperkalemia Risk: ACEi/ARB + Potassium-Sparing Agent",
                    "Combined RAAS blockade and potassium-sparing diuretic/supplement elevates risk of severe hyperkalemia.",
                    "Check baseline serum potassium and creatinine within 5-7 days and adjust dosing if K+ > 5.0 mmol/L."
            ));
        }

        long qtDrugCount = medications.stream()
                .map(m -> m.toLowerCase(Locale.ROOT))
                .filter(m -> QT_PROLONGING_TOKENS.stream().anyMatch(m::contains))
                .distinct()
                .count();
        if (qtDrugCount >= 2) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-DDI-003",
                    AlertSeverity.WARNING,
                    "DRUG_DRUG_INTERACTION",
                    "Additive QT Prolongation & Torsades de Pointes Risk",
                    "Multiple concurrent QT-prolonging medications detected in active/ordered regimen.",
                    "Obtain baseline 12-lead ECG (QTc), correct K+/Mg2+, or substitute a non-QT-prolonging alternative."
            ));
        }

        // 3. Renal Dosing Contraindication (Metformin + eGFR < 30)
        boolean hasMetformin = containsAnyToken(medications, Set.of("metformin"));
        if (hasMetformin && latestEgfr != null && latestEgfr < 30.0) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-RENAL-001",
                    AlertSeverity.CRITICAL,
                    "RENAL_DOSING_CONTRAINDICATION",
                    "Metformin Contraindicated with eGFR < 30 mL/min/1.73m2",
                    "Latest documented eGFR is " + latestEgfr + " mL/min/1.73m2; high risk of metformin-associated lactic acidosis.",
                    "Discontinue Metformin and transition to a renally appropriate glycemic agent per KDIGO/ADA guidelines."
            ));
        }

        // 4. Critical Electrolyte Panic (Potassium)
        if (latestPotassium != null && (latestPotassium >= 6.0 || latestPotassium <= 2.8)) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-LAB-K-001",
                    AlertSeverity.CRITICAL,
                    "CRITICAL_LAB_PANIC",
                    "Critical Serum Potassium Panic Value: " + latestPotassium + " mmol/L",
                    "Life-threatening dyskalemia associated with ventricular arrhythmia risk.",
                    "Obtain immediate STAT 12-lead ECG, verify non-hemolyzed sample, and initiate institutional electrolyte protocol."
            ));
        }

        // 5. qSOFA Early Sepsis Warning Cue (SBP <= 100 and RR >= 22)
        if (latestSystolicBp != null && latestSystolicBp <= 100.0
                && latestRespRate != null && latestRespRate >= 22.0) {
            alerts.add(new DeterministicClinicalAlert(
                    "CDS-SEPSIS-QSOFA",
                    AlertSeverity.CRITICAL,
                    "SEPSIS_EARLY_WARNING",
                    "Positive qSOFA Sepsis Screening Cue (qSOFA >= 2)",
                    "Hypotension (SBP " + latestSystolicBp + " mmHg) with tachypnea (RR " + latestRespRate + "/min) detected.",
                    "Assess mental status, order STAT serum lactate and blood cultures, and evaluate Surviving Sepsis 1-hour bundle."
            ));
        }

        return alerts;
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
            case "MedicationRequest", "MedicationStatement", "MedicationAdministration" -> "[" + type + "] medication="
                    + extractCodeableDisplay(resource.path("medicationCodeableConcept"))
                    + ", status=" + resource.path("status").asText("active")
                    + ", dosage=" + extractDosageText(resource)
                    + extractNotesSuffix(resource);
            case "AllergyIntolerance" -> "[AllergyIntolerance] substance=" + extractCodeableDisplay(resource.path("code"))
                    + ", criticality=" + resource.path("criticality").asText("unspecified")
                    + extractNotesSuffix(resource);
            case "Condition" -> "[Condition] condition=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + extractCodeableDisplay(resource.path("clinicalStatus"))
                    + extractNotesSuffix(resource);
            case "Observation" -> "[Observation] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", value=" + extractObservationDisplayValue(resource)
                    + ", status=" + resource.path("status").asText("final")
                    + extractNotesSuffix(resource);
            case "DiagnosticReport" -> "[DiagnosticReport] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", conclusion=" + resource.path("conclusion").asText("N/A");
            default -> "[" + type + "] " + resource.toString();
        };
    }

    private boolean containsAnyToken(List<String> items, Set<String> tokens) {
        for (String item : items) {
            String low = item.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (low.contains(token)) {
                    return true;
                }
            }
        }
        return false;
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

    private String extractDosageText(JsonNode medNode) {
        JsonNode dosage = medNode.path("dosageInstruction");
        if (!dosage.isArray() || dosage.isEmpty()) {
            dosage = medNode.path("dosage");
        }
        if (dosage.isArray() && !dosage.isEmpty()) {
            String text = dosage.get(0).path("text").asText("").trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "as directed";
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

    private ObjectNode buildCdsEngineSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode alertObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode alertProps = objectMapper.createObjectNode();
        alertProps.set("indicator", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("category", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("summary", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("clinicalRationale", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("recommendedAction", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("guidelineSource", objectMapper.createObjectNode().put("type", "STRING"));
        alertObj.set("properties", alertProps);
        props.set("cdsHooksCards", objectMapper.createObjectNode().put("type", "ARRAY").set("items", alertObj));

        ObjectNode ddiObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode ddiProps = objectMapper.createObjectNode();
        ddiProps.set("interactingAgents", objectMapper.createObjectNode().put("type", "STRING"));
        ddiProps.set("severity", objectMapper.createObjectNode().put("type", "STRING"));
        ddiProps.set("mechanism", objectMapper.createObjectNode().put("type", "STRING"));
        ddiProps.set("managementAndAlternative", objectMapper.createObjectNode().put("type", "STRING"));
        ddiObj.set("properties", ddiProps);
        props.set("drugInteractionWarnings", objectMapper.createObjectNode().put("type", "ARRAY").set("items", ddiObj));

        props.set("monitoringRecommendations", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("overallRiskTier", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("clinicianAttestationRequired", objectMapper.createObjectNode().put("type", "BOOLEAN"));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("cdsHooksCards").add("drugInteractionWarnings")
                .add("overallRiskTier").add("clinicianAttestationRequired");
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
