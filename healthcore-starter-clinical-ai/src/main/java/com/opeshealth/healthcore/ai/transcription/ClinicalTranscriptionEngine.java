/*
 * =============================================================================
 * Opes HealthCore SDK — Real-Time ClinicalTranscriptionEngine (Voice-to-SOAP + PHI Redaction)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.transcription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.LiveTranscriptEvent;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.LiveTranscriptionHandle;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TextGenerationResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Reusable Spring component inside {@code healthcore-starter-clinical-ai} that processes real-time
 * voice-to-text for clinical encounters, enforces mandatory HIPAA Safe-Harbor PHI redaction on
 * every transcribed utterance, and generates structured SOAP notes + HL7 FHIR R4 clinical drafts.
 *
 * <p><strong>Supported Modes:</strong></p>
 * <ul>
 *   <li><strong>Live WebSocket Streaming ({@code gemini-3.5-transcribe-live}):</strong> Streams
 *       16kHz 16-bit LE PCM microphone frames via {@link #openLiveAudioStream} and scrubs every
 *       interim and finalized utterance through {@link PhiRedactionGateway} in real time.</li>
 *   <li><strong>Incremental Audio Chunk Ingestion ({@code gemini-3.5-transcribe}):</strong> Accepts
 *       chunked WAV/WebM/MP3/PCM audio segments during an encounter session via
 *       {@link #ingestAudioChunk} and appends Safe-Harbor redacted speaker turns.</li>
 *   <li><strong>Automated SOAP &amp; FHIR R4 Synthesis ({@code gemini-4-hydrogen}):</strong> Converts
 *       the accumulated redacted encounter transcript into a structured SOAP note plus HL7 FHIR R4
 *       {@code Condition}, {@code Observation}, and {@code MedicationRequest} drafts quarantined
 *       under {@code DRAFT_PENDING_ATTESTATION} in {@link ClinicalAiGovernanceService}.</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Component
public class ClinicalTranscriptionEngine {

    public enum SessionStatus {
        ACTIVE,
        FINALIZED,
        CANCELLED
    }

    public enum SpeakerRole {
        CLINICIAN,
        PATIENT,
        CAREGIVER,
        UNSPECIFIED
    }

    public record EncounterSessionStartRequest(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String clinicianId,
            String specialty,
            List<String> languageCodes,
            List<String> knownPatientNames,
            List<String> knownIdentifiers,
            String knownDateOfBirth
    ) {}

    public record AudioChunkRequest(
            String audioMimeType,
            String base64AudioData,
            SpeakerRole speakerHint,
            String languageCode
    ) {}

    public record TranscriptSegmentRequest(
            SpeakerRole speakerRole,
            String rawUtteranceText,
            boolean interim
    ) {}

    public record RedactedTranscriptSegment(
            int sequenceNumber,
            SpeakerRole speakerRole,
            String redactedText,
            boolean interim,
            int phiRedactionsInSegment,
            Map<String, Integer> redactionCountsByCategory,
            String transcriptionModelId,
            Instant capturedAt
    ) {}

    public record EncounterSessionSnapshot(
            UUID sessionId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String clinicianId,
            String specialty,
            SessionStatus status,
            boolean liveStreamConnected,
            int finalizedSegmentCount,
            String aggregatedRedactedTranscript,
            int totalPhiRedactions,
            Map<String, Integer> cumulativeRedactionCountsByCategory,
            long totalTranscriptionLatencyMs,
            Instant startedAt,
            Instant updatedAt
    ) {}

    public record EncounterTranscriptionRequest(
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
            List<String> knownIdentifiers,
            String knownDateOfBirth
    ) {}

    public record ClinicalEncounterSoapResult(
            UUID draftId,
            UUID sessionId,
            String attestationStatus,
            String transcriptionModelId,
            String synthesisModelId,
            String redactedTranscript,
            JsonNode structuredSoapAndFhirDraft,
            int totalPhiRedactions,
            Map<String, Integer> redactionCountsByCategory,
            long totalLatencyMs
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final ObjectMapper objectMapper;
    private final Map<UUID, MutableEncounterSession> activeSessions = new ConcurrentHashMap<>();

    public ClinicalTranscriptionEngine(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient, "GeminiClinicalApiClient is required");
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway, "PhiRedactionGateway is required");
        this.governanceService = Objects.requireNonNull(governanceService, "ClinicalAiGovernanceService is required");
        this.objectMapper = geminiClient.getObjectMapper();
    }

    /**
     * Starts a new real-time clinical encounter transcription session after enforcing tenant AI
     * governance policies. All subsequent audio chunks or transcript segments ingested into this
     * session are scrubbed via HIPAA Safe-Harbor before storage or SOAP generation.
     */
    public EncounterSessionSnapshot startEncounterSession(EncounterSessionStartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("EncounterSessionStartRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        UUID sessionId = UUID.randomUUID();
        String specialty = (request.specialty() == null || request.specialty().isBlank())
                ? "General Medicine" : request.specialty().trim();
        List<String> languages = request.languageCodes() != null ? List.copyOf(request.languageCodes()) : List.of();
        RedactionContext redactionContext = new RedactionContext(
                request.knownPatientNames() != null ? request.knownPatientNames() : List.of(),
                request.knownIdentifiers() != null ? request.knownIdentifiers() : List.of(),
                request.knownDateOfBirth()
        );

        MutableEncounterSession session = new MutableEncounterSession(
                sessionId,
                request.facilityId(),
                request.patientId(),
                request.encounterId(),
                request.clinicianId(),
                specialty,
                languages,
                redactionContext
        );
        activeSessions.put(sessionId, session);
        return session.toSnapshot();
    }

    /**
     * Attaches a server-side Gemini Live API WebSocket stream ({@code gemini-3.5-transcribe-live})
     * to an active clinical encounter session. Every incoming interim or finalized utterance from
     * Gemini Live is immediately passed through {@link PhiRedactionGateway} before being stored in
     * the session or forwarded to {@code onRedactedSegment}.
     */
    public LiveTranscriptionHandle openLiveAudioStream(
            UUID sessionId,
            Consumer<RedactedTranscriptSegment> onRedactedSegment,
            Consumer<Throwable> onError) {
        MutableEncounterSession session = requireActiveSession(sessionId);
        governanceService.assertTenantAiAllowed(session.facilityId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        LiveTranscriptionHandle handle = geminiClient.openLiveTranscriptionSession(
                session.languageCodes,
                (LiveTranscriptEvent event) -> {
                    RedactedTranscriptSegment redacted = recordRedactedSegment(
                            session,
                            SpeakerRole.UNSPECIFIED,
                            event.text(),
                            event.interim(),
                            geminiClient.getProperties().getLiveTranscribeModel()
                    );
                    if (onRedactedSegment != null) {
                        onRedactedSegment.accept(redacted);
                    }
                },
                onError
        );
        session.attachLiveHandle(handle);
        return handle;
    }

    /**
     * Transcribes an incremental audio chunk ({@code audio/wav}, {@code audio/webm}, {@code audio/mp3},
     * or {@code audio/pcm;rate=16000}) using {@code gemini-3.5-transcribe}, immediately applies HIPAA
     * Safe-Harbor PHI redaction, and appends the scrubbed segment to the active encounter session.
     */
    public RedactedTranscriptSegment ingestAudioChunk(UUID sessionId, AudioChunkRequest chunkRequest) {
        if (chunkRequest == null || chunkRequest.base64AudioData() == null || chunkRequest.base64AudioData().isBlank()) {
            throw new IllegalArgumentException("Base64 audio chunk payload is required.");
        }
        MutableEncounterSession session = requireActiveSession(sessionId);
        governanceService.assertTenantAiAllowed(session.facilityId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        String lang = (chunkRequest.languageCode() != null && !chunkRequest.languageCode().isBlank())
                ? chunkRequest.languageCode().trim()
                : (!session.languageCodes.isEmpty() ? session.languageCodes.get(0) : "en");

        String speakerGuidance = (chunkRequest.speakerHint() != null && chunkRequest.speakerHint() != SpeakerRole.UNSPECIFIED)
                ? "Primary speaker hint: " + chunkRequest.speakerHint().name() + ". "
                : "";

        TextGenerationResult transcribed = geminiClient.transcribeClinicalAudio(
                chunkRequest.audioMimeType(),
                chunkRequest.base64AudioData(),
                "Transcribe this real-time clinical encounter audio segment in language '" + lang + "'. "
                        + speakerGuidance
                        + "Preserve exact clinical terms, vitals, and dosages. Do not fabricate clinical details."
        );
        session.addTranscriptionLatency(transcribed.latencyMs());
        session.recordTranscriptionModel(transcribed.modelId());

        SpeakerRole role = chunkRequest.speakerHint() != null ? chunkRequest.speakerHint() : SpeakerRole.UNSPECIFIED;
        return recordRedactedSegment(session, role, transcribed.text(), false, transcribed.modelId());
    }

    /**
     * Appends a real-time transcribed utterance directly into an active encounter session, scrubbing
     * all 18 HIPAA Safe-Harbor PHI categories before storing or returning the segment.
     */
    public RedactedTranscriptSegment appendTranscriptSegment(UUID sessionId, TranscriptSegmentRequest segmentRequest) {
        if (segmentRequest == null || segmentRequest.rawUtteranceText() == null || segmentRequest.rawUtteranceText().isBlank()) {
            throw new IllegalArgumentException("rawUtteranceText is required.");
        }
        MutableEncounterSession session = requireActiveSession(sessionId);
        governanceService.assertTenantAiAllowed(session.facilityId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        SpeakerRole role = segmentRequest.speakerRole() != null ? segmentRequest.speakerRole() : SpeakerRole.UNSPECIFIED;
        return recordRedactedSegment(session, role, segmentRequest.rawUtteranceText(), segmentRequest.interim(), "client-stream");
    }

    /**
     * Returns the current Safe-Harbor redacted snapshot of an encounter transcription session.
     */
    public EncounterSessionSnapshot getSessionSnapshot(UUID sessionId) {
        MutableEncounterSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Clinical transcription session not found: " + sessionId);
        }
        return session.toSnapshot();
    }

    /**
     * Cancels an active encounter transcription session and closes any attached Gemini Live stream.
     */
    public EncounterSessionSnapshot cancelEncounterSession(UUID sessionId) {
        MutableEncounterSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Clinical transcription session not found: " + sessionId);
        }
        session.closeLiveHandleQuietly();
        session.setStatus(SessionStatus.CANCELLED);
        return session.toSnapshot();
    }

    /**
     * Finalizes a real-time clinical encounter session, closes any open Gemini Live stream, re-runs
     * a full-transcript Safe-Harbor pass (including any optional supplemental clinician notes),
     * generates a structured SOAP note and HL7 FHIR R4 drafts via {@code gemini-4-hydrogen}, and
     * registers the result under {@code DRAFT_PENDING_ATTESTATION}.
     */
    public ClinicalEncounterSoapResult finalizeSessionAndGenerateSoap(
            UUID sessionId,
            String supplementalClinicianNotes) {
        MutableEncounterSession session = requireActiveSession(sessionId);
        governanceService.assertTenantAiAllowed(session.facilityId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        session.closeLiveHandleQuietly();

        StringBuilder combined = new StringBuilder(session.buildAggregatedRedactedTranscript());
        if (supplementalClinicianNotes != null && !supplementalClinicianNotes.isBlank()) {
            RedactedClinicalPayload redactedNotes = redactEncounterTranscript(
                    supplementalClinicianNotes, session.redactionContext);
            session.mergeRedactionCounts(redactedNotes.redactionCountsByCategory());
            if (!combined.isEmpty()) {
                combined.append("\n");
            }
            combined.append("[Clinician Supplemental Notes] ").append(redactedNotes.redactedText());
        }

        String finalCandidate = combined.toString().trim();
        if (finalCandidate.isBlank()) {
            throw new IllegalStateException(
                    "Cannot generate SOAP note for session " + sessionId + ": no finalized transcript segments captured.");
        }

        // Defense-in-depth second Safe-Harbor verification pass over the combined multi-segment transcript
        RedactedClinicalPayload verifiedPayload = redactEncounterTranscript(finalCandidate, session.redactionContext);
        session.mergeRedactionCounts(verifiedPayload.redactionCountsByCategory());

        Map<String, Integer> cumulativeRedactions = session.snapshotRedactionCounts();
        int totalRedactions = cumulativeRedactions.values().stream().mapToInt(Integer::intValue).sum();

        TextGenerationResult synthesis = synthesizeSoapFromRedactedTranscript(
                session.specialty, verifiedPayload.redactedText());
        long totalLatencyMs = session.totalTranscriptionLatencyMs.get() + synthesis.latencyMs();

        JsonNode structuredDraft = parseJsonSafely(synthesis.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                session.facilityId,
                session.patientId,
                AiCapabilityType.AMBIENT_CLINICAL_SCRIBE,
                synthesis.modelId(),
                verifiedPayload.redactedSha256Hex(),
                cumulativeRedactions,
                structuredDraft.toString(),
                synthesis.tokenUsage(),
                totalLatencyMs,
                session.clinicianId
        );

        session.setStatus(SessionStatus.FINALIZED);

        return new ClinicalEncounterSoapResult(
                draft.draftId(),
                session.sessionId,
                draft.status().name(),
                session.lastTranscriptionModel.get(),
                synthesis.modelId(),
                verifiedPayload.redactedText(),
                structuredDraft,
                totalRedactions,
                cumulativeRedactions,
                totalLatencyMs
        );
    }

    /**
     * One-shot pipeline: transcribes uploaded encounter audio via {@code gemini-3.5-transcribe}
     * (when audio is provided) or ingests a raw transcript, enforces HIPAA Safe-Harbor PHI redaction,
     * synthesizes a structured SOAP note + HL7 FHIR R4 drafts via {@code gemini-4-hydrogen}, and
     * quarantines the draft under {@code DRAFT_PENDING_ATTESTATION}.
     */
    public ClinicalEncounterSoapResult transcribeAndGenerateSoap(EncounterTranscriptionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("EncounterTranscriptionRequest is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.AMBIENT_CLINICAL_SCRIBE);

        long totalLatencyMs = 0L;
        String transcriptionModelUsed = null;
        String transcriptSource = request.rawTranscriptText();

        if ((transcriptSource == null || transcriptSource.isBlank())
                && request.base64AudioData() != null && !request.base64AudioData().isBlank()) {
            String lang = (request.languageCode() == null || request.languageCode().isBlank())
                    ? "en" : request.languageCode().trim();
            TextGenerationResult transcription = geminiClient.transcribeClinicalAudio(
                    request.audioMimeType(),
                    request.base64AudioData(),
                    "Transcribe this medical consultation in language '" + lang
                            + "' accurately with speaker turns (Clinician / Patient). Do not invent clinical facts."
            );
            transcriptSource = transcription.text();
            transcriptionModelUsed = transcription.modelId();
            totalLatencyMs += transcription.latencyMs();
        }

        if (transcriptSource == null || transcriptSource.isBlank()) {
            throw new IllegalArgumentException("Either base64AudioData or rawTranscriptText is required.");
        }

        RedactionContext ctx = new RedactionContext(
                request.knownPatientNames() != null ? request.knownPatientNames() : List.of(),
                request.knownIdentifiers() != null ? request.knownIdentifiers() : List.of(),
                request.knownDateOfBirth()
        );
        RedactedClinicalPayload redacted = redactEncounterTranscript(transcriptSource, ctx);

        String specialty = (request.specialty() == null || request.specialty().isBlank())
                ? "General Medicine" : request.specialty().trim();
        TextGenerationResult synthesis = synthesizeSoapFromRedactedTranscript(specialty, redacted.redactedText());
        totalLatencyMs += synthesis.latencyMs();

        JsonNode structuredDraft = parseJsonSafely(synthesis.text());
        ClinicalAiDraftRecord draft = governanceService.registerDraft(
                request.facilityId(),
                request.patientId(),
                AiCapabilityType.AMBIENT_CLINICAL_SCRIBE,
                synthesis.modelId(),
                redacted.redactedSha256Hex(),
                redacted.redactionCountsByCategory(),
                structuredDraft.toString(),
                synthesis.tokenUsage(),
                totalLatencyMs,
                request.clinicianId()
        );

        return new ClinicalEncounterSoapResult(
                draft.draftId(),
                null,
                draft.status().name(),
                transcriptionModelUsed,
                synthesis.modelId(),
                redacted.redactedText(),
                structuredDraft,
                redacted.totalRedactions(),
                redacted.redactionCountsByCategory(),
                totalLatencyMs
        );
    }

    /**
     * Pure local HIPAA Safe-Harbor redaction helper for encounter transcripts prior to storage or
     * outbound AI model synthesis.
     */
    public RedactedClinicalPayload redactEncounterTranscript(String rawTranscript, RedactionContext context) {
        if (rawTranscript == null || rawTranscript.isBlank()) {
            return phiRedactionGateway.redact("", context != null ? context : RedactionContext.empty());
        }
        String normalized = rawTranscript.replace("\r\n", "\n").trim();
        return phiRedactionGateway.redact(normalized, context != null ? context : RedactionContext.empty());
    }

    private RedactedTranscriptSegment recordRedactedSegment(
            MutableEncounterSession session,
            SpeakerRole speakerRole,
            String rawUtterance,
            boolean interim,
            String modelId) {
        RedactedClinicalPayload redacted = redactEncounterTranscript(rawUtterance, session.redactionContext);
        int seq = session.nextSequenceNumber();
        RedactedTranscriptSegment segment = new RedactedTranscriptSegment(
                seq,
                speakerRole,
                redacted.redactedText(),
                interim,
                redacted.totalRedactions(),
                redacted.redactionCountsByCategory(),
                modelId,
                Instant.now()
        );
        if (!interim) {
            session.appendFinalizedSegment(segment);
            session.mergeRedactionCounts(redacted.redactionCountsByCategory());
        } else {
            session.touch();
        }
        return segment;
    }

    private TextGenerationResult synthesizeSoapFromRedactedTranscript(String specialty, String redactedTranscript) {
        String systemInstruction = """
                You are a clinical documentation scribe embedded inside an HL7 FHIR R4 EMR/EHR platform.
                Convert the de-identified clinician-patient encounter transcript into an accurate, concise,
                clinician-ready SOAP note and structured HL7 FHIR R4 draft resources (Conditions, Observations,
                and MedicationRequests).
                Rules:
                1. Ground every finding strictly in the de-identified transcript; never fabricate vitals,
                   physical exam findings, allergies, or orders not mentioned.
                2. Preserve all HIPAA Safe-Harbor redaction tokens (e.g., [PATIENT_NAME_1], [MRN_1]) verbatim.
                3. Highlight any urgent red-flag symptoms or follow-up safety net instructions explicitly stated.
                Respond strictly as valid JSON matching the provided responseSchema.
                """;

        String prompt = "Clinical Specialty: " + specialty + "\n"
                + "De-identified Real-Time Encounter Transcript:\n" + redactedTranscript;

        return geminiClient.generateClinicalContent(
                systemInstruction,
                prompt,
                List.of(),
                buildSoapAndFhirSchema(),
                "LOW"
        );
    }

    private MutableEncounterSession requireActiveSession(UUID sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required.");
        }
        MutableEncounterSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Clinical transcription session not found: " + sessionId);
        }
        if (session.getStatus() != SessionStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Clinical transcription session " + sessionId + " is no longer ACTIVE (status=" + session.getStatus() + ").");
        }
        return session;
    }

    private ObjectNode buildSoapAndFhirSchema() {
        ObjectNode root = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode props = objectMapper.createObjectNode();

        props.set("chiefComplaint", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("subjective", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("objective", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("assessment", objectMapper.createObjectNode().put("type", "STRING"));
        props.set("plan", objectMapper.createObjectNode().put("type", "STRING"));

        ObjectNode condItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode condProps = objectMapper.createObjectNode();
        condProps.set("clinicalDisplay", objectMapper.createObjectNode().put("type", "STRING"));
        condProps.set("suggestedIcd10", objectMapper.createObjectNode().put("type", "STRING"));
        condProps.set("clinicalStatus", objectMapper.createObjectNode().put("type", "STRING"));
        condItem.set("properties", condProps);
        props.set("fhirConditionDrafts", objectMapper.createObjectNode().put("type", "ARRAY").set("items", condItem));

        ObjectNode obsItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode obsProps = objectMapper.createObjectNode();
        obsProps.set("loincDisplay", objectMapper.createObjectNode().put("type", "STRING"));
        obsProps.set("value", objectMapper.createObjectNode().put("type", "STRING"));
        obsProps.set("unit", objectMapper.createObjectNode().put("type", "STRING"));
        obsItem.set("properties", obsProps);
        props.set("fhirObservationDrafts", objectMapper.createObjectNode().put("type", "ARRAY").set("items", obsItem));

        ObjectNode medItem = objectMapper.createObjectNode().put("type", "OBJECT");
        ObjectNode medProps = objectMapper.createObjectNode();
        medProps.set("medicationDisplay", objectMapper.createObjectNode().put("type", "STRING"));
        medProps.set("dosageInstruction", objectMapper.createObjectNode().put("type", "STRING"));
        medItem.set("properties", medProps);
        props.set("fhirMedicationOrderDrafts", objectMapper.createObjectNode().put("type", "ARRAY").set("items", medItem));

        props.set("followUpActions", objectMapper.createObjectNode()
                .put("type", "ARRAY")
                .set("items", objectMapper.createObjectNode().put("type", "STRING")));
        props.set("redFlagWarnings", objectMapper.createObjectNode()
                .put("type", "ARRAY")
                .set("items", objectMapper.createObjectNode().put("type", "STRING")));

        root.set("properties", props);
        ArrayNode required = objectMapper.createArrayNode()
                .add("chiefComplaint").add("subjective").add("objective").add("assessment").add("plan");
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

    private static final class MutableEncounterSession {
        final UUID sessionId;
        final UUID facilityId;
        final UUID patientId;
        final UUID encounterId;
        final String clinicianId;
        final String specialty;
        final List<String> languageCodes;
        final RedactionContext redactionContext;
        final Instant startedAt = Instant.now();
        final List<RedactedTranscriptSegment> finalizedSegments = new CopyOnWriteArrayList<>();
        final Map<String, Integer> cumulativeRedactions = new ConcurrentHashMap<>();
        final AtomicInteger sequenceCounter = new AtomicInteger(0);
        final AtomicLong totalTranscriptionLatencyMs = new AtomicLong(0L);
        final AtomicReference<String> lastTranscriptionModel = new AtomicReference<>(null);
        final AtomicReference<LiveTranscriptionHandle> liveHandle = new AtomicReference<>(null);
        final AtomicReference<SessionStatus> status = new AtomicReference<>(SessionStatus.ACTIVE);
        volatile Instant updatedAt = startedAt;

        MutableEncounterSession(
                UUID sessionId,
                UUID facilityId,
                UUID patientId,
                UUID encounterId,
                String clinicianId,
                String specialty,
                List<String> languageCodes,
                RedactionContext redactionContext) {
            this.sessionId = sessionId;
            this.facilityId = facilityId;
            this.patientId = patientId;
            this.encounterId = encounterId;
            this.clinicianId = clinicianId;
            this.specialty = specialty;
            this.languageCodes = languageCodes;
            this.redactionContext = redactionContext;
        }

        int nextSequenceNumber() {
            return sequenceCounter.incrementAndGet();
        }

        void appendFinalizedSegment(RedactedTranscriptSegment segment) {
            finalizedSegments.add(segment);
            updatedAt = Instant.now();
        }

        void touch() {
            updatedAt = Instant.now();
        }

        void addTranscriptionLatency(long latencyMs) {
            totalTranscriptionLatencyMs.addAndGet(Math.max(0L, latencyMs));
        }

        void recordTranscriptionModel(String modelId) {
            if (modelId != null && !modelId.isBlank()) {
                lastTranscriptionModel.set(modelId);
            }
        }

        void attachLiveHandle(LiveTranscriptionHandle handle) {
            LiveTranscriptionHandle previous = liveHandle.getAndSet(handle);
            if (previous != null) {
                try {
                    previous.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup of replaced handle
                }
            }
            if (handle != null) {
                lastTranscriptionModel.set(handle.modelId());
            }
            updatedAt = Instant.now();
        }

        void closeLiveHandleQuietly() {
            LiveTranscriptionHandle current = liveHandle.getAndSet(null);
            if (current != null) {
                try {
                    current.close();
                } catch (Exception ignored) {
                    // Best-effort close
                }
            }
        }

        void mergeRedactionCounts(Map<String, Integer> counts) {
            if (counts == null) {
                return;
            }
            counts.forEach((k, v) -> cumulativeRedactions.merge(k, v, Integer::sum));
        }

        Map<String, Integer> snapshotRedactionCounts() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(cumulativeRedactions));
        }

        SessionStatus getStatus() {
            return status.get();
        }

        void setStatus(SessionStatus next) {
            status.set(next);
            updatedAt = Instant.now();
        }

        String buildAggregatedRedactedTranscript() {
            List<String> lines = new ArrayList<>(finalizedSegments.size());
            for (RedactedTranscriptSegment seg : finalizedSegments) {
                String prefix = seg.speakerRole() != SpeakerRole.UNSPECIFIED
                        ? "[" + seg.speakerRole().name() + "] " : "";
                lines.add(prefix + seg.redactedText());
            }
            return String.join("\n", lines);
        }

        EncounterSessionSnapshot toSnapshot() {
            Map<String, Integer> redactions = snapshotRedactionCounts();
            int totalRedactions = redactions.values().stream().mapToInt(Integer::intValue).sum();
            LiveTranscriptionHandle currentHandle = liveHandle.get();
            boolean connected = currentHandle != null && currentHandle.isOpen();
            return new EncounterSessionSnapshot(
                    sessionId,
                    facilityId,
                    patientId,
                    encounterId,
                    clinicianId,
                    specialty,
                    status.get(),
                    connected,
                    finalizedSegments.size(),
                    buildAggregatedRedactedTranscript(),
                    totalRedactions,
                    redactions,
                    totalTranscriptionLatencyMs.get(),
                    startedAt,
                    updatedAt
            );
        }
    }
}
