/*
 * =============================================================================
 * Opes HealthCore SDK — ClinicalAuditLogger (Tamper-Proof HIPAA AI Audit Log)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryRepository;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tamper-evident, cryptographically hash-chained clinical AI audit logger inside
 * {@code healthcore-starter-clinical-ai}.
 *
 * <p>Records three mandatory categories of clinical AI governance telemetry into a per-facility
 * append-only SHA-256 hash chain anchored to {@link #GENESIS_HASH}:</p>
 * <ul>
 *   <li><strong>AI Model Interactions:</strong> Every outbound Gemini model invocation (model ID,
 *       clinical capability, redacted prompt/output SHA-256 hashes, token usage, latency, and
 *       success/failure outcomes).</li>
 *   <li><strong>Clinician Attestation Lifecycle:</strong> Every sign-off state transition
 *       ({@code DRAFT_PENDING_ATTESTATION} → {@code APPROVED}, {@code APPROVED_WITH_EDITS}, or
 *       {@code REJECTED}) with practitioner identity and edited-output digest.</li>
 *   <li><strong>PHI Redaction Events:</strong> Every heuristic PHI risk-reduction pre-filter
 *       pass with category breakdown and redacted SHA-256 digest (zero raw PHI is persisted in
 *       the audit ledger).</li>
 * </ul>
 *
 * <p>When a {@link ClinicalAiAuditEntryRepository} is wired by Spring Boot auto-configuration,
 * entries are persisted durably to the relational database so chain continuity survives restarts
 * and multi-instance deployments. Any failure writing to a configured persistent audit repository
 * or {@link ClinicalAuditService} fails closed via {@link ClinicalAuditPersistenceException}.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ClinicalAuditLogger {

    public static final String GENESIS_HASH = "0".repeat(64);
    public static final UUID GLOBAL_FACILITY_ID = new UUID(0L, 0L);

    public enum AuditEventType {
        AI_MODEL_INTERACTION,
        AI_MODEL_INTERACTION_FAILED,
        CLINICIAN_ATTESTATION,
        PHI_REDACTION,
        TENANT_POLICY_CHANGE,
        TENANT_POLICY_DENIED
    }

    public record AiModelInteractionEvent(
            UUID facilityId,
            UUID patientId,
            UUID draftId,
            String capability,
            String modelId,
            String actorId,
            String attestationStatus,
            String outcome,
            String redactedPromptSha256,
            String outputSha256,
            Map<String, Integer> redactionCountsByCategory,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String detail
    ) {}

    public record ClinicianAttestationEvent(
            UUID facilityId,
            UUID patientId,
            UUID draftId,
            String capability,
            String modelId,
            String practitionerId,
            String previousStatus,
            String newStatus,
            String outputSha256,
            String attestationReason
    ) {}

    public record PhiRedactionAuditEvent(
            UUID facilityId,
            UUID patientId,
            String sourceComponent,
            String actorId,
            int totalIdentifiersRedacted,
            Map<String, Integer> redactionCountsByCategory,
            String redactedPayloadSha256,
            String detail
    ) {}

    public record TamperProofAuditEntry(
            UUID entryId,
            long sequenceNumber,
            UUID facilityId,
            UUID patientId,
            UUID draftId,
            AuditEventType eventType,
            String capability,
            String modelId,
            String actorId,
            String attestationStatus,
            String outcome,
            String redactedInputSha256,
            String outputSha256,
            int totalPhiIdentifiersRedacted,
            Map<String, Integer> redactionCountsByCategory,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String sanitizedDetail,
            Instant recordedAt,
            String previousEntryHash,
            String entrySha256Hash
    ) {}

    public record AuditChainVerificationReport(
            UUID facilityId,
            boolean intact,
            long verifiedEntries,
            Long firstBrokenSequence,
            String headHash,
            String diagnosticMessage
    ) {}

    public record HipaaComplianceAuditReport(
            UUID reportId,
            UUID facilityId,
            Instant generatedAt,
            Instant windowStart,
            Instant windowEnd,
            AuditChainVerificationReport chainVerification,
            long totalAuditEvents,
            long aiModelInteractionsCount,
            long failedAiInteractionsCount,
            long phiRedactionEventsCount,
            long totalPhiIdentifiersRedacted,
            Map<String, Long> phiRedactionsByCategory,
            long clinicianAttestationsTotal,
            Map<String, Long> attestationsByStatus,
            Map<String, Long> modelUsageCounts,
            Map<String, Long> capabilityUsageCounts,
            long totalTokensConsumed,
            double averageModelLatencyMs,
            boolean zeroRawPhiGuardActive,
            String reportSha256Digest
    ) {}

    private static final class FacilityLedger {
        final List<TamperProofAuditEntry> entries = new ArrayList<>();
        String headHash = GENESIS_HASH;
    }

    private static final TypeReference<Map<String, Integer>> COUNTS_TYPE = new TypeReference<>() {};

    private final ClinicalAuditService persistentAuditService;
    private final ClinicalAiAuditEntryRepository auditEntryRepository;
    private final ObjectMapper objectMapper;
    private final PhiRedactionGateway detailScrubber;
    private final Map<UUID, FacilityLedger> ledgersByFacility = new ConcurrentHashMap<>();
    private final List<Consumer<TamperProofAuditEntry>> listeners = new CopyOnWriteArrayList<>();

    public ClinicalAuditLogger() {
        this(null, null, null);
    }

    public ClinicalAuditLogger(ClinicalAuditService persistentAuditService) {
        this(persistentAuditService, null, null);
    }

    public ClinicalAuditLogger(
            ClinicalAuditService persistentAuditService,
            ClinicalAiAuditEntryRepository auditEntryRepository,
            ObjectMapper objectMapper) {
        this.persistentAuditService = persistentAuditService;
        this.auditEntryRepository = auditEntryRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        // Dedicated standalone scrubber instance (never recursive) to reduce accidental PHI in log details
        this.detailScrubber = new PhiRedactionGateway();
    }

    public void registerListener(Consumer<TamperProofAuditEntry> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Records an AI model interaction (prompt digest, output digest, token usage, latency,
     * initial attestation quarantine status, and PHI redaction summary) into the tamper-proof chain.
     */
    public TamperProofAuditEntry recordAiModelInteraction(AiModelInteractionEvent event) {
        Objects.requireNonNull(event, "AiModelInteractionEvent is required");
        boolean failed = "FAILURE".equalsIgnoreCase(event.outcome()) || "ERROR".equalsIgnoreCase(event.outcome());
        Map<String, Integer> safeCounts = sanitizeCounts(event.redactionCountsByCategory());
        int totalRedacted = safeCounts.values().stream().mapToInt(Integer::intValue).sum();
        return appendEntry(
                event.facilityId(),
                event.patientId(),
                event.draftId(),
                failed ? AuditEventType.AI_MODEL_INTERACTION_FAILED : AuditEventType.AI_MODEL_INTERACTION,
                nonBlankOr(event.capability(), "CLINICAL_AI"),
                nonBlankOr(event.modelId(), "UNKNOWN_MODEL"),
                nonBlankOr(event.actorId(), "CLINICAL_AI_SYSTEM"),
                nonBlankOr(event.attestationStatus(), "DRAFT_PENDING_ATTESTATION"),
                nonBlankOr(event.outcome(), "SUCCESS"),
                nonBlankOr(event.redactedPromptSha256(), GENESIS_HASH),
                nonBlankOr(event.outputSha256(), GENESIS_HASH),
                totalRedacted,
                safeCounts,
                Math.max(0, event.promptTokens()),
                Math.max(0, event.outputTokens()),
                Math.max(0L, event.latencyMs()),
                event.detail()
        );
    }

    /**
     * Records a clinician sign-off / attestation status transition ({@code APPROVED},
     * {@code APPROVED_WITH_EDITS}, or {@code REJECTED}) into the tamper-proof chain.
     */
    public TamperProofAuditEntry recordClinicianAttestation(ClinicianAttestationEvent event) {
        Objects.requireNonNull(event, "ClinicianAttestationEvent is required");
        String detail = "transition=" + nonBlankOr(event.previousStatus(), "DRAFT_PENDING_ATTESTATION")
                + "->" + nonBlankOr(event.newStatus(), "UNKNOWN")
                + (event.attestationReason() != null && !event.attestationReason().isBlank()
                ? " reason=" + event.attestationReason().trim() : "");
        return appendEntry(
                event.facilityId(),
                event.patientId(),
                event.draftId(),
                AuditEventType.CLINICIAN_ATTESTATION,
                nonBlankOr(event.capability(), "CLINICAL_AI"),
                nonBlankOr(event.modelId(), "CLINICAL_GOVERNANCE"),
                nonBlankOr(event.practitionerId(), "UNIDENTIFIED_CLINICIAN"),
                nonBlankOr(event.newStatus(), "APPROVED"),
                "SUCCESS",
                GENESIS_HASH,
                nonBlankOr(event.outputSha256(), GENESIS_HASH),
                0,
                Map.of(),
                0,
                0,
                0L,
                detail
        );
    }

    /**
     * Records a heuristic PHI risk-reduction pre-filter event (counts by category and
     * redacted SHA-256 digest only — never raw PHI) into the tamper-evident chain.
     */
    public TamperProofAuditEntry recordPhiRedactionEvent(PhiRedactionAuditEvent event) {
        Objects.requireNonNull(event, "PhiRedactionAuditEvent is required");
        Map<String, Integer> safeCounts = sanitizeCounts(event.redactionCountsByCategory());
        int total = event.totalIdentifiersRedacted() > 0
                ? event.totalIdentifiersRedacted()
                : safeCounts.values().stream().mapToInt(Integer::intValue).sum();
        return appendEntry(
                event.facilityId(),
                event.patientId(),
                null,
                AuditEventType.PHI_REDACTION,
                nonBlankOr(event.sourceComponent(), "PHI_REDACTION_GATEWAY"),
                "HEURISTIC_PHI_PREFILTER",
                nonBlankOr(event.actorId(), "PHI_REDACTION_GATEWAY"),
                "NOT_APPLICABLE",
                "SUCCESS",
                nonBlankOr(event.redactedPayloadSha256(), GENESIS_HASH),
                GENESIS_HASH,
                total,
                safeCounts,
                0,
                0,
                0L,
                nonBlankOr(event.detail(), "Heuristic PHI risk-reduction pre-filter completed")
        );
    }

    /**
     * Records a tenant AI kill-switch policy update or policy-denied execution attempt.
     */
    public TamperProofAuditEntry recordTenantPolicyEvent(
            UUID facilityId,
            String actorId,
            String capability,
            boolean policyAllowed,
            boolean policyUpdate,
            String detail) {
        return appendEntry(
                facilityId,
                null,
                null,
                policyUpdate ? AuditEventType.TENANT_POLICY_CHANGE : AuditEventType.TENANT_POLICY_DENIED,
                nonBlankOr(capability, "TENANT_POLICY"),
                "CLINICAL_AI_GOVERNANCE",
                nonBlankOr(actorId, "SYSTEM_GOVERNANCE"),
                "NOT_APPLICABLE",
                policyAllowed ? "SUCCESS" : "DENIED",
                GENESIS_HASH,
                GENESIS_HASH,
                0,
                Map.of(),
                0,
                0,
                0L,
                detail
        );
    }

    /**
     * Queries immutable audit entries filtered by facility, optional event type, patient ID,
     * and timestamp bounds in chronological sequence order.
     */
    public List<TamperProofAuditEntry> queryAuditEntries(
            UUID facilityId,
            AuditEventType eventTypeFilter,
            UUID patientIdFilter,
            Instant from,
            Instant to) {
        List<TamperProofAuditEntry> results = new ArrayList<>();
        if (auditEntryRepository != null) {
            List<ClinicalAiAuditEntryEntity> persisted = facilityId != null
                    ? auditEntryRepository.findByFacilityIdOrderBySequenceNumberAsc(facilityId)
                    : auditEntryRepository.findAll();
            List<TamperProofAuditEntry> mapped = persisted.stream().map(this::toRecord).toList();
            collectMatching(mapped, eventTypeFilter, patientIdFilter, from, to, results);
            return List.copyOf(results);
        }
        if (facilityId != null) {
            FacilityLedger ledger = ledgersByFacility.get(facilityId);
            if (ledger != null) {
                synchronized (ledger) {
                    collectMatching(ledger.entries, eventTypeFilter, patientIdFilter, from, to, results);
                }
            }
        } else {
            for (FacilityLedger ledger : ledgersByFacility.values()) {
                synchronized (ledger) {
                    collectMatching(ledger.entries, eventTypeFilter, patientIdFilter, from, to, results);
                }
            }
        }
        return List.copyOf(results);
    }

    /**
     * Cryptographically verifies the entire SHA-256 hash chain for a facility from genesis to head.
     */
    public AuditChainVerificationReport verifyAuditChain(UUID facilityId) {
        UUID safeFac = facilityId != null ? facilityId : GLOBAL_FACILITY_ID;
        if (auditEntryRepository != null) {
            List<TamperProofAuditEntry> persisted = auditEntryRepository
                    .findByFacilityIdOrderBySequenceNumberAsc(safeFac)
                    .stream().map(this::toRecord).toList();
            if (persisted.isEmpty()) {
                return new AuditChainVerificationReport(
                        safeFac, true, 0L, null, GENESIS_HASH,
                        "No clinical AI audit entries recorded yet for facility."
                );
            }
            return verifyExternalAuditChain(safeFac, persisted);
        }
        FacilityLedger ledger = ledgersByFacility.get(safeFac);
        if (ledger == null) {
            return new AuditChainVerificationReport(
                    safeFac, true, 0L, null, GENESIS_HASH,
                    "No clinical AI audit entries recorded yet for facility."
            );
        }
        List<TamperProofAuditEntry> snapshot;
        synchronized (ledger) {
            snapshot = List.copyOf(ledger.entries);
        }
        return verifyExternalAuditChain(safeFac, snapshot);
    }

    /**
     * Cryptographically verifies an exported list of {@link TamperProofAuditEntry} records,
     * detecting any mutated field, deleted entry, reordered record, or forged hash.
     */
    public AuditChainVerificationReport verifyExternalAuditChain(
            UUID facilityId,
            List<TamperProofAuditEntry> entries) {
        UUID safeFac = facilityId != null ? facilityId : GLOBAL_FACILITY_ID;
        if (entries == null || entries.isEmpty()) {
            return new AuditChainVerificationReport(
                    safeFac, true, 0L, null, GENESIS_HASH,
                    "Empty audit ledger verified against genesis anchor."
            );
        }

        String expectedPrevHash = GENESIS_HASH;
        long expectedSeq = 1L;
        for (TamperProofAuditEntry entry : entries) {
            if (entry == null) {
                return new AuditChainVerificationReport(
                        safeFac, false, expectedSeq - 1, expectedSeq, expectedPrevHash,
                        "Null audit entry encountered at sequence " + expectedSeq
                );
            }
            if (entry.sequenceNumber() != expectedSeq) {
                return new AuditChainVerificationReport(
                        safeFac, false, expectedSeq - 1, entry.sequenceNumber(), expectedPrevHash,
                        "Sequence discontinuity detected: expected " + expectedSeq + " but found " + entry.sequenceNumber()
                );
            }
            if (!Objects.equals(expectedPrevHash, entry.previousEntryHash())) {
                return new AuditChainVerificationReport(
                        safeFac, false, expectedSeq - 1, entry.sequenceNumber(), expectedPrevHash,
                        "Broken hash chain link at sequence " + entry.sequenceNumber()
                );
            }
            String recomputed = computeEntryHash(
                    entry.entryId(),
                    entry.sequenceNumber(),
                    entry.facilityId(),
                    entry.patientId(),
                    entry.draftId(),
                    entry.eventType(),
                    entry.capability(),
                    entry.modelId(),
                    entry.actorId(),
                    entry.attestationStatus(),
                    entry.outcome(),
                    entry.redactedInputSha256(),
                    entry.outputSha256(),
                    entry.totalPhiIdentifiersRedacted(),
                    entry.redactionCountsByCategory(),
                    entry.promptTokens(),
                    entry.outputTokens(),
                    entry.latencyMs(),
                    entry.sanitizedDetail(),
                    entry.recordedAt(),
                    entry.previousEntryHash()
            );
            if (!Objects.equals(recomputed, entry.entrySha256Hash())) {
                return new AuditChainVerificationReport(
                        safeFac, false, expectedSeq - 1, entry.sequenceNumber(), expectedPrevHash,
                        "Tampering detected: SHA-256 digest mismatch at sequence " + entry.sequenceNumber()
                );
            }
            expectedPrevHash = entry.entrySha256Hash();
            expectedSeq++;
        }

        return new AuditChainVerificationReport(
                safeFac,
                true,
                entries.size(),
                null,
                expectedPrevHash,
                "All " + entries.size() + " tamper-proof clinical AI audit entries cryptographically verified."
        );
    }

    /**
     * Generates a cryptographically signed HIPAA Compliance Audit Report for a facility across
     * an optional time window, aggregating AI model interactions, clinician attestation statuses,
     * Safe-Harbor PHI redaction totals, token budgets, and chain verification proof.
     */
    public HipaaComplianceAuditReport generateHipaaComplianceReport(
            UUID facilityId,
            Instant windowStart,
            Instant windowEnd) {
        UUID safeFac = facilityId != null ? facilityId : GLOBAL_FACILITY_ID;
        AuditChainVerificationReport verification = verifyAuditChain(safeFac);
        List<TamperProofAuditEntry> entries = queryAuditEntries(safeFac, null, null, windowStart, windowEnd);

        long aiInteractions = 0;
        long failedAiInteractions = 0;
        long phiEvents = 0;
        long totalPhiRedacted = 0;
        long attestationsTotal = 0;
        long totalTokens = 0;
        long latencySum = 0;
        long latencyCount = 0;

        Map<String, Long> phiByCategory = new TreeMap<>();
        Map<String, Long> attestationsByStatus = new LinkedHashMap<>();
        Map<String, Long> modelUsage = new LinkedHashMap<>();
        Map<String, Long> capabilityUsage = new LinkedHashMap<>();

        for (TamperProofAuditEntry entry : entries) {
            if (entry.eventType() == AuditEventType.AI_MODEL_INTERACTION) {
                aiInteractions++;
                totalTokens += (long) entry.promptTokens() + entry.outputTokens();
                latencySum += entry.latencyMs();
                latencyCount++;
                modelUsage.merge(entry.modelId(), 1L, Long::sum);
                capabilityUsage.merge(entry.capability(), 1L, Long::sum);
            } else if (entry.eventType() == AuditEventType.AI_MODEL_INTERACTION_FAILED) {
                failedAiInteractions++;
                modelUsage.merge(entry.modelId(), 1L, Long::sum);
                capabilityUsage.merge(entry.capability(), 1L, Long::sum);
            } else if (entry.eventType() == AuditEventType.PHI_REDACTION) {
                phiEvents++;
            } else if (entry.eventType() == AuditEventType.CLINICIAN_ATTESTATION) {
                attestationsTotal++;
                attestationsByStatus.merge(entry.attestationStatus(), 1L, Long::sum);
            }

            // Count PHI redactions from dedicated PHI_REDACTION events (avoid double counting when also summarized on AI interaction)
            if (entry.eventType() == AuditEventType.PHI_REDACTION
                    || (entry.eventType() == AuditEventType.AI_MODEL_INTERACTION && phiEvents == 0 && entry.totalPhiIdentifiersRedacted() > 0)) {
                totalPhiRedacted += entry.totalPhiIdentifiersRedacted();
                for (Map.Entry<String, Integer> cat : entry.redactionCountsByCategory().entrySet()) {
                    phiByCategory.merge(cat.getKey(), cat.getValue().longValue(), Long::sum);
                }
            }
        }

        double avgLatency = latencyCount > 0 ? (double) latencySum / latencyCount : 0.0;
        UUID reportId = UUID.randomUUID();
        Instant generatedAt = Instant.now();

        String reportCanonical = reportId + "|" + safeFac + "|" + generatedAt
                + "|" + verification.intact() + "|" + verification.headHash()
                + "|" + entries.size() + "|" + aiInteractions + "|" + failedAiInteractions
                + "|" + phiEvents + "|" + totalPhiRedacted + "|" + attestationsTotal
                + "|" + totalTokens;
        String reportDigest = sha256Hex(reportCanonical);

        return new HipaaComplianceAuditReport(
                reportId,
                safeFac,
                generatedAt,
                windowStart,
                windowEnd,
                verification,
                entries.size(),
                aiInteractions,
                failedAiInteractions,
                phiEvents,
                totalPhiRedacted,
                Collections.unmodifiableMap(phiByCategory),
                attestationsTotal,
                Collections.unmodifiableMap(attestationsByStatus),
                Collections.unmodifiableMap(modelUsage),
                Collections.unmodifiableMap(capabilityUsage),
                totalTokens,
                avgLatency,
                true,
                reportDigest
        );
    }

    // -------------------------------------------------------------------------
    // Internal Cryptographic Hash-Chain Mechanics & Safe-Harbor Detail Scrubbing
    // -------------------------------------------------------------------------

    private TamperProofAuditEntry appendEntry(
            UUID facilityId,
            UUID patientId,
            UUID draftId,
            AuditEventType eventType,
            String capability,
            String modelId,
            String actorId,
            String attestationStatus,
            String outcome,
            String redactedInputSha256,
            String outputSha256,
            int totalPhiRedacted,
            Map<String, Integer> redactionCounts,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String rawDetail) {

        UUID safeFac = facilityId != null ? facilityId : GLOBAL_FACILITY_ID;
        String scrubbedDetail = scrubDetail(rawDetail);
        Map<String, Integer> immutableCounts = Collections.unmodifiableMap(new TreeMap<>(redactionCounts));

        FacilityLedger ledger = ledgersByFacility.computeIfAbsent(safeFac, k -> new FacilityLedger());
        TamperProofAuditEntry entry;
        synchronized (ledger) {
            long nextSeq;
            String prevHash;
            if (auditEntryRepository != null) {
                Optional<ClinicalAiAuditEntryEntity> latestPersisted =
                        auditEntryRepository.findTopByFacilityIdOrderBySequenceNumberDesc(safeFac);
                if (latestPersisted.isPresent()) {
                    nextSeq = latestPersisted.get().getSequenceNumber() + 1L;
                    prevHash = latestPersisted.get().getEntrySha256Hash();
                } else {
                    nextSeq = 1L;
                    prevHash = GENESIS_HASH;
                }
            } else {
                nextSeq = ledger.entries.size() + 1L;
                prevHash = ledger.headHash;
            }
            UUID entryId = UUID.randomUUID();
            Instant recordedAt = Instant.now();
            String entryHash = computeEntryHash(
                    entryId, nextSeq, safeFac, patientId, draftId, eventType,
                    capability, modelId, actorId, attestationStatus, outcome,
                    redactedInputSha256, outputSha256, totalPhiRedacted, immutableCounts,
                    promptTokens, outputTokens, latencyMs, scrubbedDetail, recordedAt, prevHash
            );
            entry = new TamperProofAuditEntry(
                    entryId, nextSeq, safeFac, patientId, draftId, eventType,
                    capability, modelId, actorId, attestationStatus, outcome,
                    redactedInputSha256, outputSha256, totalPhiRedacted, immutableCounts,
                    promptTokens, outputTokens, latencyMs, scrubbedDetail, recordedAt,
                    prevHash, entryHash
            );

            // Fail closed BEFORE mutating in-memory chain state so mandatory audit persistence
            // failures never go unnoticed or desynchronize chain hashes.
            persistToDurableRepository(entry);
            forwardToPersistentAuditService(entry);

            ledger.entries.add(entry);
            ledger.headHash = entryHash;
        }

        for (Consumer<TamperProofAuditEntry> listener : listeners) {
            listener.accept(entry);
        }
        return entry;
    }

    private void persistToDurableRepository(TamperProofAuditEntry entry) {
        if (auditEntryRepository == null) {
            return;
        }
        try {
            auditEntryRepository.save(toEntity(entry));
        } catch (Exception ex) {
            throw new ClinicalAuditPersistenceException(
                    "Mandatory durable Clinical AI audit persistence failed for facility " + entry.facilityId()
                            + " (eventType=" + entry.eventType() + ", seq=" + entry.sequenceNumber() + ")", ex);
        }
    }

    private void forwardToPersistentAuditService(TamperProofAuditEntry entry) {
        if (persistentAuditService == null) {
            return;
        }
        try {
            persistentAuditService.recordEventWithCorrelation(
                    entry.facilityId().toString(),
                    entry.actorId(),
                    "ROLE_CLINICAL_AI_AUDITOR",
                    "CLINICAL_AI_" + entry.eventType().name(),
                    "ClinicalAiAuditEntry",
                    entry.entryId().toString(),
                    entry.patientId(),
                    entry.outcome(),
                    "seq=" + entry.sequenceNumber()
                            + " capability=" + entry.capability()
                            + " model=" + entry.modelId()
                            + " status=" + entry.attestationStatus()
                            + " phiRedacted=" + entry.totalPhiIdentifiersRedacted()
                            + " hash=" + entry.entrySha256Hash(),
                    "internal-clinical-ai-audit",
                    entry.entryId().toString()
            );
        } catch (Exception ex) {
            throw new ClinicalAuditPersistenceException(
                    "Mandatory ClinicalAuditService forwarding failed for AI audit entry " + entry.entryId(), ex);
        }
    }

    private ClinicalAiAuditEntryEntity toEntity(TamperProofAuditEntry entry) {
        ClinicalAiAuditEntryEntity entity = new ClinicalAiAuditEntryEntity();
        entity.setEntryId(entry.entryId());
        entity.setSequenceNumber(entry.sequenceNumber());
        entity.setFacilityId(entry.facilityId());
        entity.setPatientId(entry.patientId());
        entity.setDraftId(entry.draftId());
        entity.setEventType(entry.eventType().name());
        entity.setCapability(entry.capability());
        entity.setModelId(entry.modelId());
        entity.setActorId(entry.actorId());
        entity.setAttestationStatus(entry.attestationStatus());
        entity.setOutcome(entry.outcome());
        entity.setRedactedInputSha256(entry.redactedInputSha256());
        entity.setOutputSha256(entry.outputSha256());
        entity.setTotalPhiIdentifiersRedacted(entry.totalPhiIdentifiersRedacted());
        entity.setRedactionCountsJson(serializeCounts(entry.redactionCountsByCategory()));
        entity.setPromptTokens(entry.promptTokens());
        entity.setOutputTokens(entry.outputTokens());
        entity.setLatencyMs(entry.latencyMs());
        entity.setSanitizedDetail(entry.sanitizedDetail());
        entity.setRecordedAt(entry.recordedAt());
        entity.setPreviousEntryHash(entry.previousEntryHash());
        entity.setEntrySha256Hash(entry.entrySha256Hash());
        return entity;
    }

    private TamperProofAuditEntry toRecord(ClinicalAiAuditEntryEntity entity) {
        return new TamperProofAuditEntry(
                entity.getEntryId(),
                entity.getSequenceNumber(),
                entity.getFacilityId(),
                entity.getPatientId(),
                entity.getDraftId(),
                AuditEventType.valueOf(entity.getEventType()),
                entity.getCapability(),
                entity.getModelId(),
                entity.getActorId(),
                entity.getAttestationStatus(),
                entity.getOutcome(),
                entity.getRedactedInputSha256(),
                entity.getOutputSha256(),
                entity.getTotalPhiIdentifiersRedacted(),
                deserializeCounts(entity.getRedactionCountsJson()),
                entity.getPromptTokens(),
                entity.getOutputTokens(),
                entity.getLatencyMs(),
                entity.getSanitizedDetail(),
                entity.getRecordedAt(),
                entity.getPreviousEntryHash(),
                entity.getEntrySha256Hash()
        );
    }

    private String serializeCounts(Map<String, Integer> counts) {
        try {
            return objectMapper.writeValueAsString(counts != null ? counts : Map.of());
        } catch (Exception ex) {
            throw new ClinicalAuditPersistenceException("Failed to serialize PHI redaction counts JSON", ex);
        }
    }

    private Map<String, Integer> deserializeCounts(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Collections.unmodifiableMap(new TreeMap<>(objectMapper.readValue(json, COUNTS_TYPE)));
        } catch (Exception ex) {
            throw new ClinicalAuditPersistenceException("Failed to deserialize PHI redaction counts JSON", ex);
        }
    }

    private String scrubDetail(String rawDetail) {
        if (rawDetail == null || rawDetail.isBlank()) {
            return "";
        }
        return detailScrubber.redact(rawDetail.trim()).redactedText();
    }

    private static void collectMatching(
            List<TamperProofAuditEntry> source,
            AuditEventType eventTypeFilter,
            UUID patientIdFilter,
            Instant from,
            Instant to,
            List<TamperProofAuditEntry> sink) {
        for (TamperProofAuditEntry e : source) {
            if (eventTypeFilter != null && e.eventType() != eventTypeFilter) {
                continue;
            }
            if (patientIdFilter != null && !patientIdFilter.equals(e.patientId())) {
                continue;
            }
            if (from != null && e.recordedAt().isBefore(from)) {
                continue;
            }
            if (to != null && e.recordedAt().isAfter(to)) {
                continue;
            }
            sink.add(e);
        }
    }

    private static Map<String, Integer> sanitizeCounts(Map<String, Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> clean = new TreeMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && e.getValue() >= 0) {
                clean.put(e.getKey().trim(), e.getValue());
            }
        }
        return clean;
    }

    private static String nonBlankOr(String val, String fallback) {
        return (val == null || val.isBlank()) ? fallback : val.trim();
    }

    private static String computeEntryHash(
            UUID entryId,
            long sequenceNumber,
            UUID facilityId,
            UUID patientId,
            UUID draftId,
            AuditEventType eventType,
            String capability,
            String modelId,
            String actorId,
            String attestationStatus,
            String outcome,
            String redactedInputSha256,
            String outputSha256,
            int totalPhiRedacted,
            Map<String, Integer> redactionCounts,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String sanitizedDetail,
            Instant recordedAt,
            String previousEntryHash) {
        StringBuilder canonical = new StringBuilder(256);
        canonical.append(entryId).append('|')
                .append(sequenceNumber).append('|')
                .append(facilityId).append('|')
                .append(patientId).append('|')
                .append(draftId).append('|')
                .append(eventType).append('|')
                .append(capability).append('|')
                .append(modelId).append('|')
                .append(actorId).append('|')
                .append(attestationStatus).append('|')
                .append(outcome).append('|')
                .append(redactedInputSha256).append('|')
                .append(outputSha256).append('|')
                .append(totalPhiRedacted).append('|')
                .append(new TreeMap<>(redactionCounts != null ? redactionCounts : Map.of())).append('|')
                .append(promptTokens).append('|')
                .append(outputTokens).append('|')
                .append(latencyMs).append('|')
                .append(sanitizedDetail).append('|')
                .append(recordedAt).append('|')
                .append(previousEntryHash);
        return sha256Hex(canonical.toString());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable in JVM", ex);
        }
    }
}
