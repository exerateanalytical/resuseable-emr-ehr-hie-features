/*
 * =============================================================================
 * Opes HealthCore SDK — Multilingual Patient Discharge & Care-Gap Coach (Text + Voice)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.AudioSynthesisResult;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 8: Multilingual Patient Discharge &amp; Care-Gap Coach (Text + Voice).
 *
 * <p>Translates complex discharge summaries, medication schedules, and care-gap reminders into
 * plain-language English, French, or culturally adapted patient guidance via {@code gemini-4-hydrogen},
 * and optionally synthesizes a ready-to-play {@code audio/wav} voice guide via
 * {@code gemini-3.8-flash-lite-tts} for low-literacy patients, IVR, or WhatsApp delivery.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class PatientDischargeCoachAiService {

    public record PatientCoachRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String targetLanguage,
            String readingLevel,
            String clinicalDischargePlan,
            List<String> medicationSchedule,
            List<String> warningSignsToReturn,
            boolean synthesizeVoiceAudio,
            String voiceName
    ) {}

    public record PatientCoachDraftResponse(
            UUID draftId,
            String attestationStatus,
            String targetLanguage,
            String textModelId,
            String ttsModelId,
            JsonNode patientFriendlyPlan,
            String audioMimeType,
            String wavAudioBase64,
            int totalPhiRedactions,
            long totalLatencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public PatientDischargeCoachAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_PATIENT_DISCHARGE_COACH", resourceType = "Communication")
    public PatientCoachDraftResponse generateDischargeCoaching(PatientCoachRequest request) {
        if (request == null || request.clinicalDischargePlan() == null || request.clinicalDischargePlan().isBlank()) {
            throw new IllegalArgumentException("clinicalDischargePlan is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.PATIENT_DISCHARGE_COACH);

        String targetLang = (request.targetLanguage() == null || request.targetLanguage().isBlank())
                ? "French" : request.targetLanguage().trim();
        String readingLevel = (request.readingLevel() == null || request.readingLevel().isBlank())
                ? "Plain-language 6th-grade comprehension" : request.readingLevel().trim();

        StringBuilder source = new StringBuilder();
        source.append("Discharge Plan:\n").append(request.clinicalDischargePlan()).append("\n");
        if (request.medicationSchedule() != null && !request.medicationSchedule().isEmpty()) {
            source.append("Medications:\n").append(String.join("\n", request.medicationSchedule())).append("\n");
        }
        if (request.warningSignsToReturn() != null && !request.warningSignsToReturn().isEmpty()) {
            source.append("Emergency Return Warning Signs:\n").append(String.join("\n", request.warningSignsToReturn()));
        }

        RedactedClinicalPayload redacted = phiRedactionGateway.redact(source.toString());

        String systemInstruction = """
                You are an empathetic bilingual healthcare educator (English & French clinical contexts).
                Rewrite the de-identified hospital discharge plan and medication schedule into warm,
                crystal-clear, jargon-free patient instructions in the target language.
                Include a concise spoken script (under 150 words) suitable for audio playback.
                Do not alter medication dosages or omit emergency warning signs. Respond strictly as JSON.
                """;

        String prompt = "Target Language: " + targetLang
                + "\nReading Level: " + readingLevel
                + "\nDe-identified Discharge Instructions:\n" + redacted.redactedText();

        TextGenerationResult textResult = geminiClient.generateClinicalContent(
                systemInstruction, prompt, List.of(), buildCoachSchema(), "LOW");
        long totalLatencyMs = textResult.latencyMs();
        JsonNode parsedPlan = parseJsonSafely(textResult.text());

        String ttsModelId = null;
        String audioMimeType = null;
        String wavBase64 = null;

        if (request.synthesizeVoiceAudio()) {
            String spokenScript = parsedPlan.path("spokenAudioScript").asText("");
            if (spokenScript.isBlank()) {
                spokenScript = parsedPlan.path("summaryMessage").asText(textResult.text());
            }
            AudioSynthesisResult ttsResult = geminiClient.synthesizePatientSpeech(
                    spokenScript,
                    request.voiceName(),
                    "Warm, calm, reassuring clinical nurse speaking clearly for a recovering patient"
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
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                parsedPlan.toString(),
                textResult.tokenUsage(),
                totalLatencyMs,
                request.clinicianId()
        );

        return new PatientCoachDraftResponse(
                draft.draftId(),
                draft.status().name(),
                targetLang,
                textResult.modelId(),
                ttsModelId,
                parsedPlan,
                audioMimeType,
                wavBase64,
                redacted.totalRedactions(),
                totalLatencyMs
        );
    }

    private ObjectNode buildCoachSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("summaryMessage", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("dailyMedicationSteps", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("whenToSeekEmergencyCare", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("followUpReminder", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("spokenAudioScript", objectMapper.createObjectNode().put("type", "STRING"));
        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("summaryMessage").add("dailyMedicationSteps")
                .add("whenToSeekEmergencyCare").add("spokenAudioScript");
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
