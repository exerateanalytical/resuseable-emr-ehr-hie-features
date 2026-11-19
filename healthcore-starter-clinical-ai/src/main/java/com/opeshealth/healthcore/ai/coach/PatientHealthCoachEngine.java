/*
 * =============================================================================
 * Opes HealthCore SDK — PatientHealthCoachEngine (Longitudinal Wellness & Adherence)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.AudioSynthesisResult;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that analyzes
 * longitudinal HL7 FHIR R4 patient data ({@code Patient}, {@code Condition}, {@code MedicationRequest},
 * {@code MedicationStatement}, {@code MedicationDispense}, {@code Observation}, {@code CarePlan},
 * {@code Goal}, {@code Appointment}, or full FHIR {@code Bundle}s) to synthesize personalized
 * wellness and medication adherence coaching prompts with mandatory clinician oversight.
 *
 * <p><strong>Clinical Safety, Privacy &amp; Oversight Guarantees:</strong></p>
 * <ul>
 *   <li><strong>Mandatory HIPAA Safe-Harbor Redaction:</strong> Harvests all direct patient
 *       identifiers from FHIR {@code Patient} / {@code RelatedPerson} resources, strips PII blocks
 *       (retaining only gender and birth year), and scrubs all 18 Safe-Harbor identifier classes
 *       before invoking Gemini models.</li>
 *   <li><strong>Longitudinal Adherence &amp; Wellness Signal Extraction:</strong> Deterministically
 *       identifies regimen complexity, refill/adherence gaps, chronic wellness opportunities (BP,
 *       HbA1c, BMI, activity), and clinical escalation red flags directly from FHIR history.</li>
 *   <li><strong>Full Clinician Oversight Gate:</strong> Every generated coaching draft is quarantined
 *       in {@link ClinicalAiGovernanceService} under {@code DRAFT_PENDING_ATTESTATION} with
 *       {@code approvedForPatientRelease=false}. Coaching prompts cannot be released to a patient
 *       until an authorized clinician executes {@link #approveOrRejectCoachingDraft} with
 *       {@code APPROVED} or {@code APPROVED_WITH_EDITS}, enforced via
 *       {@link #assertDraftApprovedForPatientRelease}.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class PatientHealthCoachEngine {

    public enum CoachingChannel {
        IN_APP,
        SMS,
        WHATSAPP,
        VOICE_IVR,
        PATIENT_PORTAL
    }

    public enum AdherenceRiskLevel {
        OPTIMAL,
        MODERATE_GAP,
        HIGH_NONADHERENCE_RISK,
        NO_ACTIVE_REGIMEN
    }

    public record PatientCoachingRequest(
            UUID facilityId,
            UUID patientId,
            String overseeingClinicianId,
            String targetLanguage,
            String readingLevel,
            CoachingChannel deliveryChannel,
            List<String> longitudinalFhirResourcesJson,
            List<String> patientReportedBarriers,
            boolean synthesizeVoiceAudio,
            String voiceName
    ) {}

    public record MedicationAdherenceSummary(
            int activeMedicationCount,
            int dispenseOrAdministrationCount,
            int adherenceGapSignalCount,
            boolean complexRegimenFlag,
            AdherenceRiskLevel adherenceRiskLevel,
            List<String> activeMedicationLabels,
            List<String> adherenceGapSignals
    ) {}

    public record WellnessProfileSignals(
            List<String> activeChronicConditions,
            List<String> activeCarePlanGoals,
            List<String> wellnessOpportunities,
            boolean clinicianEscalationRecommended,
            List<String> clinicianEscalationReasons
    ) {}

    public record RedactedCoachingContext(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> resourceCountsByType,
            MedicationAdherenceSummary adherenceSummary,
            WellnessProfileSignals wellnessSignals,
            int totalResourcesProcessed
    ) {}

    public record PatientCoachingDraftResult(
            UUID draftId,
            String attestationStatus,
            boolean approvedForPatientRelease,
            String overseeingClinicianId,
            String targetLanguage,
            CoachingChannel deliveryChannel,
            String textModelId,
            String ttsModelId,
            Map<String, Integer> processedResourceCountsByType,
            MedicationAdherenceSummary adherenceSummary,
            WellnessProfileSignals wellnessSignals,
            JsonNode personalizedCoachingPlan,
            String audioMimeType,
            String wavAudioBase64,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long totalLatencyMs
    ) {}

    public record ClinicianOversightDecisionResult(
            UUID draftId,
            DraftAttestationStatus attestationStatus,
            boolean releasedToPatient,
            String attestedByPractitionerId,
            String attestationReason,
            Instant attestedAt,
            String finalApprovedCoachingJson
    ) {}

    private static final Set<String> NONADHERENCE_KEYWORDS = Set.of(
            "missed", "skipped", "ran out", "late refill", "stopped taking",
            "forgot", "non-adherent", "nonadherent", "cost barrier", "side effect"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public PatientHealthCoachEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Analyzes longitudinal HL7 FHIR R4 data to generate personalized, plain-language wellness and
     * medication adherence coaching prompts with optional {@code audio/wav} voice guidance.
     *
     * <p>The resulting plan is strictly quarantined in {@link ClinicalAiGovernanceService} under
     * {@code DRAFT_PENDING_ATTESTATION} ({@code approvedForPatientRelease=false}) so an overseeing
     * clinician can review, edit, or reject it prior to patient delivery.</p>
     */
    public PatientCoachingDraftResult generatePersonalizedCoachingDraft(PatientCoachingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("PatientCoachingRequest is required.");
        }
        if (request.overseeingClinicianId() == null || request.overseeingClinicianId().isBlank()) {
            throw new IllegalArgumentException(
                    "overseeingClinicianId is required to enforce full clinician oversight.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.PATIENT_DISCHARGE_COACH);

        String targetLang = (request.targetLanguage() == null || request.targetLanguage().isBlank())
                ? "English" : request.targetLanguage().trim();
        String readingLevel = (request.readingLevel() == null || request.readingLevel().isBlank())
                ? "Plain-language 6th-grade comprehension" : request.readingLevel().trim();
        CoachingChannel channel = request.deliveryChannel() != null
                ? request.deliveryChannel() : CoachingChannel.IN_APP;

        RedactedCoachingContext context = prepareRedactedCoachingContext(
                request.longitudinalFhirResourcesJson(),
                request.patientReportedBarriers()
        );
        if (context.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one longitudinal HL7 FHIR R4 resource or Bundle JSON payload is required.");
        }

        String systemInstruction = """
                You are an empathetic, evidence-based clinical health educator and medication adherence
                coach operating strictly under clinician oversight inside an HL7 FHIR R4 platform.
                Using ONLY the de-identified longitudinal FHIR history, medication adherence gaps, and
                wellness opportunities provided:
                1. Craft warm, supportive, culturally sensitive, jargon-free medication adherence prompts
                   aligned to the patient's active regimen and reported barriers.
                2. Craft achievable, personalized lifestyle and wellness coaching prompts (nutrition,
                   hydration, physical activity, home BP/glucose monitoring, sleep, follow-up reminders).
                3. NEVER alter prescribed dosages, NEVER recommend discontinuing medications, and NEVER
                   invent labs, diagnoses, or medications absent from the FHIR history.
                4. Include concise clinicianReviewNotes highlighting any escalation flags or clinical
                   nuances the overseeing clinician should verify before approving release to the patient.
                5. Include a spokenAudioScript (under 140 words) suitable for low-literacy audio delivery.
                6. Preserve HIPAA Safe-Harbor placeholders verbatim. Output strictly valid JSON matching responseSchema.
                """;

        String prompt = "Target Language: " + targetLang
                + "\nReading Level: " + readingLevel
                + "\nDelivery Channel: " + channel.name()
                + "\nAdherence Risk Level: " + context.adherenceSummary().adherenceRiskLevel()
                + "\nClinician Escalation Flagged: " + context.wellnessSignals().clinicianEscalationRecommended()
                + "\n\nDe-identified Longitudinal Patient Coaching Context:\n"
                + context.redactedPayload().redactedText();

        long totalLatencyMs = 0L;
        TextGenerationResult textResult = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildCoachingSchema(),
                "LOW"
        );
        totalLatencyMs += textResult.latencyMs();
        JsonNode parsedPlan = parseJsonSafely(textResult.text());

        String ttsModelId = null;
        String audioMimeType = null;
        String wavBase64 = null;
        if (request.synthesizeVoiceAudio()) {
            String spokenScript = parsedPlan.path("spokenAudioScript").asText("");
            if (spokenScript.isBlank()) {
                spokenScript = parsedPlan.path("greetingAndEncouragement").asText(textResult.text());
            }
            AudioSynthesisResult ttsResult = geminiClient.synthesizePatientSpeech(
                    spokenScript,
                    request.voiceName(),
                    "Warm, encouraging, clear clinical health coach speaking at a steady pace"
            );
            ttsModelId = ttsResult.modelId();
            audioMimeType = ttsResult.mimeType();
            wavBase64 = ttsResult.base64Audio();
            totalLatencyMs += ttsResult.latencyMs();
        }

        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.PATIENT_DISCHARGE_COACH,
                textResult.modelId(),
                context.redactedPayload().redactedSha256Hex(),
                context.redactedPayload().redactionCountsByCategory(),
                parsedPlan.toString(),
                textResult.tokenUsage(),
                totalLatencyMs,
                request.overseeingClinicianId()
        );

        return new PatientCoachingDraftResult(
                draft.draftId(),
                draft.status().name(),
                false,
                request.overseeingClinicianId(),
                targetLang,
                channel,
                textResult.modelId(),
                ttsModelId,
                context.resourceCountsByType(),
                context.adherenceSummary(),
                context.wellnessSignals(),
                parsedPlan,
                audioMimeType,
                wavBase64,
                context.redactedPayload().totalRedactions(),
                context.redactedPayload().redactionCountsByCategory(),
                totalLatencyMs
        );
    }

    /**
     * Clinician oversight sign-off action: approves (optionally with clinician edits) or rejects a
     * quarantined coaching draft before any message or voice prompt can be released to the patient.
     */
    public ClinicianOversightDecisionResult approveOrRejectCoachingDraft(
            UUID draftId,
            AttestationCommand oversightCommand) {
        ClinicalAiDraftRecord updated = governanceService.attestDraft(draftId, oversightCommand);
        boolean released = updated.status() == DraftAttestationStatus.APPROVED
                || updated.status() == DraftAttestationStatus.APPROVED_WITH_EDITS;
        String finalJson = (updated.clinicianEditedJson() != null && !updated.clinicianEditedJson().isBlank())
                ? updated.clinicianEditedJson()
                : updated.structuredOutputJson();
        return new ClinicianOversightDecisionResult(
                updated.draftId(),
                updated.status(),
                released,
                updated.attestedByPractitionerId(),
                updated.attestationReason(),
                updated.attestedAt(),
                finalJson
        );
    }

    /**
     * Hard programmatic clinician-oversight guardrail: verifies that a coaching draft has been
     * explicitly attested ({@code APPROVED} or {@code APPROVED_WITH_EDITS}) before outbound patient
     * notification delivery.
     */
    public ClinicalAiDraftRecord assertDraftApprovedForPatientRelease(UUID draftId) {
        ClinicalAiDraftRecord record = governanceService.getDraft(draftId);
        if (record.status() != DraftAttestationStatus.APPROVED
                && record.status() != DraftAttestationStatus.APPROVED_WITH_EDITS) {
            throw new IllegalStateException(
                    "Clinician oversight required: coaching draft " + draftId
                            + " cannot be released to the patient while status=" + record.status() + ".");
        }
        return record;
    }

    /**
     * Unwraps longitudinal HL7 FHIR R4 resources/Bundles, extracts deterministic medication
     * adherence &amp; wellness profile signals, harvests direct patient identifiers, strips PII
     * blocks, and applies mandatory HIPAA Safe-Harbor redaction.
     */
    public RedactedCoachingContext prepareRedactedCoachingContext(
            List<String> longitudinalFhirResourcesJson,
            List<String> patientReportedBarriers) {
        List<JsonNode> resources = new ArrayList<>();
        List<String> supplementalTexts = new ArrayList<>();
        parseFhirInputList(longitudinalFhirResourcesJson, resources, supplementalTexts);

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

        MedicationAdherenceSummary adherence = extractMedicationAdherenceSummary(resources, patientReportedBarriers);
        WellnessProfileSignals wellness = extractWellnessProfileSignals(resources, adherence);

        StringBuilder digest = new StringBuilder();
        if (!resources.isEmpty()) {
            digest.append("=== LONGITUDINAL PATIENT FHIR CONTEXT ===\n");
            for (JsonNode node : resources) {
                digest.append(formatSafeFhirLine(node)).append("\n");
            }
        }
        if (patientReportedBarriers != null && !patientReportedBarriers.isEmpty()) {
            digest.append("=== PATIENT-REPORTED BARRIERS ===\n")
                    .append(String.join("; ", patientReportedBarriers)).append("\n");
        }
        digest.append("=== MEDICATION ADHERENCE SUMMARY ===\n")
                .append("activeMedications=").append(adherence.activeMedicationCount())
                .append(", dispensesOrAdmins=").append(adherence.dispenseOrAdministrationCount())
                .append(", complexRegimen=").append(adherence.complexRegimenFlag())
                .append(", riskLevel=").append(adherence.adherenceRiskLevel()).append("\n");
        for (String gap : adherence.adherenceGapSignals()) {
            digest.append("[AdherenceGap] ").append(gap).append("\n");
        }
        digest.append("=== WELLNESS OPPORTUNITIES & OVERSIGHT FLAGS ===\n")
                .append("clinicianEscalationRecommended=").append(wellness.clinicianEscalationRecommended()).append("\n");
        for (String opp : wellness.wellnessOpportunities()) {
            digest.append("[WellnessOpportunity] ").append(opp).append("\n");
        }
        for (String esc : wellness.clinicianEscalationReasons()) {
            digest.append("[ClinicianEscalation] ").append(esc).append("\n");
        }
        for (String extra : supplementalTexts) {
            digest.append("[Supplemental Context] ").append(extra).append("\n");
        }

        RedactionContext ctx = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIds),
                harvestedDob
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digest.toString().trim(), ctx);

        return new RedactedCoachingContext(
                redacted,
                Collections.unmodifiableMap(countsByType),
                adherence,
                wellness,
                resources.size()
        );
    }

    private MedicationAdherenceSummary extractMedicationAdherenceSummary(
            List<JsonNode> resources,
            List<String> patientReportedBarriers) {
        Set<String> activeMeds = new LinkedHashSet<>();
        List<String> gapSignals = new ArrayList<>();
        int dispensesOrAdmins = 0;

        for (JsonNode res : resources) {
            String type = res.path("resourceType").asText("");
            switch (type) {
                case "MedicationRequest", "MedicationStatement" -> {
                    String status = res.path("status").asText("active").toLowerCase(Locale.ROOT);
                    String med = extractCodeableDisplay(res.path("medicationCodeableConcept"));
                    if ("active".equals(status) || "intended".equals(status)) {
                        if (!med.isBlank() && !"unspecified".equalsIgnoreCase(med)) {
                            activeMeds.add(med);
                        }
                    } else if ("on-hold".equals(status) || "stopped".equals(status)) {
                        gapSignals.add("Medication status '" + status + "' recorded for " + med);
                    }
                    String notes = extractNotesPlain(res).toLowerCase(Locale.ROOT);
                    if (NONADHERENCE_KEYWORDS.stream().anyMatch(notes::contains)) {
                        gapSignals.add("Adherence barrier noted on " + med);
                    }
                }
                case "MedicationDispense", "MedicationAdministration" -> {
                    String status = res.path("status").asText("completed").toLowerCase(Locale.ROOT);
                    String med = extractCodeableDisplay(res.path("medicationCodeableConcept"));
                    if ("completed".equals(status) || "in-progress".equals(status)) {
                        dispensesOrAdmins++;
                    } else if ("declined".equals(status) || "not-done".equals(status) || "stopped".equals(status)) {
                        gapSignals.add("Missed dispense/administration (" + status + ") for " + med);
                    }
                }
                default -> {
                    // Checked in wellness extraction
                }
            }
        }

        if (patientReportedBarriers != null) {
            for (String barrier : patientReportedBarriers) {
                if (barrier != null && !barrier.isBlank()) {
                    gapSignals.add("Patient-reported barrier: " + barrier.trim());
                }
            }
        }

        boolean complexRegimen = activeMeds.size() >= 4;
        if (complexRegimen && dispensesOrAdmins == 0) {
            gapSignals.add("Complex regimen (" + activeMeds.size() + " active medications) with no verified recent refill/dispense.");
        }

        AdherenceRiskLevel level;
        if (activeMeds.isEmpty()) {
            level = AdherenceRiskLevel.NO_ACTIVE_REGIMEN;
        } else if (gapSignals.size() >= 2 || (complexRegimen && !gapSignals.isEmpty())) {
            level = AdherenceRiskLevel.HIGH_NONADHERENCE_RISK;
        } else if (!gapSignals.isEmpty() || complexRegimen) {
            level = AdherenceRiskLevel.MODERATE_GAP;
        } else {
            level = AdherenceRiskLevel.OPTIMAL;
        }

        return new MedicationAdherenceSummary(
                activeMeds.size(),
                dispensesOrAdmins,
                gapSignals.size(),
                complexRegimen,
                level,
                List.copyOf(activeMeds),
                List.copyOf(gapSignals)
        );
    }

    private WellnessProfileSignals extractWellnessProfileSignals(
            List<JsonNode> resources,
            MedicationAdherenceSummary adherence) {
        Set<String> conditions = new LinkedHashSet<>();
        Set<String> goals = new LinkedHashSet<>();
        List<String> opportunities = new ArrayList<>();
        List<String> escalations = new ArrayList<>();

        for (JsonNode res : resources) {
            String type = res.path("resourceType").asText("");
            switch (type) {
                case "Condition" -> {
                    String status = extractCodeableDisplay(res.path("clinicalStatus")).toLowerCase(Locale.ROOT);
                    if (!status.contains("resolved") && !status.contains("inactive")) {
                        String display = extractCodeableDisplay(res.path("code"));
                        if (!"unspecified".equalsIgnoreCase(display)) {
                            conditions.add(display);
                        }
                    }
                }
                case "CarePlan" -> {
                    String title = res.path("title").asText("").trim();
                    if (!title.isBlank()) {
                        goals.add(title);
                    }
                }
                case "Goal" -> {
                    String desc = extractCodeableDisplay(res.path("description"));
                    if (!"unspecified".equalsIgnoreCase(desc)) {
                        goals.add(desc);
                    }
                }
                case "Observation" -> {
                    String codeLow = extractCodeableDisplay(res.path("code")).toLowerCase(Locale.ROOT);
                    Double val = extractNumericObservationValue(res);
                    if (val != null) {
                        if (codeLow.contains("hba1c") || codeLow.contains("4548-4")) {
                            if (val >= 10.0) {
                                escalations.add("Markedly elevated HbA1c (" + val + "%); clinician medication review recommended before coaching release.");
                            } else if (val >= 7.5) {
                                opportunities.add("Glycemic nutrition, activity, and glucose self-monitoring coaching (HbA1c " + val + "%).");
                            }
                        } else if (codeLow.contains("systolic") || codeLow.contains("8480-6")) {
                            if (val >= 180.0) {
                                escalations.add("Severe hypertension reading (SBP " + val + " mmHg); urgent clinician review required.");
                            } else if (val >= 140.0) {
                                opportunities.add("DASH sodium reduction and daily home blood pressure logging (SBP " + val + " mmHg).");
                            }
                        } else if (codeLow.contains("bmi") || codeLow.contains("body mass index") || codeLow.contains("39156-5")) {
                            if (val >= 30.0) {
                                opportunities.add("Gradual weight management and structured aerobic activity coaching (BMI " + val + ").");
                            }
                        } else if (codeLow.contains("steps") || codeLow.contains("physical activity")) {
                            if (val < 4000.0) {
                                opportunities.add("Incremental daily walking goal coaching (recent activity " + val.intValue() + " steps/day).");
                            }
                        }
                    }
                }
                case "Appointment" -> {
                    String status = res.path("status").asText("").toLowerCase(Locale.ROOT);
                    if ("booked".equals(status) || "pending".equals(status)) {
                        opportunities.add("Include upcoming clinic follow-up attendance reminder and preparation checklist.");
                    }
                }
                default -> {
                    // Included in digest
                }
            }
        }

        if (adherence.adherenceRiskLevel() == AdherenceRiskLevel.HIGH_NONADHERENCE_RISK) {
            opportunities.add("High-priority medication adherence coaching (pillbox/alarm routines and barrier troubleshooting).");
        }

        return new WellnessProfileSignals(
                List.copyOf(conditions),
                List.copyOf(goals),
                List.copyOf(opportunities),
                !escalations.isEmpty(),
                List.copyOf(escalations)
        );
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
            case "Condition" -> "[Condition] condition=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + extractCodeableDisplay(resource.path("clinicalStatus"))
                    + extractNotesSuffix(resource);
            case "MedicationRequest", "MedicationStatement", "MedicationDispense", "MedicationAdministration" -> "[" + type + "] medication="
                    + extractCodeableDisplay(resource.path("medicationCodeableConcept"))
                    + ", status=" + resource.path("status").asText("active")
                    + ", dosage=" + extractDosageText(resource)
                    + extractNotesSuffix(resource);
            case "Observation" -> "[Observation] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", value=" + extractObservationDisplayValue(resource)
                    + ", status=" + resource.path("status").asText("final")
                    + extractNotesSuffix(resource);
            case "CarePlan" -> "[CarePlan] title=" + resource.path("title").asText("unspecified")
                    + ", status=" + resource.path("status").asText("active");
            case "Goal" -> "[Goal] description=" + extractCodeableDisplay(resource.path("description"))
                    + ", status=" + resource.path("lifecycleStatus").asText("active");
            case "Appointment" -> "[Appointment] status=" + resource.path("status").asText("booked")
                    + ", description=" + resource.path("description").asText("Follow-up visit");
            default -> "[" + type + "] " + resource.toString();
        };
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
        return "as prescribed";
    }

    private String extractNotesPlain(JsonNode resource) {
        JsonNode notes = resource.path("note");
        if (notes.isArray() && !notes.isEmpty()) {
            List<String> texts = new ArrayList<>();
            for (JsonNode n : notes) {
                String t = n.path("text").asText("").trim();
                if (!t.isBlank()) {
                    texts.add(t);
                }
            }
            return String.join(" | ", texts);
        }
        return "";
    }

    private String extractNotesSuffix(JsonNode resource) {
        String plain = extractNotesPlain(resource);
        return !plain.isBlank() ? ", notes=" + plain : "";
    }

    private ObjectNode buildCoachingSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("greetingAndEncouragement", objectMapper.createObjectNode().put("type", "STRING"));

        ObjectNode medPromptObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode medPromptProps = objectMapper.createObjectNode();
        medPromptProps.set("medicationName", objectMapper.createObjectNode().put("type", "STRING"));
        medPromptProps.set("timingCue", objectMapper.createObjectNode().put("type", "STRING"));
        medPromptProps.set("adherencePrompt", objectMapper.createObjectNode().put("type", "STRING"));
        medPromptObj.set("properties", medPromptProps);
        props.set("medicationAdherencePrompts", objectMapper.createObjectNode().put("type", "ARRAY").set("items", medPromptObj));

        ObjectNode wellnessObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode wellnessProps = objectMapper.createObjectNode();
        wellnessProps.set("focusArea", objectMapper.createObjectNode().put("type", "STRING"));
        wellnessProps.set("actionablePrompt", objectMapper.createObjectNode().put("type", "STRING"));
        wellnessProps.set("weeklyCheckInQuestion", objectMapper.createObjectNode().put("type", "STRING"));
        wellnessObj.set("properties", wellnessProps);
        props.set("personalizedWellnessActions", objectMapper.createObjectNode().put("type", "ARRAY").set("items", wellnessObj));

        props.set("barrierMitigationTips", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("whenToContactCareTeam", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("spokenAudioScript", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("clinicianReviewNotes", objectMapper.createObjectNode().put("type", "STRING"));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("greetingAndEncouragement")
                .add("medicationAdherencePrompts")
                .add("personalizedWellnessActions")
                .add("whenToContactCareTeam")
                .add("spokenAudioScript")
                .add("clinicianReviewNotes");
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
