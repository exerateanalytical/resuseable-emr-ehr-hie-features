/*
 * =============================================================================
 * Opes HealthCore SDK — Automated Medical Coding & DRG/UHC Billing AI Service
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
 * Capability 2: Automated Medical Coding &amp; DRG / UHC Billing Assistant.
 *
 * <p>Extracts ICD-10-CM, ICD-11, SNOMED CT, LOINC, and CPT procedure codes with exact supporting
 * clinical text spans and DRG/UHC complexity rationale from de-identified clinical documentation.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class MedicalCodingAiService {

    public record MedicalCodingRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String coderOrClinicianId,
            String careSetting,
            String clinicalNoteText,
            List<String> requestedCodeSystems
    ) {}

    public record MedicalCodingDraftResponse(
            UUID draftId,
            String attestationStatus,
            String modelId,
            JsonNode suggestedCodesAndEvidence,
            int totalPhiRedactions,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public MedicalCodingAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_MEDICAL_CODING_SUGGEST", resourceType = "Encounter")
    public MedicalCodingDraftResponse suggestMedicalCodes(MedicalCodingRequest request) {
        if (request == null || request.clinicalNoteText() == null || request.clinicalNoteText().isBlank()) {
            throw new IllegalArgumentException("clinicalNoteText is required for medical coding.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.AUTOMATED_MEDICAL_CODING);

        RedactedClinicalPayload redacted = phiRedactionGateway.redact(request.clinicalNoteText());
        List<String> systems = (request.requestedCodeSystems() == null || request.requestedCodeSystems().isEmpty())
                ? List.of("ICD-10", "ICD-11", "SNOMED-CT", "LOINC", "CPT")
                : request.requestedCodeSystems();

        String systemInstruction = """
                You are a certified clinical coder for an enterprise hospital EMR and UHC billing engine.
                Extract accurate diagnosis, procedure, and observation codes strictly supported by the
                de-identified clinical note. For every code, quote the exact supporting evidence span
                from the note and flag any documentation gaps (CDI queries) needed to justify billing.
                Respond strictly as JSON matching the schema.
                """;

        String prompt = "Care Setting: " + Objects.requireNonNullElse(request.careSetting(), "INPATIENT_OR_OUTPATIENT")
                + "\nTarget Code Systems: " + String.join(", ", systems)
                + "\nDe-identified Clinical Documentation:\n" + redacted.redactedText();

        TextGenerationResult res = geminiClient.generateClinicalContent(
                systemInstruction, prompt, List.of(), buildCodingSchema(), "MEDIUM");

        JsonNode parsed = parseJsonSafely(res.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.AUTOMATED_MEDICAL_CODING,
                res.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                parsed.toString(),
                res.tokenUsage(),
                res.latencyMs(),
                request.coderOrClinicianId()
        );

        return new MedicalCodingDraftResponse(
                draft.draftId(),
                draft.status().name(),
                res.modelId(),
                parsed,
                redacted.totalRedactions(),
                res.latencyMs()
        );
    }

    private ObjectNode buildCodingSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        ObjectNode codeEntry = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode entryProps = objectMapper.createObjectNode();
        entryProps.set("codeSystem", objectMapper.createObjectNode().put("type", "STRING"));
        entryProps.set("code", objectMapper.createObjectNode().put("type", "STRING"));
        entryProps.set("display", objectMapper.createObjectNode().put("type", "STRING"));
        entryProps.set("evidenceSpan", objectMapper.createObjectNode().put("type", "STRING"));
        entryProps.set("confidenceScore", objectMapper.createObjectNode().put("type", "NUMBER"));
        entryProps.set("primaryDiagnosis", objectMapper.createObjectNode().put("type", "BOOLEAN"));
        codeEntry.set("properties", entryProps);

        props.set("extractedCodes", objectMapper.createObjectNode().put("type", "ARRAY").set("items", codeEntry));
        props.set("suggestedDrgOrUhcTariffGroup", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("clinicalDocumentationQueries", objectMapper.createObjectNode()
                .put("type", "ARRAY")
                .set("items", objectMapper.createObjectNode().put("type", "STRING")));

        root.set("properties", props);
        ArrayNode req = objectMapper.createArrayNode().add("extractedCodes").add("suggestedDrgOrUhcTariffGroup");
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
