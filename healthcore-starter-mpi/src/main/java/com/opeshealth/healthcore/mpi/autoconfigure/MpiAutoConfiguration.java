/*
 * =============================================================================
 * Opes HealthCore SDK — MPI Starter Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.autoconfigure;

import com.opeshealth.healthcore.mpi.domain.MpiEntities;
import com.opeshealth.healthcore.mpi.fhir.FhirPatientController;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@EntityScan(basePackageClasses = MpiEntities.class)
@EnableJpaRepositories(basePackageClasses = MpiCommandService.class)
public class MpiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MpiCommandService mpiCommandService(
            MpiCommandService.PatientRepository patientRepo,
            MpiCommandService.PatientIdentifierRepository identifierRepo,
            MpiCommandService.PatientDemographicsRepository demographicsRepo,
            MpiCommandService.PatientPhoneticRepository phoneticRepo,
            MpiCommandService.PatientMergeRepository mergeRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        return new MpiCommandService(
                patientRepo, identifierRepo, demographicsRepo, phoneticRepo, mergeRepo,
                outboxRepo, eventPublisher, jdbcTemplateProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public FhirPatientController fhirPatientController(
            MpiCommandService mpiService,
            MpiCommandService.PatientRepository patientRepo) {
        return new FhirPatientController(mpiService, patientRepo);
    }
}
