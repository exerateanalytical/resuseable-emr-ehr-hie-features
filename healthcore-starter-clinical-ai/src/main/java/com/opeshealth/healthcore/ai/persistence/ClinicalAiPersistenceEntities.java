/*
 * =============================================================================
 * Opes HealthCore SDK — Durable JPA Entities & Repositories for Clinical AI
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Relational persistence entities and Spring Data JPA repositories backing Clinical AI drafts,
 * tenant AI kill-switch policies, and tamper-evident clinical AI audit chains across restarts
 * and multi-instance deployments.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public final class ClinicalAiPersistenceEntities {

    private ClinicalAiPersistenceEntities() {}

    @Entity
    @Table(name = "hc_clinical_ai_drafts")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalAiDraftEntity {
        @Id
        @Column(name = "draft_id", nullable = false, updatable = false)
        private UUID draftId;

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id")
        private UUID patientId;

        @Column(name = "capability", nullable = false, length = 64)
        private String capability;

        @Column(name = "model_id", nullable = false, length = 128)
        private String modelId;

        @Column(name = "status", nullable = false, length = 40)
        private String status;

        @Column(name = "redacted_prompt_sha256", nullable = false, length = 64)
        private String redactedPromptSha256;

        @Column(name = "output_sha256", nullable = false, length = 64)
        private String outputSha256;

        @Lob
        @Column(name = "redaction_counts_json", nullable = false)
        private String redactionCountsJson;

        @Column(name = "total_phi_redactions", nullable = false)
        private int totalPhiRedactions;

        @Lob
        @Column(name = "structured_output_json", nullable = false)
        private String structuredOutputJson;

        @Lob
        @Column(name = "clinician_edited_json")
        private String clinicianEditedJson;

        @Column(name = "prompt_tokens", nullable = false)
        private int promptTokens;

        @Column(name = "output_tokens", nullable = false)
        private int outputTokens;

        @Column(name = "latency_ms", nullable = false)
        private long latencyMs;

        @Column(name = "created_by", nullable = false, length = 128)
        private String createdBy;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt;

        @Column(name = "attested_by_practitioner_id", length = 128)
        private String attestedByPractitionerId;

        @Column(name = "attestation_reason", length = 1024)
        private String attestationReason;

        @Column(name = "attested_at")
        private Instant attestedAt;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getDraftId() { return draftId; }
        public void setDraftId(UUID draftId) { this.draftId = draftId; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getCapability() { return capability; }
        public void setCapability(String capability) { this.capability = capability; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getRedactedPromptSha256() { return redactedPromptSha256; }
        public void setRedactedPromptSha256(String redactedPromptSha256) { this.redactedPromptSha256 = redactedPromptSha256; }
        public String getOutputSha256() { return outputSha256; }
        public void setOutputSha256(String outputSha256) { this.outputSha256 = outputSha256; }
        public String getRedactionCountsJson() { return redactionCountsJson; }
        public void setRedactionCountsJson(String redactionCountsJson) { this.redactionCountsJson = redactionCountsJson; }
        public int getTotalPhiRedactions() { return totalPhiRedactions; }
        public void setTotalPhiRedactions(int totalPhiRedactions) { this.totalPhiRedactions = totalPhiRedactions; }
        public String getStructuredOutputJson() { return structuredOutputJson; }
        public void setStructuredOutputJson(String structuredOutputJson) { this.structuredOutputJson = structuredOutputJson; }
        public String getClinicianEditedJson() { return clinicianEditedJson; }
        public void setClinicianEditedJson(String clinicianEditedJson) { this.clinicianEditedJson = clinicianEditedJson; }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public String getAttestedByPractitionerId() { return attestedByPractitionerId; }
        public void setAttestedByPractitionerId(String attestedByPractitionerId) { this.attestedByPractitionerId = attestedByPractitionerId; }
        public String getAttestationReason() { return attestationReason; }
        public void setAttestationReason(String attestationReason) { this.attestationReason = attestationReason; }
        public Instant getAttestedAt() { return attestedAt; }
        public void setAttestedAt(Instant attestedAt) { this.attestedAt = attestedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_clinical_ai_tenant_policies")
    public static class TenantAiPolicyEntity {
        @Id
        @Column(name = "facility_id", nullable = false, updatable = false)
        private UUID facilityId;

        @Column(name = "ai_enabled", nullable = false)
        private boolean aiEnabled = true;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public boolean isAiEnabled() { return aiEnabled; }
        public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_clinical_ai_audit_entries")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalAiAuditEntryEntity {
        @Id
        @Column(name = "entry_id", nullable = false, updatable = false)
        private UUID entryId;

        @Column(name = "sequence_number", nullable = false)
        private long sequenceNumber;

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id")
        private UUID patientId;

        @Column(name = "draft_id")
        private UUID draftId;

        @Column(name = "event_type", nullable = false, length = 64)
        private String eventType;

        @Column(name = "capability", nullable = false, length = 128)
        private String capability;

        @Column(name = "model_id", nullable = false, length = 128)
        private String modelId;

        @Column(name = "actor_id", nullable = false, length = 128)
        private String actorId;

        @Column(name = "attestation_status", nullable = false, length = 64)
        private String attestationStatus;

        @Column(name = "outcome", nullable = false, length = 32)
        private String outcome;

        @Column(name = "redacted_input_sha256", nullable = false, length = 64)
        private String redactedInputSha256;

        @Column(name = "output_sha256", nullable = false, length = 64)
        private String outputSha256;

        @Column(name = "total_phi_identifiers_redacted", nullable = false)
        private int totalPhiIdentifiersRedacted;

        @Lob
        @Column(name = "redaction_counts_json", nullable = false)
        private String redactionCountsJson;

        @Column(name = "prompt_tokens", nullable = false)
        private int promptTokens;

        @Column(name = "output_tokens", nullable = false)
        private int outputTokens;

        @Column(name = "latency_ms", nullable = false)
        private long latencyMs;

        @Lob
        @Column(name = "sanitized_detail", nullable = false)
        private String sanitizedDetail;

        @Column(name = "recorded_at", nullable = false)
        private Instant recordedAt;

        @Column(name = "previous_entry_hash", nullable = false, length = 64)
        private String previousEntryHash;

        @Column(name = "entry_sha256_hash", nullable = false, length = 64)
        private String entrySha256Hash;

        public UUID getEntryId() { return entryId; }
        public void setEntryId(UUID entryId) { this.entryId = entryId; }
        public long getSequenceNumber() { return sequenceNumber; }
        public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getDraftId() { return draftId; }
        public void setDraftId(UUID draftId) { this.draftId = draftId; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getCapability() { return capability; }
        public void setCapability(String capability) { this.capability = capability; }
        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getActorId() { return actorId; }
        public void setActorId(String actorId) { this.actorId = actorId; }
        public String getAttestationStatus() { return attestationStatus; }
        public void setAttestationStatus(String attestationStatus) { this.attestationStatus = attestationStatus; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public String getRedactedInputSha256() { return redactedInputSha256; }
        public void setRedactedInputSha256(String redactedInputSha256) { this.redactedInputSha256 = redactedInputSha256; }
        public String getOutputSha256() { return outputSha256; }
        public void setOutputSha256(String outputSha256) { this.outputSha256 = outputSha256; }
        public int getTotalPhiIdentifiersRedacted() { return totalPhiIdentifiersRedacted; }
        public void setTotalPhiIdentifiersRedacted(int totalPhiIdentifiersRedacted) { this.totalPhiIdentifiersRedacted = totalPhiIdentifiersRedacted; }
        public String getRedactionCountsJson() { return redactionCountsJson; }
        public void setRedactionCountsJson(String redactionCountsJson) { this.redactionCountsJson = redactionCountsJson; }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
        public long getLatencyMs() { return latencyMs; }
        public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
        public String getSanitizedDetail() { return sanitizedDetail; }
        public void setSanitizedDetail(String sanitizedDetail) { this.sanitizedDetail = sanitizedDetail; }
        public Instant getRecordedAt() { return recordedAt; }
        public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
        public String getPreviousEntryHash() { return previousEntryHash; }
        public void setPreviousEntryHash(String previousEntryHash) { this.previousEntryHash = previousEntryHash; }
        public String getEntrySha256Hash() { return entrySha256Hash; }
        public void setEntrySha256Hash(String entrySha256Hash) { this.entrySha256Hash = entrySha256Hash; }
    }

    public interface ClinicalAiDraftRepository extends JpaRepository<ClinicalAiDraftEntity, UUID> {
        List<ClinicalAiDraftEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        List<ClinicalAiDraftEntity> findByFacilityIdAndStatusOrderByCreatedAtDesc(UUID facilityId, String status);
        Optional<ClinicalAiDraftEntity> findByDraftIdAndFacilityId(UUID draftId, UUID facilityId);
    }

    public interface TenantAiPolicyRepository extends JpaRepository<TenantAiPolicyEntity, UUID> {
    }

    public interface ClinicalAiAuditEntryRepository extends JpaRepository<ClinicalAiAuditEntryEntity, UUID> {
        List<ClinicalAiAuditEntryEntity> findByFacilityIdOrderBySequenceNumberAsc(UUID facilityId);
        Optional<ClinicalAiAuditEntryEntity> findTopByFacilityIdOrderBySequenceNumberDesc(UUID facilityId);
    }
}
