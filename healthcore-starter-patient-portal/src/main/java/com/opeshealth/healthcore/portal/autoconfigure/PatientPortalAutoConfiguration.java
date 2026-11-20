/*
 * =============================================================================
 * Opes HealthCore SDK — Patient & Proxy Portal Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.portal.autoconfigure;

import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.*;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = PatientPortalEntities.class)
@EnableJpaRepositories(basePackageClasses = PatientPortalService.class, considerNestedRepositories = true)
@Import(PatientPortalController.class)
public class PatientPortalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PatientPortalService patientPortalService(
            PortalAccountRepository accountRepo,
            ProxyGrantRepository proxyRepo,
            MessageThreadRepository threadRepo,
            PortalMessageRepository messageRepo,
            SchedulingService schedulingService,
            UnifiedOrdersResultsService ordersResultsService,
            ClinicalAuditService auditService) {
        return new PatientPortalService(
                accountRepo, proxyRepo, threadRepo, messageRepo,
                schedulingService, ordersResultsService, auditService);
    }
}
