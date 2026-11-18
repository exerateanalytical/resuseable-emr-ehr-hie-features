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
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService;
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService.DicomPacsController;
import com.opeshealth.healthcore.hie.service.FhirOutboxSubscriptionRelay;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.*;
import com.opeshealth.healthcore.hie.service.Hl7MllpTcpServer;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.persistence.autoconfigure.ModuleSliceConditionSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = HieEntities.class)
@EnableJpaRepositories(basePackageClasses = HieInteropService.class)
public class HieInteropAutoConfiguration {

    private static final String FHIR_HIE_MARKER = "com.opeshealth.healthcore.starters.FhirHieStarter";
    private static final String IHE_MARKER = "com.opeshealth.healthcore.starters.InteroperabilityIheStarter";
    private static final String HL7_DICOM_MARKER = "com.opeshealth.healthcore.starters.Hl7AstmDicomStarter";
    private static final List<String> GROUP_MARKERS = List.of(FHIR_HIE_MARKER, IHE_MARKER, HL7_DICOM_MARKER);

    @Bean
    @ConditionalOnMissingBean
    public HieInteropService hieInteropService(
            MpiCommandService.PatientRepository patientRepo,
            FhirSubscriptionRepository subscriptionRepo,
            DicomStudyRepository dicomRepo,
            HieDlqRepository dlqRepo,
            IheDocumentRepository docRepo,
            JdbcTemplate jdbcTemplate) {
        return new HieInteropService(patientRepo, subscriptionRepo, dicomRepo, dlqRepo, docRepo, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public DicomPacsProxyService dicomPacsProxyService(
            DicomStudyRepository dicomRepo,
            HieDlqRepository dlqRepo) {
        return new DicomPacsProxyService(dicomRepo, dlqRepo);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "healthcore.hie.subscription-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FhirOutboxSubscriptionRelay fhirOutboxSubscriptionRelay(
            FhirSubscriptionRepository subscriptionRepo,
            HieInteropService hieService) {
        return new FhirOutboxSubscriptionRelay(subscriptionRepo, hieService);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnFhirHieSlice.class)
    public FhirHieController fhirHieController(
            HieInteropService service,
            FhirSubscriptionRepository subscriptionRepo) {
        return new FhirHieController(service, subscriptionRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnInteroperabilityIheSlice.class)
    public InteroperabilityIheController interoperabilityIheController(
            IheDocumentRepository docRepo,
            MpiCommandService.PatientRepository patientRepo) {
        return new InteroperabilityIheController(docRepo, patientRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnHl7AstmDicomSlice.class)
    public Hl7AstmDicomController hl7AstmDicomController(
            HieInteropService service,
            DicomStudyRepository dicomRepo) {
        return new Hl7AstmDicomController(service, dicomRepo);
    }

    @Bean @ConditionalOnMissingBean @Conditional(OnHl7AstmDicomSlice.class)
    public DicomPacsController dicomPacsController(DicomPacsProxyService proxy) {
        return new DicomPacsController(proxy);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "healthcore.hie.mllp", name = "enabled", havingValue = "true")
    public Hl7MllpTcpServer hl7MllpTcpServer(
            @Value("${healthcore.hie.mllp.port:2575}") int port,
            HieInteropService hieInteropService) {
        return new Hl7MllpTcpServer(port, hieInteropService);
    }

    static class OnFhirHieSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.fhir-hie.enabled", FHIR_HIE_MARKER, GROUP_MARKERS);
        }
    }
    static class OnInteroperabilityIheSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.interoperability-ihe.enabled", IHE_MARKER, GROUP_MARKERS);
        }
    }
    static class OnHl7AstmDicomSlice implements Condition {
        @Override public boolean matches(ConditionContext c, AnnotatedTypeMetadata m) {
            return ModuleSliceConditionSupport.isSliceEnabled(c, "healthcore.modules.hl7-astm-dicom.enabled", HL7_DICOM_MARKER, GROUP_MARKERS);
        }
    }
}
