/*
 * =============================================================================
 * Opes HealthCore SDK — Radiology RIS Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.radiology.autoconfigure;

import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.ModalityWorklistRepository;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.RadiologyReportRepository;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.RadiologyRisController;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = RadiologyRisEntities.class)
@EnableJpaRepositories(basePackageClasses = RadiologyRisService.class, considerNestedRepositories = true)
@Import(RadiologyRisController.class)
public class RadiologyRisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RadiologyRisService radiologyRisService(
            ModalityWorklistRepository worklistRepository,
            RadiologyReportRepository reportRepository,
            ClinicalAuditService auditService) {
        return new RadiologyRisService(worklistRepository, reportRepository, auditService);
    }
}
