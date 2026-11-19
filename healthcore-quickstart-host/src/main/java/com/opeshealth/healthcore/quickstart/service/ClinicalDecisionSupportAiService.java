/*
 * =============================================================================
 * Opes HealthCore SDK — AI Clinical Decision Support & Differential Assistant
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
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 5: AI Clinical Decision Support (CDS) &amp; Differential Assistant.
 *
 * <p>Synthesizes vitals, laboratory panels, active medications, allergies, and pharmacogenomics
 * profiles via {@code gemini-4-hydrogen} to highlight drug-disease/drug-gene alerts, sepsis risk
 * cues, differential considerations, and guideline-referenced workup suggestions strictly under
 * clinician human-in-the-loop attestation.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ClinicalDecisionSupportAiService {

    public record ClinicalCdsRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            String chiefComplaintAndHistory,
            Map<String, String> vitals,
            Map<String, String> labResults,
            List<String> activeMedications,
            List<String> documentedAllergies,
            List<String> pharmacogenomicsPhenotypes
    ) {}

    public record ClinicalCdsDraftResponse(
            UUID draftId,
            String attestationStatus,
            String modelId,
            JsonNode cdsAdvisoryPayload,
            int totalPhiRedactions,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public ClinicalDecisionSupportAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_CDS_ADVISORY", resourceType = "Patient")
    public ClinicalCdsDraftResponse evaluateClinicalDecisionSupport(ClinicalCdsRequest request) {
        if (request == null || request.chiefComplaintAndHistory() == null || request.chiefComplaintAndHistory().isBlank()) {
            throw new IllegalArgumentException("chiefComplaintAndHistory is required for AI CDS evaluation.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CLINICAL_DECISION_SUPPORT);

        StringBuilder composite = new StringBuilder();
        composite.append("Chief Complaint & History: ").append(request.chiefComplaintAndHistory()).append("\n");
        composite.append("Vitals: ").append(Objects.requireNonNullElse(request.vitals(), Map.of())).append("\n");
        composite.append("Labs: ").append(Objects.requireNonNullElse(request.labResults(), Map.of())).append("\n");
        composite.append("Active Medications: ").append(Objects.requireNonNullElse(request.activeMedications(), List.of())).append("\n");
        composite.append("Allergies: ").append(Objects.requireNonNullElse(request.documentedAllergies(), List.of())).append("\n");
        composite.append("Pharmacogenomics (CPIC): ").append(Objects.requireNonNullElse(request.pharmacogenomicsPhenotypes(), List.of()));

        RedactedClinicalPayload redacted = phiRedactionGateway.redact(composite.toString());

        String systemInstruction = """
                You are an evidence-based Clinical Decision Support (CDS) advisor for licensed physicians.
                Analyze the de-identified vitals, labs, medications, allergies, and pharmacogenomics profile.
                Surface:
                1. High-priority safety alerts (drug-drug, drug-allergy, drug-disease, CPIC pharmacogenomics, sepsis/qSOFA/NEWS2 cues).
                2. Ranked differential considerations with clinical reasoning.
                3. Guideline-aligned diagnostic workup suggestions (WHO / NICE / IDSA / ACC-AHA).
                This is strictly advisory; require clinician judgment and sign-off. Return valid JSON matching schema.
                """;

        TextGenerationResult res = geminiClient.generateClinicalContent(
                systemInstruction,
                redacted.redactedText(),
                List.of(),
                buildCdsSchema(),
                "MEDIUM"
        );

        JsonNode parsed = parseJsonSafely(res.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.CLINICAL_DECISION_SUPPORT,
                res.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                parsed.toString(),
                res.tokenUsage(),
                res.latencyMs(),
                request.clinicianId()
        );

        return new ClinicalCdsDraftResponse(
                draft.draftId(),
                draft.status().name(),
                res.modelId(),
                parsed,
                redacted.totalRedactions(),
                res.latencyMs()
        );
    }

    private ObjectNode buildCdsSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode alertObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode alertProps = objectMapper.createObjectNode();
        alertProps.set("severity", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("category", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("summary", objectMapper.createObjectNode().put("type", "STRING"));
        alertProps.set("recommendedAction", objectMapper.createObjectNode().put("type", "STRING"));
        alertObj.set("properties", alertProps);
        props.set("safetyAlerts", objectMapper.createObjectNode().put("type", "ARRAY").set("items", alertObj));

        ObjectNode diffObj = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode diffProps = objectMapper.createObjectNode();
        diffProps.set("condition", objectMapper.createObjectNode().put("type", "STRING"));
        diffProps.set("likelihoodTier", objectMapper.createObjectNode().put("type", "STRING"));
        diffProps.set("supportingCues", objectMapper.createObjectNode().put("type", "STRING"));
        diffObj.set("properties", diffProps);
        props.set("differentialConsiderations", objectMapper.createObjectNode().put("type", "ARRAY").set("items", diffObj));

        props.set("suggestedWorkup", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("sepsisRiskFlag", objectMapper.createObjectNode().put("type", "BOOLEAN"));
        props.set("guidelineReferences", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("safetyAlerts").add("differentialConsiderations").add("suggestedWorkup").add("sepsisRiskFlag");
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
