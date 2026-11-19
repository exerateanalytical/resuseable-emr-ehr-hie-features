/*
 * =============================================================================
 * Opes HealthCore SDK — Calibrated Clinical Triage & Policy Decision Engine
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
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.DecisionEvaluationResult;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.DecisionQuestion;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.InlineBinaryPart;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 7: Calibrated Clinical Triage &amp; Policy Decision Engine using {@code gemini-decision-1}
 * via the Gemini Decisions API ({@code POST /v1beta/decisions}, zero generated output tokens).
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Emergency Severity Index (ESI 1–5) calibrated {@code choice} routing</li>
 *   <li>Immediate sepsis / life-threat escalation calibrated {@code noul} probability gates</li>
 *   <li>Prior authorization medical necessity ordered rubric {@code score} evaluation</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class CalibratedClinicalDecisionService {

    public record EmergencyTriageDecisionRequest(
            UUID facilityId,
            UUID patientId,
            String triageNurseId,
            String chiefComplaintAndVitals,
            String optionalImageMimeType,
            String optionalImageBase64
    ) {}

    public record PriorAuthPolicyRubricRequest(
            UUID facilityId,
            UUID patientId,
            String reviewerId,
            String requestedProcedureOrTherapy,
            String payerPolicySummary,
            String clinicalEvidenceText
    ) {}

    public record CalibratedDecisionResponse(
            UUID draftId,
            String attestationStatus,
            String modelId,
            Map<String, JsonNode> calibratedAnswers,
            String recommendedRoutingAction,
            int totalPhiRedactions,
            long latencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public CalibratedClinicalDecisionService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
        this.objectMapper = geminiClient.getObjectMapper();
    }

    @ClinicalAudit(action = "CLINICAL_AI_CALIBRATED_ESI_TRIAGE", resourceType = "Encounter")
    public CalibratedDecisionResponse evaluateEmergencyTriage(EmergencyTriageDecisionRequest request) {
        if (request == null || request.chiefComplaintAndVitals() == null || request.chiefComplaintAndVitals().isBlank()) {
            throw new IllegalArgumentException("chiefComplaintAndVitals is required for calibrated ESI triage.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CALIBRATED_CLINICAL_DECISION);

        RedactedClinicalPayload redacted = phiRedactionGateway.redact(request.chiefComplaintAndVitals());

        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        questions.put("immediate_life_threat", new DecisionQuestion(
                "noul",
                "Does the patient presentation indicate an immediate life-threatening emergency requiring resuscitation or immediate physician intervention?",
                Map.of(
                        "true_description", "Airway compromise, shock, severe hypoxia, active severe hemorrhage, stroke signs, or unresponsiveness.",
                        "false_description", "Hemodynamically stable presentation that can safely undergo standard triage staging."
                )
        ));
        questions.put("sepsis_escalation_gate", new DecisionQuestion(
                "noul",
                "Do the documented symptoms and vitals indicate suspected sepsis or septic shock warranting immediate sepsis bundle activation?",
                Map.of(
                        "true_description", "Suspected infection with tachycardia, hypotension, tachypnea, altered mental status, or fever/hypothermia.",
                        "false_description", "No systemic inflammatory or organ dysfunction cues for sepsis."
                )
        ));
        questions.put("esi_acuity_level", new DecisionQuestion(
                "choice",
                "Assign the most appropriate Emergency Severity Index (ESI) triage acuity level for this patient.",
                Map.of(
                        "esi_1_resuscitation", "Immediate life-saving intervention required right now.",
                        "esi_2_emergent", "High-risk situation, confused/lethargic, severe pain/distress, or dangerous vital signs.",
                        "esi_3_urgent", "Stable vitals but requires two or more diagnostic/therapeutic resources.",
                        "esi_4_less_urgent", "Stable patient requiring one simple resource (e.g., single lab or X-ray).",
                        "esi_5_non_urgent", "Stable patient requiring zero ED resources (routine clinic follow-up)."
                )
        ));
        questions.put("clinical_deterioration_risk", new DecisionQuestion(
                "score",
                "Rate the patient's short-term risk of clinical deterioration in the waiting room over the next 2 hours.",
                List.of(
                        "Minimal risk: stable minor complaint with normal vital signs.",
                        "Low risk: mild symptoms with reassuring vitals.",
                        "Moderate risk: abnormal single vital sign or comorbidity requiring timely monitoring.",
                        "High risk: multiple abnormal vitals or high-risk chest/neuro/sepsis complaint.",
                        "Critical risk: imminent decompensation requiring immediate bedside monitoring."
                )
        ));

        List<InlineBinaryPart> media = (request.optionalImageBase64() != null && !request.optionalImageBase64().isBlank())
                ? List.of(new InlineBinaryPart(
                        Objects.requireNonNullElse(request.optionalImageMimeType(), "image/jpeg"),
                        request.optionalImageBase64()))
                : List.of();

        DecisionEvaluationResult result = geminiClient.evaluateClinicalDecisions(
                redacted.redactedText(), media, questions);

        String routing = deriveTriageRoutingAction(result.answers());
        String serialized = serializeAnswersSafely(result.answers());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.CALIBRATED_CLINICAL_DECISION,
                result.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                serialized,
                new TokenUsage(0, 0, 0),
                result.latencyMs(),
                request.triageNurseId()
        );

        return new CalibratedDecisionResponse(
                draft.draftId(),
                draft.status().name(),
                result.modelId(),
                result.answers(),
                routing,
                redacted.totalRedactions(),
                result.latencyMs()
        );
    }

    @ClinicalAudit(action = "CLINICAL_AI_CALIBRATED_PRIOR_AUTH_RUBRIC", resourceType = "Claim")
    public CalibratedDecisionResponse evaluatePriorAuthPolicyRubric(PriorAuthPolicyRubricRequest request) {
        if (request == null || request.clinicalEvidenceText() == null || request.clinicalEvidenceText().isBlank()) {
            throw new IllegalArgumentException("clinicalEvidenceText is required for prior-auth policy rubric scoring.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.CALIBRATED_CLINICAL_DECISION);

        String combined = "Requested Procedure/Therapy: " + Objects.requireNonNullElse(request.requestedProcedureOrTherapy(), "N/A")
                + "\nPayer Policy Criteria: " + Objects.requireNonNullElse(request.payerPolicySummary(), "Standard medical necessity policy")
                + "\nClinical Evidence:\n" + request.clinicalEvidenceText();
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(combined);

        Map<String, DecisionQuestion> questions = new LinkedHashMap<>();
        questions.put("meets_medical_necessity", new DecisionQuestion(
                "noul",
                "Does the clinical evidence satisfy the stated payer medical necessity policy criteria?",
                Map.of(
                        "true_description", "Objective diagnosis, failed conservative therapy, and supporting labs/imaging satisfy policy criteria.",
                        "false_description", "Criteria are unmet or critical required clinical documentation is missing."
                )
        ));
        questions.put("documentation_completeness", new DecisionQuestion(
                "score",
                "Rate how completely the submitted clinical chart documents payer prior-authorization prerequisites.",
                List.of(
                        "Incomplete: missing diagnosis or clinical rationale.",
                        "Partial: diagnosis present but lacks objective labs, imaging, or prior treatment history.",
                        "Substantial: most criteria documented with minor clarifying detail needed.",
                        "Complete: all medical necessity criteria are explicitly substantiated."
                )
        ));
        questions.put("recommended_queue", new DecisionQuestion(
                "choice",
                "Which utilization management queue should receive this prior-authorization request?",
                Map.of(
                        "fast_track_clinical_signoff", "Strong medical necessity evidence ready for clinician/UM approval.",
                        "request_additional_records", "Likely approvable but missing specific labs or conservative therapy notes.",
                        "medical_director_review", "Complex edge case or high-cost therapy requiring peer-to-peer review."
                )
        ));

        DecisionEvaluationResult result = geminiClient.evaluateClinicalDecisions(
                redacted.redactedText(), List.of(), questions);

        String queue = result.answers().containsKey("recommended_queue")
                ? result.answers().get("recommended_queue").path("choice").asText("medical_director_review")
                : "medical_director_review";

        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.CALIBRATED_CLINICAL_DECISION,
                result.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                serializeAnswersSafely(result.answers()),
                new TokenUsage(0, 0, 0),
                result.latencyMs(),
                request.reviewerId()
        );

        return new CalibratedDecisionResponse(
                draft.draftId(),
                draft.status().name(),
                result.modelId(),
                result.answers(),
                queue,
                redacted.totalRedactions(),
                result.latencyMs()
        );
    }

    private String deriveTriageRoutingAction(Map<String, JsonNode> answers) {
        double lifeThreatP = answers.getOrDefault("immediate_life_threat", objectMapper.createObjectNode())
                .path("noul").asDouble(0.0);
        double sepsisP = answers.getOrDefault("sepsis_escalation_gate", objectMapper.createObjectNode())
                .path("noul").asDouble(0.0);
        JsonNode esi = answers.get("esi_acuity_level");
        String esiChoice = esi != null ? esi.path("choice").asText("esi_3_urgent") : "esi_3_urgent";
        double esiConf = esi != null ? esi.path("confidence").asDouble(0.0) : 0.0;

        if (lifeThreatP >= 0.70 || "esi_1_resuscitation".equals(esiChoice)) {
            return "IMMEDIATE_RESUSCITATION_BAY_AND_PHYSICIAN_ALERT";
        }
        if (sepsisP >= 0.65) {
            return "ACTIVATE_SEPSIS_BUNDLE_AND_HIGH_ACUITY_BED";
        }
        if (esiConf < 0.60) {
            return "SENIOR_TRIAGE_NURSE_MANUAL_ADJUDICATION";
        }
        return "ROUTE_" + esiChoice.toUpperCase(java.util.Locale.ROOT);
    }

    private String serializeAnswersSafely(Map<String, JsonNode> answers) {
        try {
            return objectMapper.writeValueAsString(answers);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
