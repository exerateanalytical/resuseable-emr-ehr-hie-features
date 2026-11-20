/*
 * =============================================================================
 * Opes HealthCore SDK — Revenue Coding & Claims Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.revenueclaims.autoconfigure;

import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = RevenueCodingClaimsEntities.class)
@EnableJpaRepositories(basePackageClasses = RevenueCodingClaimsService.class, considerNestedRepositories = true)
@Import(RevenueCodingClaimsController.class)
public class RevenueCodingClaimsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RevenueCodingClaimsService revenueCodingClaimsService(
            FacilityTariffRepository tariffRepo,
            ClinicalCodingRepository codingRepo,
            InsuranceAuthorizationRepository authRepo,
            PayerClaimRepository claimRepo,
            ClinicalAuditService auditService) {
        return new RevenueCodingClaimsService(tariffRepo, codingRepo, authRepo, claimRepo, auditService);
    }
}
