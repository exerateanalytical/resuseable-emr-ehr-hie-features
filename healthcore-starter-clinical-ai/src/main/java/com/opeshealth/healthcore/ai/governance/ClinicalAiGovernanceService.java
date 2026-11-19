/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI Governance & Mandatory Attestation Ledger
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.governance;

import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Enforces Human-in-the-Loop Clinical AI Governance across Opes HealthCore:
 * <ul>
 *   <li>Tenant-level AI kill-switch &amp; capability policy enforcement</li>
 *   <li>Mandatory {@code DRAFT_PENDING_ATTESTATION} quarantine before chart commitment</li>
 *   <li>Cryptographic SHA-256 prompt/output audit logging (zero raw PHI persisted in audit events)</li>
 *   <li>Practitioner attestation workflow ({@code APPROVE}, {@code APPROVE_WITH_EDITS}, {@code REJECT})</li>
 *   <li>Per-facility AI telemetry (redaction counts, latency, token budgets, approval rate)</li>
 * </ul>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public class ClinicalAiGovernanceService {

    public enum AiCapabilityType {
        AMBIENT_CLINICAL_SCRIBE,
        AUTOMATED_MEDICAL_CODING,
        CHART_AND_IPS_SUMMARIZER,
        MULTIMODAL_IMAGE_TRIAGE,
        CLINICAL_DECISION_SUPPORT,
        PRIOR_AUTH_AND_APPEALS,
        CALIBRATED_CLINICAL_DECISION,
        PATIENT_DISCHARGE_COACH,
        SEMANTIC_CLINICAL_SEARCH,
        PREDICTIVE_RISK_AND_PROGRESSION,
        CAPACITY_AND_STAFFING_FORECAST,
        MEDICATION_RECONCILIATION
    }

    public enum DraftAttestationStatus {
        DRAFT_PENDING_ATTESTATION,
        APPROVED,
        APPROVED_WITH_EDITS,
        REJECTED
    }

    public record ClinicalAiDraftRecord(
            UUID draftId,
            UUID facilityId,
            UUID patientId,
            AiCapabilityType capability,
            String modelId,
            DraftAttestationStatus status,
            String redactedPromptSha256,
            String outputSha256,
            Map<String, Integer> redactionCountsByCategory,
            int totalPhiRedactions,
            String structuredOutputJson,
            String clinicianEditedJson,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String createdBy,
            Instant createdAt,
            String attestedByPractitionerId,
            String attestationReason,
            Instant attestedAt
    ) {}

    public record AttestationCommand(
            String practitionerId,
            DraftAttestationStatus decision,
            String editedStructuredJson,
            String attestationReason
    ) {}

    public record TenantAiGovernanceMetrics(
            UUID facilityId,
            boolean aiEnabledForFacility,
            long totalDraftsGenerated,
            long pendingAttestationCount,
            long approvedCount,
            long approvedWithEditsCount,
            long rejectedCount,
            long totalPhiIdentifiersRedacted,
            long totalTokensConsumed,
            double averageLatencyMs,
            Map<String, Long> requestsByCapability
    ) {}

    private final ClinicalAuditService clinicalAuditService;
    private final Map<UUID, ClinicalAiDraftRecord> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tenantAiFlags = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> facilityLatencySumMs = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> facilityRequestCount = new ConcurrentHashMap<>();

    public ClinicalAiGovernanceService() {
        this(null);
    }

    public ClinicalAiGovernanceService(ClinicalAuditService clinicalAuditService) {
        this.clinicalAuditService = clinicalAuditService;
    }

    /**
     * Enables or disables Clinical AI execution for a specific healthcare facility tenant.
     */
    public boolean setTenantAiEnabled(UUID facilityId, boolean enabled) {
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        tenantAiFlags.put(safeFac, enabled);
        emitAudit(safeFac, null, "SYSTEM_GOVERNANCE", "CLINICAL_AI_TENANT_POLICY_UPDATE",
                safeFac.toString(), "SUCCESS", "aiEnabled=" + enabled);
        return enabled;
    }

    public boolean isTenantAiEnabled(UUID facilityId) {
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        return tenantAiFlags.getOrDefault(safeFac, true);
    }

    public void assertTenantAiAllowed(UUID facilityId, AiCapabilityType capability) {
        if (!isTenantAiEnabled(facilityId)) {
            emitAudit(facilityId, null, "SYSTEM_GOVERNANCE", "CLINICAL_AI_BLOCKED_BY_TENANT_POLICY",
                    capability.name(), "DENIED", "Clinical AI disabled for tenant");
            throw new IllegalStateException("Clinical AI is disabled for facility tenant " + facilityId
                    + " (capability=" + capability + ").");
        }
    }

    /**
     * Registers every clinical AI generation in {@code DRAFT_PENDING_ATTESTATION} state so no
     * AI-generated clinical artifact enters the chart without explicit clinician sign-off.
     */
    public ClinicalAiDraftRecord registerDraft(
            UUID facilityId,
            UUID patientId,
            AiCapabilityType capability,
            String modelId,
            String redactedPromptSha256,
            Map<String, Integer> redactionCounts,
            String structuredOutputJson,
            TokenUsage tokenUsage,
            long latencyMs,
            String createdBy) {
        UUID safeFacility = facilityId != null ? facilityId : new UUID(0L, 0L);
        UUID draftId = UUID.randomUUID();
        Map<String, Integer> safeCounts = redactionCounts != null ? Map.copyOf(redactionCounts) : Map.of();
        int totalRedactions = safeCounts.values().stream().mapToInt(Integer::intValue).sum();
        String outputHash = PhiRedactionGateway.sha256Hex(structuredOutputJson);
        int promptTok = tokenUsage != null ? tokenUsage.promptTokens() : 0;
        int outTok = tokenUsage != null ? tokenUsage.outputTokens() : 0;

        ClinicalAiDraftRecord record = new ClinicalAiDraftRecord(
                draftId,
                safeFacility,
                patientId,
                capability,
                modelId,
                DraftAttestationStatus.DRAFT_PENDING_ATTESTATION,
                redactedPromptSha256,
                outputHash,
                safeCounts,
                totalRedactions,
                structuredOutputJson,
                null,
                promptTok,
                outTok,
                latencyMs,
                (createdBy == null || createdBy.isBlank()) ? "CLINICIAN_SYSTEM" : createdBy.trim(),
                Instant.now(),
                null,
                null,
                null
        );
        drafts.put(draftId, record);
        facilityLatencySumMs.computeIfAbsent(safeFacility, k -> new LongAdder()).add(Math.max(0L, latencyMs));
        facilityRequestCount.computeIfAbsent(safeFacility, k -> new LongAdder()).increment();

        emitAudit(safeFacility, patientId, record.createdBy(), "CLINICAL_AI_DRAFT_CREATED",
                draftId.toString(), "SUCCESS",
                "capability=" + capability + " model=" + modelId + " phiRedacted=" + totalRedactions
                        + " promptSha256=" + redactedPromptSha256 + " outputSha256=" + outputHash);
        return record;
    }

    /**
     * Performs mandatory clinician sign-off on an AI draft ({@code APPROVED}, {@code APPROVED_WITH_EDITS},
     * or {@code REJECTED}).
     */
    public ClinicalAiDraftRecord attestDraft(UUID draftId, AttestationCommand command) {
        ClinicalAiDraftRecord existing = drafts.get(draftId);
        if (existing == null) {
            throw new NoSuchElementException("Clinical AI draft not found: " + draftId);
        }
        if (command == null || command.practitionerId() == null || command.practitionerId().isBlank()) {
            throw new IllegalArgumentException("Attesting practitionerId is required for clinical AI sign-off.");
        }
        if (command.decision() == null || command.decision() == DraftAttestationStatus.DRAFT_PENDING_ATTESTATION) {
            throw new IllegalArgumentException("Attestation decision must be APPROVED, APPROVED_WITH_EDITS, or REJECTED.");
        }
        if (command.decision() == DraftAttestationStatus.APPROVED_WITH_EDITS
                && (command.editedStructuredJson() == null || command.editedStructuredJson().isBlank())) {
            throw new IllegalArgumentException("editedStructuredJson is required when decision is APPROVED_WITH_EDITS.");
        }

        ClinicalAiDraftRecord updated = new ClinicalAiDraftRecord(
                existing.draftId(),
                existing.facilityId(),
                existing.patientId(),
                existing.capability(),
                existing.modelId(),
                command.decision(),
                existing.redactedPromptSha256(),
                command.editedStructuredJson() != null
                        ? PhiRedactionGateway.sha256Hex(command.editedStructuredJson())
                        : existing.outputSha256(),
                existing.redactionCountsByCategory(),
                existing.totalPhiRedactions(),
                existing.structuredOutputJson(),
                command.editedStructuredJson(),
                existing.promptTokens(),
                existing.outputTokens(),
                existing.latencyMs(),
                existing.createdBy(),
                existing.createdAt(),
                command.practitionerId().trim(),
                command.attestationReason() != null ? command.attestationReason().trim() : "Clinician reviewed and signed",
                Instant.now()
        );
        drafts.put(draftId, updated);

        emitAudit(existing.facilityId(), existing.patientId(), updated.attestedByPractitionerId(),
                "CLINICAL_AI_DRAFT_" + command.decision().name(),
                draftId.toString(), "SUCCESS",
                "capability=" + existing.capability() + " reason=" + updated.attestationReason());
        return updated;
    }

    public ClinicalAiDraftRecord getDraft(UUID draftId) {
        ClinicalAiDraftRecord found = drafts.get(draftId);
        if (found == null) {
            throw new NoSuchElementException("Clinical AI draft not found: " + draftId);
        }
        return found;
    }

    public List<ClinicalAiDraftRecord> listDrafts(UUID facilityId, DraftAttestationStatus statusFilter) {
        return drafts.values().stream()
                .filter(d -> facilityId == null || facilityId.equals(d.facilityId()))
                .filter(d -> statusFilter == null || statusFilter == d.status())
                .sorted(Comparator.comparing(ClinicalAiDraftRecord::createdAt).reversed())
                .toList();
    }

    public TenantAiGovernanceMetrics getTenantMetrics(UUID facilityId) {
        UUID safeFacility = facilityId != null ? facilityId : new UUID(0L, 0L);
        List<ClinicalAiDraftRecord> facDrafts = drafts.values().stream()
                .filter(d -> safeFacility.equals(d.facilityId()))
                .toList();

        long pending = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.DRAFT_PENDING_ATTESTATION).count();
        long approved = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.APPROVED).count();
        long edited = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.APPROVED_WITH_EDITS).count();
        long rejected = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.REJECTED).count();
        long phiTotal = facDrafts.stream().mapToLong(ClinicalAiDraftRecord::totalPhiRedactions).sum();
        long tokensTotal = facDrafts.stream().mapToLong(d -> (long) d.promptTokens() + d.outputTokens()).sum();

        long reqCount = facilityRequestCount.getOrDefault(safeFacility, new LongAdder()).sum();
        long latSum = facilityLatencySumMs.getOrDefault(safeFacility, new LongAdder()).sum();
        double avgLatency = reqCount > 0 ? (double) latSum / reqCount : 0.0;

        Map<String, Long> byCapability = new LinkedHashMap<>();
        for (AiCapabilityType cap : AiCapabilityType.values()) {
            long c = facDrafts.stream().filter(d -> d.capability() == cap).count();
            if (c > 0) {
                byCapability.put(cap.name(), c);
            }
        }

        return new TenantAiGovernanceMetrics(
                safeFacility,
                isTenantAiEnabled(safeFacility),
                facDrafts.size(),
                pending,
                approved,
                edited,
                rejected,
                phiTotal,
                tokensTotal,
                avgLatency,
                byCapability
        );
    }

    private void emitAudit(
            UUID facilityId,
            UUID patientId,
            String actor,
            String action,
            String resourceId,
            String outcome,
            String detail) {
        if (clinicalAuditService == null) {
            return;
        }
        try {
            clinicalAuditService.recordEventWithCorrelation(
                    facilityId != null ? facilityId.toString() : "GLOBAL",
                    actor,
                    "ROLE_CLINICAL_AI_ENGINE",
                    action,
                    "ClinicalAiDraft",
                    resourceId,
                    patientId,
                    outcome,
                    detail,
                    "internal-ai-gateway",
                    UUID.randomUUID().toString()
            );
        } catch (Exception ignored) {
            // Audit persistence errors never block governance state updates in standalone mode
        }
    }
}
