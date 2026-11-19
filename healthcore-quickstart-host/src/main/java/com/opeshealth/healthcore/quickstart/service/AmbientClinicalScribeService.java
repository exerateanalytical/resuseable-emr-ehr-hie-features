/*
 * =============================================================================
 * Opes HealthCore SDK — Ambient Clinical Scribe & Voice-to-SOAP AI Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.ClinicalEncounterSoapResult;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.EncounterTranscriptionRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 1: Ambient Clinical Scribe &amp; Voice-to-SOAP Service.
 *
 * <p>Delegates real-time voice-to-text transcription, HIPAA Safe-Harbor PHI redaction,
 * structured SOAP + HL7 FHIR R4 draft synthesis, and clinician attestation quarantine to the
 * reusable {@link ClinicalTranscriptionEngine} component inside {@code healthcore-starter-clinical-ai}.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class AmbientClinicalScribeService {

    public record AmbientScribeRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String clinicianId,
            String specialty,
            String languageCode,
            String audioMimeType,
            String base64AudioData,
            String rawTranscriptText,
            List<String> knownPatientNames,
            List<String> knownIdentifiers
    ) {}

    public record AmbientScribeDraftResponse(
            UUID draftId,
            String attestationStatus,
            String transcriptionModelId,
            String synthesisModelId,
            String redactedTranscript,
            JsonNode structuredSoapAndFhirDraft,
            int totalPhiRedactions,
            long totalLatencyMs
    ) {}

    private final ClinicalTranscriptionEngine transcriptionEngine;

    @Autowired
    public AmbientClinicalScribeService(ClinicalTranscriptionEngine transcriptionEngine) {
        this.transcriptionEngine = Objects.requireNonNull(
                transcriptionEngine, "ClinicalTranscriptionEngine is required");
    }

    public AmbientClinicalScribeService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this(new ClinicalTranscriptionEngine(geminiClient, phiRedactionGateway, governanceService));
    }

    @ClinicalAudit(action = "CLINICAL_AI_AMBIENT_SCRIBE_DRAFT", resourceType = "Encounter")
    public AmbientScribeDraftResponse generateSoapAndFhirDraft(AmbientScribeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AmbientScribeRequest is required.");
        }
        ClinicalEncounterSoapResult result = transcriptionEngine.transcribeAndGenerateSoap(
                new EncounterTranscriptionRequest(
                        request.facilityId(),
                        request.patientId(),
                        request.encounterId(),
                        request.clinicianId(),
                        request.specialty(),
                        request.languageCode(),
                        request.audioMimeType(),
                        request.base64AudioData(),
                        request.rawTranscriptText(),
                        request.knownPatientNames(),
                        request.knownIdentifiers(),
                        null
                )
        );
        return new AmbientScribeDraftResponse(
                result.draftId(),
                result.attestationStatus(),
                result.transcriptionModelId(),
                result.synthesisModelId(),
                result.redactedTranscript(),
                result.structuredSoapAndFhirDraft(),
                result.totalPhiRedactions(),
                result.totalLatencyMs()
        );
    }
}
