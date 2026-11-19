/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Operations Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.ops.autoconfigure;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities;
import com.opeshealth.healthcore.ops.service.BiomedicalColdChainMqttClient;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.*;
import com.opeshealth.healthcore.persistence.autoconfigure.ModuleSliceConditionSupport;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.Arrays;
import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = HospitalOpsEntities.class)
@EnableJpaRepositories(basePackageClasses = HospitalOpsService.class)
@Import(BiomedicalColdChainMqttClient.class)
public class HospitalOpsAutoConfiguration {

    private static final String BLOOD_MARKER = "com.opeshealth.healthcore.starters.BloodBankStarter";
    private static final String SURGERY_MARKER = "com.opeshealth.healthcore.starters.SurgeryOtStarter";
    private static final String TELEMED_MARKER = "com.opeshealth.healthcore.starters.TelemedicineStarter";
    private static final String BIOMED_MARKER = "com.opeshealth.healthcore.starters.BiomedicalColdChainStarter";
    private static final String CRVS_MARKER = "com.opeshealth.healthcore.starters.CrvsMortuaryStarter";
    private static final String WORKFORCE_MARKER = "com.opeshealth.healthcore.starters.WorkforceRosterStarter";
    private static final String DEID_MARKER = "com.opeshealth.healthcore.starters.DeidentificationResearchStarter";
    private static final List<String> GROUP_MARKERS = List.of(
            BLOOD_MARKER, SURGERY_MARKER, TELEMED_MARKER, BIOMED_MARKER, CRVS_MARKER, WORKFORCE_MARKER, DEID_MARKER);

    @Bean
    @ConditionalOnMissingBean
    public HospitalOpsService hospitalOpsService(
            BloodUnitRepository bloodRepo,
            BloodDonorRepository donorRepo,
            TransfusionReactionRepository reactionRepo,
            SurgicalScheduleRepository surgeryRepo,
            AnesthesiaPacuRepository anesthesiaRepo,
            TelemedicineRepository telemedRepo,
            ColdChainRepository coldChainRepo,
            BiomedMaintenanceRepository biomedRepo,
            CrvsRepository crvsRepo,
            CredentialRepository credentialRepo,
            WorkforceRosterRepository rosterRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            Environment environment,
            @Value("${healthcore.workforce.allow-unregistered-in-dev:false}") boolean allowUnregisteredInDev) {
        HospitalOpsService service = new HospitalOpsService(
                bloodRepo, donorRepo, reactionRepo, surgeryRepo, anesthesiaRepo,
                telemedRepo, coldChainRepo, biomedRepo, crvsRepo, credentialRepo, rosterRepo, outboxRepo);
        boolean prodActive = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> "prod".equalsIgnoreCase(p) || "production".equalsIgnoreCase(p));
        service.configurePermissiveDevMode(allowUnregisteredInDev, prodActive);
        return service;
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnBloodBankSlice.class)
    public BloodBankController bloodBankController(HospitalOpsService service, BloodUnitRepository bloodRepo) {
        return new BloodBankController(service, bloodRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnSurgeryOtSlice.class)
    public SurgeryOtController surgeryOtController(HospitalOpsService service, SurgicalScheduleRepository surgeryRepo) {
        return new SurgeryOtController(service, surgeryRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnTelemedicineSlice.class)
    public TelemedicineController telemedicineController(TelemedicineRepository telemedRepo) {
        return new TelemedicineController(telemedRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnBiomedSlice.class)
    public BiomedicalColdChainController biomedicalColdChainController(
            HospitalOpsService service, BiomedMaintenanceRepository biomedRepo) {
        return new BiomedicalColdChainController(service, biomedRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnCrvsSlice.class)
    public CrvsMortuaryController crvsMortuaryController(HospitalOpsService service, CrvsRepository crvsRepo) {
        return new CrvsMortuaryController(service, crvsRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnWorkforceSlice.class)
    public WorkforceRosterController workforceRosterController(
            CredentialRepository credentialRepo, WorkforceRosterRepository rosterRepo) {
        return new WorkforceRosterController(credentialRepo, rosterRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnDeidSlice.class)
    public DeidentificationResearchController deidentificationResearchController(HospitalOpsService service) {
        return new DeidentificationResearchController(service);
    }

    static class OnBloodBankSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.blood-bank.enabled", BLOOD_MARKER, GROUP_MARKERS);
        }
    }
    static class OnSurgeryOtSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.surgery-ot.enabled", SURGERY_MARKER, GROUP_MARKERS);
        }
    }
    static class OnTelemedicineSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.telemedicine.enabled", TELEMED_MARKER, GROUP_MARKERS);
        }
    }
    public static class OnBiomedSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.biomedical-coldchain.enabled", BIOMED_MARKER, GROUP_MARKERS);
        }
    }
    static class OnCrvsSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.crvs-mortuary.enabled", CRVS_MARKER, GROUP_MARKERS);
        }
    }
    static class OnWorkforceSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.workforce-roster.enabled", WORKFORCE_MARKER, GROUP_MARKERS);
        }
    }
    static class OnDeidSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.deidentification-research.enabled", DEID_MARKER, GROUP_MARKERS);
        }
    }
}
