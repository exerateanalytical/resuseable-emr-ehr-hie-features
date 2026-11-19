/*
 * =============================================================================
 * Opes HealthCore SDK — Offline-First Sync Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.sync.autoconfigure;

import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock.Ed25519QrHealthCardSigner;
import com.opeshealth.healthcore.sync.service.OfflineDeviceGovernanceService;
import com.opeshealth.healthcore.sync.service.OfflineDeviceGovernanceService.OfflineDeviceController;
import com.opeshealth.healthcore.sync.service.OfflineDeviceGovernanceService.SyncDeviceRepository;
import com.opeshealth.healthcore.sync.service.OfflineSyncService;
import com.opeshealth.healthcore.sync.service.OfflineSyncService.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = {OfflineSyncService.class, OfflineDeviceGovernanceService.class})
@EnableJpaRepositories(basePackageClasses = {OfflineSyncService.class, OfflineDeviceGovernanceService.class}, considerNestedRepositories = true)
public class OfflineSyncAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HybridLogicalClock hybridLogicalClock() {
        return new HybridLogicalClock("server-primary");
    }

    @Bean
    @ConditionalOnMissingBean
    public Ed25519QrHealthCardSigner ed25519QrHealthCardSigner() {
        return new Ed25519QrHealthCardSigner();
    }

    @Bean
    @ConditionalOnMissingBean
    public OfflineDeviceGovernanceService offlineDeviceGovernanceService(
            SyncDeviceRepository deviceRepo,
            ObjectProvider<ClinicalAuditService> auditServiceProvider) {
        return new OfflineDeviceGovernanceService(deviceRepo, auditServiceProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public OfflineDeviceController offlineDeviceController(OfflineDeviceGovernanceService deviceService) {
        return new OfflineDeviceController(deviceService);
    }

    @Bean
    @ConditionalOnMissingBean
    public OfflineSyncService offlineSyncService(
            SyncDeltaRepository deltaRepo,
            SyncTombstoneRepository tombstoneRepo,
            SyncConflictRepository conflictRepo,
            HybridLogicalClock hlc,
            Ed25519QrHealthCardSigner qrSigner,
            OfflineDeviceGovernanceService deviceGovernanceService) {
        OfflineSyncService svc = new OfflineSyncService(deltaRepo, tombstoneRepo, conflictRepo, hlc, qrSigner);
        svc.setDeviceGovernanceService(deviceGovernanceService);
        return svc;
    }

    @Bean
    @ConditionalOnMissingBean
    public OfflineSyncController offlineSyncController(
            OfflineSyncService service,
            SyncConflictRepository conflictRepo,
            Ed25519QrHealthCardSigner qrSigner) {
        return new OfflineSyncController(service, conflictRepo, qrSigner);
    }
}
