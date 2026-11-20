/*
 * =============================================================================
 * Opes HealthCore SDK — Offline Device Governance, Revocation & Remote Wipe
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.sync.service;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Manages mobile &amp; edge sync device registration, instant device revocation,
 * and remote wipe enforcement for lost or compromised clinical tablets/phones.
 */
public class OfflineDeviceGovernanceService {

    private static final Set<String> ALLOWED_PLATFORMS = Set.of("ANDROID", "IOS", "IPAD", "EDGE_NODE");

    @Entity
    @Table(name = "hc_sync_devices")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class SyncDeviceEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "device_node_id", nullable = false, length = 80) private String deviceNodeId;
        @Column(name = "device_platform", nullable = false, length = 24) private String devicePlatform;
        @Column(name = "assigned_user_id", nullable = false, length = 120) private String assignedUserId;
        @Column(name = "device_status", nullable = false, length = 24) private String deviceStatus = "ACTIVE";
        @Column(name = "remote_wipe_required", nullable = false) private boolean remoteWipeRequired = false;
        @Column(name = "remote_wipe_confirmed_at") private Instant remoteWipeConfirmedAt;
        @Column(name = "revoked_by", length = 120) private String revokedBy;
        @Column(name = "revoked_reason", length = 300) private String revokedReason;
        @Column(name = "registered_at", nullable = false) private Instant registeredAt = Instant.now();
        @Column(name = "revoked_at") private Instant revokedAt;
        @Column(name = "last_sync_at") private Instant lastSyncAt;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDeviceNodeId() { return deviceNodeId; }
        public void setDeviceNodeId(String deviceNodeId) { this.deviceNodeId = deviceNodeId; }
        public String getDevicePlatform() { return devicePlatform; }
        public void setDevicePlatform(String devicePlatform) { this.devicePlatform = devicePlatform; }
        public String getAssignedUserId() { return assignedUserId; }
        public void setAssignedUserId(String assignedUserId) { this.assignedUserId = assignedUserId; }
        public String getDeviceStatus() { return deviceStatus; }
        public void setDeviceStatus(String deviceStatus) { this.deviceStatus = deviceStatus; }
        public boolean isRemoteWipeRequired() { return remoteWipeRequired; }
        public void setRemoteWipeRequired(boolean remoteWipeRequired) { this.remoteWipeRequired = remoteWipeRequired; }
        public Instant getRemoteWipeConfirmedAt() { return remoteWipeConfirmedAt; }
        public void setRemoteWipeConfirmedAt(Instant remoteWipeConfirmedAt) { this.remoteWipeConfirmedAt = remoteWipeConfirmedAt; }
        public String getRevokedBy() { return revokedBy; }
        public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
        public String getRevokedReason() { return revokedReason; }
        public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }
        public Instant getRegisteredAt() { return registeredAt; }
        public Instant getRevokedAt() { return revokedAt; }
        public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
        public Instant getLastSyncAt() { return lastSyncAt; }
        public void setLastSyncAt(Instant lastSyncAt) { this.lastSyncAt = lastSyncAt; }
        public long getVersion() { return version; }
    }

    public interface SyncDeviceRepository extends JpaRepository<SyncDeviceEntity, UUID> {
        Optional<SyncDeviceEntity> findByFacilityIdAndDeviceNodeId(UUID facilityId, String deviceNodeId);
        List<SyncDeviceEntity> findByFacilityIdOrderByRegisteredAtDesc(UUID facilityId);
    }

    public record RegisterDeviceRequest(String deviceNodeId, String devicePlatform, String assignedUserId) {}
    public record RevokeDeviceRequest(String revokedBy, String reason, boolean remoteWipeRequired) {}

    private final SyncDeviceRepository deviceRepo;
    private final ClinicalAuditService auditService;

    public OfflineDeviceGovernanceService(SyncDeviceRepository deviceRepo, ClinicalAuditService auditService) {
        this.deviceRepo = deviceRepo;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Facility context required for offline device governance."));
    }

    @Transactional
    public SyncDeviceEntity registerDevice(RegisterDeviceRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.deviceNodeId() == null || req.deviceNodeId().isBlank()
                || req.assignedUserId() == null || req.assignedUserId().isBlank()) {
            throw new IllegalArgumentException("deviceNodeId and assignedUserId are required.");
        }
        String platform = req.devicePlatform() == null ? "" : req.devicePlatform().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("Unsupported mobile/edge platform: " + req.devicePlatform());
        }
        SyncDeviceEntity entity = deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, req.deviceNodeId().trim())
                .orElseGet(SyncDeviceEntity::new);
        entity.setFacilityId(facilityId);
        entity.setDeviceNodeId(req.deviceNodeId().trim());
        entity.setDevicePlatform(platform);
        entity.setAssignedUserId(req.assignedUserId().trim());
        entity.setDeviceStatus("ACTIVE");
        entity.setRemoteWipeRequired(false);
        entity.setRevokedAt(null);
        entity.setRevokedBy(null);
        entity.setRevokedReason(null);

        SyncDeviceEntity saved = deviceRepo.save(entity);
        audit(facilityId, req.assignedUserId(), "SYNC_DEVICE_REGISTERED", saved.getDeviceNodeId(), "platform=" + platform);
        return saved;
    }

    @Transactional
    public SyncDeviceEntity revokeDevice(String deviceNodeId, RevokeDeviceRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.revokedBy() == null || req.revokedBy().isBlank()
                || req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("revokedBy and reason are required to revoke a clinical device.");
        }
        SyncDeviceEntity entity = deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, deviceNodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync device not found: " + deviceNodeId));
        entity.setDeviceStatus("REVOKED");
        entity.setRemoteWipeRequired(req.remoteWipeRequired());
        entity.setRevokedBy(req.revokedBy().trim());
        entity.setRevokedReason(req.reason().trim());
        entity.setRevokedAt(Instant.now());

        SyncDeviceEntity saved = deviceRepo.save(entity);
        audit(facilityId, req.revokedBy(), "SYNC_DEVICE_REVOKED", saved.getDeviceNodeId(),
                "remoteWipe=" + req.remoteWipeRequired() + " reason=" + req.reason());
        return saved;
    }

    /**
     * Enforces device authorization prior to accepting offline push or serving delta pulls.
     * Revoked devices are immediately denied with an explicit remote-wipe directive if flagged.
     */
    @Transactional
    public void assertDeviceAuthorizedForSync(UUID facilityId, String deviceNodeId) {
        if (deviceNodeId == null || deviceNodeId.isBlank()) {
            return;
        }
        Optional<SyncDeviceEntity> opt = deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, deviceNodeId.trim());
        if (opt.isPresent()) {
            SyncDeviceEntity device = opt.get();
            if ("REVOKED".equalsIgnoreCase(device.getDeviceStatus())) {
                String wipeFlag = device.isRemoteWipeRequired() ? " [REMOTE_WIPE_REQUIRED]" : "";
                throw new AccessDeniedException("Device '" + deviceNodeId + "' has been revoked" + wipeFlag
                        + ". Reason: " + Objects.toString(device.getRevokedReason(), "security revocation"));
            }
            device.setLastSyncAt(Instant.now());
            deviceRepo.save(device);
        }
    }

    @Transactional
    public SyncDeviceEntity confirmRemoteWipe(String deviceNodeId) {
        UUID facilityId = requireFacilityId();
        SyncDeviceEntity entity = deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, deviceNodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sync device not found"));
        entity.setRemoteWipeConfirmedAt(Instant.now());
        SyncDeviceEntity saved = deviceRepo.save(entity);
        audit(facilityId, entity.getAssignedUserId(), "SYNC_DEVICE_WIPE_CONFIRMED", deviceNodeId, "wipeConfirmed=true");
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SyncDeviceEntity> listDevices() {
        return deviceRepo.findByFacilityIdOrderByRegisteredAtDesc(requireFacilityId());
    }

    private void audit(UUID facilityId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.recordEvent(facilityId.toString(), actor != null ? actor : "SYNC_GOVERNANCE",
                    "SYNC_GOVERNANCE", action, "OfflineSyncDevice", resourceId, null, "SUCCESS", detail, "internal");
        }
    }

    @RestController
    @RequestMapping("/api/v1/sync/devices")
    @Tag(name = "Offline Mobile & Edge Device Governance", description = "Device enrollment, instant revocation, and remote wipe confirmation")
    public static class OfflineDeviceController {
        private final OfflineDeviceGovernanceService service;

        public OfflineDeviceController(OfflineDeviceGovernanceService service) {
            this.service = service;
        }

        @PostMapping
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Register a clinical mobile/iPad/edge sync device")
        public SyncDeviceEntity register(@RequestBody RegisterDeviceRequest request) {
            return service.registerDevice(request);
        }

        @PostMapping("/{deviceNodeId}/revoke")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Revoke a lost or compromised device and optionally require remote wipe")
        public SyncDeviceEntity revoke(@PathVariable String deviceNodeId, @RequestBody RevokeDeviceRequest request) {
            return service.revokeDevice(deviceNodeId, request);
        }

        @PostMapping("/{deviceNodeId}/wipe-confirmed")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_chw:visit:write') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Record client acknowledgment that encrypted local PHI store has been wiped")
        public SyncDeviceEntity confirmWipe(@PathVariable String deviceNodeId) {
            return service.confirmRemoteWipe(deviceNodeId);
        }

        @GetMapping
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "List registered offline sync devices for active facility")
        public List<SyncDeviceEntity> list() {
            return service.listDevices();
        }
    }
}
