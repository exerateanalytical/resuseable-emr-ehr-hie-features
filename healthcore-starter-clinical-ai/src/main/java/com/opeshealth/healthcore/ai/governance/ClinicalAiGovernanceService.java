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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger.AiModelInteractionEvent;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger.ClinicianAttestationEvent;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger.PhiRedactionAuditEvent;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditPersistenceException;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyRepository;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;

import java.time.Instant;
import java.util.Collections;
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

    private static final TypeReference<Map<String, Integer>> COUNTS_TYPE = new TypeReference<>() {};

    private final ClinicalAuditService clinicalAuditService;
    private final ClinicalAuditLogger clinicalAuditLogger;
    private final ClinicalAiDraftRepository draftRepository;
    private final TenantAiPolicyRepository tenantPolicyRepository;
    private final ObjectMapper objectMapper;
    private final Map<UUID, ClinicalAiDraftRecord> drafts = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> tenantAiFlags = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> facilityLatencySumMs = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> facilityRequestCount = new ConcurrentHashMap<>();

    public ClinicalAiGovernanceService() {
        this(null, null, null, null, null);
    }

    public ClinicalAiGovernanceService(ClinicalAuditService clinicalAuditService) {
        this(clinicalAuditService, null, null, null, null);
    }

    public ClinicalAiGovernanceService(
            ClinicalAuditService clinicalAuditService,
            ClinicalAuditLogger clinicalAuditLogger) {
        this(clinicalAuditService, clinicalAuditLogger, null, null, null);
    }

    public ClinicalAiGovernanceService(
            ClinicalAuditService clinicalAuditService,
            ClinicalAuditLogger clinicalAuditLogger,
            ClinicalAiDraftRepository draftRepository,
            TenantAiPolicyRepository tenantPolicyRepository,
            ObjectMapper objectMapper) {
        this.clinicalAuditService = clinicalAuditService;
        this.clinicalAuditLogger = clinicalAuditLogger != null
                ? clinicalAuditLogger
                : new ClinicalAuditLogger(clinicalAuditService);
        this.draftRepository = draftRepository;
        this.tenantPolicyRepository = tenantPolicyRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public ClinicalAuditLogger getClinicalAuditLogger() {
        return clinicalAuditLogger;
    }

    /**
     * Enables or disables Clinical AI execution for a specific healthcare facility tenant.
     * Persisted durably when {@link TenantAiPolicyRepository} is configured.
     */
    public boolean setTenantAiEnabled(UUID facilityId, boolean enabled) {
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        if (tenantPolicyRepository != null) {
            TenantAiPolicyEntity policy = tenantPolicyRepository.findById(safeFac).orElseGet(() -> {
                TenantAiPolicyEntity created = new TenantAiPolicyEntity();
                created.setFacilityId(safeFac);
                return created;
            });
            policy.setAiEnabled(enabled);
            policy.setUpdatedAt(Instant.now());
            tenantPolicyRepository.save(policy);
        }
        tenantAiFlags.put(safeFac, enabled);
        clinicalAuditLogger.recordTenantPolicyEvent(
                safeFac, "SYSTEM_GOVERNANCE", "TENANT_AI_POLICY", true, true, "aiEnabled=" + enabled);
        emitAudit(safeFac, null, "SYSTEM_GOVERNANCE", "CLINICAL_AI_TENANT_POLICY_UPDATE",
                safeFac.toString(), "SUCCESS", "aiEnabled=" + enabled);
        return enabled;
    }

    public boolean isTenantAiEnabled(UUID facilityId) {
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        if (tenantPolicyRepository != null) {
            return tenantPolicyRepository.findById(safeFac)
                    .map(TenantAiPolicyEntity::isAiEnabled)
                    .orElse(true);
        }
        return tenantAiFlags.getOrDefault(safeFac, true);
    }

    public void assertTenantAiAllowed(UUID facilityId, AiCapabilityType capability) {
        if (!isTenantAiEnabled(facilityId)) {
            clinicalAuditLogger.recordTenantPolicyEvent(
                    facilityId, "SYSTEM_GOVERNANCE", capability.name(), false, false,
                    "Clinical AI disabled for facility tenant");
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
        if (draftRepository != null) {
            draftRepository.save(toEntity(record));
        }
        drafts.put(draftId, record);
        facilityLatencySumMs.computeIfAbsent(safeFacility, k -> new LongAdder()).add(Math.max(0L, latencyMs));
        facilityRequestCount.computeIfAbsent(safeFacility, k -> new LongAdder()).increment();

        if (totalRedactions > 0) {
            clinicalAuditLogger.recordPhiRedactionEvent(new PhiRedactionAuditEvent(
                    safeFacility,
                    patientId,
                    capability.name(),
                    record.createdBy(),
                    totalRedactions,
                    safeCounts,
                    redactedPromptSha256,
                    "Pre-model heuristic PHI pre-filter prior to " + modelId
            ));
        }
        clinicalAuditLogger.recordAiModelInteraction(new AiModelInteractionEvent(
                safeFacility,
                patientId,
                draftId,
                capability.name(),
                modelId,
                record.createdBy(),
                record.status().name(),
                "SUCCESS",
                redactedPromptSha256,
                outputHash,
                safeCounts,
                promptTok,
                outTok,
                latencyMs,
                "Draft quarantined pending clinician sign-off"
        ));

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
        ClinicalAiDraftRecord existing = findDraftInternal(draftId);
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
        if (draftRepository != null) {
            ClinicalAiDraftEntity entity = draftRepository.findById(draftId)
                    .orElseGet(() -> toEntity(existing));
            applyRecordToEntity(updated, entity);
            draftRepository.save(entity);
        }
        drafts.put(draftId, updated);

        clinicalAuditLogger.recordClinicianAttestation(new ClinicianAttestationEvent(
                existing.facilityId(),
                existing.patientId(),
                draftId,
                existing.capability().name(),
                existing.modelId(),
                updated.attestedByPractitionerId(),
                existing.status().name(),
                updated.status().name(),
                updated.outputSha256(),
                updated.attestationReason()
        ));

        emitAudit(existing.facilityId(), existing.patientId(), updated.attestedByPractitionerId(),
                "CLINICAL_AI_DRAFT_" + command.decision().name(),
                draftId.toString(), "SUCCESS",
                "capability=" + existing.capability() + " reason=" + updated.attestationReason());
        return updated;
    }

    public ClinicalAiDraftRecord getDraft(UUID draftId) {
        ClinicalAiDraftRecord found = findDraftInternal(draftId);
        if (found == null) {
            throw new NoSuchElementException("Clinical AI draft not found: " + draftId);
        }
        return found;
    }

    public List<ClinicalAiDraftRecord> listDrafts(UUID facilityId, DraftAttestationStatus statusFilter) {
        if (draftRepository != null) {
            List<ClinicalAiDraftEntity> rows;
            if (facilityId != null && statusFilter != null) {
                rows = draftRepository.findByFacilityIdAndStatusOrderByCreatedAtDesc(facilityId, statusFilter.name());
            } else if (facilityId != null) {
                rows = draftRepository.findByFacilityIdOrderByCreatedAtDesc(facilityId);
            } else {
                rows = draftRepository.findAll();
            }
            return rows.stream()
                    .map(this::toRecord)
                    .filter(d -> statusFilter == null || statusFilter == d.status())
                    .sorted(Comparator.comparing(ClinicalAiDraftRecord::createdAt).reversed())
                    .toList();
        }
        return drafts.values().stream()
                .filter(d -> facilityId == null || facilityId.equals(d.facilityId()))
                .filter(d -> statusFilter == null || statusFilter == d.status())
                .sorted(Comparator.comparing(ClinicalAiDraftRecord::createdAt).reversed())
                .toList();
    }

    public TenantAiGovernanceMetrics getTenantMetrics(UUID facilityId) {
        UUID safeFacility = facilityId != null ? facilityId : new UUID(0L, 0L);
        List<ClinicalAiDraftRecord> facDrafts = listDrafts(safeFacility, null);

        long pending = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.DRAFT_PENDING_ATTESTATION).count();
        long approved = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.APPROVED).count();
        long edited = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.APPROVED_WITH_EDITS).count();
        long rejected = facDrafts.stream().filter(d -> d.status() == DraftAttestationStatus.REJECTED).count();
        long phiTotal = facDrafts.stream().mapToLong(ClinicalAiDraftRecord::totalPhiRedactions).sum();
        long tokensTotal = facDrafts.stream().mapToLong(d -> (long) d.promptTokens() + d.outputTokens()).sum();

        long reqCount = facDrafts.size();
        long latSum = facDrafts.stream().mapToLong(ClinicalAiDraftRecord::latencyMs).sum();
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

    private ClinicalAiDraftRecord findDraftInternal(UUID draftId) {
        if (draftRepository != null) {
            return draftRepository.findById(draftId).map(this::toRecord).orElse(null);
        }
        return drafts.get(draftId);
    }

    private ClinicalAiDraftEntity toEntity(ClinicalAiDraftRecord record) {
        ClinicalAiDraftEntity entity = new ClinicalAiDraftEntity();
        applyRecordToEntity(record, entity);
        return entity;
    }

    private void applyRecordToEntity(ClinicalAiDraftRecord record, ClinicalAiDraftEntity entity) {
        entity.setDraftId(record.draftId());
        entity.setFacilityId(record.facilityId());
        entity.setPatientId(record.patientId());
        entity.setCapability(record.capability().name());
        entity.setModelId(record.modelId());
        entity.setStatus(record.status().name());
        entity.setRedactedPromptSha256(record.redactedPromptSha256());
        entity.setOutputSha256(record.outputSha256());
        entity.setRedactionCountsJson(serializeCounts(record.redactionCountsByCategory()));
        entity.setTotalPhiRedactions(record.totalPhiRedactions());
        entity.setStructuredOutputJson(record.structuredOutputJson());
        entity.setClinicianEditedJson(record.clinicianEditedJson());
        entity.setPromptTokens(record.promptTokens());
        entity.setOutputTokens(record.outputTokens());
        entity.setLatencyMs(record.latencyMs());
        entity.setCreatedBy(record.createdBy());
        entity.setCreatedAt(record.createdAt());
        entity.setAttestedByPractitionerId(record.attestedByPractitionerId());
        entity.setAttestationReason(record.attestationReason());
        entity.setAttestedAt(record.attestedAt());
    }

    private ClinicalAiDraftRecord toRecord(ClinicalAiDraftEntity entity) {
        return new ClinicalAiDraftRecord(
                entity.getDraftId(),
                entity.getFacilityId(),
                entity.getPatientId(),
                AiCapabilityType.valueOf(entity.getCapability()),
                entity.getModelId(),
                DraftAttestationStatus.valueOf(entity.getStatus()),
                entity.getRedactedPromptSha256(),
                entity.getOutputSha256(),
                deserializeCounts(entity.getRedactionCountsJson()),
                entity.getTotalPhiRedactions(),
                entity.getStructuredOutputJson(),
                entity.getClinicianEditedJson(),
                entity.getPromptTokens(),
                entity.getOutputTokens(),
                entity.getLatencyMs(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getAttestedByPractitionerId(),
                entity.getAttestationReason(),
                entity.getAttestedAt()
        );
    }

    private String serializeCounts(Map<String, Integer> counts) {
        try {
            return objectMapper.writeValueAsString(counts != null ? counts : Map.of());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize AI draft redaction counts JSON", ex);
        }
    }

    private Map<String, Integer> deserializeCounts(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Collections.unmodifiableMap(objectMapper.readValue(json, COUNTS_TYPE));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize AI draft redaction counts JSON", ex);
        }
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
        } catch (Exception ex) {
            throw new ClinicalAuditPersistenceException(
                    "Mandatory clinical governance audit persistence failed (action=" + action
                            + ", resourceId=" + resourceId + ")", ex);
        }
    }
}
