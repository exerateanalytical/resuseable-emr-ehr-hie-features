/*
 * =============================================================================
 * Opes HealthCore SDK — Multimodal Diagnostic & Clinical Image Triage Service
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
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.InlineBinaryPart;
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
 * Capability 4: Multimodal Diagnostic &amp; Clinical Image Triage Assistant.
 *
 * <p>Processes wound photographs, dermatology captures, scanned laboratory slips, and ECG strips
 * alongside redacted patient vitals via {@code gemini-4-hydrogen} multimodal input, producing
 * structured draft findings strictly for clinician review and attestation.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class MultimodalImageTriageAiService {

    public enum ClinicalImageModality {
        WOUND_PHOTO,
        DERMATOLOGY_CAPTURE,
        SCANNED_LAB_SLIP,
        ECG_STRIP,
        RADIOLOGY_PRELIMINARY_TRIAGE
    }

    public record ImageTriageRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            ClinicalImageModality modality,
            String imageMimeType,
            String imageBase64Data,
            String clinicalContextAndVitals
    ) {}

    public record ImageTriageDraftResponse(
            UUID draftId,
            String attestationStatus,
            ClinicalImageModality modality,
            String modelId,
            JsonNode structuredTriageFindings,
            int totalPhiRedactions,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public MultimodalImageTriageAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_MULTIMODAL_IMAGE_TRIAGE", resourceType = "DiagnosticReport")
    public ImageTriageDraftResponse analyzeClinicalImage(ImageTriageRequest request) {
        if (request == null || request.imageBase64Data() == null || request.imageBase64Data().isBlank()) {
            throw new IllegalArgumentException("imageBase64Data is required for multimodal clinical triage.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.MULTIMODAL_IMAGE_TRIAGE);

        ClinicalImageModality modality = request.modality() != null
                ? request.modality() : ClinicalImageModality.WOUND_PHOTO;
        String mimeType = (request.imageMimeType() == null || request.imageMimeType().isBlank())
                ? "image/jpeg" : request.imageMimeType().trim();
        RedactedClinicalPayload redactedContext = phiRedactionGateway.redact(
                Objects.requireNonNullElse(request.clinicalContextAndVitals(), "No supplemental vitals provided."));

        String systemInstruction = """
                You are a clinical decision support vision assistant for licensed clinicians.
                Analyze the provided clinical image and de-identified vitals context to produce
                structured observations, visual quality assessment, red-flag urgency cues, and
                recommended clinician verification steps. Never issue autonomous diagnoses; clearly
                mark all findings as preliminary clinician-review drafts. Output strictly JSON.
                """;

        String prompt = "Clinical Image Modality: " + modality.name()
                + "\nDe-identified Vitals & History:\n" + redactedContext.redactedText();

        TextGenerationResult res = geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(new InlineBinaryPart(mimeType, request.imageBase64Data())),
                buildImageTriageSchema(),
                "MEDIUM"
        );

        JsonNode parsed = parseJsonSafely(res.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.MULTIMODAL_IMAGE_TRIAGE,
                res.modelId(),
                redactedContext.redactedSha256Hex(),
                redactedContext.redactionCountsByCategory(),
                parsed.toString(),
                res.tokenUsage(),
                res.latencyMs(),
                request.clinicianId()
        );

        return new ImageTriageDraftResponse(
                draft.draftId(),
                draft.status().name(),
                modality,
                res.modelId(),
                parsed,
                redactedContext.totalRedactions(),
                res.latencyMs()
        );
    }

    private ObjectNode buildImageTriageSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("imageQualityAssessment", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("urgencyTier", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("visualObservations", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("extractedMeasurementsOrLabValues", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("redFlagWarnings", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("recommendedClinicianNextSteps", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("clinicianReviewDisclaimer", objectMapper.createObjectNode().put("type", "STRING"));
        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("imageQualityAssessment").add("urgencyTier").add("visualObservations")
                .add("recommendedClinicianNextSteps").add("clinicianReviewDisclaimer");
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
