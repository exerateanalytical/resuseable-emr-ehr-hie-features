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

import com.opeshealth.healthcore.persistence.autoconfigure.ModuleSliceConditionSupport;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.*;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = PharmacyLisEntities.class)
@EnableJpaRepositories(basePackageClasses = PharmacyLisService.class, considerNestedRepositories = true)
public class PharmacyLisAutoConfiguration {

    private static final String PHARMACY_MARKER = "com.opeshealth.healthcore.starters.PharmacyInventoryStarter";
    private static final String LIS_MARKER = "com.opeshealth.healthcore.starters.LisDiagnosticsStarter";
    private static final List<String> GROUP_MARKERS = List.of(PHARMACY_MARKER, LIS_MARKER);

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
    @Conditional(OnPharmacySlice.class)
    public PharmacyController pharmacyController(PharmacyLisService service) {
        return new PharmacyController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(OnLisSlice.class)
    public LisController lisController(PharmacyLisService service) {
        return new LisController(service);
    }

    static class OnPharmacySlice implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return ModuleSliceConditionSupport.isSliceEnabled(
                    context, "healthcore.modules.pharmacy.enabled", PHARMACY_MARKER, GROUP_MARKERS);
        }
    }

    static class OnLisSlice implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return ModuleSliceConditionSupport.isSliceEnabled(
                    context, "healthcore.modules.lis.enabled", LIS_MARKER, GROUP_MARKERS);
        }
    }
}
