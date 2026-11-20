/*
 * =============================================================================
 * Opes HealthCore SDK — Integration Operations Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.integrationops.autoconfigure;

import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.IntegrationOpsController;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.MessageLedgerRepository;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.PartnerProfileRepository;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.ReplayAuditRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = IntegrationOpsEntities.class)
@EnableJpaRepositories(basePackageClasses = IntegrationOpsService.class, considerNestedRepositories = true)
@Import(IntegrationOpsController.class)
public class IntegrationOpsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IntegrationOpsService integrationOpsService(
            PartnerProfileRepository partnerProfileRepository,
            MessageLedgerRepository messageLedgerRepository,
            ReplayAuditRepository replayAuditRepository,
            ClinicalAuditService clinicalAuditService) {
        return new IntegrationOpsService(
                partnerProfileRepository, messageLedgerRepository, replayAuditRepository, clinicalAuditService);
    }
}
