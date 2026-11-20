/*
 * =============================================================================
 * Opes HealthCore SDK — Terminology & CDSS Starter Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.terminology.autoconfigure;

import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresAdvisoryLockHelper;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities;
import com.opeshealth.healthcore.terminology.service.FhirTerminologySyncClient;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = TerminologyEntities.class)
@EnableJpaRepositories(basePackageClasses = TerminologyService.class, considerNestedRepositories = true)
public class TerminologyCdssAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TerminologyService terminologyService(
            TerminologyService.CodeConceptRepository conceptRepo,
            TerminologyService.ConceptClosureRepository closureRepo,
            TerminologyService.ConceptMapRepository mapRepo,
            TerminologyService.DdiRuleRepository ddiRepo) {
        return new TerminologyService(conceptRepo, closureRepo, mapRepo, ddiRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public CdsHooksEngine cdsHooksEngine(
            TerminologyService terminologyService,
            TerminologyService.DdiRuleRepository ddiRepo) {
        return new CdsHooksEngine(terminologyService, ddiRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public CdsHooksEngine.CdsHooksController cdsHooksController(
            TerminologyService terminologyService,
            CdsHooksEngine cdsHooksEngine) {
        return new CdsHooksEngine.CdsHooksController(terminologyService, cdsHooksEngine);
    }

    @Bean
    @ConditionalOnMissingBean
    public FhirTerminologySyncClient fhirTerminologySyncClient(
            TerminologyService terminologyService,
            ObjectProvider<PostgresAdvisoryLockHelper> lockHelperProvider) {
        return new FhirTerminologySyncClient(terminologyService, lockHelperProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public FhirTerminologySyncClient.TerminologyManagementController terminologyManagementController(
            TerminologyService terminologyService,
            FhirTerminologySyncClient syncClient) {
        return new FhirTerminologySyncClient.TerminologyManagementController(terminologyService, syncClient);
    }
}
