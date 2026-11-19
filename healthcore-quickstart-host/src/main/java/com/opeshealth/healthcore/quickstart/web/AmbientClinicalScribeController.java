/*
 * =============================================================================
 * Opes HealthCore SDK — Ambient Clinical Scribe REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.AudioChunkRequest;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.ClinicalEncounterSoapResult;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.EncounterSessionSnapshot;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.EncounterSessionStartRequest;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.RedactedTranscriptSegment;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine.TranscriptSegmentRequest;
import com.opeshealth.healthcore.quickstart.service.AmbientClinicalScribeService;
import com.opeshealth.healthcore.quickstart.service.AmbientClinicalScribeService.AmbientScribeDraftResponse;
import com.opeshealth.healthcore.quickstart.service.AmbientClinicalScribeService.AmbientScribeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Capability 1: Ambient Clinical Scribe &amp; Real-Time Voice-to-SOAP,
 * backed by {@link ClinicalTranscriptionEngine}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ai/scribe")
@Tag(name = "Clinical AI — Ambient Scribe & Real-Time Voice-to-SOAP",
        description = "Real-time clinical voice-to-text (gemini-3.5-transcribe / gemini-3.5-transcribe-live), Safe-Harbor PHI redaction, and automated SOAP + FHIR R4 draft generation requiring clinician attestation")
public class AmbientClinicalScribeController {

    public record FinalizeSessionRequest(String supplementalClinicianNotes) {}

    private final AmbientClinicalScribeService scribeService;
    private final ClinicalTranscriptionEngine transcriptionEngine;

    public AmbientClinicalScribeController(
            AmbientClinicalScribeService scribeService,
            ClinicalTranscriptionEngine transcriptionEngine) {
        this.scribeService = Objects.requireNonNull(scribeService);
        this.transcriptionEngine = Objects.requireNonNull(transcriptionEngine);
    }

    @PostMapping(value = "/soap-draft", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe consultation audio or transcript into a Safe-Harbor redacted SOAP & FHIR R4 draft")
    public ResponseEntity<AmbientScribeDraftResponse> generateSoapDraft(@RequestBody AmbientScribeRequest request) {
        return ResponseEntity.status(201).body(scribeService.generateSoapAndFhirDraft(request));
    }

    @PostMapping(value = "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Start a real-time clinical encounter transcription session with Safe-Harbor PHI redaction")
    public ResponseEntity<EncounterSessionSnapshot> startEncounterSession(
            @RequestBody EncounterSessionStartRequest request) {
        return ResponseEntity.status(201).body(transcriptionEngine.startEncounterSession(request));
    }

    @PostMapping(value = "/sessions/{sessionId}/audio-chunk", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Transcribe an incremental real-time encounter audio chunk with immediate Safe-Harbor PHI redaction")
    public ResponseEntity<RedactedTranscriptSegment> ingestAudioChunk(
            @PathVariable UUID sessionId,
            @RequestBody AudioChunkRequest request) {
        return ResponseEntity.ok(transcriptionEngine.ingestAudioChunk(sessionId, request));
    }

    @PostMapping(value = "/sessions/{sessionId}/transcript-segment", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Append a real-time transcribed utterance into an encounter session with immediate Safe-Harbor PHI redaction")
    public ResponseEntity<RedactedTranscriptSegment> appendTranscriptSegment(
            @PathVariable UUID sessionId,
            @RequestBody TranscriptSegmentRequest request) {
        return ResponseEntity.ok(transcriptionEngine.appendTranscriptSegment(sessionId, request));
    }

    @GetMapping(value = "/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Fetch current Safe-Harbor redacted transcript snapshot for an encounter session")
    public ResponseEntity<EncounterSessionSnapshot> getSessionSnapshot(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(transcriptionEngine.getSessionSnapshot(sessionId));
    }

    @PostMapping(value = "/sessions/{sessionId}/finalize-soap", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Finalize a real-time encounter session and generate an attestation-pending SOAP note + HL7 FHIR R4 draft")
    public ResponseEntity<ClinicalEncounterSoapResult> finalizeSessionAndGenerateSoap(
            @PathVariable UUID sessionId,
            @RequestBody(required = false) FinalizeSessionRequest request) {
        String notes = request != null ? request.supplementalClinicianNotes() : null;
        return ResponseEntity.status(201).body(transcriptionEngine.finalizeSessionAndGenerateSoap(sessionId, notes));
    }

    @PostMapping(value = "/sessions/{sessionId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel an active real-time encounter transcription session and close any open Live stream")
    public ResponseEntity<EncounterSessionSnapshot> cancelEncounterSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(transcriptionEngine.cancelEncounterSession(sessionId));
    }
}
