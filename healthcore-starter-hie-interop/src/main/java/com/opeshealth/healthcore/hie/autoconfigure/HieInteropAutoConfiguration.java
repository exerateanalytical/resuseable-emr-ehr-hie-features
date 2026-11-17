/*
 * =============================================================================
 * Opes HealthCore SDK — HIE Interoperability Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.autoconfigure;

import com.opeshealth.healthcore.hie.domain.HieEntities;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.*;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@EntityScan(basePackageClasses = HieEntities.class)
@EnableJpaRepositories(basePackageClasses = HieInteropService.class)
public class HieInteropAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HieInteropService hieInteropService(
            MpiCommandService.PatientRepository patientRepo,
            FhirSubscriptionRepository subscriptionRepo,
            DicomStudyRepository dicomRepo,
            HieDlqRepository dlqRepo,
            JdbcTemplate jdbcTemplate) {
        return new HieInteropService(patientRepo, subscriptionRepo, dicomRepo, dlqRepo, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public HieInteropController hieInteropController(
            HieInteropService service,
            FhirSubscriptionRepository subscriptionRepo,
            DicomStudyRepository dicomRepo) {
        return new HieInteropController(service, subscriptionRepo, dicomRepo);
    }
}
