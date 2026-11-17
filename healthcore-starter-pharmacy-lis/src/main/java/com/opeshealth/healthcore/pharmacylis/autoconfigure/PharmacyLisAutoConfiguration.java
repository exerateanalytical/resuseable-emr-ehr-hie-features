/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmacy (PIS) & Lab (LIS) Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacylis.autoconfigure;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.*;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = PharmacyLisEntities.class)
@EnableJpaRepositories(basePackageClasses = PharmacyLisService.class)
public class PharmacyLisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PharmacyLisService pharmacyLisService(
            MedicationBatchRepository batchRepo,
            PharmacyDispenseRepository dispenseRepo,
            LabSpecimenRepository specimenRepo,
            DiagnosticReportRepository reportRepo,
            CdsHooksEngine cdsHooksEngine,
            OutboxRelayService.OutboxRepository outboxRepo) {
        return new PharmacyLisService(batchRepo, dispenseRepo, specimenRepo, reportRepo, cdsHooksEngine, outboxRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public PharmacyLisController pharmacyLisController(PharmacyLisService service) {
        return new PharmacyLisController(service);
    }
}
