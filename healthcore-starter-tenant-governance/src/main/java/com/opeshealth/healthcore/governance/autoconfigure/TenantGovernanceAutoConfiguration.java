/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-Tenant Governance Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.governance.autoconfigure;

import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = TenantGovernanceEntities.class)
@EnableJpaRepositories(basePackageClasses = TenantGovernanceService.class, considerNestedRepositories = true)
@Import(TenantGovernanceController.class)
public class TenantGovernanceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TenantGovernanceService tenantGovernanceService(
            OrganizationRepository organizationRepository,
            FacilityRepository facilityRepository,
            DepartmentRepository departmentRepository,
            FeatureFlagRepository featureFlagRepository,
            CrossFacilityGrantRepository grantRepository,
            ClinicalAuditService auditService) {
        return new TenantGovernanceService(
                organizationRepository,
                facilityRepository,
                departmentRepository,
                featureFlagRepository,
                grantRepository,
                auditService);
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantGovernanceGuardFilter tenantGovernanceGuardFilter(TenantGovernanceService governanceService) {
        return new TenantGovernanceGuardFilter(governanceService);
    }
}
