/*
 * =============================================================================
 * Opes HealthCore SDK — Offline Sync, Conflict Quarantine & Device Revocation Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.sync;

import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock.Ed25519QrHealthCardSigner;
import com.opeshealth.healthcore.sync.service.OfflineDeviceGovernanceService;
import com.opeshealth.healthcore.sync.service.OfflineDeviceGovernanceService.*;
import com.opeshealth.healthcore.sync.service.OfflineSyncService;
import com.opeshealth.healthcore.sync.service.OfflineSyncService.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OfflineSyncAndRevocationTest {

    private SyncDeltaRepository deltaRepo;
    private SyncTombstoneRepository tombstoneRepo;
    private SyncConflictRepository conflictRepo;
    private SyncDeviceRepository deviceRepo;
    private OfflineDeviceGovernanceService deviceService;
    private OfflineSyncService syncService;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        deltaRepo = mock(SyncDeltaRepository.class);
        tombstoneRepo = mock(SyncTombstoneRepository.class);
        conflictRepo = mock(SyncConflictRepository.class);
        deviceRepo = mock(SyncDeviceRepository.class);
        deviceService = new OfflineDeviceGovernanceService(deviceRepo, mock(ClinicalAuditService.class));
        syncService = new OfflineSyncService(
                deltaRepo, tombstoneRepo, conflictRepo,
                new HybridLogicalClock("server-test"),
                new Ed25519QrHealthCardSigner());
        syncService.setDeviceGovernanceService(deviceService);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void quarantinesClinicalSafetyConflictsInsteadOfSilentOverwrite() {
        UUID noteId = UUID.randomUUID();
        SyncDeltaRecordEntity existing = new SyncDeltaRecordEntity();
        existing.setFacilityId(facilityId);
        existing.setOriginNodeId("ipad-ward-2");
        existing.setEntityType("ClinicalNote");
        existing.setEntityId(noteId);
        existing.setHlcTimestamp("20261119120000:0005:ipad-ward-2");

        when(deltaRepo.findMaxSyncSeq(facilityId)).thenReturn(10L);
        when(deltaRepo.findTopByFacilityIdAndEntityTypeAndEntityIdOrderBySyncSeqDesc(facilityId, "ClinicalNote", noteId))
                .thenReturn(Optional.of(existing));
        when(deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, "android-chw-1")).thenReturn(Optional.empty());

        Map<String, Object> result = syncService.pushDeltaBatch(facilityId, List.of(
                new IncomingMutation("android-chw-1", "ClinicalNote", noteId, "UPSERT",
                        "20261119115500:0001:android-chw-1", "{\"note\":\"stale offline edit\"}")));

        assertEquals(0, result.get("appliedCount"));
        assertEquals(1, result.get("conflictCount"));
        verify(conflictRepo, times(1)).save(any(SyncConflictEntity.class));
    }

    @Test
    void revokingDeviceBlocksOfflineSyncPushWithRemoteWipeDirective() {
        when(deviceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, "android-lost-09")).thenReturn(Optional.empty());

        SyncDeviceEntity device = deviceService.registerDevice(
                new RegisterDeviceRequest("android-lost-09", "ANDROID", "chw-ekoko"));
        when(deviceRepo.findByFacilityIdAndDeviceNodeId(facilityId, "android-lost-09")).thenReturn(Optional.of(device));

        SyncDeviceEntity revoked = deviceService.revokeDevice("android-lost-09",
                new RevokeDeviceRequest("sec-admin-1", "Device reported stolen in field", true));
        assertEquals("REVOKED", revoked.getDeviceStatus());
        assertTrue(revoked.isRemoteWipeRequired());

        AccessDeniedException ex = assertThrows(AccessDeniedException.class, () ->
                syncService.pushDeltaBatch(facilityId, List.of(
                        new IncomingMutation("android-lost-09", "Patient", UUID.randomUUID(), "UPSERT",
                                "20261119130000:0001:android-lost-09", "{}"))));
        assertTrue(ex.getMessage().contains("REMOTE_WIPE_REQUIRED"));

        SyncDeviceEntity wiped = deviceService.confirmRemoteWipe("android-lost-09");
        assertNotNull(wiped.getRemoteWipeConfirmedAt());
    }
}
