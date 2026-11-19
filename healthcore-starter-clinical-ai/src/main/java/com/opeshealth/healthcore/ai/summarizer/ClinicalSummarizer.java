/*
 * =============================================================================
 * Opes HealthCore SDK — Reusable FHIR R4 ClinicalSummarizer Component (Gemini)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.summarizer;

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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that processes raw
 * HL7 FHIR R4 patient resources (individual resources or FHIR {@code Bundle} payloads) into
 * concise, clinician-ready structured summaries via {@link GeminiClinicalApiClient}.
 *
 * <p><strong>Mandatory PHI Redaction Guarantee:</strong> Before any clinical context is sent to
 * the Gemini API, this component:</p>
 * <ol>
 *   <li>Unwraps FHIR {@code Bundle.entry[].resource} items and harvests explicit direct identifiers
 *       from any {@code Patient} or {@code RelatedPerson} resource ({@code name}, {@code identifier},
 *       {@code telecom}, {@code address}, {@code birthDate}) into a {@link RedactionContext}.</li>
 *   <li>Strips direct-PII demographic sub-trees ({@code photo}, {@code telecom}, {@code address},
 *       {@code contact}) while retaining clinically meaningful attributes (gender, age decade/bracket,
 *       conditions, allergies, medications, vitals, labs, encounters, diagnostic conclusions).</li>
 *   <li>Executes {@link PhiRedactionGateway#redact(String, RedactionContext)} across the entire
 *       assembled clinical digest so direct identifiers embedded inside free-text notes or narratives
 *       are scrubbed into HIPAA Safe-Harbor placeholders.</li>
 *   <li>Quarantines every generated summary in {@link ClinicalAiGovernanceService} under
 *       {@code DRAFT_PENDING_ATTESTATION} for mandatory clinician sign-off.</li>
 * </ol>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalSummarizer {

    public enum ClinicalSummaryStyle {
        CLINICAL_CONCISE,
        SBAR_HANDOFF,
        ED_RAPID_TRIAGE,
        IPS_EXECUTIVE_BRIEF,
        PATIENT_DISCHARGE
    }

    public record ClinicalSummarizationRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            ClinicalSummaryStyle style,
            String targetLanguage,
            List<String> fhirResourceJsonList,
            String supplementalClinicalText
    ) {
        public static ClinicalSummarizationRequest fromSingleFhirJson(
                UUID facilityId,
                UUID patientId,
                String clinicianId,
                ClinicalSummaryStyle style,
                String targetLanguage,
                String fhirResourceOrBundleJson) {
            List<String> list = (fhirResourceOrBundleJson != null && !fhirResourceOrBundleJson.isBlank())
                    ? List.of(fhirResourceOrBundleJson)
                    : List.of();
            return new ClinicalSummarizationRequest(
                    facilityId, patientId, clinicianId, style, targetLanguage, list, null);
        }
    }

    public record RedactedFhirDigest(
            RedactedClinicalPayload redactedPayload,
            Map<String, Integer> resourceCountsByType,
            int totalResourcesProcessed,
            int harvestedPatientIdentifiersCount
    ) {}

    public record ClinicalSummaryResult(
            UUID draftId,
            String attestationStatus,
            ClinicalSummaryStyle style,
            String targetLanguage,
            String modelId,
            Map<String, Integer> processedResourceCountsByType,
            int totalFhirResourcesProcessed,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            String redactedClinicalDigest,
            JsonNode structuredSummary,
            long latencyMs
    ) {}

    private static final Set<String> STRIPPED_PATIENT_PII_FIELDS = Set.of(
            "name", "telecom", "address", "photo", "contact", "identifier", "birthDate"
    );

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public ClinicalSummarizer(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Processes one or more HL7 FHIR R4 patient resources (including FHIR {@code Bundle}s) into
     * a concise, clinician-ready summary using the server-side Gemini API with mandatory HIPAA
     * Safe-Harbor PHI redaction and governance draft registration.
     */
    public ClinicalSummaryResult summarizeFhirResources(ClinicalSummarizationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ClinicalSummarizationRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CHART_AND_IPS_SUMMARIZER);

        RedactedFhirDigest digest = prepareRedactedFhirDigest(
                request.fhirResourceJsonList(),
                request.supplementalClinicalText()
        );
        if (digest.redactedPayload().redactedText().isBlank()) {
            throw new IllegalArgumentException(
                    "At least one FHIR R4 resource JSON payload or supplemental clinical context is required.");
        }

        ClinicalSummaryStyle style = request.style() != null
                ? request.style() : ClinicalSummaryStyle.CLINICAL_CONCISE;
        String language = (request.targetLanguage() == null || request.targetLanguage().isBlank())
                ? "English" : request.targetLanguage().trim();

        String systemInstruction = """
                You are a board-certified clinical summarization engine embedded inside an HL7 FHIR R4 EMR/EHR.
                Synthesize the de-identified patient FHIR resource digest into a concise, high-signal,
                clinician-ready summary tailored to the requested clinical style:
                - CLINICAL_CONCISE: Rapid problem-oriented attending brief (headline, active problems, allergies, meds, key labs/vitals, action items).
                - SBAR_HANDOFF: Structured Situation, Background, Assessment, Recommendation for shift handover.
                - ED_RAPID_TRIAGE: Emergency summary prioritizing life-threatening allergies, red-flag vitals/labs, and active high-risk conditions.
                - IPS_EXECUTIVE_BRIEF: International Patient Summary clinical highlights for cross-border/HIE continuity of care.
                - PATIENT_DISCHARGE: Warm, jargon-free discharge instructions preserving exact medication schedules and return precautions.
                Never invent clinical facts absent from the FHIR digest. Preserve Safe-Harbor placeholders (e.g., [PATIENT_NAME_1]) verbatim.
                Output strictly valid JSON matching the provided responseSchema.
                """;

        String userPrompt = "Summary Style: " + style.name()
                + "\nTarget Language: " + language
                + "\nProcessed FHIR Resource Counts: " + digest.resourceCountsByType()
                + "\n\nDe-identified HL7 FHIR R4 Patient Clinical Digest:\n"
                + digest.redactedPayload().redactedText();

        TextGenerationResult aiResult = geminiClient.generateClinicalContent(
                systemInstruction,
                userPrompt,
                List.of(),
                buildClinicianSummarySchema(),
                "LOW"
        );

        JsonNode structuredSummary = parseJsonSafely(aiResult.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.CHART_AND_IPS_SUMMARIZER,
                aiResult.modelId(),
                digest.redactedPayload().redactedSha256Hex(),
                digest.redactedPayload().redactionCountsByCategory(),
                structuredSummary.toString(),
                aiResult.tokenUsage(),
                aiResult.latencyMs(),
                request.clinicianId()
        );

        return new ClinicalSummaryResult(
                draft.draftId(),
                draft.status().name(),
                style,
                language,
                aiResult.modelId(),
                digest.resourceCountsByType(),
                digest.totalResourcesProcessed(),
                digest.redactedPayload().totalRedactions(),
                digest.redactedPayload().redactionCountsByCategory(),
                digest.redactedPayload().redactedText(),
                structuredSummary,
                aiResult.latencyMs()
        );
    }

    /**
     * Extracts FHIR R4 resources (unwrapping {@code Bundle} entries), harvests explicit patient
     * direct identifiers into a {@link RedactionContext}, removes direct-PII structural fields,
     * and applies mandatory HIPAA Safe-Harbor redaction across the entire assembled digest.
     */
    public RedactedFhirDigest prepareRedactedFhirDigest(
            List<String> fhirResourceJsonList,
            String supplementalClinicalText) {
        List<JsonNode> flattenedResources = new ArrayList<>();
        List<String> nonJsonClinicalNotes = new ArrayList<>();

        if (fhirResourceJsonList != null) {
            for (String raw : fhirResourceJsonList) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    JsonNode root = objectMapper.readTree(raw);
                    flattenFhirNode(root, flattenedResources);
                } catch (Exception ex) {
                    // Treat non-JSON input safely as supplemental clinical narrative text (still fully redacted)
                    nonJsonClinicalNotes.add(raw.trim());
                }
            }
        }
        if (supplementalClinicalText != null && !supplementalClinicalText.isBlank()) {
            nonJsonClinicalNotes.add(supplementalClinicalText.trim());
        }

        Set<String> harvestedNames = new LinkedHashSet<>();
        Set<String> harvestedIdentifiers = new LinkedHashSet<>();
        String harvestedBirthDate = null;
        Map<String, Integer> countsByType = new LinkedHashMap<>();

        for (JsonNode res : flattenedResources) {
            String type = res.path("resourceType").asText("UnknownResource");
            countsByType.merge(type, 1, Integer::sum);

            if ("Patient".equals(type) || "RelatedPerson".equals(type)) {
                harvestPatientDirectIdentifiers(res, harvestedNames, harvestedIdentifiers);
                if (harvestedBirthDate == null && res.hasNonNull("birthDate")) {
                    String dob = res.path("birthDate").asText("").trim();
                    if (!dob.isBlank()) {
                        harvestedBirthDate = dob;
                    }
                }
            }
        }

        StringBuilder digestBuilder = new StringBuilder();
        for (JsonNode res : flattenedResources) {
            digestBuilder.append(formatResourceLine(res)).append("\n");
        }
        for (String note : nonJsonClinicalNotes) {
            digestBuilder.append("[Supplemental Clinical Context] ").append(note).append("\n");
        }

        RedactionContext redactionContext = new RedactionContext(
                List.copyOf(harvestedNames),
                List.copyOf(harvestedIdentifiers),
                harvestedBirthDate
        );
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(digestBuilder.toString().trim(), redactionContext);

        return new RedactedFhirDigest(
                redacted,
                Collections.unmodifiableMap(countsByType),
                flattenedResources.size(),
                harvestedNames.size() + harvestedIdentifiers.size() + (harvestedBirthDate != null ? 1 : 0)
        );
    }

    private void flattenFhirNode(JsonNode node, List<JsonNode> sink) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                flattenFhirNode(child, sink);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        String resourceType = node.path("resourceType").asText("");
        if ("Bundle".equals(resourceType) && node.path("entry").isArray()) {
            for (JsonNode entry : node.path("entry")) {
                JsonNode embedded = entry.path("resource");
                if (embedded.isObject()) {
                    flattenFhirNode(embedded, sink);
                }
            }
            return;
        }
        sink.add(node);
    }

    private void harvestPatientDirectIdentifiers(
            JsonNode patientNode,
            Set<String> names,
            Set<String> identifiers) {
        JsonNode nameArray = patientNode.path("name");
        if (nameArray.isArray()) {
            for (JsonNode hn : nameArray) {
                String textName = hn.path("text").asText("").trim();
                if (textName.length() >= 2) {
                    names.add(textName);
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

        JsonNode idArray = patientNode.path("identifier");
        if (idArray.isArray()) {
            for (JsonNode idObj : idArray) {
                String val = idObj.path("value").asText("").trim();
                if (val.length() >= 3) {
                    identifiers.add(val);
                }
            }
        }

        JsonNode telecomArray = patientNode.path("telecom");
        if (telecomArray.isArray()) {
            for (JsonNode tel : telecomArray) {
                String val = tel.path("value").asText("").trim();
                if (val.length() >= 4) {
                    identifiers.add(val);
                }
            }
        }

        JsonNode addressArray = patientNode.path("address");
        if (addressArray.isArray()) {
            for (JsonNode addr : addressArray) {
                if (addr.path("line").isArray()) {
                    for (JsonNode line : addr.path("line")) {
                        String lv = line.asText("").trim();
                        if (lv.length() >= 4) {
                            identifiers.add(lv);
                        }
                    }
                }
                String postal = addr.path("postalCode").asText("").trim();
                if (postal.length() >= 4) {
                    identifiers.add(postal);
                }
            }
        }
    }

    private String formatResourceLine(JsonNode resource) {
        String type = resource.path("resourceType").asText("Resource");
        return switch (type) {
            case "Patient" -> {
                String gender = resource.path("gender").asText("unknown");
                String birthYear = extractSafeBirthYear(resource.path("birthDate").asText(""));
                ObjectNode stripped = resource.deepCopy();
                STRIPPED_PATIENT_PII_FIELDS.forEach(stripped::remove);
                yield "[Patient] gender=" + gender + ", birthYear=" + birthYear
                        + ", active=" + resource.path("active").asText("true");
            }
            case "AllergyIntolerance" -> "[AllergyIntolerance] substance=" + extractCodeableDisplay(resource.path("code"))
                    + ", criticality=" + resource.path("criticality").asText("unspecified")
                    + ", clinicalStatus=" + extractCodeableDisplay(resource.path("clinicalStatus"))
                    + extractNotesSuffix(resource);
            case "Condition" -> "[Condition] condition=" + extractCodeableDisplay(resource.path("code"))
                    + ", clinicalStatus=" + extractCodeableDisplay(resource.path("clinicalStatus"))
                    + ", severity=" + extractCodeableDisplay(resource.path("severity"))
                    + extractNotesSuffix(resource);
            case "MedicationRequest", "MedicationStatement" -> "[" + type + "] medication="
                    + extractCodeableDisplay(resource.path("medicationCodeableConcept"))
                    + ", status=" + resource.path("status").asText("active")
                    + ", dosage=" + extractDosageText(resource)
                    + extractNotesSuffix(resource);
            case "Observation" -> "[Observation] code=" + extractCodeableDisplay(resource.path("code"))
                    + ", value=" + extractObservationValue(resource)
                    + ", interpretation=" + extractFirstCodeableDisplay(resource.path("interpretation"))
                    + ", status=" + resource.path("status").asText("final")
                    + extractNotesSuffix(resource);
            case "Encounter" -> "[Encounter] status=" + resource.path("status").asText("unknown")
                    + ", class=" + resource.path("class").path("code").asText("AMB")
                    + ", reason=" + extractFirstCodeableDisplay(resource.path("reasonCode"));
            case "DiagnosticReport" -> "[DiagnosticReport] report=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + resource.path("status").asText("final")
                    + ", conclusion=" + resource.path("conclusion").asText("N/A");
            case "Immunization" -> "[Immunization] vaccine=" + extractCodeableDisplay(resource.path("vaccineCode"))
                    + ", status=" + resource.path("status").asText("completed");
            case "Procedure" -> "[Procedure] procedure=" + extractCodeableDisplay(resource.path("code"))
                    + ", status=" + resource.path("status").asText("completed")
                    + extractNotesSuffix(resource);
            default -> "[" + type + "] " + resource.toString();
        };
    }

    private String extractSafeBirthYear(String birthDate) {
        if (birthDate != null && birthDate.length() >= 4) {
            return birthDate.substring(0, 4);
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

    private String extractFirstCodeableDisplay(JsonNode arrayNode) {
        if (arrayNode != null && arrayNode.isArray() && !arrayNode.isEmpty()) {
            return extractCodeableDisplay(arrayNode.get(0));
        }
        return "unspecified";
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

    private String extractObservationValue(JsonNode obs) {
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
        if (obs.path("component").isArray() && !obs.path("component").isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode comp : obs.path("component")) {
                parts.add(extractCodeableDisplay(comp.path("code")) + "=" + extractObservationValue(comp));
            }
            return String.join("; ", parts);
        }
        return "unspecified";
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

    private ObjectNode buildClinicianSummarySchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();
        props.set("headline", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("situation", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("background", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("assessment", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("recommendation", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("keyActiveProblems", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("criticalAllergiesAndAlerts", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("activeMedications", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("pertinentLabsAndVitals", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("pendingFollowUps", objectMapper.createObjectNode()
                .put("type", "ARRAY").set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("clinicianVerificationRequired", objectMapper.createObjectNode().put("type", "BOOLEAN"));
        root.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode()
                .add("headline").add("situation").add("background").add("assessment")
                .add("recommendation").add("clinicianVerificationRequired");
        root.set("required", required);
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
