/*
 * =============================================================================
 * Opes HealthCore SDK — Unified Orders & Results Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.orders.autoconfigure;

import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = OrdersResultsEntities.class)
@EnableJpaRepositories(basePackageClasses = UnifiedOrdersResultsService.class, considerNestedRepositories = true)
@Import(UnifiedOrdersResultsController.class)
public class UnifiedOrdersResultsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UnifiedOrdersResultsService unifiedOrdersResultsService(
            OrderSetRepository orderSetRepo,
            UnifiedOrderRepository orderRepo,
            OrderSpecimenRepository specimenRepo,
            ClinicalResultRepository resultRepo,
            ReflexOrderRuleRepository reflexRuleRepo,
            ClinicalAuditService auditService) {
        return new UnifiedOrdersResultsService(
                orderSetRepo, orderRepo, specimenRepo, resultRepo, reflexRuleRepo, auditService);
    }
}
