/*
 * =============================================================================
 * Opes HealthCore SDK — EMR Clinical & Specialty Packs Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emr.autoconfigure;

import com.opeshealth.healthcore.emr.domain.EmrEntities;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.*;
import com.opeshealth.healthcore.emr.specialty.SpecialtyPackSpi;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = EmrEntities.class)
@EnableJpaRepositories(basePackageClasses = EmrClinicalWorkflowService.class, considerNestedRepositories = true)
public class EmrClinicalAutoConfiguration {

    @Bean @ConditionalOnMissingBean public SpecialtyPackSpi.DentalPack dentalPack() { return new SpecialtyPackSpi.DentalPack(); }
    @Bean @ConditionalOnMissingBean public SpecialtyPackSpi.EyeClinicPack eyeClinicPack() { return new SpecialtyPackSpi.EyeClinicPack(); }
    @Bean @ConditionalOnMissingBean public SpecialtyPackSpi.CardiacPack cardiacPack() { return new SpecialtyPackSpi.CardiacPack(); }
    @Bean @ConditionalOnMissingBean public SpecialtyPackSpi.PediatricPack pediatricPack() { return new SpecialtyPackSpi.PediatricPack(); }
    @Bean @ConditionalOnMissingBean public SpecialtyPackSpi.DermatologyPack dermatologyPack() { return new SpecialtyPackSpi.DermatologyPack(); }

    @Bean
    @ConditionalOnMissingBean
    public EmrClinicalWorkflowService emrClinicalWorkflowService(
            AppointmentRepository appointmentRepo,
            EncounterRepository encounterRepo,
            TriageRepository triageRepo,
            VitalSignsRepository vitalsRepo,
            ClinicalNoteRepository noteRepo,
            ClinicalOrderRepository orderRepo,
            WardBedRepository bedRepo,
            AdmissionRepository admissionRepo,
            EmarRepository emarRepo,
            CarePlanRepository carePlanRepo,
            SpecialtyRecordRepository specialtyRepo,
            FluidBalanceRepository fluidRepo,
            ShiftHandoverRepository handoverRepo,
            MedReconciliationRepository medRecRepo,
            ClinicalReferralRepository referralRepo,
            CdsHooksEngine cdsHooksEngine,
            OutboxRelayService.OutboxRepository outboxRepo,
            ObjectProvider<WorkforceCredentialSpi> workforceCredentialProvider,
            List<SpecialtyPackSpi> specialtyPacks) {
        return new EmrClinicalWorkflowService(
                appointmentRepo, encounterRepo, triageRepo, vitalsRepo, noteRepo,
                orderRepo, bedRepo, admissionRepo, emarRepo, carePlanRepo,
                specialtyRepo, fluidRepo, handoverRepo, medRecRepo, referralRepo,
                cdsHooksEngine, outboxRepo, workforceCredentialProvider, specialtyPacks);
    }

    @Bean
    @ConditionalOnMissingBean
    public EmrClinicalController emrClinicalController(EmrClinicalWorkflowService service) {
        return new EmrClinicalController(service);
    }
}
