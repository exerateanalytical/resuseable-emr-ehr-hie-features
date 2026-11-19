/*
 * =============================================================================
 * Opes HealthCore SDK — Prior Authorization & Claim Denial Appeal AI Service
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
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 6: Prior Authorization Medical Necessity &amp; Claim Denial Appeal Writer.
 *
 * <p>Generates payer-ready Da Vinci PAS medical necessity narratives and CARC/RARC claim denial
 * appeal letters grounded in de-identified patient chart evidence, lab values, and prior
 * conservative therapy history.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class RevenueCycleAiService {

    public enum RevenueDocumentType {
        PRIOR_AUTH_MEDICAL_NECESSITY,
        CARC_RARC_DENIAL_APPEAL
    }

    public record RevenueNarrativeRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianOrBillerId,
            RevenueDocumentType documentType,
            String payerName,
            String requestedProcedureOrDrugCode,
            String denialCarcRarcCode,
            String denialReasonSummary,
            String clinicalChartEvidenceText
    ) {}

    public record RevenueNarrativeDraftResponse(
            UUID draftId,
            String attestationStatus,
            RevenueDocumentType documentType,
            String modelId,
            JsonNode structuredNarrative,
            int totalPhiRedactions,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public RevenueCycleAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_REVENUE_NARRATIVE_DRAFT", resourceType = "Claim")
    public RevenueNarrativeDraftResponse draftPriorAuthOrAppeal(RevenueNarrativeRequest request) {
        if (request == null || request.clinicalChartEvidenceText() == null || request.clinicalChartEvidenceText().isBlank()) {
            throw new IllegalArgumentException("clinicalChartEvidenceText is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.PRIOR_AUTH_AND_APPEALS);

        RevenueDocumentType docType = request.documentType() != null
                ? request.documentType() : RevenueDocumentType.PRIOR_AUTH_MEDICAL_NECESSITY;
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(request.clinicalChartEvidenceText());

        String systemInstruction = """
                You are a clinical utilization management and payer appeals specialist.
                Draft a concise, authoritative, evidence-grounded medical necessity narrative or
                CARC/RARC claim denial appeal letter strictly citing facts from the de-identified
                clinical chart. Highlight failed prior conservative treatments, objective lab/imaging
                findings, and missing documentation checklist items. Output strictly valid JSON.
                """;

        String prompt = "Document Type: " + docType.name()
                + "\nPayer: " + Objects.requireNonNullElse(request.payerName(), "National UHC / Commercial Payer")
                + "\nRequested Service/Drug Code: " + Objects.requireNonNullElse(request.requestedProcedureOrDrugCode(), "N/A")
                + "\nDenial CARC/RARC Code: " + Objects.requireNonNullElse(request.denialCarcRarcCode(), "NONE")
                + "\nDenial Summary: " + Objects.requireNonNullElse(request.denialReasonSummary(), "N/A")
                + "\nDe-identified Clinical Evidence:\n" + redacted.redactedText();

        TextGenerationResult res = geminiClient.generateClinicalContent(
                systemInstruction, prompt, List.of(), buildRevenueSchema(), "MEDIUM");

        JsonNode parsed = parseJsonSafely(res.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.PRIOR_AUTH_AND_APPEALS,
                res.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                parsed.toString(),
                res.tokenUsage(),
                res.latencyMs(),
                request.clinicianOrBillerId()
        );

        return new RevenueNarrativeDraftResponse(
                draft.draftId(),
                draft.status().name(),
                docType,
                res.modelId(),
                parsed,
                redacted.totalRedactions(),
                res.latencyMs()
        );
    }

    private ObjectNode buildRevenueSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("subjectLine", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("narrativeLetterBody", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("clinicalEvidenceBulletPoints", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("policyCriteriaAddressed", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("missingAttachmentChecklist", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode()
                .add("subjectLine").add("narrativeLetterBody").add("clinicalEvidenceBulletPoints");
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
