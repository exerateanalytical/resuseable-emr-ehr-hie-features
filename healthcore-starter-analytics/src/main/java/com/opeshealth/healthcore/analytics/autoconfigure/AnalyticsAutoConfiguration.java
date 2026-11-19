/*
 * =============================================================================
 * Opes HealthCore SDK — Analytics & Clinical Quality Measures Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.analytics.autoconfigure;

import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
@EntityScan(basePackageClasses = AnalyticsEntities.class)
@EnableJpaRepositories(basePackageClasses = HospitalAnalyticsService.class, considerNestedRepositories = true)
@Import(HospitalAnalyticsController.class)
public class AnalyticsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HospitalAnalyticsService hospitalAnalyticsService(
            QualityMeasureRepository qualityMeasureRepository,
            CohortExportRepository cohortExportRepository,
            JdbcTemplate jdbcTemplate,
            ClinicalAuditService auditService) {
        return new HospitalAnalyticsService(
                qualityMeasureRepository, cohortExportRepository, jdbcTemplate, auditService);
    }
}
