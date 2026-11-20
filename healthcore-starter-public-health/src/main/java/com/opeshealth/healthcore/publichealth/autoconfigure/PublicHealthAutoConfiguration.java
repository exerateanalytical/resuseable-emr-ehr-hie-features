/*
 * =============================================================================
 * Opes HealthCore SDK — Public Health, CHW & Disease Watch Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth.autoconfigure;

import com.opeshealth.healthcore.persistence.autoconfigure.ModuleSliceConditionSupport;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresAdvisoryLockHelper;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities;
import com.opeshealth.healthcore.publichealth.service.Dhis2PushClient;
import com.opeshealth.healthcore.publichealth.service.Dhis2PushClient.*;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = PublicHealthEntities.class)
@EnableJpaRepositories(basePackageClasses = {PublicHealthService.class, Dhis2PushClient.class}, considerNestedRepositories = true)
public class PublicHealthAutoConfiguration {

    private static final String CHW_MARKER = "com.opeshealth.healthcore.starters.ChwHomeCareStarter";
    private static final String EPI_MARKER = "com.opeshealth.healthcore.starters.ProgramsImmunizationStarter";
    private static final String IDSR_MARKER = "com.opeshealth.healthcore.starters.DiseaseSurveillanceStarter";
    private static final List<String> GROUP_MARKERS = List.of(CHW_MARKER, EPI_MARKER, IDSR_MARKER);

    @Bean
    @ConditionalOnMissingBean
    public PublicHealthService publicHealthService(
            ChwHouseholdRepository householdRepo,
            ChwVisitRepository visitRepo,
            HomeCarePlanRepository homeCareRepo,
            ImmunizationRepository immunizationRepo,
            SurveillanceCaseRepository surveillanceRepo,
            ContactTracingRepository contactRepo,
            CaseInvestigationRepository cifRepo,
            ChronicCascadeRepository cascadeRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        return new PublicHealthService(
                householdRepo, visitRepo, homeCareRepo, immunizationRepo, surveillanceRepo,
                contactRepo, cifRepo, cascadeRepo, outboxRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    public Dhis2PushClient dhis2PushClient(
            Dhis2MappingRepository mappingRepo,
            Dhis2SyncLogRepository syncLogRepo,
            SurveillanceCaseRepository surveillanceRepo,
            ObjectProvider<PostgresAdvisoryLockHelper> lockHelperProvider) {
        return new Dhis2PushClient(
                mappingRepo, syncLogRepo, surveillanceRepo, lockHelperProvider.getIfAvailable());
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnChwHomeCareSlice.class)
    public ChwHomeCareController chwHomeCareController(
            PublicHealthService service,
            ChwHouseholdRepository householdRepo,
            HomeCarePlanRepository homeCareRepo) {
        return new ChwHomeCareController(service, householdRepo, homeCareRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnProgramsEpiSlice.class)
    public ProgramsImmunizationController programsImmunizationController(
            PublicHealthService service,
            ChronicCascadeRepository cascadeRepo) {
        return new ProgramsImmunizationController(service, cascadeRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnDiseaseSurveillanceSlice.class)
    public DiseaseSurveillanceController diseaseSurveillanceController(
            PublicHealthService service,
            ContactTracingRepository contactRepo) {
        return new DiseaseSurveillanceController(service, contactRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnDiseaseSurveillanceSlice.class)
    public Dhis2Controller dhis2Controller(
            Dhis2PushClient client,
            Dhis2MappingRepository mappingRepo,
            Dhis2SyncLogRepository logRepo) {
        return new Dhis2Controller(client, mappingRepo, logRepo);
    }

    static class OnChwHomeCareSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.chw-homecare.enabled", CHW_MARKER, GROUP_MARKERS);
        }
    }
    static class OnProgramsEpiSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.programs-immunization.enabled", EPI_MARKER, GROUP_MARKERS);
        }
    }
    static class OnDiseaseSurveillanceSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.disease-surveillance.enabled", IDSR_MARKER, GROUP_MARKERS);
        }
    }
}
