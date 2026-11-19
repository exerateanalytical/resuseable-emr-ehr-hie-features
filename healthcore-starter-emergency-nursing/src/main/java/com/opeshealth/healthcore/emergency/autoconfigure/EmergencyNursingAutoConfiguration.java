/*
 * =============================================================================
 * Opes HealthCore SDK — Emergency & Inpatient Nursing Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emergency.autoconfigure;

import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = EmergencyNursingEntities.class)
@EnableJpaRepositories(basePackageClasses = EmergencyNursingService.class, considerNestedRepositories = true)
@Import(EmergencyNursingController.class)
public class EmergencyNursingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public EmergencyNursingService emergencyNursingService(
            EmergencyVisitRepository edVisitRepository,
            NursingAssignmentRepository assignmentRepository,
            News2ObservationRepository news2Repository,
            DischargeReadinessRepository dischargeRepository,
            ClinicalAuditService auditService) {
        return new EmergencyNursingService(
                edVisitRepository, assignmentRepository, news2Repository, dischargeRepository, auditService);
    }
}
