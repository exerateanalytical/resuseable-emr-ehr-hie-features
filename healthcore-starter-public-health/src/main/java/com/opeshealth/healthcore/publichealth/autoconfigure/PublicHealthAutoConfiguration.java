/*
 * =============================================================================
 * Opes HealthCore SDK — Public Health, CHW & Disease Watch Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth.autoconfigure;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = PublicHealthEntities.class)
@EnableJpaRepositories(basePackageClasses = PublicHealthService.class)
public class PublicHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PublicHealthService publicHealthService(
            ChwHouseholdRepository householdRepo,
            ChwVisitRepository visitRepo,
            HomeCarePlanRepository homeCareRepo,
            ImmunizationRepository immunizationRepo,
            SurveillanceCaseRepository surveillanceRepo,
            ContactTracingRepository contactRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        return new PublicHealthService(
                householdRepo, visitRepo, homeCareRepo, immunizationRepo, surveillanceRepo, contactRepo, outboxRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public PublicHealthController publicHealthController(
            PublicHealthService service,
            ChwHouseholdRepository householdRepo,
            HomeCarePlanRepository homeCareRepo,
            ContactTracingRepository contactRepo) {
        return new PublicHealthController(service, householdRepo, homeCareRepo, contactRepo);
    }
}
