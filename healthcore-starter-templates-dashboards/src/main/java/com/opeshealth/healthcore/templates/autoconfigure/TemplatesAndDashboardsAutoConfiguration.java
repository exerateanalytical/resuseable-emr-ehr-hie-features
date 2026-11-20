/*
 * =============================================================================
 * Opes HealthCore SDK — Templates, Dashboards & Onboarding Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.templates.autoconfigure;

import com.opeshealth.healthcore.templates.domain.TemplatesAndOnboardingEntities;
import com.opeshealth.healthcore.templates.service.TemplatesAndOperationsService;
import com.opeshealth.healthcore.templates.service.TemplatesAndOperationsService.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = TemplatesAndOnboardingEntities.class)
@EnableJpaRepositories(basePackageClasses = TemplatesAndOperationsService.class, considerNestedRepositories = true)
public class TemplatesAndDashboardsAutoConfiguration {

    @Bean @ConditionalOnMissingBean public PostgresBackupProvider postgresBackupProvider() { return new PostgresBackupProvider(); }
    @Bean @ConditionalOnMissingBean public MssqlBackupProvider mssqlBackupProvider() { return new MssqlBackupProvider(); }
    @Bean @ConditionalOnMissingBean public MysqlBackupProvider mysqlBackupProvider() { return new MysqlBackupProvider(); }

    @Bean
    @ConditionalOnMissingBean
    public TemplatesAndOperationsService templatesAndOperationsService(
            FacilityNodeRepository facilityRepo,
            DocumentTemplateRepository templateRepo,
            QuestionnaireResponseRepository responseRepo,
            BackupManifestRepository backupRepo,
            EdgeDrStateRepository edgeDrRepo,
            JdbcTemplate jdbcTemplate,
            List<BackupProviderSpi> backupProviders) {
        return new TemplatesAndOperationsService(
                facilityRepo, templateRepo, responseRepo, backupRepo, edgeDrRepo, jdbcTemplate, backupProviders);
    }

    @Bean
    @ConditionalOnMissingBean
    public TemplatesAndOpsController templatesAndOpsController(
            TemplatesAndOperationsService service,
            DocumentTemplateRepository templateRepo,
            QuestionnaireResponseRepository responseRepo,
            JdbcTemplate jdbcTemplate) {
        return new TemplatesAndOpsController(service, templateRepo, responseRepo, jdbcTemplate);
    }
}
