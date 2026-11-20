/*
 * =============================================================================
 * Opes HealthCore SDK — Scheduling Starter Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.scheduling.autoconfigure;

import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@EntityScan(basePackageClasses = SchedulingEntities.class)
@EnableJpaRepositories(basePackageClasses = SchedulingService.class, considerNestedRepositories = true)
@Import(SchedulingController.class)
public class SchedulingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SchedulingService schedulingService(
            ResourceCalendarRepository calendarRepo,
            AppointmentTypeRepository typeRepo,
            ScheduledAppointmentRepository appointmentRepo,
            WaitlistRepository waitlistRepo,
            ClinicalAuditService auditService) {
        return new SchedulingService(calendarRepo, typeRepo, appointmentRepo, waitlistRepo, auditService);
    }
}
