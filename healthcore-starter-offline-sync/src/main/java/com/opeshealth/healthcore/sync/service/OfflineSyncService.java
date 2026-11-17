/*
 * =============================================================================
 * Opes HealthCore SDK — Offline Delta Sync Entities, Service & REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.sync.service;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock.Ed25519QrHealthCardSigner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Bidirectional offline-first delta synchronization engine for Android/iOS/iPad mobile apps
 * and Clinic Edge Nodes operating under intermittent or zero connectivity.
 */
public class OfflineSyncService {

    private static final Set<String> CLINICAL_SAFETY_ENTITIES = Set.of(
            "ClinicalNote", "ClinicalOrder", "Prescription", "Allergy", "EmarAdministration"
    );

    @Entity @Table(name = "hc_sync_deltas")
    public static class SyncDeltaRecordEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "sync_seq", nullable = false) private long syncSeq;
        @Column(name = "hlc_timestamp", nullable = false, length = 48) private String hlcTimestamp;
        @Column(name = "origin_node_id", nullable = false, length = 80) private String originNodeId;
        @Column(name = "entity_type", nullable = false, length = 64) private String entityType;
        @Column(name = "entity_id", nullable = false) private UUID entityId;
        @Column(name = "operation", nullable = false, length = 16) private String operation; // UPSERT, DELETE
        @Lob @Column(name = "payload_json", nullable = false) private String payloadJson;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public long getSyncSeq() { return syncSeq; }
        public void setSyncSeq(long syncSeq) { this.syncSeq = syncSeq; }
        public String getHlcTimestamp() { return hlcTimestamp; }
        public void setHlcTimestamp(String hlcTimestamp) { this.hlcTimestamp = hlcTimestamp; }
        public String getOriginNodeId() { return originNodeId; }
        public void setOriginNodeId(String originNodeId) { this.originNodeId = originNodeId; }
        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        public UUID getEntityId() { return entityId; }
        public void setEntityId(UUID entityId) { this.entityId = entityId; }
        public String getOperation() { return operation; }
        public void setOperation(String operation) { this.operation = operation; }
        public String getPayloadJson() { return payloadJson; }
        public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity @Table(name = "hc_sync_tombstones")
    public static class SyncTombstoneEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "entity_type", nullable = false, length = 64) private String entityType;
        @Column(name = "entity_id", nullable = false) private UUID entityId;
        @Column(name = "hlc_timestamp", nullable = false, length = 48) private String hlcTimestamp;
        @Column(name = "deleted_at", nullable = false) private Instant deletedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        public UUID getEntityId() { return entityId; }
        public void setEntityId(UUID entityId) { this.entityId = entityId; }
        public String getHlcTimestamp() { return hlcTimestamp; }
        public void setHlcTimestamp(String hlcTimestamp) { this.hlcTimestamp = hlcTimestamp; }
        public Instant getDeletedAt() { return deletedAt; }
    }

    @Entity @Table(name = "hc_sync_conflicts")
    public static class SyncConflictEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "entity_type", nullable = false, length = 64) private String entityType;
        @Column(name = "entity_id", nullable = false) private UUID entityId;
        @Column(name = "origin_node_id", nullable = false, length = 80) private String originNodeId;
        @Column(name = "incoming_hlc", nullable = false, length = 48) private String incomingHlc;
        @Column(name = "server_hlc", nullable = false, length = 48) private String serverHlc;
        @Lob @Column(name = "incoming_payload", nullable = false) private String incomingPayload;
        @Column(name = "status", nullable = false, length = 24) private String status = "OPEN";
        @Column(name = "resolved_by", length = 120) private String resolvedBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getEntityType() { return entityType; }
        public void setEntityType(String entityType) { this.entityType = entityType; }
        public UUID getEntityId() { return entityId; }
        public void setEntityId(UUID entityId) { this.entityId = entityId; }
        public String getOriginNodeId() { return originNodeId; }
        public void setOriginNodeId(String originNodeId) { this.originNodeId = originNodeId; }
        public String getIncomingHlc() { return incomingHlc; }
        public void setIncomingHlc(String incomingHlc) { this.incomingHlc = incomingHlc; }
        public String getServerHlc() { return serverHlc; }
        public void setServerHlc(String serverHlc) { this.serverHlc = serverHlc; }
        public String getIncomingPayload() { return incomingPayload; }
        public void setIncomingPayload(String incomingPayload) { this.incomingPayload = incomingPayload; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getResolvedBy() { return resolvedBy; }
        public void setResolvedBy(String resolvedBy) { this.resolvedBy = resolvedBy; }
        public Instant getCreatedAt() { return createdAt; }
    }

    public interface SyncDeltaRepository extends JpaRepository<SyncDeltaRecordEntity, UUID> {
        List<SyncDeltaRecordEntity> findByFacilityIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(
                UUID facilityId, long sinceSeq, org.springframework.data.domain.Pageable pageable);

        Optional<SyncDeltaRecordEntity> findTopByFacilityIdAndEntityTypeAndEntityIdOrderBySyncSeqDesc(
                UUID facilityId, String entityType, UUID entityId);

        @Query("SELECT COALESCE(MAX(d.syncSeq), 0) FROM SyncDeltaRecordEntity d WHERE d.facilityId = :facilityId")
        long findMaxSyncSeq(@Param("facilityId") UUID facilityId);
    }

    public interface SyncTombstoneRepository extends JpaRepository<SyncTombstoneEntity, UUID> {}
    public interface SyncConflictRepository extends JpaRepository<SyncConflictEntity, UUID> {
        List<SyncConflictEntity> findByFacilityIdAndStatus(UUID facilityId, String status);
    }

    public record IncomingMutation(
            String originNodeId, String entityType, UUID entityId,
            String operation, String hlcTimestamp, String payloadJson
    ) {}

    private final SyncDeltaRepository deltaRepo;
    private final SyncTombstoneRepository tombstoneRepo;
    private final SyncConflictRepository conflictRepo;
    private final HybridLogicalClock hlc;
    private final Ed25519QrHealthCardSigner qrSigner;

    public OfflineSyncService(
            SyncDeltaRepository deltaRepo,
            SyncTombstoneRepository tombstoneRepo,
            SyncConflictRepository conflictRepo,
            HybridLogicalClock hlc,
            Ed25519QrHealthCardSigner qrSigner) {
        this.deltaRepo = deltaRepo;
        this.tombstoneRepo = tombstoneRepo;
        this.conflictRepo = conflictRepo;
        this.hlc = hlc;
        this.qrSigner = qrSigner;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pullDelta(UUID facilityId, long sinceSeq, int limit) {
        List<SyncDeltaRecordEntity> deltas = deltaRepo
                .findByFacilityIdAndSyncSeqGreaterThanOrderBySyncSeqAsc(
                        facilityId, sinceSeq, PageRequest.of(0, Math.min(limit, 500)));
        long nextCursor = deltas.isEmpty() ? sinceSeq : deltas.getLast().getSyncSeq();
        return Map.of("deltas", deltas, "nextSyncSeq", nextCursor, "serverHlc", hlc.nextTimestamp());
    }

    /**
     * Pushes offline client mutations to the facility sync log.
     * Clinical safety entities (Notes, Orders, Allergies, MAR) route concurrent edits
     * into {@link SyncConflictEntity} for clinician review instead of silent LWW overwrite.
     */
    @Transactional
    @ClinicalAudit(action = "OFFLINE_SYNC_PUSH", resourceType = "SyncDelta")
    public Map<String, Object> pushDeltaBatch(UUID facilityId, List<IncomingMutation> mutations) {
        long seq = deltaRepo.findMaxSyncSeq(facilityId);
        int applied = 0;
        int quarantined = 0;

        for (IncomingMutation m : mutations) {
            Optional<SyncDeltaRecordEntity> latest = deltaRepo
                    .findTopByFacilityIdAndEntityTypeAndEntityIdOrderBySyncSeqDesc(facilityId, m.entityType(), m.entityId());

            if (latest.isPresent()
                    && CLINICAL_SAFETY_ENTITIES.contains(m.entityType())
                    && !Objects.equals(latest.get().getOriginNodeId(), m.originNodeId())
                    && m.hlcTimestamp().compareTo(latest.get().getHlcTimestamp()) <= 0) {
                SyncConflictEntity conflict = new SyncConflictEntity();
                conflict.setFacilityId(facilityId);
                conflict.setEntityType(m.entityType());
                conflict.setEntityId(m.entityId());
                conflict.setOriginNodeId(m.originNodeId());
                conflict.setIncomingHlc(m.hlcTimestamp());
                conflict.setServerHlc(latest.get().getHlcTimestamp());
                conflict.setIncomingPayload(m.payloadJson());
                conflictRepo.save(conflict);
                quarantined++;
                continue;
            }

            seq++;
            String stampedHlc = hlc.nextTimestamp();
            SyncDeltaRecordEntity rec = new SyncDeltaRecordEntity();
            rec.setFacilityId(facilityId);
            rec.setSyncSeq(seq);
            rec.setHlcTimestamp(stampedHlc);
            rec.setOriginNodeId(m.originNodeId());
            rec.setEntityType(m.entityType());
            rec.setEntityId(m.entityId());
            rec.setOperation(m.operation());
            rec.setPayloadJson(m.payloadJson());
            deltaRepo.save(rec);

            if ("DELETE".equalsIgnoreCase(m.operation())) {
                SyncTombstoneEntity tomb = new SyncTombstoneEntity();
                tomb.setFacilityId(facilityId);
                tomb.setEntityType(m.entityType());
                tomb.setEntityId(m.entityId());
                tomb.setHlcTimestamp(stampedHlc);
                tombstoneRepo.save(tomb);
            }
            applied++;
        }
        return Map.of("appliedCount", applied, "conflictCount", quarantined, "latestSyncSeq", seq);
    }

    @RestController
    @RequestMapping("/api/v1/sync")
    @Tag(name = "Offline-First Sync & QR Card", description = "HLC + sync_seq delta pull/push, clinical conflict queue & Ed25519 QR IPS Health Card")
    public static class OfflineSyncController {
        private final OfflineSyncService service;
        private final SyncConflictRepository conflictRepo;
        private final Ed25519QrHealthCardSigner qrSigner;

        public OfflineSyncController(
                OfflineSyncService service,
                SyncConflictRepository conflictRepo,
                Ed25519QrHealthCardSigner qrSigner) {
            this.service = service;
            this.conflictRepo = conflictRepo;
            this.qrSigner = qrSigner;
        }

        @GetMapping("/pull")
        @Operation(summary = "Pull monotonic delta batch since last syncSeq cursor")
        public Map<String, Object> pull(
                @RequestParam UUID facilityId,
                @RequestParam(defaultValue = "0") long sinceSeq,
                @RequestParam(defaultValue = "200") int limit) {
            return service.pullDelta(facilityId, sinceSeq, limit);
        }

        @PostMapping("/push")
        @Operation(summary = "Push offline Mobile/iPad/Edge mutations with clinical conflict safety")
        public Map<String, Object> push(
                @RequestParam UUID facilityId,
                @RequestBody List<IncomingMutation> mutations) {
            return service.pushDeltaBatch(facilityId, mutations);
        }

        @GetMapping("/conflicts")
        @Operation(summary = "List unresolved clinical sync conflicts awaiting clinician review")
        public List<SyncConflictEntity> conflicts(@RequestParam UUID facilityId) {
            return conflictRepo.findByFacilityIdAndStatus(facilityId, "OPEN");
        }

        @PostMapping("/qr-card/sign")
        @Operation(summary = "Issue Ed25519 cryptographically signed offline QR IPS Health Card")
        public Map<String, String> signQrCard(@RequestBody String compactIpsJson) {
            return Map.of(
                    "qrToken", qrSigner.signCardPayload(compactIpsJson),
                    "publicKeyBase64", qrSigner.publicKeyBase64());
        }

        @PostMapping("/qr-card/verify")
        @Operation(summary = "Verify offline QR IPS Health Card signature")
        public Map<String, Boolean> verifyQrCard(@RequestBody String qrToken) {
            return Map.of("valid", qrSigner.verifyCardToken(qrToken));
        }
    }
}
