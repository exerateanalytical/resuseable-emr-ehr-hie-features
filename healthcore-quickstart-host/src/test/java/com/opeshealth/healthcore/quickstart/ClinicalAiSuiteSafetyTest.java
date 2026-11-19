/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical & Operational AI Suite Safety & Governance Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.DeterministicClinicalAlert;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.FhirCdsTriggerRequest;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.FhirTriggerEvent;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine.RedactedFhirTriggerContext;
import com.opeshealth.healthcore.ai.client.GeminiApiException;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.DecisionQuestion;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.InlineBinaryPart;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.AdherenceRiskLevel;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.ClinicianOversightDecisionResult;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.CoachingChannel;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.PatientCoachingRequest;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine.RedactedCoachingContext;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.BillingComplianceFinding;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.BillingComplianceReport;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.BillingRegime;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.CandidateBillingCode;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.CareSetting;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.ClinicalCodingRequest;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine.RedactedCodingContext;
import com.opeshealth.healthcore.ai.config.ClinicalAiProperties;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.TenantAiGovernanceMetrics;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityAuditEvent;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityForecastRequest;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CapacityHorizon;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.CareUnitType;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.DeterministicCapacityMetrics;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.FacilityCapacityBaseline;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.HistoricalAdmissionWindow;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.RedactedCapacityContext;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.ShiftStaffingRecommendation;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor.SurgeLevel;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.BiomarkerTrend;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.ForecastHorizon;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.LongitudinalFhirSignals;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.PredictiveForecastRequest;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.RedactedLongitudinalContext;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.RiskTier;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine.TrendDirection;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.DeterministicReconciliationBaseline;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.MedicationEntry;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.MedicationReconciliationRequest;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.MedicationSource;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.PharmacyDispenseRecord;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.ReconciliationDiscrepancy;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.ReconciliationTransition;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine.RedactedReconciliationContext;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.ClinicalSummarizationRequest;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.ClinicalSummaryStyle;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.RedactedFhirDigest;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.AudioChunkRequest;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.EncounterSessionSnapshot;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.EncounterSessionStartRequest;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.RedactedTranscriptSegment;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.SessionStatus;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.SpeakerRole;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.TranscriptSegmentRequest;
import com.opeshealth.healthcore.quickstart.service.AmbientClinicalScribeService;
import com.opeshealth.healthcore.quickstart.service.AmbientClinicalScribeService.AmbientScribeRequest;
import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService;
import com.opeshealth.healthcore.quickstart.service.CalibratedClinicalDecisionService.EmergencyTriageDecisionRequest;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.ClinicalDocumentKind;
import com.opeshealth.healthcore.quickstart.service.SemanticClinicalSearchAiService.SemanticSearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies HIPAA Safe-Harbor PHI redaction, mandatory clinician attestation governance,
 * tenant AI kill-switch enforcement, Gemini active model request schemas, strict error
 * propagation (no simulated clinical fallbacks), and semantic vector cosine ranking.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
class ClinicalAiSuiteSafetyTest {

    @Test
    void safeHarborPhiRedactionScrubsDirectIdentifiersAndSupportsAuthorizedRehydration() {
        PhiRedactionGateway gateway = new PhiRedactionGateway();
        String rawClinicalNote = "Patient: Jean Mbarga, MRN: CM-88412, DOB: 1981-07-14, "
                + "CNI: 112049872, Phone +237-699-445-120, email jean.mbarga@example.org, "
                + "Address 42 Boulevard de la Liberte, SSN 123-45-6789, IP 192.168.10.22, "
                + "presents with BP 162/98 mmHg and HbA1c 8.4%.";

        RedactionContext ctx = new RedactionContext(List.of("Jean Mbarga"), List.of("CM-88412"), "1981-07-14");
        RedactedClinicalPayload redacted = gateway.redact(rawClinicalNote, ctx);

        assertFalse(redacted.redactedText().contains("Jean Mbarga"));
        assertFalse(redacted.redactedText().contains("CM-88412"));
        assertFalse(redacted.redactedText().contains("1981-07-14"));
        assertFalse(redacted.redactedText().contains("jean.mbarga@example.org"));
        assertFalse(redacted.redactedText().contains("123-45-6789"));
        assertFalse(redacted.redactedText().contains("192.168.10.22"));
        // Clinical findings MUST remain intact
        assertTrue(redacted.redactedText().contains("BP 162/98 mmHg"));
        assertTrue(redacted.redactedText().contains("HbA1c 8.4%"));
        assertTrue(redacted.totalRedactions() >= 7);
        assertEquals(64, redacted.redactedSha256Hex().length());

        String rehydrated = redacted.rehydrateForAuthorizedClinician(redacted.redactedText());
        assertTrue(rehydrated.contains("Jean Mbarga"));
        assertTrue(rehydrated.contains("CM-88412"));
    }

    @Test
    void governanceEnforcesMandatoryClinicianAttestationAndTenantKillSwitch() {
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        ClinicalAiDraftRecord draft = governance.registerDraft(
                facilityId,
                patientId,
                AiCapabilityType.AMBIENT_CLINICAL_SCRIBE,
                ClinicalAiProperties.DEFAULT_TEXT_MODEL,
                PhiRedactionGateway.sha256Hex("redacted-prompt"),
                Map.of("PATIENT_NAME", 1, "MRN", 1),
                "{\"chiefComplaint\":\"Persistent cough\"}",
                new TokenUsage(120, 80, 200),
                420L,
                "DR-TCHOUMBA"
        );

        assertEquals(DraftAttestationStatus.DRAFT_PENDING_ATTESTATION, draft.status());
        assertEquals(2, draft.totalPhiRedactions());

        ClinicalAiDraftRecord approved = governance.attestDraft(
                draft.draftId(),
                new AttestationCommand("DR-TCHOUMBA", DraftAttestationStatus.APPROVED, null, "Reviewed bedside exam")
        );
        assertEquals(DraftAttestationStatus.APPROVED, approved.status());
        assertEquals("DR-TCHOUMBA", approved.attestedByPractitionerId());
        assertNotNull(approved.attestedAt());

        TenantAiGovernanceMetrics metrics = governance.getTenantMetrics(facilityId);
        assertEquals(1L, metrics.totalDraftsGenerated());
        assertEquals(1L, metrics.approvedCount());
        assertEquals(2L, metrics.totalPhiIdentifiersRedacted());
        assertEquals(200L, metrics.totalTokensConsumed());

        // Verify per-tenant AI kill-switch blocks execution immediately
        governance.setTenantAiEnabled(facilityId, false);
        assertFalse(governance.isTenantAiEnabled(facilityId));
        assertThrows(IllegalStateException.class,
                () -> governance.assertTenantAiAllowed(facilityId, AiCapabilityType.CLINICAL_DECISION_SUPPORT));
    }

    @Test
    void geminiClientBuildsValidActiveModelWirePayloadsAndPropagatesMissingKeyErrors() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey(""); // Explicitly blank to test strict error propagation
        ObjectMapper mapper = new ObjectMapper();
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props, mapper);

        // 1. Verify active default model IDs match Gemini API policy
        assertEquals("gemini-4-hydrogen", props.getTextModel());
        assertEquals("gemini-3.5-transcribe", props.getTranscribeModel());
        assertEquals("gemini-3.8-flash-lite-tts", props.getTtsModel());
        assertEquals("gemini-embedding-2", props.getEmbeddingModel());
        assertEquals("gemini-decision-1", props.getDecisionsModel());

        // 2. Verify generateContent payload omits deprecated sampling params & thinkingBudget
        ObjectNode schema = mapper.createObjectNode().put("type", "OBJECT");
        ObjectNode genBody = client.buildGenerateContentRequestBody(
                "Clinical system prompt",
                "De-identified SOAP prompt",
                List.of(new InlineBinaryPart("image/jpeg", "AQID")),
                schema,
                "LOW"
        );
        ObjectNode genConfig = (ObjectNode) genBody.path("generationConfig");
        assertEquals("application/json", genConfig.path("responseMimeType").asText());
        assertEquals("LOW", genConfig.path("thinkingConfig").path("thinkingLevel").asText());
        assertFalse(genConfig.has("temperature"));
        assertFalse(genConfig.has("topP"));
        assertFalse(genConfig.has("topK"));
        assertFalse(genConfig.path("thinkingConfig").has("thinkingBudget"));

        // 3. Verify TTS payload targets AUDIO modality & prebuiltVoiceConfig
        ObjectNode ttsBody = client.buildTtsRequestBody("Prenez votre medicament le matin.", "Kore", "Warm nurse");
        assertEquals("AUDIO", ttsBody.path("generationConfig").path("responseModalities").path(0).asText());
        assertEquals("Kore", ttsBody.path("generationConfig").path("speechConfig")
                .path("voiceConfig").path("prebuiltVoiceConfig").path("voiceName").asText());

        // 4. Verify Decisions API payload uses bare "gemini-decision-1" without "models/" prefix
        ObjectNode decisionBody = client.buildDecisionsRequestBody(
                "models/gemini-decision-1",
                "BP 84/50, HR 128, Temp 39.4C, lactate 4.2",
                List.of(),
                Map.of("sepsis_gate", new DecisionQuestion("noul", "Suspected sepsis?", Map.of()))
        );
        assertEquals("gemini-decision-1", decisionBody.path("model").asText());
        assertEquals("noul", decisionBody.path("questions").path("sepsis_gate").path("type").asText());

        // 5. Verify missing API key propagates GeminiApiException (503) rather than fake clinical output
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        AmbientClinicalScribeService scribeService = new AmbientClinicalScribeService(client, redactor, governance);
        CalibratedClinicalDecisionService decisionService = new CalibratedClinicalDecisionService(client, redactor, governance);

        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException scribeEx = assertThrows(GeminiApiException.class, () ->
                    scribeService.generateSoapAndFhirDraft(new AmbientScribeRequest(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "DR-1",
                            "Internal Medicine", "en", null, null,
                            "Patient presents with 3 days of fever and productive cough.",
                            List.of(), List.of())));
            assertEquals(503, scribeEx.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", scribeEx.getErrorCode());

            GeminiApiException decisionEx = assertThrows(GeminiApiException.class, () ->
                    decisionService.evaluateEmergencyTriage(new EmergencyTriageDecisionRequest(
                            UUID.randomUUID(), UUID.randomUUID(), "NURSE-1",
                            "Chest pain radiating to left arm, diaphoretic, BP 92/58", null, null)));
            assertEquals(503, decisionEx.getHttpStatus());
        }
    }

    @Test
    void semanticClinicalSearchRanksTenantVectorsByCosineSimilarityWithPhiScrubbed() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        SemanticClinicalSearchAiService searchService = new SemanticClinicalSearchAiService(client, redactor, governance);

        UUID facilityA = UUID.randomUUID();
        UUID facilityB = UUID.randomUUID();

        searchService.registerPrecomputedVector(
                facilityA, UUID.randomUUID(), "ClinicalNote/101", ClinicalDocumentKind.CLINICAL_NOTE,
                "Patient: Marie Fotso with uncontrolled Type 2 Diabetes HbA1c 9.8% eligible for GLP-1 trial.",
                List.of(0.95, 0.25, 0.10), Map.of("condition", "T2DM"));
        searchService.registerPrecomputedVector(
                facilityA, UUID.randomUUID(), "ClinicalNote/102", ClinicalDocumentKind.CLINICAL_NOTE,
                "Routine orthopedic follow-up for healed ankle sprain.",
                List.of(0.05, 0.10, 0.99), Map.of("condition", "ORTHO"));
        // Different tenant should be completely isolated
        searchService.registerPrecomputedVector(
                facilityB, UUID.randomUUID(), "ClinicalNote/999", ClinicalDocumentKind.CLINICAL_NOTE,
                "Type 2 Diabetes trial candidate in Facility B.",
                List.of(0.99, 0.15, 0.05), Map.of("condition", "T2DM"));

        List<SemanticSearchHit> hits = searchService.searchWithPrecomputedQueryVector(
                facilityA, ClinicalDocumentKind.CLINICAL_NOTE, List.of(0.96, 0.24, 0.08), 5, 0.50);

        assertEquals(1, hits.size());
        assertEquals("ClinicalNote/101", hits.get(0).sourceResourceRef());
        assertTrue(hits.get(0).cosineSimilarity() > 0.99);
        assertFalse(hits.get(0).redactedSnippet().contains("Marie Fotso"));
    }

    @Test
    void clinicalSummarizerRedactsFhirBundlePhiAndEnforcesGovernance() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        ClinicalSummarizer summarizer = new ClinicalSummarizer(client, redactor, governance);

        String fhirBundleJson = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "id": "pat-4401",
                        "active": true,
                        "gender": "female",
                        "birthDate": "1979-11-03",
                        "name": [{"family": "Nkoumou", "given": ["Clarisse"]}],
                        "identifier": [{"value": "CM-DLA-99812"}],
                        "telecom": [{"system": "phone", "value": "+237-677-554-321"}, {"system": "email", "value": "clarisse.nkoumou@example.cm"}],
                        "address": [{"line": ["18 Rue Joss Bonanjo"], "postalCode": "23701"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "AllergyIntolerance",
                        "criticality": "high",
                        "clinicalStatus": {"coding": [{"code": "active", "display": "Active"}]},
                        "code": {"text": "Penicillin G", "coding": [{"code": "7980", "display": "Penicillin G"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active", "display": "Active"}]},
                        "code": {"text": "Type 2 Diabetes Mellitus", "coding": [{"code": "E11.9", "display": "Type 2 diabetes mellitus"}]},
                        "note": [{"text": "Clarisse Nkoumou (MRN CM-DLA-99812) reports blurry vision since last month."}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "HbA1c", "coding": [{"code": "4548-4", "display": "Hemoglobin A1c"}]},
                        "valueQuantity": {"value": 9.2, "unit": "%"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Metformin 500mg oral tablet"},
                        "dosageInstruction": [{"text": "500mg PO BID with meals"}]
                      }
                    }
                  ]
                }
                """;

        RedactedFhirDigest digest = summarizer.prepareRedactedFhirDigest(List.of(fhirBundleJson), null);
        String redactedText = digest.redactedPayload().redactedText();

        assertEquals(5, digest.totalResourcesProcessed());
        assertEquals(1, digest.resourceCountsByType().get("Patient"));
        assertEquals(1, digest.resourceCountsByType().get("AllergyIntolerance"));
        assertEquals(1, digest.resourceCountsByType().get("Condition"));
        assertEquals(1, digest.resourceCountsByType().get("Observation"));
        assertEquals(1, digest.resourceCountsByType().get("MedicationRequest"));

        // Every direct patient identifier (including narrative references in Condition.note) MUST be scrubbed
        assertFalse(redactedText.contains("Clarisse"));
        assertFalse(redactedText.contains("Nkoumou"));
        assertFalse(redactedText.contains("CM-DLA-99812"));
        assertFalse(redactedText.contains("1979-11-03"));
        assertFalse(redactedText.contains("+237-677-554-321"));
        assertFalse(redactedText.contains("clarisse.nkoumou@example.cm"));
        assertFalse(redactedText.contains("18 Rue Joss Bonanjo"));

        // Clinical facts MUST remain intact for clinician-ready summarization
        assertTrue(redactedText.contains("Penicillin G"));
        assertTrue(redactedText.contains("Type 2 Diabetes Mellitus"));
        assertTrue(redactedText.contains("9.2 %"));
        assertTrue(redactedText.contains("Metformin 500mg oral tablet"));
        assertTrue(redactedText.contains("birthYear=1979"));
        assertTrue(digest.redactedPayload().totalRedactions() >= 2);

        // Verify tenant kill-switch blocks summarization before calling Gemini
        UUID blockedFacility = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFacility, false);
        assertThrows(IllegalStateException.class, () ->
                summarizer.summarizeFhirResources(ClinicalSummarizationRequest.fromSingleFhirJson(
                        blockedFacility, UUID.randomUUID(), "DR-200",
                        ClinicalSummaryStyle.CLINICAL_CONCISE, "English", fhirBundleJson)));

        // Verify missing GEMINI_API_KEY propagates HTTP 503 GeminiApiException cleanly
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            UUID activeFacility = UUID.randomUUID();
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    summarizer.summarizeFhirResources(ClinicalSummarizationRequest.fromSingleFhirJson(
                            activeFacility, UUID.randomUUID(), "DR-200",
                            ClinicalSummaryStyle.CLINICAL_CONCISE, "English", fhirBundleJson)));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    void clinicalDecisionEngineDetectsFhirDrugInteractionsAndRedactsPhi() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        ClinicalDecisionEngine engine = new ClinicalDecisionEngine(client, redactor, governance);

        String triggerOrderBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "collection",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "draft",
                        "medicationCodeableConcept": {"text": "Ibuprofen 600mg oral tablet"},
                        "dosageInstruction": [{"text": "Take 600mg PO TID for Samuel Biya (MRN CM-77410)"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "draft",
                        "medicationCodeableConcept": {"text": "Amoxicillin 500mg capsule"}
                      }
                    }
                  ]
                }
                """;

        String patientContextBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "male",
                        "birthDate": "1958-02-22",
                        "name": [{"family": "Biya", "given": ["Samuel"]}],
                        "identifier": [{"value": "CM-77410"}],
                        "telecom": [{"system": "phone", "value": "+237-699-880-112"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Warfarin 5mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Metformin 500mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "AllergyIntolerance",
                        "criticality": "high",
                        "code": {"text": "Amoxicillin"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "eGFR", "coding": [{"code": "33914-3", "display": "eGFR"}]},
                        "valueQuantity": {"value": 24, "unit": "mL/min/1.73m2"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "Serum Potassium", "coding": [{"code": "2823-3", "display": "Potassium"}]},
                        "valueQuantity": {"value": 6.3, "unit": "mmol/L"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "Systolic Blood Pressure", "coding": [{"code": "8480-6"}]},
                        "valueQuantity": {"value": 88, "unit": "mmHg"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "Respiratory Rate", "coding": [{"code": "9279-1"}]},
                        "valueQuantity": {"value": 26, "unit": "/min"}
                      }
                    }
                  ]
                }
                """;

        RedactedFhirTriggerContext ctx = engine.prepareRedactedTriggerContext(
                List.of(triggerOrderBundle),
                List.of(patientContextBundle),
                List.of("CYP2C9 Poor Metabolizer")
        );

        String redactedText = ctx.redactedPayload().redactedText();
        assertEquals(10, ctx.totalResourcesProcessed());
        assertFalse(redactedText.contains("Samuel"));
        assertFalse(redactedText.contains("Biya"));
        assertFalse(redactedText.contains("CM-77410"));
        assertFalse(redactedText.contains("1958-02-22"));
        assertFalse(redactedText.contains("+237-699-880-112"));
        assertTrue(redactedText.contains("Warfarin 5mg oral tablet"));
        assertTrue(redactedText.contains("Ibuprofen 600mg oral tablet"));
        assertTrue(redactedText.contains("CYP2C9 Poor Metabolizer"));

        List<String> ruleCodes = ctx.deterministicRuleAlerts().stream()
                .map(DeterministicClinicalAlert::ruleCode)
                .toList();
        assertTrue(ruleCodes.contains("CDS-ALLERGY-001"));
        assertTrue(ruleCodes.contains("CDS-DDI-001"));
        assertTrue(ruleCodes.contains("CDS-RENAL-001"));
        assertTrue(ruleCodes.contains("CDS-LAB-K-001"));
        assertTrue(ruleCodes.contains("CDS-SEPSIS-QSOFA"));

        // Verify tenant kill-switch blocks execution
        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                engine.evaluateFhirTrigger(new FhirCdsTriggerRequest(
                        blockedFac, UUID.randomUUID(), "DR-301", FhirTriggerEvent.ORDER_SIGN,
                        List.of(triggerOrderBundle), List.of(patientContextBundle), List.of())));

        // Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly without fabricated AI cards
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            UUID activeFac = UUID.randomUUID();
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    engine.evaluateFhirTrigger(new FhirCdsTriggerRequest(
                            activeFac, UUID.randomUUID(), "DR-301", FhirTriggerEvent.ORDER_SIGN,
                            List.of(triggerOrderBundle), List.of(patientContextBundle), List.of())));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    void clinicalTranscriptionEngineRedactsRealTimeEncounterAndBuildsLivePayloads() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        ClinicalTranscriptionEngine engine = new ClinicalTranscriptionEngine(client, redactor, governance);

        // 1. Verify Live Audio Transcription model & wire payloads conform to Gemini Live API rules
        assertEquals("gemini-3.5-transcribe-live", props.getLiveTranscribeModel());
        ObjectNode liveSetup = client.buildLiveTranscribeSetupMessage(List.of("en", "fr"));
        assertEquals("models/gemini-3.5-transcribe-live", liveSetup.path("setup").path("model").asText());
        assertEquals("TEXT", liveSetup.path("setup").path("generationConfig").path("responseModalities").path(0).asText());
        assertEquals("en", liveSetup.path("setup").path("inputAudioTranscription").path("languageCodes").path(0).asText());

        ObjectNode liveAudioFrame = client.buildLiveRealtimeAudioInputMessage("AQIDBA==");
        assertEquals("audio/pcm;rate=16000", liveAudioFrame.path("realtimeInput").path("audio").path("mimeType").asText());
        assertEquals("AQIDBA==", liveAudioFrame.path("realtimeInput").path("audio").path("data").asText());
        assertFalse(liveAudioFrame.path("realtimeInput").has("media"));
        assertFalse(liveAudioFrame.path("realtimeInput").has("mediaChunks"));

        // 2. Start real-time encounter session and stream speaker segments containing direct PHI
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        EncounterSessionSnapshot started = engine.startEncounterSession(new EncounterSessionStartRequest(
                facilityId, patientId, encounterId, "DR-NGONO", "Internal Medicine",
                List.of("en"), List.of("Amina Bello"), List.of("CM-DLA-55209"), "1986-04-19"
        ));
        assertEquals(SessionStatus.ACTIVE, started.status());
        assertEquals(0, started.finalizedSegmentCount());

        RedactedTranscriptSegment interimSeg = engine.appendTranscriptSegment(started.sessionId(),
                new TranscriptSegmentRequest(SpeakerRole.PATIENT,
                        "My name is Amina Bello, phone +237-690-112-334, chest tightness since morning.", true));
        assertTrue(interimSeg.interim());
        assertFalse(interimSeg.redactedText().contains("Amina Bello"));
        assertFalse(interimSeg.redactedText().contains("+237-690-112-334"));
        assertTrue(interimSeg.redactedText().contains("chest tightness"));
        // Interim segments must NOT pollute finalized segment count
        assertEquals(0, engine.getSessionSnapshot(started.sessionId()).finalizedSegmentCount());

        RedactedTranscriptSegment seg1 = engine.appendTranscriptSegment(started.sessionId(),
                new TranscriptSegmentRequest(SpeakerRole.CLINICIAN,
                        "Examining Amina Bello (MRN CM-DLA-55209, DOB 1986-04-19). BP is 154/94 mmHg, HR 96 bpm, SpO2 98%.", false));
        RedactedTranscriptSegment seg2 = engine.appendTranscriptSegment(started.sessionId(),
                new TranscriptSegmentRequest(SpeakerRole.PATIENT,
                        "Please email my discharge plan to amina.bello@example.cm. I have dry cough for 4 days and fever 38.6 C.", false));

        assertFalse(seg1.redactedText().contains("Amina Bello"));
        assertFalse(seg1.redactedText().contains("CM-DLA-55209"));
        assertFalse(seg1.redactedText().contains("1986-04-19"));
        assertFalse(seg2.redactedText().contains("amina.bello@example.cm"));

        EncounterSessionSnapshot snapshot = engine.getSessionSnapshot(started.sessionId());
        assertEquals(2, snapshot.finalizedSegmentCount());
        assertTrue(snapshot.totalPhiRedactions() >= 4);
        assertTrue(snapshot.aggregatedRedactedTranscript().contains("BP is 154/94 mmHg"));
        assertTrue(snapshot.aggregatedRedactedTranscript().contains("fever 38.6 C"));
        assertFalse(snapshot.aggregatedRedactedTranscript().contains("Amina Bello"));
        assertFalse(snapshot.aggregatedRedactedTranscript().contains("CM-DLA-55209"));
        assertFalse(snapshot.aggregatedRedactedTranscript().contains("amina.bello@example.cm"));

        // 3. Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly on audio chunks, Live stream, and SOAP finalization
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException chunkEx = assertThrows(GeminiApiException.class, () ->
                    engine.ingestAudioChunk(started.sessionId(),
                            new AudioChunkRequest("audio/wav", "UklGRg==", SpeakerRole.CLINICIAN, "en")));
            assertEquals(503, chunkEx.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", chunkEx.getErrorCode());

            GeminiApiException liveEx = assertThrows(GeminiApiException.class, () ->
                    engine.openLiveAudioStream(started.sessionId(), seg -> {}, err -> {}));
            assertEquals(503, liveEx.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", liveEx.getErrorCode());

            GeminiApiException soapEx = assertThrows(GeminiApiException.class, () ->
                    engine.finalizeSessionAndGenerateSoap(started.sessionId(), "Plan: start Amlodipine 5mg daily."));
            assertEquals(503, soapEx.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", soapEx.getErrorCode());
        }

        // 4. Verify cancel transitions status cleanly and tenant kill-switch blocks new sessions
        EncounterSessionSnapshot cancelled = engine.cancelEncounterSession(started.sessionId());
        assertEquals(SessionStatus.CANCELLED, cancelled.status());

        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                engine.startEncounterSession(new EncounterSessionStartRequest(
                        blockedFac, UUID.randomUUID(), UUID.randomUUID(), "DR-NGONO",
                        "Internal Medicine", List.of("en"), List.of(), List.of(), null)));
    }

    @Test
    void clinicalPredictiveEngineExtractsLongitudinalFhirTrendsAndRedactsPhi() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        ClinicalPredictiveEngine engine = new ClinicalPredictiveEngine(client, redactor, governance);

        String historicalBundleJson = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "male",
                        "birthDate": "1955-08-14",
                        "name": [{"family": "Atangana", "given": ["Paul"]}],
                        "identifier": [{"value": "CM-YDE-30419"}],
                        "telecom": [{"system": "phone", "value": "+237-677-301-998"}, {"system": "email", "value": "paul.atangana@example.cm"}],
                        "address": [{"line": ["24 Avenue Kennedy Yaounde"]}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Encounter",
                        "status": "finished",
                        "class": {"code": "EMER"},
                        "period": {"start": "2026-06-01T08:00:00Z", "end": "2026-06-01T20:00:00Z"},
                        "reasonCode": [{"text": "Acute decompensated heart failure"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Encounter",
                        "status": "finished",
                        "class": {"code": "IMP"},
                        "period": {"start": "2026-08-10T09:00:00Z", "end": "2026-08-16T11:00:00Z"},
                        "reasonCode": [{"text": "Fluid overload and hyperkalemia"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Encounter",
                        "status": "finished",
                        "class": {"code": "EMER"},
                        "period": {"start": "2026-10-02T14:00:00Z", "end": "2026-10-03T06:00:00Z"},
                        "reasonCode": [{"text": "Dyspnea exacerbation"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Chronic Kidney Disease Stage 4", "coding": [{"code": "N18.4"}]},
                        "note": [{"text": "Paul Atangana (MRN CM-YDE-30419) missed nephrology clinic due to transport barrier."}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Congestive Heart Failure", "coding": [{"code": "I50.9"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Type 2 Diabetes Mellitus", "coding": [{"code": "E11.9"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Essential Hypertension", "coding": [{"code": "I10"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-02-10T09:00:00Z",
                        "code": {"text": "eGFR", "coding": [{"code": "33914-3"}]},
                        "valueQuantity": {"value": 46, "unit": "mL/min/1.73m2"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-09-25T09:00:00Z",
                        "code": {"text": "eGFR", "coding": [{"code": "33914-3"}]},
                        "valueQuantity": {"value": 26, "unit": "mL/min/1.73m2"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-02-10T09:00:00Z",
                        "code": {"text": "HbA1c", "coding": [{"code": "4548-4"}]},
                        "valueQuantity": {"value": 7.6, "unit": "%"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-09-25T09:00:00Z",
                        "code": {"text": "HbA1c", "coding": [{"code": "4548-4"}]},
                        "valueQuantity": {"value": 9.3, "unit": "%"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-02-10T09:00:00Z",
                        "code": {"text": "BNP", "coding": [{"code": "30934-4"}]},
                        "valueQuantity": {"value": 260, "unit": "pg/mL"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "effectiveDateTime": "2026-09-25T09:00:00Z",
                        "code": {"text": "BNP", "coding": [{"code": "30934-4"}]},
                        "valueQuantity": {"value": 980, "unit": "pg/mL"}
                      }
                    },
                    { "resource": { "resourceType": "MedicationRequest", "status": "active", "medicationCodeableConcept": {"text": "Furosemide 40mg"} } },
                    { "resource": { "resourceType": "MedicationRequest", "status": "active", "medicationCodeableConcept": {"text": "Carvedilol 12.5mg"} } },
                    { "resource": { "resourceType": "MedicationRequest", "status": "active", "medicationCodeableConcept": {"text": "Insulin Glargine"} } },
                    { "resource": { "resourceType": "MedicationRequest", "status": "active", "medicationCodeableConcept": {"text": "Atorvastatin 40mg"} } },
                    { "resource": { "resourceType": "MedicationRequest", "status": "active", "medicationCodeableConcept": {"text": "Amlodipine 10mg"} } }
                  ]
                }
                """;

        RedactedLongitudinalContext ctx = engine.prepareRedactedLongitudinalContext(
                List.of(historicalBundleJson),
                List.of("Transportation insecurity", "Lives alone")
        );

        String redactedText = ctx.redactedPayload().redactedText();
        assertEquals(19, ctx.totalResourcesProcessed());

        // Mandatory HIPAA Safe-Harbor redaction verification
        assertFalse(redactedText.contains("Paul"));
        assertFalse(redactedText.contains("Atangana"));
        assertFalse(redactedText.contains("CM-YDE-30419"));
        assertFalse(redactedText.contains("1955-08-14"));
        assertFalse(redactedText.contains("+237-677-301-998"));
        assertFalse(redactedText.contains("paul.atangana@example.cm"));
        assertFalse(redactedText.contains("24 Avenue Kennedy Yaounde"));
        assertTrue(redactedText.contains("birthYear=1955"));
        assertTrue(redactedText.contains("Transportation insecurity"));

        // Verify deterministic LACE readmission baseline & worsening longitudinal biomarker slopes
        LongitudinalFhirSignals signals = ctx.longitudinalSignals();
        assertEquals(3, signals.totalEncounters());
        assertEquals(3, signals.inpatientOrEmergencyEncounters());
        assertTrue(signals.maxInpatientLengthOfStayDays() >= 6);
        assertEquals(4, signals.activeChronicConditionCount());
        assertEquals(5, signals.activeMedicationCount());
        assertTrue(signals.polypharmacyFlag());
        assertTrue(signals.baselineLaceScore() >= 12);
        assertEquals(RiskTier.VERY_HIGH, signals.baselineReadmissionTier());

        Map<String, BiomarkerTrend> trendByName = new java.util.LinkedHashMap<>();
        for (BiomarkerTrend t : signals.biomarkerTrends()) {
            trendByName.put(t.biomarkerName(), t);
        }
        assertEquals(TrendDirection.WORSENING, trendByName.get("eGFR").direction());
        assertEquals(TrendDirection.WORSENING, trendByName.get("HbA1c").direction());
        assertEquals(TrendDirection.WORSENING, trendByName.get("BNP / NT-proBNP").direction());

        // Verify tenant kill-switch blocks predictive forecasting
        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                engine.forecastReadmissionAndDiseaseProgression(new PredictiveForecastRequest(
                        blockedFac, UUID.randomUUID(), "DR-401", ForecastHorizon.DAYS_30,
                        List.of(historicalBundleJson), List.of("Lives alone"), true)));

        // Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly without fabricated predictions
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            UUID activeFac = UUID.randomUUID();
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    engine.forecastReadmissionAndDiseaseProgression(new PredictiveForecastRequest(
                            activeFac, UUID.randomUUID(), "DR-401", ForecastHorizon.DAYS_30,
                            List.of(historicalBundleJson), List.of("Lives alone"), true)));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    void patientHealthCoachEngineAnalyzesAdherenceRedactsPhiAndEnforcesClinicianOversight() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        PatientHealthCoachEngine engine = new PatientHealthCoachEngine(client, redactor, governance);

        String longitudinalBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "female",
                        "birthDate": "1972-05-09",
                        "name": [{"family": "Eboa", "given": ["Beatrice"]}],
                        "identifier": [{"value": "CM-DLA-61820"}],
                        "telecom": [{"system": "phone", "value": "+237-699-412-887"}, {"system": "email", "value": "beatrice.eboa@example.cm"}],
                        "address": [{"line": ["12 Rue Akwa Douala"]}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Type 2 Diabetes Mellitus", "coding": [{"code": "E11.9"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Essential Hypertension", "coding": [{"code": "I10"}]}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Metformin 1000mg oral tablet"},
                        "dosageInstruction": [{"text": "Take 1000mg BID with meals"}],
                        "note": [{"text": "Beatrice Eboa (MRN CM-DLA-61820) frequently missed evening dose due to mild nausea."}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Lisinopril 20mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Atorvastatin 20mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationRequest",
                        "status": "active",
                        "medicationCodeableConcept": {"text": "Amlodipine 5mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "MedicationDispense",
                        "status": "declined",
                        "medicationCodeableConcept": {"text": "Lisinopril 20mg oral tablet"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "HbA1c", "coding": [{"code": "4548-4"}]},
                        "valueQuantity": {"value": 10.4, "unit": "%"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "Systolic Blood Pressure", "coding": [{"code": "8480-6"}]},
                        "valueQuantity": {"value": 152, "unit": "mmHg"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": {"text": "Body Mass Index", "coding": [{"code": "39156-5"}]},
                        "valueQuantity": {"value": 32.1, "unit": "kg/m2"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Goal",
                        "lifecycleStatus": "active",
                        "description": {"text": "Walk 30 minutes five days per week"}
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Appointment",
                        "status": "booked",
                        "description": "Endocrinology follow-up visit"
                      }
                    }
                  ]
                }
                """;

        RedactedCoachingContext ctx = engine.prepareRedactedCoachingContext(
                List.of(longitudinalBundle),
                List.of("Forgets evening dose", "Pharmacy transport cost")
        );

        String redactedText = ctx.redactedPayload().redactedText();
        assertEquals(13, ctx.totalResourcesProcessed());

        // Mandatory HIPAA Safe-Harbor redaction verification
        assertFalse(redactedText.contains("Beatrice"));
        assertFalse(redactedText.contains("Eboa"));
        assertFalse(redactedText.contains("CM-DLA-61820"));
        assertFalse(redactedText.contains("1972-05-09"));
        assertFalse(redactedText.contains("+237-699-412-887"));
        assertFalse(redactedText.contains("beatrice.eboa@example.cm"));
        assertFalse(redactedText.contains("12 Rue Akwa Douala"));
        assertTrue(redactedText.contains("birthYear=1972"));
        assertTrue(redactedText.contains("Metformin 1000mg oral tablet"));

        // Verify deterministic adherence risk, wellness opportunities, and clinician escalation
        assertEquals(4, ctx.adherenceSummary().activeMedicationCount());
        assertTrue(ctx.adherenceSummary().complexRegimenFlag());
        assertEquals(AdherenceRiskLevel.HIGH_NONADHERENCE_RISK, ctx.adherenceSummary().adherenceRiskLevel());
        assertTrue(ctx.adherenceSummary().adherenceGapSignalCount() >= 3);
        assertTrue(ctx.wellnessSignals().clinicianEscalationRecommended());
        assertTrue(ctx.wellnessSignals().wellnessOpportunities().size() >= 3);

        // Verify clinician oversight release gate blocks unapproved drafts and releases only after attestation
        UUID facId = UUID.randomUUID();
        UUID patId = UUID.randomUUID();
        ClinicalAiDraftRecord pendingDraft = governance.registerDraft(
                facId, patId, AiCapabilityType.PATIENT_DISCHARGE_COACH,
                ClinicalAiProperties.DEFAULT_TEXT_MODEL,
                ctx.redactedPayload().redactedSha256Hex(),
                ctx.redactedPayload().redactionCountsByCategory(),
                "{\"greetingAndEncouragement\":\"Keep going!\"}",
                new TokenUsage(90, 60, 150),
                210L,
                "DR-ESSOMBA"
        );
        assertThrows(IllegalStateException.class, () ->
                engine.assertDraftApprovedForPatientRelease(pendingDraft.draftId()));

        ClinicianOversightDecisionResult approved = engine.approveOrRejectCoachingDraft(
                pendingDraft.draftId(),
                new AttestationCommand("DR-ESSOMBA", DraftAttestationStatus.APPROVED, null, "Verified doses and escalation plan")
        );
        assertTrue(approved.releasedToPatient());
        assertEquals(DraftAttestationStatus.APPROVED, approved.attestationStatus());
        assertEquals(DraftAttestationStatus.APPROVED,
                engine.assertDraftApprovedForPatientRelease(pendingDraft.draftId()).status());

        // Verify missing overseeingClinicianId is rejected before any AI call
        assertThrows(IllegalArgumentException.class, () ->
                engine.generatePersonalizedCoachingDraft(new PatientCoachingRequest(
                        facId, patId, "   ", "French", null, CoachingChannel.WHATSAPP,
                        List.of(longitudinalBundle), List.of(), false, null)));

        // Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly without fabricated coaching prompts
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    engine.generatePersonalizedCoachingDraft(new PatientCoachingRequest(
                            facId, patId, "DR-ESSOMBA", "French", null, CoachingChannel.WHATSAPP,
                            List.of(longitudinalBundle), List.of("Forgets evening dose"), true, "Kore")));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    void clinicalCapacityPredictorForecastsBaselineStaffingRedactsPhiAndLogsAuditTrail() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();

        List<String> recordedAuditActions = new java.util.ArrayList<>();
        ClinicalCapacityPredictor predictor = new ClinicalCapacityPredictor(client, redactor, governance);
        predictor.registerAuditListener(evt -> recordedAuditActions.add(evt.action() + ":" + evt.outcome()));

        UUID facilityId = UUID.randomUUID();
        FacilityCapacityBaseline baseline = new FacilityCapacityBaseline(
                40, 3, 6, 2, 4, 4.0,
                List.of("Seasonal malaria surge", "Regional highway mass-casualty alert involving Patient: Henri Mballa MRN CM-99210")
        );
        List<HistoricalAdmissionWindow> windows = List.of(
                new HistoricalAdmissionWindow("2026-11-16T08:00:00Z", "MONDAY", "DAY", 24, 9, 7, 3, 38.0, 6, 82.0),
                new HistoricalAdmissionWindow("2026-11-16T16:00:00Z", "MONDAY", "EVENING", 28, 11, 6, 5, 48.0, 8, 86.0),
                new HistoricalAdmissionWindow("2026-11-17T08:00:00Z", "TUESDAY", "DAY", 34, 14, 8, 8, 62.0, 11, 93.0),
                new HistoricalAdmissionWindow("2026-11-17T16:00:00Z", "TUESDAY", "EVENING", 38, 16, 7, 11, 74.0, 13, 96.0)
        );
        String fhirEncounterBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "male",
                        "birthDate": "1984-03-11",
                        "name": [{"family": "Mballa", "given": ["Henri"]}],
                        "identifier": [{"value": "CM-99210"}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Encounter",
                        "status": "in-progress",
                        "class": {"code": "EMER"}
                      }
                    }
                  ]
                }
                """;

        CapacityForecastRequest req = new CapacityForecastRequest(
                facilityId,
                "CHARGE-NURSE-01",
                CareUnitType.EMERGENCY_DEPARTMENT,
                CapacityHorizon.NEXT_72_HOURS,
                baseline,
                windows,
                List.of(fhirEncounterBundle)
        );

        RedactedCapacityContext ctx = predictor.analyzeCapacityBaselineWithAudit(req);
        String redactedText = ctx.redactedPayload().redactedText();

        // Mandatory HIPAA Safe-Harbor redaction across operational notes & FHIR bundles
        assertFalse(redactedText.contains("Henri Mballa"));
        assertFalse(redactedText.contains("CM-99210"));
        assertTrue(redactedText.contains("Seasonal malaria surge"));

        // Verify deterministic surge detection, positive arrival slope, and nurse staffing deltas
        DeterministicCapacityMetrics metrics = ctx.deterministicMetrics();
        assertEquals(4, metrics.windowsAnalyzed());
        assertEquals(1, metrics.fhirEncountersIngested());
        assertTrue(metrics.arrivalTrendSlopePercent() > 30.0);
        assertEquals(SurgeLevel.RED_CRITICAL_DIVERSION, metrics.surgeLevel());
        assertEquals(3, metrics.shiftStaffingBaselines().size());
        ShiftStaffingRecommendation eveningShift = metrics.shiftStaffingBaselines().stream()
                .filter(s -> "EVENING".equals(s.shiftName()))
                .findFirst()
                .orElseThrow();
        assertTrue(eveningShift.recommendedRns() > baseline.currentRnsOnDuty());
        assertTrue(eveningShift.additionalRnsNeeded() > 0);

        // Verify audit trail recorded the baseline analysis event with SHA-256 input digest
        List<CapacityAuditEvent> trailAfterBaseline = predictor.getAuditTrailForFacility(facilityId);
        assertEquals(1, trailAfterBaseline.size());
        assertEquals("CLINICAL_AI_CAPACITY_BASELINE_ANALYZED", trailAfterBaseline.get(0).action());
        assertEquals(64, trailAfterBaseline.get(0).inputDigestSha256().length());

        // Verify tenant kill-switch blocks forecasting AND appends a DENIED audit event
        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                predictor.forecastPatientFlowAndStaffing(new CapacityForecastRequest(
                        blockedFac, "CHARGE-NURSE-01", CareUnitType.EMERGENCY_DEPARTMENT,
                        CapacityHorizon.NEXT_24_HOURS, baseline, windows, List.of())));
        assertTrue(predictor.getAuditTrailForFacility(blockedFac).stream()
                .anyMatch(e -> "CLINICAL_AI_CAPACITY_FORECAST_DENIED".equals(e.action())
                        && "DENIED".equals(e.outcome())));

        // Verify missing GEMINI_API_KEY propagates HTTP 503 AND records REQUESTED + FAILED audit trail events
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    predictor.forecastPatientFlowAndStaffing(req));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
            assertTrue(recordedAuditActions.contains("CLINICAL_AI_CAPACITY_FORECAST_REQUESTED:IN_PROGRESS"));
            assertTrue(recordedAuditActions.contains("CLINICAL_AI_CAPACITY_FORECAST_FAILED:FAILURE"));
        }
    }

    @Test
    void clinicalCodingEngineEnforcesBillingComplianceAndRedactsPhi() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        ClinicalCodingEngine engine = new ClinicalCodingEngine(client, redactor, governance);

        String encounterFhirBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "female",
                        "birthDate": "1989-12-04",
                        "name": [{"family": "Tchouamo", "given": ["Nadine"]}],
                        "identifier": [{"value": "CM-BAF-77102"}],
                        "telecom": [{"system": "phone", "value": "+237-655-221-904"}, {"system": "email", "value": "nadine.tchouamo@example.cm"}],
                        "address": [{"line": ["77 Boulevard Bafoussam"]}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "Condition",
                        "clinicalStatus": {"coding": [{"code": "active"}]},
                        "code": {"text": "Type 2 diabetes mellitus without complications", "coding": [{"code": "E11.9"}]}
                      }
                    }
                  ]
                }
                """;

        List<CandidateBillingCode> testCandidates = List.of(
                new CandidateBillingCode("ICD-10-CM", "V01.1", "Pedestrian injured in collision with pedal cycle", true, List.of()),
                new CandidateBillingCode("ICD-10-CM", "E11.9", "Type 2 diabetes mellitus without complications", false, List.of()),
                new CandidateBillingCode("ICD-10-CM", "INVALID-999", "Malformed diagnosis", false, List.of()),
                new CandidateBillingCode("CPT", "99213", "Office outpatient visit established 20-29 min", false, List.of()),
                new CandidateBillingCode("CPT", "99215", "Office outpatient visit high MDM", false, List.of()),
                new CandidateBillingCode("CPT", "80053", "Comprehensive metabolic panel", false, List.of()),
                new CandidateBillingCode("CPT", "80048", "Basic metabolic panel", false, List.of())
        );

        UUID facId = UUID.randomUUID();
        UUID patId = UUID.randomUUID();
        UUID encId = UUID.randomUUID();

        ClinicalCodingRequest req = new ClinicalCodingRequest(
                facId,
                patId,
                encId,
                "CODER-CPC-09",
                CareSetting.TELEHEALTH,
                BillingRegime.CMS_MEDICARE_USA,
                "Patient: Nadine Tchouamo (MRN CM-BAF-77102, DOB 1989-12-04, email nadine.tchouamo@example.cm) "
                        + "seen via video follow-up for stable Type 2 Diabetes Mellitus, HbA1c 7.1%.",
                "Routine telehealth chronic care follow-up, refilled Metformin 500mg BID.",
                List.of(encounterFhirBundle),
                List.of("ICD-10-CM", "CPT"),
                testCandidates,
                List.of(),
                List.of(),
                null
        );

        RedactedCodingContext ctx = engine.prepareRedactedCodingContext(req);
        String redactedText = ctx.redactedPayload().redactedText();

        // 1. Mandatory Safe-Harbor PHI redaction verification
        assertFalse(redactedText.contains("Nadine"));
        assertFalse(redactedText.contains("Tchouamo"));
        assertFalse(redactedText.contains("CM-BAF-77102"));
        assertFalse(redactedText.contains("1989-12-04"));
        assertFalse(redactedText.contains("+237-655-221-904"));
        assertFalse(redactedText.contains("nadine.tchouamo@example.cm"));
        assertFalse(redactedText.contains("77 Boulevard Bafoussam"));
        assertTrue(redactedText.contains("HbA1c 7.1%"));
        assertTrue(redactedText.contains("Metformin 500mg BID"));

        // 2. Deterministic billing compliance audit verification
        BillingComplianceReport report = ctx.preScreenComplianceReport();
        assertFalse(report.claimReadyWithoutBlockingErrors());
        assertTrue(report.blockingErrorCount() >= 4);
        List<String> ruleCodes = report.findings().stream()
                .map(BillingComplianceFinding::ruleCode)
                .toList();
        assertTrue(ruleCodes.contains("BILL-ICD10-FORMAT"));
        assertTrue(ruleCodes.contains("BILL-ICD10-SEQ"));
        assertTrue(ruleCodes.contains("BILL-ICD10-UNSPECIFIED"));
        assertTrue(ruleCodes.contains("BILL-NCCI-001"));
        assertTrue(ruleCodes.contains("BILL-NCCI-002"));
        assertTrue(ruleCodes.contains("BILL-UPCODING-001"));
        assertTrue(ruleCodes.contains("BILL-TELEHEALTH-MOD"));

        // 3. Verify tenant kill-switch blocks coding automation
        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                engine.automateClinicalCoding(new ClinicalCodingRequest(
                        blockedFac, patId, encId, "CODER-CPC-09", CareSetting.OUTPATIENT,
                        BillingRegime.CMS_MEDICARE_USA, "Routine hypertension visit.", null,
                        List.of(), List.of("ICD-10-CM", "CPT"), List.of(), List.of(), List.of(), null)));

        // 4. Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly without fabricated billing codes
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    engine.automateClinicalCoding(req));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }

    @Test
    void medicationReconciliationEngineDetectsDiscrepanciesRedactsPhiAndEnforcesGovernance() {
        ClinicalAiProperties props = new ClinicalAiProperties();
        props.setApiKey("");
        GeminiClinicalApiClient client = new GeminiClinicalApiClient(props);
        PhiRedactionGateway redactor = new PhiRedactionGateway();
        ClinicalAiGovernanceService governance = new ClinicalAiGovernanceService();
        MedicationReconciliationEngine engine = new MedicationReconciliationEngine(client, redactor, governance);

        String fhirBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "searchset",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "gender": "male",
                        "birthDate": "1961-07-18",
                        "name": [{"family": "Nkeng", "given": ["Victor"]}],
                        "identifier": [{"value": "CM-DLA-84215"}],
                        "telecom": [{"system": "phone", "value": "+237-670-443-210"}, {"system": "email", "value": "victor.nkeng@example.cm"}],
                        "address": [{"line": ["88 Rue Bonanjo Douala"]}]
                      }
                    },
                    {
                      "resource": {
                        "resourceType": "AllergyIntolerance",
                        "criticality": "high",
                        "code": {"text": "Penicillin"}
                      }
                    }
                  ]
                }
                """;

        List<MedicationEntry> currentList = List.of(
                // Warfarin (high-alert) omitted from admission notes -> MREC-OMISSION-001
                new MedicationEntry("Coumadin", "855332", "5", "mg", "once daily", "PO", "active",
                        MedicationSource.CURRENT_MEDICATION_LIST, "For atrial fibrillation"),
                // Lisinopril dose conflict (10mg in EHR vs 20mg in pharmacy & admission note) -> MREC-DOSE-001
                new MedicationEntry("Lisinopril", "314076", "10", "mg", "once daily", "PO", "active",
                        MedicationSource.CURRENT_MEDICATION_LIST, null),
                // Metformin frequency conflict (BID in EHR vs once daily in admission note) -> MREC-FREQ-001
                new MedicationEntry("Glucophage", "861004", "500", "mg", "twice daily", "PO", "active",
                        MedicationSource.CURRENT_MEDICATION_LIST, null),
                // Atorvastatin concordant across all 3 sources -> matchedMedications
                new MedicationEntry("Atorvastatin", "617312", "40", "mg", "once daily", "PO", "active",
                        MedicationSource.CURRENT_MEDICATION_LIST, null),
                // Furosemide declined at pharmacy -> MREC-ADHERENCE-001
                new MedicationEntry("Furosemide", "310429", "40", "mg", "once daily", "PO", "active",
                        MedicationSource.CURRENT_MEDICATION_LIST, null)
        );

        String admissionNotes = "Admitted Patient: Victor Nkeng (MRN CM-DLA-84215, DOB 1961-07-18, phone +237-670-443-210). "
                + "Home medications per patient report: Lisinopril 20mg once daily, Metformin 500mg once daily, "
                + "Atorvastatin 40mg once daily, Furosemide 40mg once daily, and Amoxicillin 500mg three times daily "
                + "started at urgent care yesterday. Also taking Gabapentin 300mg at bedtime.";

        List<PharmacyDispenseRecord> pharmacyRecords = List.of(
                new PharmacyDispenseRecord("Pharmacie Bonanjo", "Warfarin sodium", "855332", "5mg", "Take 1 tablet once daily", "2026-10-20", 30, "completed"),
                new PharmacyDispenseRecord("Pharmacie Bonanjo", "Lisinopril", "314077", "20mg", "Take 1 tablet once daily", "2026-10-25", 30, "completed"),
                new PharmacyDispenseRecord("Pharmacie Bonanjo", "Metformin HCl", "861004", "500mg", "Take 1 tablet twice daily", "2026-10-25", 30, "completed"),
                new PharmacyDispenseRecord("Pharmacie Bonanjo", "Atorvastatin", "617312", "40mg", "Take 1 tablet once daily", "2026-10-25", 30, "completed"),
                new PharmacyDispenseRecord("Pharmacie Bonanjo", "Furosemide", "310429", "40mg", "Take 1 tablet once daily", "2026-10-01", 30, "declined"),
                // Unlisted pharmacy fill + therapeutic duplication with Lisinopril (RAAS_INHIBITOR) -> MREC-OMISSION-002 & MREC-DUP-001
                new PharmacyDispenseRecord("Pharmacie Akwa", "Losartan", "979492", "50mg", "Take 1 tablet once daily", "2026-11-02", 30, "completed")
        );

        UUID facId = UUID.randomUUID();
        UUID patId = UUID.randomUUID();
        UUID encId = UUID.randomUUID();

        MedicationReconciliationRequest req = new MedicationReconciliationRequest(
                facId,
                patId,
                encId,
                "PHARMD-MBAPPE",
                ReconciliationTransition.ADMISSION,
                currentList,
                admissionNotes,
                pharmacyRecords,
                List.of(fhirBundle),
                List.of(),
                List.of(),
                List.of(),
                null
        );

        RedactedReconciliationContext ctx = engine.prepareRedactedReconciliationContext(req);
        String redactedText = ctx.redactedPayload().redactedText();

        // 1. Mandatory HIPAA Safe-Harbor PHI redaction verification across admission notes & FHIR bundle
        assertFalse(redactedText.contains("Victor"));
        assertFalse(redactedText.contains("Nkeng"));
        assertFalse(redactedText.contains("CM-DLA-84215"));
        assertFalse(redactedText.contains("1961-07-18"));
        assertFalse(redactedText.contains("+237-670-443-210"));
        assertFalse(redactedText.contains("victor.nkeng@example.cm"));
        assertFalse(redactedText.contains("88 Rue Bonanjo Douala"));
        assertTrue(redactedText.contains("birthYear=1961"));
        assertTrue(redactedText.contains("Lisinopril"));

        // 2. Deterministic three-way reconciliation discrepancy verification
        DeterministicReconciliationBaseline baseline = ctx.deterministicBaseline();
        assertTrue(baseline.criticalCount() >= 2);
        assertTrue(baseline.matchedMedications().contains("atorvastatin"));

        List<String> ruleCodes = baseline.discrepancies().stream()
                .map(ReconciliationDiscrepancy::ruleCode)
                .toList();
        assertTrue(ruleCodes.contains("MREC-OMISSION-001"));
        assertTrue(ruleCodes.contains("MREC-OMISSION-002"));
        assertTrue(ruleCodes.contains("MREC-COMMISSION-001"));
        assertTrue(ruleCodes.contains("MREC-DOSE-001"));
        assertTrue(ruleCodes.contains("MREC-FREQ-001"));
        assertTrue(ruleCodes.contains("MREC-DUP-001"));
        assertTrue(ruleCodes.contains("MREC-ALLERGY-001"));
        assertTrue(ruleCodes.contains("MREC-ADHERENCE-001"));

        // 3. Verify tenant kill-switch blocks reconciliation
        UUID blockedFac = UUID.randomUUID();
        governance.setTenantAiEnabled(blockedFac, false);
        assertThrows(IllegalStateException.class, () ->
                engine.reconcileMedications(new MedicationReconciliationRequest(
                        blockedFac, patId, encId, "PHARMD-MBAPPE", ReconciliationTransition.ADMISSION,
                        currentList, admissionNotes, pharmacyRecords, List.of(), List.of(), List.of(), List.of(), null)));

        // 4. Verify missing GEMINI_API_KEY propagates HTTP 503 cleanly without fabricated reconciliation plans
        if (System.getenv("GEMINI_API_KEY") == null || System.getenv("GEMINI_API_KEY").isBlank()) {
            GeminiApiException ex = assertThrows(GeminiApiException.class, () ->
                    engine.reconcileMedications(req));
            assertEquals(503, ex.getHttpStatus());
            assertEquals("GEMINI_API_KEY_NOT_CONFIGURED", ex.getErrorCode());
        }
    }
}
