/*
 * =============================================================================
 * Opes HealthCore SDK — Spring Boot 4.0 Clinical AI Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger;
import com.opeshealth.healthcore.ai.cds.ClinicalDecisionEngine;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.coach.PatientHealthCoachEngine;
import com.opeshealth.healthcore.ai.coding.ClinicalCodingEngine;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.operations.ClinicalCapacityPredictor;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyRepository;
import com.opeshealth.healthcore.ai.predictive.ClinicalPredictiveEngine;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.reconciliation.MedicationReconciliationEngine;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer;
import com.opeshealth.healthcore.ai.transcription.ClinicalTranscriptionEngine;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot 4.0 Auto-Configuration for {@code healthcore-starter-clinical-ai}.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@AutoConfiguration
@EnableConfigurationProperties(ClinicalAiProperties.class)
@EntityScan(basePackages = "com.opeshealth.healthcore.ai.persistence")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.ai.persistence", considerNestedRepositories = true)
public class ClinicalAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ClinicalAuditLogger clinicalAuditLogger(
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider,
            ObjectProvider<ClinicalAiAuditEntryRepository> auditEntryRepositoryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new ClinicalAuditLogger(
                clinicalAuditServiceProvider.getIfAvailable(),
                auditEntryRepositoryProvider.getIfAvailable(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PhiRedactionGateway phiRedactionGateway(ClinicalAuditLogger clinicalAuditLogger) {
        return new PhiRedactionGateway(clinicalAuditLogger);
    }

    @Bean
    @ConditionalOnMissingBean
    public GeminiClinicalApiClient geminiClinicalApiClient(
            ClinicalAiProperties properties,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new GeminiClinicalApiClient(properties, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalAiGovernanceService clinicalAiGovernanceService(
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider,
            ClinicalAuditLogger clinicalAuditLogger,
            ObjectProvider<ClinicalAiDraftRepository> draftRepositoryProvider,
            ObjectProvider<TenantAiPolicyRepository> tenantPolicyRepositoryProvider,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new ClinicalAiGovernanceService(
                clinicalAuditServiceProvider.getIfAvailable(),
                clinicalAuditLogger,
                draftRepositoryProvider.getIfAvailable(),
                tenantPolicyRepositoryProvider.getIfAvailable(),
                objectMapperProvider.getIfAvailable(ObjectMapper::new)
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalSummarizer clinicalSummarizer(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new ClinicalSummarizer(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalDecisionEngine clinicalDecisionEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new ClinicalDecisionEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalTranscriptionEngine clinicalTranscriptionEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new ClinicalTranscriptionEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalPredictiveEngine clinicalPredictiveEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new ClinicalPredictiveEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PatientHealthCoachEngine patientHealthCoachEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new PatientHealthCoachEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalCapacityPredictor clinicalCapacityPredictor(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService,
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider) {
        return new ClinicalCapacityPredictor(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService,
                clinicalAuditServiceProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalCodingEngine clinicalCodingEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new ClinicalCodingEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public MedicationReconciliationEngine medicationReconciliationEngine(
            GeminiClinicalApiClient geminiClinicalApiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService clinicalAiGovernanceService) {
        return new MedicationReconciliationEngine(
                geminiClinicalApiClient,
                phiRedactionGateway,
                clinicalAiGovernanceService
        );
    }
}
