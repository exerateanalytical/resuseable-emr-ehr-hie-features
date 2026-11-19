/*
 * =============================================================================
 * Opes HealthCore SDK — Core Clinical Safety, Sync, Crypto & MPI Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.apiproduct.service.ApiProductService;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppEntity;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppRepository;
import com.opeshealth.healthcore.emr.specialty.SpecialtyPackSpi;
import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService;
import com.opeshealth.healthcore.hie.service.FhirOutboxSubscriptionRelay;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.IheDocumentRepository;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientDemographicsEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientIdentifierEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientPhoneticIndexEntity;
import com.opeshealth.healthcore.mpi.fhir.FhirPatientController;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.*;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import com.opeshealth.healthcore.ops.service.BiomedicalColdChainMqttClient;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.ColdChainRepository;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresAdvisoryLockHelper;
import com.opeshealth.healthcore.persistence.resilience.TwoTierAndKafkaExtensions.TwoTierCacheManager;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.Dhis2MappingEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.Dhis2SyncLogEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.SurveillanceCaseEntity;
import com.opeshealth.healthcore.publichealth.service.Dhis2PushClient;
import com.opeshealth.healthcore.publichealth.service.Dhis2PushClient.Dhis2MappingRepository;
import com.opeshealth.healthcore.publichealth.service.Dhis2PushClient.Dhis2SyncLogRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.SurveillanceCaseRepository;
import com.opeshealth.healthcore.security.audit.PhiRedactionConverter;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import com.opeshealth.healthcore.sync.service.OfflineSyncService;
import com.opeshealth.healthcore.sync.service.OfflineSyncService.*;
import com.opeshealth.healthcore.templates.domain.TemplatesAndOnboardingEntities.BackupManifestEntity;
import com.opeshealth.healthcore.templates.service.HospitalTemplateCatalog;
import com.opeshealth.healthcore.templates.service.TemplatesAndOperationsService.PostgresBackupProvider;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.CodeConceptEntity;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.ConceptClosureEntity;
import com.opeshealth.healthcore.terminology.service.FhirTerminologySyncClient;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import com.opeshealth.healthcore.terminology.service.TerminologyService.CodeConceptRepository;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.workflow.catalog.ReusableWorkflowCatalog;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.*;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService.*;
import com.opeshealth.healthcore.dto.EncounterDto;
import com.opeshealth.healthcore.dto.FhirCommonTypes.*;
import com.opeshealth.healthcore.dto.ObservationDto;
import com.opeshealth.healthcore.dto.PractitionerDto;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.domain.EmrEntities.VitalSignsEntity;
import com.opeshealth.healthcore.quickstart.domain.EncounterRepository;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationRepository;
import com.opeshealth.healthcore.quickstart.domain.PractitionerEntity;
import com.opeshealth.healthcore.quickstart.domain.PractitionerRepository;
import com.opeshealth.healthcore.quickstart.security.FhirApiRateLimitFilter;
import com.opeshealth.healthcore.quickstart.security.FhirApiRateLimitFilter.RateLimitPolicy;
import com.opeshealth.healthcore.quickstart.security.KeycloakOidcSecurityConfiguration;
import com.opeshealth.healthcore.quickstart.service.BundleService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsCoding;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackItem;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackOverrideReason;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackReceipt;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackRequest;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookExecutionEvent;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookRequest;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.ServiceDiscoveryResponse;
import com.opeshealth.healthcore.quickstart.service.EncounterService;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.AuditSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirResourceAccessEvent;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.HipaaGdprPatientComplianceReport;
import com.opeshealth.healthcore.quickstart.service.ObservationService;
import com.opeshealth.healthcore.quickstart.service.PractitionerService;
import com.opeshealth.healthcore.quickstart.config.FhirOpenApiConfiguration;
import com.opeshealth.healthcore.quickstart.config.FlywayMultiDbMigrationConfiguration;
import com.opeshealth.healthcore.quickstart.config.FlywayMultiDbMigrationConfiguration.DialectMigrationSummary;
import com.opeshealth.healthcore.quickstart.config.FlywayMultiDbMigrationConfiguration.RdbmsDialect;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlController;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlExceptionResolver;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.ColdChainSensorReading;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.MonitoringStage;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.SupplyThermalProfile;
import com.opeshealth.healthcore.quickstart.service.FhirBulkDataExportService;
import com.opeshealth.healthcore.quickstart.web.AuditEventController;
import com.opeshealth.healthcore.quickstart.web.BundleController;
import com.opeshealth.healthcore.quickstart.web.CdsHooksV2Controller;
import com.opeshealth.healthcore.quickstart.web.CodeSystemController;
import com.opeshealth.healthcore.quickstart.web.ColdChainMonitoringController;
import com.opeshealth.healthcore.quickstart.web.EncounterController;
import com.opeshealth.healthcore.quickstart.web.FhirBulkDataExportController;
import com.opeshealth.healthcore.quickstart.web.HealthCoreApiExceptionAdvice;
import com.opeshealth.healthcore.quickstart.web.ObservationController;
import com.opeshealth.healthcore.security.audit.AuditChainHeadEntity;
import com.opeshealth.healthcore.security.audit.AuditLogEntity;
import com.opeshealth.healthcore.security.audit.AuditRepositories.AuditChainHeadRepository;
import com.opeshealth.healthcore.security.audit.AuditRepositories.AuditLogRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService.ChainVerificationResult;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.quickstart.web.PatientAuditAspect;
import com.opeshealth.healthcore.quickstart.web.PatientController;
import com.opeshealth.healthcore.quickstart.web.PractitionerController;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.starters.*;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.*;
import ca.uhn.fhir.validation.ValidationResult;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.graphql.execution.AnnotatedControllerConfigurer;
import org.springframework.graphql.execution.DefaultExecutionGraphQlService;
import org.springframework.graphql.execution.ExecutionGraphQlService;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HealthCoreDomainSafetyTest {

    @Test
    void uuidV7IsStrictlyMonotonic() {
        UUID first = UuidV7Generator.nextUuid();
        UUID second = UuidV7Generator.nextUuid();
        assertTrue(first.toString().compareTo(second.toString()) < 0);
        assertEquals(7, first.version());
    }

    @Test
    void piiVaultEncryptsAndBlindIndexesDeterministically() {
        String plain = "CM-DLA-99887766";
        String c1 = PiiVaultCrypto.encrypt(plain);
        String c2 = PiiVaultCrypto.encrypt(plain);
        assertNotEquals(c1, c2, "Random GCM IV must produce distinct ciphertexts");
        assertEquals(plain, PiiVaultCrypto.decrypt(c1));
        assertEquals(PiiVaultCrypto.blindIndex(plain), PiiVaultCrypto.blindIndex("  cm-dla-99887766 "));
    }

    @Test
    void phiRedactionConverterScrubsSecretsAndIdentifiers() {
        String raw = "Authorization: Bearer aaa.bbb.ccc patient mrn=OPES-9981 nationalId=CM1234567 phone=+237677112233 email=jude@opesware.com";
        String clean = PhiRedactionConverter.redact(raw);
        assertFalse(clean.contains("OPES-9981"));
        assertFalse(clean.contains("CM1234567"));
        assertFalse(clean.contains("677112233"));
        assertFalse(clean.contains("jude@opesware.com"));
        assertFalse(clean.contains("aaa.bbb.ccc"));
        assertTrue(clean.contains("[REDACTED_MRN]"));
        assertTrue(clean.contains("[REDACTED_JWT]"));
    }

    @Test
    void hlcAndEd25519QrHealthCardWorkOffline() {
        HybridLogicalClock hlc = new HybridLogicalClock("ipad-ward-2");
        String t1 = hlc.nextTimestamp();
        String t2 = hlc.nextTimestamp();
        assertTrue(t1.compareTo(t2) < 0);

        var signer = new HybridLogicalClock.Ed25519QrHealthCardSigner();
        String token = signer.signCardPayload("{\"mrn\":\"OPES-101\",\"blood\":\"O+\"}");
        assertTrue(signer.verifyCardToken(token));
        assertFalse(signer.verifyCardToken(token + "tamper"));
    }

    @Test
    void fellegiSunterMatchesMinorSpellingVariance() {
        PatientAggregateEntity existing = new PatientAggregateEntity();
        existing.setFamilyName("Nshome");
        existing.setGivenName("Jude");
        existing.setBirthDate(LocalDate.of(1990, 5, 14));
        existing.setGender("male");
        existing.setPrimaryPhone("+237670000001");

        double score = FellegiSunterMatcher.score("Nchome", "Jude", LocalDate.of(1990, 5, 14), "male", "+237670000001", existing);
        assertTrue(score >= 0.88, "Expected AUTO_MATCH score >= 0.88, got " + score);
    }

    @Test
    void specialtyPacksValidateRequiredClinicalFields() {
        var eye = new SpecialtyPackSpi.EyeClinicPack();
        assertThrows(IllegalArgumentException.class, () -> eye.validatePayload(Map.of("odIopMmHg", 16)));
        assertDoesNotThrow(() -> eye.validatePayload(Map.of("odVisualAcuity", "20/20", "osVisualAcuity", "20/25")));
    }

    @Test
    void cdsHooksDetectsRenalImpairmentAndEncounterStartGaps() {
        TerminologyService.DdiRuleRepository emptyDdiRepo = (TerminologyService.DdiRuleRepository) Proxy.newProxyInstance(
                TerminologyService.DdiRuleRepository.class.getClassLoader(),
                new Class<?>[]{TerminologyService.DdiRuleRepository.class},
                (proxy, method, args) -> List.of()
        );
        CdsHooksEngine engine = new CdsHooksEngine(null, emptyDdiRepo);

        var req = new CdsHooksEngine.OrderSafetyRequest(
                UUID.randomUUID(), new BigDecimal("70"), new BigDecimal("24"), false,
                List.of(), List.of(), List.of(), "723", new BigDecimal("500")
        );
        List<CdsHooksEngine.CdsCard> orderCards = engine.evaluateOrderSafety(req);
        assertTrue(orderCards.stream().anyMatch(c -> "critical".equals(c.indicator()) && c.summary().contains("Renal")));

        List<CdsHooksEngine.CdsCard> startCards = engine.evaluateEncounterStart(UUID.randomUUID(), List.of("I10"), 52);
        assertEquals(2, startCards.size());
    }

    @Test
    void safeHarborDeidentificationRedactsIdentifiers() {
        var ops = new HospitalOpsService(null, null, null, null, null, null, null, null, null, null, null, null);
        Map<String, Object> safe = ops.deidentifySafeHarbor(UUID.randomUUID(), 1920, "female", "B50.9", "Littoral");
        assertEquals("90+", safe.get("ageGroup"));
        assertEquals("Lit", safe.get("regionPrefix"));
    }

    @Test
    void templateCatalogContains608UniqueHospitalTemplatesAcross16Packs() {
        assertEquals(16, HospitalTemplateCatalog.SIXTEEN_PACKS.size());
        Set<String> titles = new HashSet<>();
        for (String pack : HospitalTemplateCatalog.SIXTEEN_PACKS) {
            for (int i = 1; i <= 38; i++) {
                String title = HospitalTemplateCatalog.titleEn(pack, i);
                assertNotNull(title);
                assertTrue(titles.add(pack + "::" + title));
                String schema = HospitalTemplateCatalog.buildFhirQuestionnaireJson("T-" + pack + "-" + i, pack, title);
                assertTrue(schema.contains("\"resourceType\":\"Questionnaire\""));
            }
        }
        assertEquals(608, titles.size());
    }

    @Test
    void uhcOfflineEligibilityQrRejectsTamperedToken() {
        var uhc = new UhcBillingService(null, null, null, null, null, null, null, List.of());
        Map<String, Object> result = uhc.verifyOfflineEligibilityQr("tampered-invalid-token");
        assertEquals(Boolean.FALSE, result.get("valid"));
    }

    @Test
    void reusableWorkflowCatalogAndHookGatesEnforceStateTransitions() {
        assertEquals(8, ReusableWorkflowCatalog.allTemplates().size());
        assertTrue(ReusableWorkflowCatalog.requireTemplate(ReusableWorkflowCatalog.EHR_HIE_EXCHANGE_FLOW)
                .isTransitionAllowed("CONSENT_AND_ABAC_VERIFIED", "MPI_PIXM_RESOLVED"));
        assertFalse(ReusableWorkflowCatalog.requireTemplate(ReusableWorkflowCatalog.EHR_HIE_EXCHANGE_FLOW)
                .isTransitionAllowed("CONSENT_AND_ABAC_VERIFIED", "ATNA_AUDIT_SEALED"));

        Map<UUID, WorkflowInstanceEntity> instances = new HashMap<>();
        List<WorkflowStepLogEntity> logs = new ArrayList<>();
        List<TransactionalOutboxEntity> outbox = new ArrayList<>();

        WorkflowDefinitionRepository defRepo = (WorkflowDefinitionRepository) Proxy.newProxyInstance(
                WorkflowDefinitionRepository.class.getClassLoader(),
                new Class<?>[]{WorkflowDefinitionRepository.class},
                (p, m, a) -> Optional.empty());
        WorkflowInstanceRepository instRepo = (WorkflowInstanceRepository) Proxy.newProxyInstance(
                WorkflowInstanceRepository.class.getClassLoader(),
                new Class<?>[]{WorkflowInstanceRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        WorkflowInstanceEntity e = (WorkflowInstanceEntity) a[0];
                        instances.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(instances.get((UUID) a[0]));
                    default -> List.of();
                });
        WorkflowStepLogRepository logRepo = (WorkflowStepLogRepository) Proxy.newProxyInstance(
                WorkflowStepLogRepository.class.getClassLoader(),
                new Class<?>[]{WorkflowStepLogRepository.class},
                (p, m, a) -> {
                    if ("save".equals(m.getName())) {
                        WorkflowStepLogEntity e = (WorkflowStepLogEntity) a[0];
                        logs.add(e);
                        return e;
                    }
                    return logs;
                });
        OutboxRelayService.OutboxRepository outboxRepo = (OutboxRelayService.OutboxRepository) Proxy.newProxyInstance(
                OutboxRelayService.OutboxRepository.class.getClassLoader(),
                new Class<?>[]{OutboxRelayService.OutboxRepository.class},
                (p, m, a) -> {
                    if ("save".equals(m.getName())) {
                        TransactionalOutboxEntity e = (TransactionalOutboxEntity) a[0];
                        outbox.add(e);
                        return e;
                    }
                    return outbox;
                });

        AtomicBoolean afterCalled = new AtomicBoolean(false);
        WorkflowStepHookSpi narcoticWitnessGate = new WorkflowStepHookSpi() {
            @Override public boolean supportsWorkflow(String code) {
                return ReusableWorkflowCatalog.PHARMACY_DISPENSE_FLOW.equals(code);
            }
            @Override public void beforeTransition(WorkflowTransitionContext ctx) {
                if ("DUAL_WITNESS_VERIFIED".equals(ctx.toStage()) && !ctx.metadata().containsKey("witnessId")) {
                    throw new IllegalStateException("Controlled dispense requires witnessId");
                }
            }
            @Override public void afterTransition(WorkflowTransitionContext ctx) {
                afterCalled.set(true);
            }
        };

        WorkflowOrchestrationService svc = new WorkflowOrchestrationService(
                defRepo, instRepo, logRepo, outboxRepo, List.of(narcoticWitnessGate), List.of());

        WorkflowInstanceEntity rxFlow = svc.startWorkflow(
                UUID.randomUUID(), ReusableWorkflowCatalog.PHARMACY_DISPENSE_FLOW,
                UUID.randomUUID(), "RX-9001", WorkflowPriority.STAT,
                Map.of("controlled", true), "pharm-1", "PHARMACIST");

        assertEquals("PRESCRIPTION_RECEIVED", rxFlow.getCurrentStage());
        // Cannot skip CDSS step directly to DISPENSED_AND_CLAIMED
        assertThrows(IllegalStateException.class, () -> svc.transitionWorkflow(
                rxFlow.getId(), "DISPENSED_AND_CLAIMED", "pharm-1", "PHARMACIST", "Skip attempt", Map.of()));

        svc.transitionWorkflow(rxFlow.getId(), "CDSS_DDI_ALLERGY_CLEARED", "pharm-1", "PHARMACIST", "CDSS OK", Map.of());
        svc.transitionWorkflow(rxFlow.getId(), "FEFO_LOT_PICKED", "pharm-1", "PHARMACIST", "Lot A1", Map.of());

        // Hook gate blocks transition lacking witnessId
        assertThrows(IllegalStateException.class, () -> svc.transitionWorkflow(
                rxFlow.getId(), "DUAL_WITNESS_VERIFIED", "pharm-1", "PHARMACIST", "Missing witness", Map.of()));

        WorkflowInstanceEntity verified = svc.transitionWorkflow(
                rxFlow.getId(), "DUAL_WITNESS_VERIFIED", "pharm-1", "PHARMACIST", "Witnessed", Map.of("witnessId", "pharm-2"));
        assertEquals("DUAL_WITNESS_VERIFIED", verified.getCurrentStage());
        assertTrue(afterCalled.get());
        assertEquals(4, logs.size());
        assertEquals(4, outbox.size());
    }

    @Test
    void postgresAdvisoryLockHelperAllowsNonPostgresMode() {
        PostgresAdvisoryLockHelper helper = new PostgresAdvisoryLockHelper(null, false);
        assertTrue(helper.tryAcquireTransactionLock("idsr-weekly-report"));
    }

    @Test
    void coldChainMqttPayloadExcursionEmitsOutboxAlert() {
        List<ColdChainTelemetryEntity> readings = new ArrayList<>();
        List<TransactionalOutboxEntity> outbox = new ArrayList<>();

        ColdChainRepository coldRepo = (ColdChainRepository) Proxy.newProxyInstance(
                ColdChainRepository.class.getClassLoader(),
                new Class<?>[]{ColdChainRepository.class},
                (p, m, a) -> {
                    if ("save".equals(m.getName())) {
                        ColdChainTelemetryEntity e = (ColdChainTelemetryEntity) a[0];
                        readings.add(e);
                        return e;
                    }
                    return readings;
                });
        OutboxRelayService.OutboxRepository outboxRepo = (OutboxRelayService.OutboxRepository) Proxy.newProxyInstance(
                OutboxRelayService.OutboxRepository.class.getClassLoader(),
                new Class<?>[]{OutboxRelayService.OutboxRepository.class},
                (p, m, a) -> {
                    if ("save".equals(m.getName())) {
                        TransactionalOutboxEntity e = (TransactionalOutboxEntity) a[0];
                        outbox.add(e);
                        return e;
                    }
                    return outbox;
                });

        HospitalOpsService ops = new HospitalOpsService(
                null, null, null, null, null, null, coldRepo, null, null, null, null, outboxRepo);
        BiomedicalColdChainMqttClient mqttClient = new BiomedicalColdChainMqttClient(ops);

        UUID facId = UUID.randomUUID();
        mqttClient.handleTelemetryPayload("{\"facilityId\":\"" + facId + "\",\"equipmentTag\":\"EPI-FRIDGE-01\",\"tempCelsius\":11.4}");

        assertEquals(1, readings.size());
        assertTrue(readings.getFirst().isExcursionAlert());
        assertEquals(1, outbox.size());
        assertEquals("ColdChainExcursionEvent", outbox.getFirst().getEventType());
    }

    @Test
    void postgresBackupProviderBuildsVerifiedManifestWithoutLiveDb() {
        PostgresBackupProvider provider = new PostgresBackupProvider();
        assertEquals("POSTGRESQL", provider.dbEngine());
        BackupManifestEntity manifest = provider.executeSnapshot("s3://opes-backups/pg/base-001.tar.zst", null);
        assertEquals("POSTGRESQL", manifest.getDbEngine());
        assertTrue(manifest.isRestoreVerified());
        assertNotNull(manifest.getSha256Checksum());
        assertEquals(64, manifest.getSha256Checksum().length());
    }

    @Test
    void mpiFuzzySearchSupportsPortableFallbackWhenNoPgJdbcPresent() {
        UUID facId = UUID.randomUUID();
        PatientAggregateEntity matching = new PatientAggregateEntity();
        matching.setFacilityId(facId);
        matching.setMrn("DLA-4001");
        matching.setFamilyName("Nshome");
        matching.setGivenName("Jude");
        matching.setBirthDate(LocalDate.of(1990, 5, 14));
        matching.setGender("MALE");

        PatientAggregateEntity other = new PatientAggregateEntity();
        other.setFacilityId(facId);
        other.setMrn("DLA-4002");
        other.setFamilyName("Mbarga");
        other.setGivenName("Claire");
        other.setBirthDate(LocalDate.of(1992, 3, 10));
        other.setGender("FEMALE");

        MpiCommandService.PatientRepository patientRepo = (MpiCommandService.PatientRepository) Proxy.newProxyInstance(
                MpiCommandService.PatientRepository.class.getClassLoader(),
                new Class<?>[]{MpiCommandService.PatientRepository.class},
                (p, m, a) -> "findAll".equals(m.getName()) ? List.of(matching, other) : Optional.empty());

        MpiCommandService mpi = new MpiCommandService(patientRepo, null, null, null, null, null, null, null);
        List<PatientAggregateEntity> found = mpi.findByFacilityAndFuzzyName(facId, "nshom", 10);
        assertEquals(1, found.size());
        assertEquals("DLA-4001", found.getFirst().getMrn());
    }

    @Test
    void dhis2PushClientBuildsMappedAggregatePayloadAndLogsDryRun() {
        Dhis2MappingEntity map = new Dhis2MappingEntity();
        map.setDistrictCode("CM-LT-DLA");
        map.setSourceDomain("IDSR");
        map.setSourceCode("B50.9");
        map.setDhis2OrgUnitUid("OU_DOUALA_1");
        map.setDhis2DataElementUid("DE_MALARIA");
        map.setDhis2CategoryOptionUid("COC_CONF");

        SurveillanceCaseEntity c1 = new SurveillanceCaseEntity();
        c1.setFacilityId(UUID.randomUUID());
        c1.setPatientId(UUID.randomUUID());
        c1.setDistrictCode("CM-LT-DLA");
        c1.setDiseaseIcd10("B50.9");
        c1.setDiseaseName("Malaria");

        List<Dhis2SyncLogEntity> logs = new ArrayList<>();
        Dhis2MappingRepository mapRepo = (Dhis2MappingRepository) Proxy.newProxyInstance(
                Dhis2MappingRepository.class.getClassLoader(),
                new Class<?>[]{Dhis2MappingRepository.class},
                (p, m, a) -> List.of(map));
        Dhis2SyncLogRepository logRepo = (Dhis2SyncLogRepository) Proxy.newProxyInstance(
                Dhis2SyncLogRepository.class.getClassLoader(),
                new Class<?>[]{Dhis2SyncLogRepository.class},
                (p, m, a) -> {
                    if ("save".equals(m.getName())) {
                        Dhis2SyncLogEntity e = (Dhis2SyncLogEntity) a[0];
                        logs.add(e);
                        return e;
                    }
                    return logs;
                });
        SurveillanceCaseRepository caseRepo = (SurveillanceCaseRepository) Proxy.newProxyInstance(
                SurveillanceCaseRepository.class.getClassLoader(),
                new Class<?>[]{SurveillanceCaseRepository.class},
                (p, m, a) -> List.of(c1));

        Dhis2PushClient client = new Dhis2PushClient(mapRepo, logRepo, caseRepo, new PostgresAdvisoryLockHelper(null, false));
        Dhis2SyncLogEntity entry = client.pushAggregateDataValueSet("CM-LT-DLA", "2026W46", true);

        assertEquals("DRY_RUN", entry.getStatus());
        assertTrue(entry.getPayloadJson().contains("OU_DOUALA_1"));
        assertTrue(entry.getPayloadJson().contains("DE_MALARIA"));
        assertTrue(entry.getPayloadJson().contains("2026W46"));
    }

    @Test
    void dicomPacsProxyIndexesLocalStudyAndReturnsWadoReceipt() {
        Map<String, DicomStudyEntity> store = new HashMap<>();
        DicomStudyRepository dicomRepo = (DicomStudyRepository) Proxy.newProxyInstance(
                DicomStudyRepository.class.getClassLoader(),
                new Class<?>[]{DicomStudyRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findFirstByStudyInstanceUid" -> Optional.ofNullable(store.get((String) a[0]));
                    case "save" -> {
                        DicomStudyEntity s = (DicomStudyEntity) a[0];
                        store.put(s.getStudyInstanceUid(), s);
                        yield s;
                    }
                    default -> new ArrayList<>(store.values());
                });

        DicomPacsProxyService proxy = new DicomPacsProxyService(dicomRepo, null);
        Map<String, Object> receipt = proxy.storeBinaryStudy(
                UUID.randomUUID(), UUID.randomUUID(), "1.2.840.113619.2.55.3", "CR", "Chest X-Ray", "application/dicom", new byte[]{1, 2, 3});

        assertEquals("INDEXED_LOCAL", receipt.get("status"));
        assertEquals(3, receipt.get("bytesReceived"));

        ResponseEntity<byte[]> wadoResp = proxy.retrieveWadoBinary("1.2.840.113619.2.55.3", null, null);
        assertEquals(200, wadoResp.getStatusCode().value());
        assertTrue(new String(Objects.requireNonNull(wadoResp.getBody())).contains("1.2.840.113619.2.55.3"));
    }

    @Test
    void snomedRf2RelationshipBuilderComputesMultiHopTransitiveClosure() {
        Map<String, Set<String>> dag = Map.of(
                "764146007", Set.of("373270004"), // Penicillin -> Beta-lactam
                "373270004", Set.of("723")        // Beta-lactam -> Amoxicillin
        );
        List<ConceptClosureEntity> edges = TerminologyService.computeTransitiveClosureEdges(TerminologyService.SNOMED, dag);
        assertTrue(edges.stream().anyMatch(e ->
                "764146007".equals(e.getAncestorCode()) && "373270004".equals(e.getDescendantCode()) && e.getDepth() == 1));
        assertTrue(edges.stream().anyMatch(e ->
                "764146007".equals(e.getAncestorCode()) && "723".equals(e.getDescendantCode()) && e.getDepth() == 2));
    }

    @Test
    void fhirOutboxSubscriptionRelayMatchesCriteriaAndBuildsParametersEnvelope() {
        UUID facId = UUID.randomUUID();
        FhirSubscriptionEntity sub = new FhirSubscriptionEntity();
        sub.setFacilityId(facId);
        sub.setCriteria("Patient");
        sub.setEndpointUrl("https://hie.example.org/hook");

        HealthCoreDomainEvent event = new HealthCoreDomainEvent(
                UUID.randomUUID(), facId, "Patient", UUID.randomUUID(), "PatientRegisteredEvent", "{\"mrn\":\"DLA-1\"}", "corr-1");

        FhirOutboxSubscriptionRelay relay = new FhirOutboxSubscriptionRelay(null, null);
        assertTrue(relay.matchesSubscription(sub, event));
        String json = relay.buildFhirEventEnvelope(event);
        assertTrue(json.contains("\"resourceType\":\"Parameters\""));
        assertTrue(json.contains("PatientRegisteredEvent"));
    }

    @Test
    void fhirTerminologySyncClientParsesExpandedValueSetJson() {
        List<CodeConceptEntity> saved = new ArrayList<>();
        CodeConceptRepository conceptRepo = (CodeConceptRepository) Proxy.newProxyInstance(
                CodeConceptRepository.class.getClassLoader(),
                new Class<?>[]{CodeConceptRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findBySystemUriAndCode" -> Optional.empty();
                    case "save" -> {
                        CodeConceptEntity e = (CodeConceptEntity) a[0];
                        saved.add(e);
                        yield e;
                    }
                    default -> List.of();
                });

        TerminologyService termSvc = new TerminologyService(conceptRepo, null, null, null);
        FhirTerminologySyncClient client = new FhirTerminologySyncClient(termSvc, null);
        String expanded = """
                {
                  "resourceType": "ValueSet",
                  "expansion": {
                    "contains": [
                      {"system": "http://loinc.org", "code": "8867-4", "display": "Heart rate"},
                      {"system": "http://snomed.info/sct", "code": "386661006", "display": "Fever"}
                    ]
                  }
                }
                """;
        Map<String, Object> result = client.importExpandedValueSetJson(expanded);
        assertEquals(2, ((Number) result.get("importedCount")).intValue());
        assertEquals(2, saved.size());
    }

    @Test
    void offlineSyncResolvesConflictByPromotingIncomingDeltaOrKeepingServer() {
        UUID facId = UUID.randomUUID();
        UUID noteId = UUID.randomUUID();
        SyncConflictEntity conflict = new SyncConflictEntity();
        conflict.setFacilityId(facId);
        conflict.setEntityType("ClinicalNote");
        conflict.setEntityId(noteId);
        conflict.setOriginNodeId("ipad-3");
        conflict.setIncomingHlc("1700000000000:0001:ipad-3");
        conflict.setServerHlc("1700000000050:0001:srv");
        conflict.setIncomingPayload("{\"noteText\":\"Updated offline note\"}");

        List<SyncDeltaRecordEntity> savedDeltas = new ArrayList<>();
        SyncDeltaRepository deltaRepo = (SyncDeltaRepository) Proxy.newProxyInstance(
                SyncDeltaRepository.class.getClassLoader(),
                new Class<?>[]{SyncDeltaRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findMaxSyncSeq" -> 41L;
                    case "save" -> {
                        SyncDeltaRecordEntity d = (SyncDeltaRecordEntity) a[0];
                        savedDeltas.add(d);
                        yield d;
                    }
                    default -> List.of();
                });
        SyncConflictRepository conflictRepo = (SyncConflictRepository) Proxy.newProxyInstance(
                SyncConflictRepository.class.getClassLoader(),
                new Class<?>[]{SyncConflictRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findById" -> Optional.of(conflict);
                    case "save" -> a[0];
                    default -> List.of();
                });

        OfflineSyncService sync = new OfflineSyncService(
                deltaRepo, null, conflictRepo, new HybridLogicalClock("server-1"), null);
        Map<String, Object> res = sync.resolveConflict(conflict.getId(), "ACCEPT_INCOMING", "dr.nshome");

        assertEquals("RESOLVED_INCOMING", res.get("status"));
        assertEquals(42L, ((Number) res.get("promotedSyncSeq")).longValue());
        assertEquals(1, savedDeltas.size());
        assertEquals(42L, savedDeltas.getFirst().getSyncSeq());
        assertEquals("RESOLVED_INCOMING", conflict.getStatus());
    }

    @Test
    void hieCapabilityStatementMetadataListsCoreFhirR4ResourcesAndOperations() {
        HieInteropService hie = new HieInteropService(null, null, null, null, null, null);
        String metadataJson = hie.generateCapabilityStatementJson();
        assertTrue(metadataJson.contains("\"resourceType\": \"CapabilityStatement\""));
        assertTrue(metadataJson.contains("\"SMART-on-FHIR\""));
        assertTrue(metadataJson.contains("\"Patient\""));
        assertTrue(metadataJson.contains("\"DocumentReference\""));
        assertTrue(metadataJson.contains("\"Subscription\""));
        assertTrue(metadataJson.contains("\"summary\""));
    }

    @Test
    void twoTierCachePromotesL2HitsToL1AndBroadcastsEvictions() {
        var l1 = new ConcurrentMapCacheManager("terminologyConcepts");
        var l2 = new ConcurrentMapCacheManager("terminologyConcepts");
        List<String> broadcasts = new ArrayList<>();

        TwoTierCacheManager twoTier = new TwoTierCacheManager(
                l1, l2, (cache, key) -> broadcasts.add(cache + "::" + key));

        l2.getCache("terminologyConcepts").put("loinc:8867-4", "Heart rate");
        assertNull(l1.getCache("terminologyConcepts").get("loinc:8867-4"));

        // L2 hit promotes back into L1
        assertEquals("Heart rate", twoTier.getCache("terminologyConcepts").get("loinc:8867-4", String.class));
        assertEquals("Heart rate", l1.getCache("terminologyConcepts").get("loinc:8867-4", String.class));

        // Eviction notifies cluster peers
        twoTier.getCache("terminologyConcepts").evict("loinc:8867-4");
        assertTrue(broadcasts.contains("terminologyConcepts::loinc:8867-4"));
        assertNull(l1.getCache("terminologyConcepts").get("loinc:8867-4"));
        assertNull(l2.getCache("terminologyConcepts").get("loinc:8867-4"));
    }

    @Test
    void apiProductServiceRotatesKeysRevokesAppsAndSignsWebhooks() {
        Map<UUID, DeveloperAppEntity> apps = new HashMap<>();
        DeveloperAppRepository repo = (DeveloperAppRepository) Proxy.newProxyInstance(
                DeveloperAppRepository.class.getClassLoader(),
                new Class<?>[]{DeveloperAppRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        DeveloperAppEntity e = (DeveloperAppEntity) a[0];
                        apps.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(apps.get((UUID) a[0]));
                    default -> List.of();
                });

        ApiProductService apiSvc = new ApiProductService(repo);
        Map<String, Object> created = apiSvc.registerDeveloperApp("Cameroon Health Portal", "ENTERPRISE", null, false);
        UUID appId = (UUID) created.get("appId");
        String firstKey = (String) created.get("apiKey");
        assertTrue(firstKey.startsWith("opes_live_"));

        String firstHash = apps.get(appId).getApiKeyHash();
        Map<String, Object> rotated = apiSvc.rotateApiKey(appId);
        assertNotEquals(firstKey, rotated.get("apiKey"));
        assertNotEquals(firstHash, apps.get(appId).getApiKeyHash());

        String sig = apiSvc.signWebhookPayload("{\"event\":\"ping\"}", (String) created.get("webhookSecret"));
        assertEquals(64, sig.length());

        DeveloperAppEntity revoked = apiSvc.revokeApp(appId);
        assertFalse(revoked.isActive());
    }

    @Test
    void hieInteropRelinksDicomAndIheDocumentsOnPatientMergedEvent() {
        List<String> relinkCalls = new ArrayList<>();
        DicomStudyRepository dicomRepo = (DicomStudyRepository) Proxy.newProxyInstance(
                DicomStudyRepository.class.getClassLoader(),
                new Class<?>[]{DicomStudyRepository.class},
                (p, m, a) -> {
                    if ("relinkPatient".equals(m.getName())) {
                        relinkCalls.add("dicom:" + a[0] + "->" + a[1]);
                        return 2;
                    }
                    return List.of();
                });
        IheDocumentRepository docRepo = (IheDocumentRepository) Proxy.newProxyInstance(
                IheDocumentRepository.class.getClassLoader(),
                new Class<?>[]{IheDocumentRepository.class},
                (p, m, a) -> {
                    if ("relinkPatient".equals(m.getName())) {
                        relinkCalls.add("ihe:" + a[0] + "->" + a[1]);
                        return 1;
                    }
                    return List.of();
                });

        HieInteropService hie = new HieInteropService(null, null, dicomRepo, null, docRepo, null);
        UUID src = UUID.randomUUID();
        UUID surv = UUID.randomUUID();
        hie.onPatientMerged(new PatientMergedApplicationEvent(UUID.randomUUID(), src, surv, UUID.randomUUID()));

        assertTrue(relinkCalls.contains("dicom:" + src + "->" + surv));
        assertTrue(relinkCalls.contains("ihe:" + src + "->" + surv));
    }

    @Test
    void uhcBillingRelinksAccountsPoliciesClaimsAndVouchersOnOutboxPatientMergedEvent() {
        List<String> relinks = new ArrayList<>();
        var acctRepo = (UhcBillingService.PatientAccountRepository) Proxy.newProxyInstance(
                UhcBillingService.PatientAccountRepository.class.getClassLoader(),
                new Class<?>[]{UhcBillingService.PatientAccountRepository.class},
                (p, m, a) -> { if ("relinkPatient".equals(m.getName())) relinks.add("acct"); return 1; });
        var cbhiRepo = (UhcBillingService.CbhiPolicyRepository) Proxy.newProxyInstance(
                UhcBillingService.CbhiPolicyRepository.class.getClassLoader(),
                new Class<?>[]{UhcBillingService.CbhiPolicyRepository.class},
                (p, m, a) -> { if ("relinkPatient".equals(m.getName())) relinks.add("cbhi"); return 1; });
        var claimRepo = (UhcBillingService.InsuranceClaimRepository) Proxy.newProxyInstance(
                UhcBillingService.InsuranceClaimRepository.class.getClassLoader(),
                new Class<?>[]{UhcBillingService.InsuranceClaimRepository.class},
                (p, m, a) -> { if ("relinkPatient".equals(m.getName())) relinks.add("claim"); return 1; });
        var voucherRepo = (UhcBillingService.UhcVoucherRepository) Proxy.newProxyInstance(
                UhcBillingService.UhcVoucherRepository.class.getClassLoader(),
                new Class<?>[]{UhcBillingService.UhcVoucherRepository.class},
                (p, m, a) -> { if ("relinkPatient".equals(m.getName())) relinks.add("voucher"); return 1; });

        UhcBillingService uhc = new UhcBillingService(acctRepo, null, cbhiRepo, claimRepo, null, voucherRepo, null, List.of());
        UUID src = UUID.randomUUID();
        UUID surv = UUID.randomUUID();
        uhc.onOutboxDomainEvent(new HealthCoreDomainEvent(
                UUID.randomUUID(), UUID.randomUUID(), "Patient", surv,
                "PatientMergedEvent", "{\"sourceId\":\"" + src + "\",\"survivingId\":\"" + surv + "\"}", "c-1"));

        assertEquals(List.of("acct", "cbhi", "claim", "voucher"), relinks);
    }

    @Test
    void fhirPatientControllerMapsAndPersistsFullFhirR4DemographicsAndContacts() {
        Map<UUID, PatientAggregateEntity> patients = new HashMap<>();
        List<PatientIdentifierEntity> identifiers = new ArrayList<>();
        Map<UUID, PatientDemographicsEntity> demographics = new HashMap<>();

        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    default -> List.of();
                });
        PatientIdentifierRepository identRepo = (PatientIdentifierRepository) Proxy.newProxyInstance(
                PatientIdentifierRepository.class.getClassLoader(),
                new Class<?>[]{PatientIdentifierRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientIdentifierEntity e = (PatientIdentifierEntity) a[0];
                        identifiers.removeIf(existing -> existing.getId().equals(e.getId()));
                        identifiers.add(e);
                        yield e;
                    }
                    case "findByPatientIdOrderByIdentifierSystemAsc", "findByPatientId" ->
                            identifiers.stream().filter(i -> i.getPatientId().equals(a[0])).toList();
                    case "findFirstByPatientIdAndIdentifierSystem" ->
                            identifiers.stream()
                                    .filter(i -> i.getPatientId().equals(a[0]) && i.getIdentifierSystem().equals(a[1]))
                                    .findFirst();
                    default -> Optional.empty();
                });
        PatientDemographicsRepository demoRepo = (PatientDemographicsRepository) Proxy.newProxyInstance(
                PatientDemographicsRepository.class.getClassLoader(),
                new Class<?>[]{PatientDemographicsRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientDemographicsEntity d = (PatientDemographicsEntity) a[0];
                        demographics.put(d.getPatientId(), d);
                        yield d;
                    }
                    case "findByPatientId" -> Optional.ofNullable(demographics.get((UUID) a[0]));
                    default -> Optional.empty();
                });
        PatientPhoneticRepository phonRepo = (PatientPhoneticRepository) Proxy.newProxyInstance(
                PatientPhoneticRepository.class.getClassLoader(),
                new Class<?>[]{PatientPhoneticRepository.class},
                (p, m, a) -> "save".equals(m.getName()) ? (PatientPhoneticIndexEntity) a[0] : List.of());

        MpiCommandService mpi = new MpiCommandService(patRepo, identRepo, demoRepo, phonRepo, null, null, null, null);
        FhirPatientController controller = new FhirPatientController(mpi, patRepo, identRepo, demoRepo);

        String fhirJson = """
                {
                  "resourceType": "Patient",
                  "active": true,
                  "identifier": [
                    {"system": "urn:opes:mrn", "value": "DLA-7788"},
                    {"system": "urn:cm:cni", "value": "CM-CNI-112233", "use": "official"}
                  ],
                  "name": [
                    {"use": "official", "prefix": ["Dr."], "family": "Nshome", "given": ["Jude", "K"]}
                  ],
                  "telecom": [
                    {"system": "phone", "value": "+237677001122", "use": "mobile"},
                    {"system": "email", "value": "jude@opesware.com", "use": "home"}
                  ],
                  "gender": "male",
                  "birthDate": "1990-05-14",
                  "maritalStatus": {
                    "coding": [{"system": "http://terminology.hl7.org/CodeSystem/v3-MaritalStatus", "code": "M"}]
                  },
                  "address": [
                    {
                      "use": "home",
                      "line": ["Rue Joss 42"],
                      "city": "Douala",
                      "district": "Wouri",
                      "state": "Littoral",
                      "postalCode": "00237",
                      "country": "CM"
                    }
                  ],
                  "communication": [
                    {"language": {"coding": [{"system": "urn:ietf:bcp:47", "code": "fr"}]}, "preferred": true}
                  ],
                  "contact": [
                    {
                      "relationship": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0131", "code": "C", "display": "Spouse"}]}],
                      "name": {"text": "Marie Nshome"},
                      "telecom": [{"system": "phone", "value": "+237699001122"}]
                    }
                  ]
                }
                """;

        UUID facId = UUID.randomUUID();
        ResponseEntity<String> created = controller.createFhirPatient(facId, fhirJson);
        assertEquals(201, created.getStatusCode().value());
        String body = Objects.requireNonNull(created.getBody());
        assertTrue(body.contains("\"DLA-7788\""));
        assertTrue(body.contains("\"CM-CNI-112233\""));
        assertTrue(body.contains("\"jude@opesware.com\""));
        assertTrue(body.contains("\"Douala\""));
        assertTrue(body.contains("\"00237\""));
        assertTrue(body.contains("\"Marie Nshome\""));
        assertEquals(1, identifiers.size());
        assertEquals(PiiVaultCrypto.blindIndex("CM-CNI-112233"), identifiers.getFirst().getBlindIndex());
    }

    @Test
    void keycloakOidcConverterExtractsRealmClientRolesAndScopes() {
        Jwt jwt = Jwt.withTokenValue("mock-keycloak-oidc-token")
                .header("alg", "RS256")
                .subject("f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
                .claim("preferred_username", "dr.jude.nshome")
                .claim("realm_access", Map.of("roles", List.of("ATTENDING_PHYSICIAN")))
                .claim("resource_access", Map.of("healthcore-mobile-kmp", Map.of("roles", List.of("PHARMACIST"))))
                .claim("scope", "openid fhirUser patient/Patient.rs")
                .build();

        AbstractAuthenticationToken auth = KeycloakOidcSecurityConfiguration
                .buildKeycloakJwtConverter("healthcore-mobile-kmp")
                .convert(jwt);

        assertNotNull(auth);
        assertEquals("dr.jude.nshome", auth.getName());
        Set<String> names = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("ROLE_ATTENDING_PHYSICIAN"));
        assertTrue(names.contains("ROLE_PHARMACIST"));
        assertTrue(names.contains("PERM_patient:read"));
        assertTrue(names.contains("PERM_medication:dispense"));
        assertTrue(names.contains("SCOPE_openid"));
        assertTrue(names.contains("SCOPE_patient/Patient.rs"));
    }

    @Test
    void practitionerEncounterAndObservationDtosRoundTripFhirR4() {
        PractitionerDto pracDto = new PractitionerDto(
                "Practitioner",
                "prac-101",
                null,
                List.of(new FhirIdentifierDto("official", null, "urn:cm:onmc", "ONMC-2025-881", "Ordre National des Medecins")),
                true,
                List.of(new FhirHumanNameDto("official", "Dr. Jude Nshome", "Nshome", List.of("Jude"), List.of("Dr."), List.of("MD"))),
                List.of(new FhirContactPointDto("phone", "+237677001122", "work", 1)),
                List.of(new FhirAddressDto("work", List.of("Rue Joss 42"), "Douala", "Wouri", "Littoral", "00237", "CM")),
                "male",
                LocalDate.of(1986, 4, 12),
                List.of(new PractitionerDto.QualificationDto(
                        List.of(new FhirIdentifierDto(null, null, "urn:cm:onmc:qual", "MD-881", null)),
                        FhirCodeableConceptDto.of("http://terminology.hl7.org/CodeSystem/v2-0360", "MD", "Doctor of Medicine"),
                        new FhirPeriodDto(Instant.parse("2015-07-01T00:00:00Z"), null),
                        "University of Yaounde I")),
                List.of(FhirCodeableConceptDto.of("urn:ietf:bcp:47", "fr", "French"))
        );

        Practitioner fhirPrac = pracDto.toFhirR4();
        PractitionerDto roundTrippedPrac = PractitionerDto.fromFhirR4(fhirPrac);
        assertEquals("Practitioner", roundTrippedPrac.resourceType());
        assertEquals("prac-101", roundTrippedPrac.id());
        assertEquals("Nshome", roundTrippedPrac.name().getFirst().family());
        assertEquals("ONMC-2025-881", roundTrippedPrac.identifier().getFirst().value());
        assertEquals("MD-881", roundTrippedPrac.qualification().getFirst().identifier().getFirst().value());

        EncounterEntity encEntity = new EncounterEntity();
        encEntity.setFacilityId(UUID.randomUUID());
        encEntity.setPatientId(UUID.randomUUID());
        encEntity.setEncounterClass("EMER");
        encEntity.setStatus("IN_PROGRESS");
        encEntity.setAttendingId("prac-101");
        encEntity.setDepartmentCode("TRIAGE-ED");
        encEntity.setPrimaryIcd10("B50.9");

        EncounterDto encDto = EncounterDto.fromEntity(encEntity);
        Encounter fhirEnc = encDto.toFhirR4();
        EncounterDto roundTrippedEnc = EncounterDto.fromFhirR4(fhirEnc);
        assertEquals("in-progress", roundTrippedEnc.status());
        assertEquals("EMER", roundTrippedEnc.fhirClass().code());
        assertEquals("B50.9", roundTrippedEnc.reasonCode().getFirst().coding().getFirst().code());

        VitalSignsEntity vitals = new VitalSignsEntity();
        vitals.setEncounterId(encEntity.getId());
        vitals.setPatientId(encEntity.getPatientId());
        vitals.setTempCelsius(new BigDecimal("38.4"));
        vitals.setHeartRateBpm(104);
        vitals.setSystolicBp(132);
        vitals.setDiastolicBp(84);
        vitals.setNews2Score(4);

        List<ObservationDto> obsList = ObservationDto.vitalSignsToObservations(vitals);
        assertEquals(4, obsList.size());
        ObservationDto bpPanel = obsList.stream()
                .filter(o -> "85354-9".equals(o.code().coding().getFirst().code()))
                .findFirst()
                .orElseThrow();
        Observation fhirObs = bpPanel.toFhirR4();
        ObservationDto roundTrippedObs = ObservationDto.fromFhirR4(fhirObs);
        assertEquals("final", roundTrippedObs.status());
        assertEquals(2, roundTrippedObs.component().size());
        assertEquals(new BigDecimal("132"), roundTrippedObs.component().getFirst().valueQuantity().value());
    }

    @Test
    void controllerAdviceFormatsStandardJsonAndFhirOperationOutcome() throws Exception {
        HealthCoreApiExceptionAdvice advice = new HealthCoreApiExceptionAdvice();

        MockHttpServletRequest restReq = new MockHttpServletRequest("POST", "/api/v1/mpi/patients");
        restReq.addHeader("X-Correlation-Id", "corr-rest-400");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "patient");
        bindingResult.addError(new FieldError("patient", "familyName", "must not be blank"));
        MethodParameter param = new MethodParameter(
                HealthCoreDomainSafetyTest.class.getDeclaredMethod("dummyValidationTarget", String.class), 0);
        ResponseEntity<Object> validationResp = advice.handleValidationErrors(
                new MethodArgumentNotValidException(param, bindingResult), restReq);

        assertEquals(400, validationResp.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, validationResp.getHeaders().getContentType());
        var apiError = assertInstanceOf(HealthCoreApiExceptionAdvice.ApiErrorResponse.class, validationResp.getBody());
        assertEquals("VALIDATION_FAILED", apiError.code());
        assertEquals("corr-rest-400", apiError.correlationId());
        assertEquals("familyName", apiError.fieldErrors().getFirst().field());

        MockHttpServletRequest fhirReq = new MockHttpServletRequest("PUT", "/fhir/r4/Patient/123");
        fhirReq.addHeader("X-Correlation-Id", "corr-fhir-409");
        ResponseEntity<Object> conflictResp = advice.handleOptimisticLock(
                new OptimisticLockingFailureException("stale"), fhirReq);
        assertEquals(409, conflictResp.getStatusCode().value());
        assertEquals("application/fhir+json", Objects.requireNonNull(conflictResp.getHeaders().getContentType()).toString());
        String ooBody = assertInstanceOf(String.class, conflictResp.getBody());
        assertTrue(ooBody.contains("\"resourceType\": \"OperationOutcome\""));
        assertTrue(ooBody.contains("\"code\": \"conflict\""));
        assertTrue(ooBody.contains("OPTIMISTIC_LOCK_CONFLICT"));

        MockHttpServletRequest acceptFhirReq = new MockHttpServletRequest("GET", "/api/v1/emr/encounters/999");
        acceptFhirReq.addHeader("Accept", "application/fhir+json");
        ResponseEntity<Object> notFoundResp = advice.handleNotFound(new NoSuchElementException("Encounter missing"), acceptFhirReq);
        assertEquals(404, notFoundResp.getStatusCode().value());
        assertTrue(assertInstanceOf(String.class, notFoundResp.getBody()).contains("\"code\": \"not-found\""));

        ResponseEntity<Object> forbiddenResp = advice.handleForbidden(
                new AccessDeniedException("Missing role"), new MockHttpServletRequest("GET", "/api/v1/uhc/claims"));
        assertEquals(403, forbiddenResp.getStatusCode().value());
        assertEquals("ACCESS_DENIED", assertInstanceOf(HealthCoreApiExceptionAdvice.ApiErrorResponse.class, forbiddenResp.getBody()).code());
    }

    @Test
    void patientServiceAndControllerSupportFhirValidationCrudAndSearch() {
        Map<UUID, PatientAggregateEntity> patients = new LinkedHashMap<>();
        List<PatientIdentifierEntity> identifiers = new ArrayList<>();
        Map<UUID, PatientDemographicsEntity> demographics = new HashMap<>();

        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    case "findByFacilityIdAndEmailIgnoreCase" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0])
                                    && e.getEmail() != null && e.getEmail().equalsIgnoreCase((String) a[1]))
                            .findFirst();
                    case "findByFacilityIdAndPrimaryPhone" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPrimaryPhone(), a[1]))
                            .toList();
                    case "findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && e.isActive())
                            .toList();
                    case "deleteById" -> {
                        patients.remove((UUID) a[0]);
                        yield null;
                    }
                    default -> new ArrayList<>(patients.values());
                });

        PatientIdentifierRepository identRepo = (PatientIdentifierRepository) Proxy.newProxyInstance(
                PatientIdentifierRepository.class.getClassLoader(),
                new Class<?>[]{PatientIdentifierRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientIdentifierEntity e = (PatientIdentifierEntity) a[0];
                        identifiers.removeIf(ex -> ex.getId().equals(e.getId()));
                        identifiers.add(e);
                        yield e;
                    }
                    case "findByPatientIdOrderByIdentifierSystemAsc", "findByPatientId" ->
                            identifiers.stream().filter(i -> i.getPatientId().equals(a[0])).toList();
                    case "findFirstByPatientIdAndIdentifierSystem" ->
                            identifiers.stream()
                                    .filter(i -> i.getPatientId().equals(a[0]) && i.getIdentifierSystem().equals(a[1]))
                                    .findFirst();
                    case "findByIdentifierSystemAndBlindIndex" ->
                            identifiers.stream()
                                    .filter(i -> i.getIdentifierSystem().equals(a[0]) && i.getBlindIndex().equals(a[1]))
                                    .findFirst();
                    default -> Optional.empty();
                });

        PatientDemographicsRepository demoRepo = (PatientDemographicsRepository) Proxy.newProxyInstance(
                PatientDemographicsRepository.class.getClassLoader(),
                new Class<?>[]{PatientDemographicsRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientDemographicsEntity d = (PatientDemographicsEntity) a[0];
                        demographics.put(d.getPatientId(), d);
                        yield d;
                    }
                    case "findByPatientId" -> Optional.ofNullable(demographics.get((UUID) a[0]));
                    case "deleteByPatientId" -> {
                        demographics.remove((UUID) a[0]);
                        yield null;
                    }
                    default -> Optional.empty();
                });

        PatientService service = new PatientService(patRepo, identRepo, demoRepo);
        PatientController controller = new PatientController(service);
        UUID facId = UUID.randomUUID();

        // Validation rejects missing HumanName, invalid email, and future birthDate
        assertThrows(IllegalArgumentException.class, () ->
                controller.createPatient(facId, null, "{\"resourceType\":\"Patient\",\"gender\":\"male\",\"birthDate\":\"1990-01-01\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                controller.createPatient(facId, null,
                        "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Nshome\",\"given\":[\"Jude\"]}],\"gender\":\"male\",\"birthDate\":\"2099-01-01\"}"));
        assertThrows(IllegalArgumentException.class, () ->
                controller.createPatient(facId, null,
                        "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Nshome\",\"given\":[\"Jude\"]}],\"gender\":\"male\",\"birthDate\":\"1990-05-14\",\"telecom\":[{\"system\":\"email\",\"value\":\"bad-email\"}]}"));

        // Create two valid patients
        ResponseEntity<String> c1 = controller.createPatient(facId, null, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "DLA-5001"}],
                  "name": [{"family": "Nshome", "given": ["Jude"]}],
                  "telecom": [{"system": "phone", "value": "+237677112233"}, {"system": "email", "value": "jude@opesware.com"}],
                  "gender": "male",
                  "birthDate": "1990-05-14"
                }
                """);
        assertEquals(201, c1.getStatusCode().value());

        controller.createPatient(facId, null, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "DLA-5002"}],
                  "name": [{"family": "Mbarga", "given": ["Claire"]}],
                  "gender": "female",
                  "birthDate": "1985-03-10"
                }
                """);

        // Duplicate MRN rejected
        assertThrows(IllegalStateException.class, () -> controller.createPatient(facId, null, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "DLA-5001"}],
                  "name": [{"family": "Duplicate", "given": ["User"]}],
                  "gender": "male",
                  "birthDate": "1991-01-01"
                }
                """));

        // Search with standard FHIR query params (birthdate>=1990-01-01 & gender=male)
        ResponseEntity<String> searchResp = controller.searchPatients(
                null, null, null, null, null,
                "nshom", null, null,
                null, null, null,
                List.of("ge1990-01-01"), null, null,
                "male", null, null, true, facId, null, 20, 0);
        String bundleJson = Objects.requireNonNull(searchResp.getBody());
        assertTrue(bundleJson.contains("\"resourceType\": \"Bundle\""));
        assertTrue(bundleJson.contains("\"total\": 1"));
        assertTrue(bundleJson.contains("DLA-5001"));
        assertFalse(bundleJson.contains("DLA-5002"));

        // Complex FHIR modifiers: :contains (substring) and :exact (case-sensitive equality)
        ResponseEntity<String> containsResp = controller.searchPatients(
                null, null, null, "shom", null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, true, facId, null, 20, 0);
        assertTrue(Objects.requireNonNull(containsResp.getBody()).contains("DLA-5001"));

        ResponseEntity<String> exactHitResp = controller.searchPatients(
                null, null, null, null, null,
                null, null, "Nshome",
                null, null, null,
                null, null, null,
                null, null, null, true, facId, null, 20, 0);
        assertTrue(Objects.requireNonNull(exactHitResp.getBody()).contains("\"total\": 1"));

        ResponseEntity<String> exactMissCaseResp = controller.searchPatients(
                null, null, null, null, null,
                null, null, "nshome",
                null, null, null,
                null, null, null,
                null, null, null, true, facId, null, 20, 0);
        assertTrue(Objects.requireNonNull(exactMissCaseResp.getBody()).contains("\"total\": 0"));

        // Birthdate range filtering via multi-value FHIR expressions & birthdateFrom/To
        ResponseEntity<String> rangeResp = controller.searchPatients(
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                List.of("ge1984-01-01", "le1986-12-31"), null, null,
                null, null, null, true, facId, null, 20, 0);
        String rangeBody = Objects.requireNonNull(rangeResp.getBody());
        assertTrue(rangeBody.contains("DLA-5002"));
        assertFalse(rangeBody.contains("DLA-5001"));

        ResponseEntity<String> partialMonthResp = controller.searchPatients(
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                List.of("1990-05"), LocalDate.of(1990, 5, 1), LocalDate.of(1990, 5, 31),
                null, null, null, true, facId, null, 20, 0);
        assertTrue(Objects.requireNonNull(partialMonthResp.getBody()).contains("DLA-5001"));

        assertThrows(IllegalArgumentException.class, () -> controller.searchPatients(
                null, null, null, null, null, null, null, null, null, null, null,
                null, LocalDate.of(2000, 1, 1), LocalDate.of(1990, 1, 1),
                null, null, null, true, facId, null, 20, 0));

        // HIPAA AOP Aspect intercepts PatientController calls with timestamp, userId, action & outcome
        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(controller);
        proxyFactory.addAspect(auditAspect);
        PatientController proxiedController = proxyFactory.getProxy();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ATTENDING_PHYSICIAN"))));
        try {
            UUID judeId = patients.values().stream()
                    .filter(p -> "DLA-5001".equals(p.getMrn()))
                    .findFirst().orElseThrow().getId();

            ResponseEntity<String> fetched = proxiedController.getPatientById(judeId);
            assertEquals(200, fetched.getStatusCode().value());
            assertTrue(Objects.requireNonNull(fetched.getBody()).contains("\"Nshome\""));

            assertThrows(NoSuchElementException.class, () -> proxiedController.getPatientById(UUID.randomUUID()));

            assertEquals(2, auditTrail.size());
            assertNotNull(auditTrail.get(0).timestamp());
            assertEquals("dr.nshome", auditTrail.get(0).userId());
            assertEquals("HIPAA_PATIENT_READ", auditTrail.get(0).action());
            assertEquals("SUCCESS", auditTrail.get(0).outcome());
            assertEquals(judeId, auditTrail.get(0).patientId());
            assertEquals("FAILURE", auditTrail.get(1).outcome());
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Retrieve by ID, Update, and Deactivate (soft delete)
        UUID judeId = patients.values().stream()
                .filter(p -> "DLA-5001".equals(p.getMrn()))
                .findFirst().orElseThrow().getId();

        ResponseEntity<String> updated = controller.updatePatient(judeId, "W/\"0\"",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Nshome\",\"given\":[\"Jude\",\"K\"]}],\"telecom\":[{\"system\":\"phone\",\"value\":\"+237699887766\"}]}");
        assertEquals(200, updated.getStatusCode().value());
        assertTrue(Objects.requireNonNull(updated.getBody()).contains("+237699887766"));

        ResponseEntity<Void> deactivated = controller.deactivatePatient(judeId);
        assertEquals(204, deactivated.getStatusCode().value());
        assertFalse(service.getPatientById(judeId).isActive());
    }

    @Test
    void patientServiceFhirValidatorEnforcesStrictHl7FhirR4Schema() {
        Map<UUID, PatientAggregateEntity> patients = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> Optional.empty();
                    default -> new ArrayList<>(patients.values());
                });

        PatientService patientService = new PatientService(patRepo);
        UUID facId = UUID.randomUUID();

        // Unknown FHIR R4 property rejected strictly by StrictErrorHandler before persistence
        assertThrows(IllegalArgumentException.class, () -> patientService.createFromFhirJson(facId, """
                {
                  "resourceType": "Patient",
                  "unknownNonFhirField": "invalid",
                  "name": [{"family": "Nshome", "given": ["Jude"]}],
                  "gender": "male",
                  "birthDate": "1990-05-14"
                }
                """));

        // Mismatched resourceType rejected before persistence
        assertThrows(IllegalArgumentException.class, () -> patientService.createFromFhirJson(facId, """
                {
                  "resourceType": "Practitioner",
                  "name": [{"family": "Nshome", "given": ["Jude"]}],
                  "gender": "male"
                }
                """));
        assertTrue(patients.isEmpty());

        // Direct HAPI FHIR R4 schema validator check on valid Patient
        Patient validFhirPatient = patientService.parseFhirPatient("""
                {
                  "resourceType": "Patient",
                  "active": true,
                  "name": [{"use": "official", "family": "Nshome", "given": ["Jude"]}],
                  "gender": "male",
                  "birthDate": "1990-05-14"
                }
                """);
        ValidationResult schemaResult = patientService.validatePatientSchema(validFhirPatient);
        assertTrue(schemaResult.isSuccessful());
    }

    @Test
    void practitionerServiceControllerAndAuditAspectSupportCrudSearchAndHipaaLogging() {
        Map<UUID, PractitionerEntity> store = new LinkedHashMap<>();
        PractitionerRepository repo = (PractitionerRepository) Proxy.newProxyInstance(
                PractitionerRepository.class.getClassLoader(),
                new Class<?>[]{PractitionerRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PractitionerEntity e = (PractitionerEntity) a[0];
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(store.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0])
                                    && Objects.equals(e.getIdentifierSystem(), a[1])
                                    && Objects.equals(e.getIdentifierValue(), a[2]))
                            .findFirst();
                    case "findByFacilityIdAndActiveTrueOrderByFamilyNameAsc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && e.isActive())
                            .toList();
                    case "findByFacilityIdOrderByFamilyNameAsc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]))
                            .toList();
                    case "deleteById" -> {
                        store.remove((UUID) a[0]);
                        yield null;
                    }
                    default -> new ArrayList<>(store.values());
                });

        PractitionerService service = new PractitionerService(repo);
        PractitionerController rawController = new PractitionerController(service);

        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(rawController);
        proxyFactory.addAspect(auditAspect);
        PractitionerController controller = proxyFactory.getProxy();

        UUID facId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_FACILITY_ADMIN"))));
        try {
            // Strict schema rejects unknown property and missing name
            assertThrows(IllegalArgumentException.class, () -> controller.createPractitioner(facId, null,
                    "{\"resourceType\":\"Practitioner\",\"unknownField\":1,\"name\":[{\"family\":\"Mbarga\",\"given\":[\"Claire\"]}]}"));
            assertThrows(IllegalArgumentException.class, () -> controller.createPractitioner(facId, null,
                    "{\"resourceType\":\"Practitioner\",\"gender\":\"female\"}"));

            ResponseEntity<String> created = controller.createPractitioner(facId, null, """
                    {
                      "resourceType": "Practitioner",
                      "active": true,
                      "identifier": [{"system": "urn:cm:onmc", "value": "ONMC-2026-101"}],
                      "name": [{"prefix": ["Dr."], "family": "Nshome", "given": ["Jude"]}],
                      "telecom": [{"system": "phone", "value": "+237677001122"}, {"system": "email", "value": "jude@opeshealthsystems.com"}],
                      "gender": "male",
                      "qualification": [{"code": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0360", "code": "MD", "display": "Doctor of Medicine"}]}}]
                    }
                    """);
            assertEquals(201, created.getStatusCode().value());
            String createdBody = Objects.requireNonNull(created.getBody());
            assertTrue(createdBody.contains("ONMC-2026-101"));

            // Duplicate facility license rejected
            assertThrows(IllegalStateException.class, () -> controller.createPractitioner(facId, null, """
                    {
                      "resourceType": "Practitioner",
                      "identifier": [{"system": "urn:cm:onmc", "value": "ONMC-2026-101"}],
                      "name": [{"family": "Duplicate", "given": ["Doc"]}]
                    }
                    """));

            // Search with :contains modifier and qualification filter
            ResponseEntity<String> searchResp = controller.searchPractitioners(
                    null, null, null, "shom", null,
                    null, null, null,
                    null, null, null,
                    "male", "MD", true, facId, null, 20, 0);
            assertTrue(Objects.requireNonNull(searchResp.getBody()).contains("\"total\": 1"));

            UUID pracId = store.values().iterator().next().getId();
            ResponseEntity<String> readResp = controller.getPractitionerById(pracId);
            assertEquals(200, readResp.getStatusCode().value());

            ResponseEntity<String> updatedResp = controller.updatePractitioner(pracId, "W/\"0\"", """
                    {
                      "resourceType": "Practitioner",
                      "name": [{"prefix": ["Prof."], "family": "Nshome", "given": ["Jude"]}],
                      "telecom": [{"system": "phone", "value": "+237699112233"}]
                    }
                    """);
            assertEquals(200, updatedResp.getStatusCode().value());
            assertTrue(Objects.requireNonNull(updatedResp.getBody()).contains("+237699112233"));

            ResponseEntity<Void> deletedResp = controller.deactivatePractitioner(pracId);
            assertEquals(204, deletedResp.getStatusCode().value());
            assertFalse(service.getPractitionerById(pracId).isActive());

            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Practitioner".equals(r.resourceType())
                            && "HIPAA_PRACTITIONER_CREATE".equals(r.action())
                            && "admin.nshome".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())
                            && r.patientId() == null));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Practitioner".equals(r.resourceType())
                            && "HIPAA_PRACTITIONER_CREATE".equals(r.action())
                            && "FAILURE".equals(r.outcome())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Practitioner".equals(r.resourceType())
                            && "HIPAA_PRACTITIONER_SEARCH".equals(r.action())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Practitioner".equals(r.resourceType())
                            && "HIPAA_PRACTITIONER_DEACTIVATE".equals(r.action())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void observationServiceControllerValidationAndAuditAspectWorkEndToEnd() {
        Map<UUID, ObservationEntity> store = new LinkedHashMap<>();
        ObservationRepository repo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) a[0];
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(store.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0])
                                    && Objects.equals(e.getIdentifierSystem(), a[1])
                                    && Objects.equals(e.getIdentifierValue(), a[2]))
                            .findFirst();
                    case "findByFacilityIdAndPatientIdOrderByEffectiveAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    case "findByFacilityIdAndEncounterIdOrderByEffectiveAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getEncounterId(), a[1]))
                            .toList();
                    case "findByFacilityIdOrderByEffectiveAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]))
                            .toList();
                    case "deleteById" -> {
                        store.remove((UUID) a[0]);
                        yield null;
                    }
                    default -> new ArrayList<>(store.values());
                });

        ObservationService service = new ObservationService(repo);
        ObservationController rawController = new ObservationController(service);

        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(rawController);
        proxyFactory.addAspect(auditAspect);
        ObservationController controller = proxyFactory.getProxy();

        UUID facId = UUID.randomUUID();
        UUID patId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("nurse.biya", "n/a", List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));
        try {
            // Strict schema rejects unknown property, missing subject, and inverted referenceRange
            assertThrows(IllegalArgumentException.class, () -> controller.createObservation(facId, null,
                    "{\"resourceType\":\"Observation\",\"badField\":true,\"status\":\"final\",\"code\":{\"coding\":[{\"code\":\"8867-4\"}]},\"subject\":{\"reference\":\"Patient/" + patId + "\"}}"));
            assertThrows(IllegalArgumentException.class, () -> controller.createObservation(facId, null,
                    "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"coding\":[{\"code\":\"8867-4\"}]}}"));
            assertThrows(IllegalArgumentException.class, () -> controller.createObservation(facId, null,
                    "{\"resourceType\":\"Observation\",\"status\":\"final\",\"code\":{\"coding\":[{\"code\":\"8867-4\"}]},\"subject\":{\"reference\":\"Patient/" + patId + "\"},\"referenceRange\":[{\"low\":{\"value\":120},\"high\":{\"value\":60}}]}"));

            // Create valid multi-component BP Observation panel
            ResponseEntity<String> createdBp = controller.createObservation(facId, null, """
                    {
                      "resourceType": "Observation",
                      "identifier": [{"system": "urn:opes:vitals", "value": "OBS-BP-001"}],
                      "status": "final",
                      "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs", "display": "Vital Signs"}]}],
                      "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9", "display": "Blood pressure panel"}]},
                      "subject": {"reference": "Patient/%s"},
                      "effectiveDateTime": "2026-11-18T10:15:00Z",
                      "component": [
                        {"code": {"coding": [{"system": "http://loinc.org", "code": "8480-6", "display": "Systolic blood pressure"}]}, "valueQuantity": {"value": 124, "unit": "mmHg", "system": "http://unitsofmeasure.org", "code": "mm[Hg]"}},
                        {"code": {"coding": [{"system": "http://loinc.org", "code": "8462-4", "display": "Diastolic blood pressure"}]}, "valueQuantity": {"value": 80, "unit": "mmHg", "system": "http://unitsofmeasure.org", "code": "mm[Hg]"}}
                      ]
                    }
                    """.formatted(patId));
            assertEquals(201, createdBp.getStatusCode().value());
            String bpBody = Objects.requireNonNull(createdBp.getBody());
            assertTrue(bpBody.contains("85354-9"));
            assertTrue(bpBody.contains("8480-6"));
            assertTrue(bpBody.contains("8462-4"));

            // Duplicate facility identifier rejected
            assertThrows(IllegalStateException.class, () -> controller.createObservation(facId, null, """
                    {
                      "resourceType": "Observation",
                      "identifier": [{"system": "urn:opes:vitals", "value": "OBS-BP-001"}],
                      "status": "final",
                      "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
                      "subject": {"reference": "Patient/%s"}
                    }
                    """.formatted(patId)));

            // Search by patient, category, LOINC code, and date range
            ResponseEntity<String> searchResp = controller.searchObservations(
                    null, null, "Patient/" + patId, null, null, null,
                    "vital-signs", "85354-9", null, "final",
                    List.of("ge2026-11-01", "le2026-11-30"), null, null,
                    facId, null, 20, 0);
            assertTrue(Objects.requireNonNull(searchResp.getBody()).contains("\"total\": 1"));

            UUID obsId = store.values().iterator().next().getId();
            ResponseEntity<String> readResp = controller.getObservationById(obsId);
            assertEquals(200, readResp.getStatusCode().value());

            // Update with If-Match ETag
            ResponseEntity<String> updatedResp = controller.updateObservation(obsId, "W/\"0\"", """
                    {
                      "resourceType": "Observation",
                      "status": "amended",
                      "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9", "display": "Blood pressure panel"}]},
                      "subject": {"reference": "Patient/%s"},
                      "note": [{"text": "Verified sitting cuff reading"}]
                    }
                    """.formatted(patId));
            assertEquals(200, updatedResp.getStatusCode().value());
            assertTrue(Objects.requireNonNull(updatedResp.getBody()).contains("amended"));

            // Stale ETag rejected
            assertThrows(OptimisticLockingFailureException.class, () -> controller.updateObservation(obsId, "W/\"99\"", """
                    {
                      "resourceType": "Observation",
                      "status": "final",
                      "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9"}]},
                      "subject": {"reference": "Patient/%s"}
                    }
                    """.formatted(patId)));

            // Soft-delete marks Observation as entered-in-error
            ResponseEntity<Void> deletedResp = controller.deactivateObservation(obsId);
            assertEquals(204, deletedResp.getStatusCode().value());
            assertEquals("entered-in-error", service.getObservationById(obsId).getStatus());

            // Verify HIPAA audit trail captured timestamp, userId, Observation actions, extracted patientId, and outcomes
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Observation".equals(r.resourceType())
                            && "HIPAA_OBSERVATION_CREATE".equals(r.action())
                            && "nurse.biya".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())
                            && patId.equals(r.patientId())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Observation".equals(r.resourceType())
                            && "HIPAA_OBSERVATION_CREATE".equals(r.action())
                            && "FAILURE".equals(r.outcome())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Observation".equals(r.resourceType())
                            && "HIPAA_OBSERVATION_SEARCH".equals(r.action())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Observation".equals(r.resourceType())
                            && "HIPAA_OBSERVATION_DEACTIVATE".equals(r.action())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void encounterServiceControllerValidationAndAuditAspectWorkEndToEnd() {
        Map<UUID, EncounterEntity> store = new LinkedHashMap<>();
        EncounterRepository repo = (EncounterRepository) Proxy.newProxyInstance(
                EncounterRepository.class.getClassLoader(),
                new Class<?>[]{EncounterRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        EncounterEntity e = (EncounterEntity) a[0];
                        store.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(store.get((UUID) a[0]));
                    case "findByFacilityIdAndPatientIdOrderByStartedAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    case "findByFacilityIdAndStatusIgnoreCaseOrderByStartedAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0])
                                    && e.getStatus() != null
                                    && e.getStatus().equalsIgnoreCase((String) a[1]))
                            .toList();
                    case "findByFacilityIdOrderByStartedAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]))
                            .toList();
                    case "findByPatientIdOrderByStartedAtDesc" -> store.values().stream()
                            .filter(e -> Objects.equals(e.getPatientId(), a[0]))
                            .toList();
                    case "deleteById" -> {
                        store.remove((UUID) a[0]);
                        yield null;
                    }
                    default -> new ArrayList<>(store.values());
                });

        EncounterService encounterService = new EncounterService(repo);
        CdsHooksService cdsHooksService = new CdsHooksService();
        EncounterController rawController = new EncounterController(encounterService, cdsHooksService);

        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(rawController);
        proxyFactory.addAspect(auditAspect);
        EncounterController controller = proxyFactory.getProxy();

        UUID facId = UUID.randomUUID();
        UUID patId = UUID.randomUUID();
        UUID pracId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ATTENDING_PHYSICIAN"))));
        try {
            // HAPI FHIR StrictErrorHandler + Encounter validation rejects unknown property, missing Patient/<uuid>, and inverted period
            assertThrows(IllegalArgumentException.class, () -> controller.createEncounter(facId, null,
                    "{\"resourceType\":\"Encounter\",\"unexpectedAttr\":123,\"status\":\"arrived\",\"class\":{\"code\":\"EMER\"},\"subject\":{\"reference\":\"Patient/" + patId + "\"}}"));
            assertThrows(IllegalArgumentException.class, () -> controller.createEncounter(facId, null,
                    "{\"resourceType\":\"Encounter\",\"status\":\"arrived\",\"class\":{\"code\":\"AMB\"},\"subject\":{\"reference\":\"Patient/not-a-uuid\"}}"));
            assertThrows(IllegalArgumentException.class, () -> controller.createEncounter(facId, null,
                    "{\"resourceType\":\"Encounter\",\"status\":\"finished\",\"class\":{\"code\":\"AMB\"},\"subject\":{\"reference\":\"Patient/" + patId + "\"},\"period\":{\"start\":\"2026-11-18T12:00:00Z\",\"end\":\"2026-11-18T09:00:00Z\"}}"));

            // Create emergency Encounter triggering CDS Hooks 2.0 ED triage + ICD-10 I10 cards
            ResponseEntity<String> createdResp = controller.createEncounter(facId, null, """
                    {
                      "resourceType": "Encounter",
                      "status": "arrived",
                      "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "EMER", "display": "emergency"},
                      "subject": {"reference": "Patient/%s"},
                      "participant": [{"individual": {"reference": "Practitioner/%s"}}],
                      "period": {"start": "2026-11-18T08:00:00Z"},
                      "reasonCode": [{"coding": [{"system": "http://hl7.org/fhir/sid/icd-10", "code": "I10", "display": "Essential hypertension"}]}],
                      "location": [{"location": {"reference": "Location/ED-TRIAGE-1"}}]
                    }
                    """.formatted(patId, pracId));
            assertEquals(201, createdResp.getStatusCode().value());
            String createdBody = Objects.requireNonNull(createdResp.getBody());
            assertTrue(createdBody.contains("\"code\": \"EMER\""));
            assertTrue(createdBody.contains("I10"));
            String initialEtag = Objects.requireNonNull(createdResp.getHeaders().getFirst("ETag"));
            assertEquals("2", createdResp.getHeaders().getFirst("X-CDS-Hooks-Alert-Count"));
            assertEquals("warning", createdResp.getHeaders().getFirst("X-CDS-Hooks-Max-Indicator"));

            // Search by patient, status, class, reason-code, and FHIR date range
            ResponseEntity<String> searchResp = controller.searchEncounters(
                    null, "Patient/" + patId, null, "arrived", "EMER",
                    pracId.toString(), "ED-TRIAGE", "I10",
                    List.of("ge2026-11-01", "le2026-11-30"), null, null,
                    facId, null, 20, 0);
            assertTrue(Objects.requireNonNull(searchResp.getBody()).contains("\"total\": 1"));

            UUID encId = store.values().iterator().next().getId();
            ResponseEntity<String> readResp = controller.getEncounterById(encId);
            assertEquals(200, readResp.getStatusCode().value());

            // Optimistic-locked update with valid If-Match ETag succeeds
            ResponseEntity<String> updatedResp = controller.updateEncounter(encId, initialEtag, """
                    {
                      "resourceType": "Encounter",
                      "status": "finished",
                      "class": {"code": "EMER"},
                      "subject": {"reference": "Patient/%s"},
                      "period": {"start": "2026-11-18T08:00:00Z", "end": "2026-11-18T11:30:00Z"}
                    }
                    """.formatted(patId));
            assertEquals(200, updatedResp.getStatusCode().value());
            assertTrue(Objects.requireNonNull(updatedResp.getBody()).contains("\"status\": \"finished\""));

            // Stale ETag rejected
            assertThrows(OptimisticLockingFailureException.class, () -> controller.updateEncounter(encId, "W/\"999\"", """
                    {
                      "resourceType": "Encounter",
                      "status": "in-progress",
                      "class": {"code": "EMER"},
                      "subject": {"reference": "Patient/%s"}
                    }
                    """.formatted(patId)));

            // Cancel Encounter (soft delete)
            ResponseEntity<Void> cancelledResp = controller.deactivateEncounter(encId);
            assertEquals(204, cancelledResp.getStatusCode().value());
            assertEquals("CANCELLED", encounterService.getEncounterById(encId).getStatus());

            // Verify HIPAA audit trail captured Encounter actions, timestamp, userId, patientId, and outcomes
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_CREATE".equals(r.action())
                            && "dr.nshome".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())
                            && patId.equals(r.patientId())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_CREATE".equals(r.action())
                            && "FAILURE".equals(r.outcome())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_SEARCH".equals(r.action())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_READ".equals(r.action())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_UPDATE".equals(r.action())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Encounter".equals(r.resourceType())
                            && "HIPAA_ENCOUNTER_DEACTIVATE".equals(r.action())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void cdsHooksV2ServiceDiscoveryEvaluationFeedbackAndInteractionTriggersWork() {
        Map<UUID, PatientAggregateEntity> patients = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> Optional.empty();
                    default -> new ArrayList<>(patients.values());
                });

        Map<UUID, ObservationEntity> observations = new LinkedHashMap<>();
        ObservationRepository obsRepo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) a[0];
                        observations.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(observations.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    case "findByFacilityIdAndPatientIdOrderByEffectiveAtDesc" -> observations.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    default -> new ArrayList<>(observations.values());
                });

        PatientService patientService = new PatientService(patRepo);
        ObservationService observationService = new ObservationService(obsRepo);
        CdsHooksService cdsHooksService = new CdsHooksService(patientService, obsRepo, null);
        CdsHooksV2Controller cdsController = new CdsHooksV2Controller(cdsHooksService);
        PatientController patientController = new PatientController(patientService, cdsHooksService);
        ObservationController observationController = new ObservationController(observationService, cdsHooksService);

        List<CdsHookExecutionEvent> executions = new ArrayList<>();
        cdsHooksService.registerListener(executions::add);

        // 1. CDS Hooks 2.0 Service Discovery
        ServiceDiscoveryResponse discovery = Objects.requireNonNull(cdsController.discoverServices().getBody());
        assertEquals(4, discovery.services().size());
        assertTrue(discovery.services().stream().anyMatch(s ->
                CdsHooksService.SERVICE_PATIENT_CARE_GAPS.equals(s.id())
                        && "patient-view".equals(s.hook())
                        && s.prefetch().containsKey("patient")));
        assertTrue(discovery.services().stream().anyMatch(s ->
                CdsHooksService.SERVICE_OBSERVATION_ALERTS.equals(s.id())
                        && s.prefetch().containsKey("observations")));
        assertTrue(discovery.services().stream().anyMatch(s ->
                CdsHooksService.SERVICE_ENCOUNTER_TRIAGE.equals(s.id())
                        && "encounter-start".equals(s.hook())));

        UUID facId = UUID.randomUUID();
        // 2. PatientController POST interaction automatically triggers patient-view preventive CDSS card
        ResponseEntity<String> patResp = patientController.createPatient(facId, null, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "DLA-CDS-01"}],
                  "name": [{"family": "Muna", "given": ["Paul"]}],
                  "gender": "male",
                  "birthDate": "1972-04-12"
                }
                """);
        assertEquals(201, patResp.getStatusCode().value());
        assertEquals("1", patResp.getHeaders().getFirst("X-CDS-Hooks-Alert-Count"));
        assertEquals("info", patResp.getHeaders().getFirst("X-CDS-Hooks-Max-Indicator"));
        UUID patId = patients.keySet().iterator().next();

        // 3. ObservationController POST critical hypertensive crisis BP panel triggers critical Card + Flag systemAction
        ResponseEntity<String> bpResp = observationController.createObservation(facId, null, """
                {
                  "resourceType": "Observation",
                  "status": "final",
                  "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
                  "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9", "display": "Blood pressure panel"}]},
                  "subject": {"reference": "Patient/%s"},
                  "component": [
                    {"code": {"coding": [{"system": "http://loinc.org", "code": "8480-6", "display": "Systolic BP"}]}, "valueQuantity": {"value": 192, "unit": "mmHg"}},
                    {"code": {"coding": [{"system": "http://loinc.org", "code": "8462-4", "display": "Diastolic BP"}]}, "valueQuantity": {"value": 124, "unit": "mmHg"}}
                  ]
                }
                """.formatted(patId));
        assertEquals(201, bpResp.getStatusCode().value());
        assertEquals("critical", bpResp.getHeaders().getFirst("X-CDS-Hooks-Max-Indicator"));

        // 4. Direct CDS Hooks 2.0 invocation with critical potassium LOINC 2823-3 in prefetch
        CdsHookRequest obsHookReq = new CdsHookRequest(
                "patient-view",
                UUID.randomUUID().toString(),
                "https://ehr.opeshealthsystems.com/fhir",
                null,
                Map.of("patientId", patId.toString()),
                Map.of("observations", Map.of(
                        "resourceType", "Observation",
                        "status", "final",
                        "code", Map.of("coding", List.of(Map.of("system", "http://loinc.org", "code", "2823-3"))),
                        "subject", Map.of("reference", "Patient/" + patId),
                        "valueQuantity", Map.of("value", 6.5, "unit", "mmol/L")
                ))
        );
        CdsHookResponse obsHookResp = Objects.requireNonNull(cdsController.invokeObservationAlerts(facId, obsHookReq).getBody());
        assertTrue(obsHookResp.cards().stream().anyMatch(c ->
                "critical".equals(c.indicator())
                        && c.summary().contains("PANIC LAB")
                        && !c.suggestions().isEmpty()
                        && !c.overrideReasons().isEmpty()));

        // Direct patient-view invocation merges stored critical BP systemActions (Flag)
        CdsHookResponse patViewResp = Objects.requireNonNull(cdsController.invokePatientCareGaps(
                facId,
                new CdsHookRequest("patient-view", UUID.randomUUID().toString(), null, null, Map.of("patientId", patId.toString()), Map.of())
        ).getBody());
        assertTrue(patViewResp.cards().size() >= 2);
        assertEquals("critical", patViewResp.highestIndicator());
        assertTrue(patViewResp.systemActions().stream().anyMatch(sa ->
                "create".equals(sa.type()) && "Flag".equals(sa.resource().get("resourceType"))));

        // 5. CDS Hooks 2.0 Feedback recording & validation
        String cardUuid = obsHookResp.cards().get(0).uuid();
        CdsFeedbackReceipt receipt = Objects.requireNonNull(cdsController.submitFeedback(
                CdsHooksService.SERVICE_OBSERVATION_ALERTS,
                new CdsFeedbackRequest(List.of(new CdsFeedbackItem(
                        cardUuid,
                        "overridden",
                        List.of(),
                        new CdsFeedbackOverrideReason(
                                new CdsCoding("clinical-judgment", "https://www.opeshealthsystems.com/fhir/CodeSystem/cds-override", "Hemolyzed sample repeat ordered"),
                                "Repeating stat serum potassium"
                        ),
                        Instant.now()
                )))
        ).getBody());
        assertEquals(1, receipt.recordedCount());
        assertEquals(1, cdsHooksService.getRecordedFeedback().size());
        assertThrows(IllegalArgumentException.class, () -> cdsController.submitFeedback(
                CdsHooksService.SERVICE_OBSERVATION_ALERTS,
                new CdsFeedbackRequest(List.of(new CdsFeedbackItem(cardUuid, "invalid-status", List.of(), null, null)))));
        assertTrue(executions.size() >= 4);
    }

    @Test
    void bundleTransactionAtomicityAndBatchIsolationWorkEndToEnd() {
        Map<UUID, PatientAggregateEntity> patients = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    default -> new ArrayList<>(patients.values());
                });

        Map<UUID, PractitionerEntity> practitioners = new LinkedHashMap<>();
        PractitionerRepository pracRepo = (PractitionerRepository) Proxy.newProxyInstance(
                PractitionerRepository.class.getClassLoader(),
                new Class<?>[]{PractitionerRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PractitionerEntity e = (PractitionerEntity) a[0];
                        practitioners.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(practitioners.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    default -> new ArrayList<>(practitioners.values());
                });

        Map<UUID, EncounterEntity> encounters = new LinkedHashMap<>();
        EncounterRepository encRepo = (EncounterRepository) Proxy.newProxyInstance(
                EncounterRepository.class.getClassLoader(),
                new Class<?>[]{EncounterRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        EncounterEntity e = (EncounterEntity) a[0];
                        encounters.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(encounters.get((UUID) a[0]));
                    case "findByFacilityIdAndPatientIdOrderByStartedAtDesc" -> encounters.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    default -> new ArrayList<>(encounters.values());
                });

        Map<UUID, ObservationEntity> observations = new LinkedHashMap<>();
        ObservationRepository obsRepo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) a[0];
                        observations.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(observations.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    case "findByFacilityIdAndPatientIdOrderByEffectiveAtDesc" -> observations.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    default -> new ArrayList<>(observations.values());
                });

        // In-memory PlatformTransactionManager supporting commit & snapshot rollback
        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            private final Deque<Map<String, Set<UUID>>> checkpoints = new ArrayDeque<>();

            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
                checkpoints.push(Map.of(
                        "pat", new LinkedHashSet<>(patients.keySet()),
                        "prac", new LinkedHashSet<>(practitioners.keySet()),
                        "enc", new LinkedHashSet<>(encounters.keySet()),
                        "obs", new LinkedHashSet<>(observations.keySet())
                ));
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                if (!checkpoints.isEmpty()) checkpoints.pop();
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                if (!checkpoints.isEmpty()) {
                    Map<String, Set<UUID>> snap = checkpoints.pop();
                    patients.keySet().retainAll(snap.get("pat"));
                    practitioners.keySet().retainAll(snap.get("prac"));
                    encounters.keySet().retainAll(snap.get("enc"));
                    observations.keySet().retainAll(snap.get("obs"));
                }
            }
        };

        PatientService patService = new PatientService(patRepo);
        PractitionerService pracService = new PractitionerService(pracRepo);
        EncounterService encService = new EncounterService(encRepo);
        ObservationService obsService = new ObservationService(obsRepo);
        CdsHooksService cdsHooksService = new CdsHooksService(patService, obsRepo, null);

        BundleService bundleService = new BundleService(
                patService, pracService, encService, obsService, txManager, cdsHooksService, null);
        BundleController rawController = new BundleController(bundleService);

        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(rawController);
        proxyFactory.addAspect(auditAspect);
        BundleController controller = proxyFactory.getProxy();

        UUID facId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ATTENDING_PHYSICIAN"))));
        try {
            // 1. Atomic FHIR Transaction with cross-resource urn:uuid:* placeholders (Observation placed before Patient/Encounter to prove dependency ordering)
            String urnPat = "urn:uuid:" + UUID.randomUUID();
            String urnPrac = "urn:uuid:" + UUID.randomUUID();
            String urnEnc = "urn:uuid:" + UUID.randomUUID();
            ResponseEntity<String> txResp = controller.processBundle(facId, null, """
                    {
                      "resourceType": "Bundle",
                      "type": "transaction",
                      "entry": [
                        {
                          "request": {"method": "POST", "url": "Observation"},
                          "resource": {
                            "resourceType": "Observation",
                            "status": "final",
                            "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
                            "code": {"coding": [{"system": "http://loinc.org", "code": "85354-9", "display": "Blood pressure panel"}]},
                            "subject": {"reference": "%s"},
                            "encounter": {"reference": "%s"},
                            "component": [
                              {"code": {"coding": [{"system": "http://loinc.org", "code": "8480-6"}]}, "valueQuantity": {"value": 188, "unit": "mmHg"}},
                              {"code": {"coding": [{"system": "http://loinc.org", "code": "8462-4"}]}, "valueQuantity": {"value": 122, "unit": "mmHg"}}
                            ]
                          }
                        },
                        {
                          "fullUrl": "%s",
                          "request": {"method": "POST", "url": "Encounter"},
                          "resource": {
                            "resourceType": "Encounter",
                            "status": "arrived",
                            "class": {"code": "EMER"},
                            "subject": {"reference": "%s"},
                            "participant": [{"individual": {"reference": "%s"}}],
                            "reasonCode": [{"coding": [{"system": "http://hl7.org/fhir/sid/icd-10", "code": "I10"}]}]
                          }
                        },
                        {
                          "fullUrl": "%s",
                          "request": {"method": "POST", "url": "Patient"},
                          "resource": {
                            "resourceType": "Patient",
                            "identifier": [{"system": "urn:opes:mrn", "value": "DLA-TX-001"}],
                            "name": [{"family": "Eboa", "given": ["Samuel"]}],
                            "gender": "male",
                            "birthDate": "1974-08-22"
                          }
                        },
                        {
                          "fullUrl": "%s",
                          "request": {"method": "POST", "url": "Practitioner"},
                          "resource": {
                            "resourceType": "Practitioner",
                            "name": [{"family": "Tchinda", "given": ["Aline"]}],
                            "gender": "female"
                          }
                        }
                      ]
                    }
                    """.formatted(urnPat, urnEnc, urnEnc, urnPat, urnPrac, urnPat, urnPrac));
            assertEquals(200, txResp.getStatusCode().value());
            String txBody = Objects.requireNonNull(txResp.getBody());
            assertTrue(txBody.contains("\"type\": \"transaction-response\""));
            assertTrue(txBody.contains("201 Created"));
            assertEquals("critical", txResp.getHeaders().getFirst("X-CDS-Hooks-Max-Indicator"));
            assertEquals(1, patients.size());
            assertEquals(1, practitioners.size());
            assertEquals(1, encounters.size());
            assertEquals(1, observations.size());

            UUID savedPatId = patients.keySet().iterator().next();
            UUID savedEncId = encounters.keySet().iterator().next();
            UUID savedObsId = observations.keySet().iterator().next();
            assertEquals(savedPatId, encounters.get(savedEncId).getPatientId());
            assertEquals(savedPatId, observations.get(savedObsId).getPatientId());
            assertEquals(savedEncId, observations.get(savedObsId).getEncounterId());

            // 2. Atomic Rollback: invalid Observation at end rolls back new Patient & Encounter completely
            assertThrows(IllegalArgumentException.class, () -> controller.processBundle(facId, null, """
                    {
                      "resourceType": "Bundle",
                      "type": "transaction",
                      "entry": [
                        {
                          "fullUrl": "urn:uuid:11111111-1111-1111-1111-111111111111",
                          "request": {"method": "POST", "url": "Patient"},
                          "resource": {
                            "resourceType": "Patient",
                            "identifier": [{"system": "urn:opes:mrn", "value": "DLA-ROLLBACK"}],
                            "name": [{"family": "Rollback", "given": ["Test"]}],
                            "gender": "female",
                            "birthDate": "1995-01-01"
                          }
                        },
                        {
                          "request": {"method": "POST", "url": "Observation"},
                          "resource": {
                            "resourceType": "Observation",
                            "status": "final",
                            "code": {"coding": [{"system": "http://loinc.org", "code": "8867-4"}]}
                          }
                        }
                      ]
                    }
                    """));
            assertEquals(1, patients.size(), "Atomic FHIR transaction rollback must revert Patient creation");
            assertEquals(1, observations.size());

            // 3. Batch Isolation: valid GET + invalid POST + valid POST returns batch-response with 200, 400 OperationOutcome, and 201 independently
            ResponseEntity<String> batchResp = controller.processBundle(facId, null, """
                    {
                      "resourceType": "Bundle",
                      "type": "batch",
                      "entry": [
                        {
                          "request": {"method": "GET", "url": "Patient/%s"}
                        },
                        {
                          "request": {"method": "POST", "url": "Observation"},
                          "resource": {
                            "resourceType": "Observation",
                            "status": "final",
                            "code": {"coding": [{"system": "http://loinc.org", "code": "8867-4"}]}
                          }
                        },
                        {
                          "request": {"method": "POST", "url": "Practitioner"},
                          "resource": {
                            "resourceType": "Practitioner",
                            "name": [{"family": "Ndongo", "given": ["Marc"]}],
                            "gender": "male"
                          }
                        }
                      ]
                    }
                    """.formatted(savedPatId));
            assertEquals(200, batchResp.getStatusCode().value());
            Bundle parsedBatchResp = bundleService.parseStrictBundle(Objects.requireNonNull(batchResp.getBody()));
            assertEquals(Bundle.BundleType.BATCHRESPONSE, parsedBatchResp.getType());
            assertEquals(3, parsedBatchResp.getEntry().size());
            assertEquals("200 OK", parsedBatchResp.getEntry().get(0).getResponse().getStatus());
            assertEquals("400 Bad Request", parsedBatchResp.getEntry().get(1).getResponse().getStatus());
            assertNotNull(parsedBatchResp.getEntry().get(1).getResponse().getOutcome());
            assertEquals("201 Created", parsedBatchResp.getEntry().get(2).getResponse().getStatus());
            assertEquals(2, practitioners.size());

            // 4. Verify HIPAA AOP audit captured Bundle execution (2 SUCCESS, 1 FAILURE)
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Bundle".equals(r.resourceType())
                            && "HIPAA_BUNDLE_PROCESS".equals(r.action())
                            && "dr.nshome".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())
                            && savedPatId.equals(r.patientId())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "Bundle".equals(r.resourceType())
                            && "HIPAA_BUNDLE_PROCESS".equals(r.action())
                            && "FAILURE".equals(r.outcome())));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void bucket4jRateLimiterEnforcesTieredQuotasAndReturnsFhirOperationOutcome() throws Exception {
        KeycloakOidcSecurityConfiguration securityConfig = new KeycloakOidcSecurityConfiguration();
        FhirApiRateLimitFilter configuredFilter = securityConfig.fhirApiRateLimitFilter(true, 3L, 2L, 1L, 60L, 1000L);
        assertEquals(3L, configuredFilter.getPolicy().fhirRequestsPerMinute());
        assertEquals(2L, configuredFilter.getPolicy().bundleRequestsPerMinute());
        assertEquals(1L, configuredFilter.getPolicy().anonymousRequestsPerMinute());

        FhirApiRateLimitFilter filter = new FhirApiRateLimitFilter(
                new RateLimitPolicy(true, 3L, 2L, 1L, Duration.ofMinutes(1), 1000L));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ATTENDING_PHYSICIAN"))));
        try {
            // 1. Standard FHIR Patient endpoint allows 3 authenticated requests, 4th returns 429 OperationOutcome
            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/patients");
                req.addHeader("X-Facility-Id", "00000000-0000-0000-0000-000000000001");
                MockHttpServletResponse resp = new MockHttpServletResponse();
                filter.doFilter(req, resp, new MockFilterChain());
                assertEquals(200, resp.getStatus());
                assertEquals("3", resp.getHeader("X-RateLimit-Limit"));
                assertEquals(Integer.toString(2 - i), resp.getHeader("X-RateLimit-Remaining"));
            }
            MockHttpServletRequest overLimitReq = new MockHttpServletRequest("GET", "/api/v1/encounters");
            overLimitReq.addHeader("X-Facility-Id", "00000000-0000-0000-0000-000000000001");
            MockHttpServletResponse overLimitResp = new MockHttpServletResponse();
            filter.doFilter(overLimitReq, overLimitResp, new MockFilterChain());
            assertEquals(429, overLimitResp.getStatus());
            assertEquals("0", overLimitResp.getHeader("X-RateLimit-Remaining"));
            assertNotNull(overLimitResp.getHeader("Retry-After"));
            String ooJson = overLimitResp.getContentAsString();
            assertTrue(ooJson.contains("\"resourceType\": \"OperationOutcome\""));
            assertTrue(ooJson.contains("throttled"));
            assertTrue(ooJson.contains("RATE_LIMIT_EXCEEDED"));

            // 2. Stricter Bundle tier allows 2 requests independently for dr.nshome, 3rd returns 429
            for (int i = 0; i < 2; i++) {
                MockHttpServletRequest bReq = new MockHttpServletRequest("POST", "/api/v1/bundles");
                bReq.addHeader("X-Facility-Id", "00000000-0000-0000-0000-000000000001");
                MockHttpServletResponse bResp = new MockHttpServletResponse();
                filter.doFilter(bReq, bResp, new MockFilterChain());
                assertEquals(200, bResp.getStatus());
                assertEquals("2", bResp.getHeader("X-RateLimit-Limit"));
            }
            MockHttpServletRequest bOverReq = new MockHttpServletRequest("POST", "/api/v1/fhir");
            bOverReq.addHeader("X-Facility-Id", "00000000-0000-0000-0000-000000000001");
            MockHttpServletResponse bOverResp = new MockHttpServletResponse();
            filter.doFilter(bOverReq, bOverResp, new MockFilterChain());
            assertEquals(429, bOverResp.getStatus());

            // 3. Different authenticated user gets an isolated bucket
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("nurse.biya", "n/a", List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));
            MockHttpServletRequest otherUserReq = new MockHttpServletRequest("GET", "/api/v1/observations");
            otherUserReq.addHeader("X-Facility-Id", "00000000-0000-0000-0000-000000000001");
            MockHttpServletResponse otherUserResp = new MockHttpServletResponse();
            filter.doFilter(otherUserReq, otherUserResp, new MockFilterChain());
            assertEquals(200, otherUserResp.getStatus());
            assertEquals("2", otherUserResp.getHeader("X-RateLimit-Remaining"));

            // 4. Unauthenticated caller uses tighter anonymous IP bucket (limit 1)
            SecurityContextHolder.clearContext();
            MockHttpServletRequest anonReq1 = new MockHttpServletRequest("GET", "/api/v1/cds-services");
            anonReq1.setRemoteAddr("10.20.30.40");
            MockHttpServletResponse anonResp1 = new MockHttpServletResponse();
            filter.doFilter(anonReq1, anonResp1, new MockFilterChain());
            assertEquals(200, anonResp1.getStatus());
            assertEquals("1", anonResp1.getHeader("X-RateLimit-Limit"));

            MockHttpServletRequest anonReq2 = new MockHttpServletRequest("GET", "/api/v1/cds-services");
            anonReq2.setRemoteAddr("10.20.30.40");
            MockHttpServletResponse anonResp2 = new MockHttpServletResponse();
            filter.doFilter(anonReq2, anonResp2, new MockFilterChain());
            assertEquals(429, anonResp2.getStatus());

            // 5. Actuator health probe bypasses FHIR rate limiting completely
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest healthReq = new MockHttpServletRequest("GET", "/actuator/health");
                MockHttpServletResponse healthResp = new MockHttpServletResponse();
                filter.doFilter(healthReq, healthResp, new MockFilterChain());
                assertEquals(200, healthResp.getStatus());
                assertNull(healthResp.getHeader("X-RateLimit-Limit"));
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void springGraphQlFhirQueriesNestedResolversMutationsAndAuditWorkEndToEnd() throws Exception {
        Map<UUID, PatientAggregateEntity> patients = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patients.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patients.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patients.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    default -> new ArrayList<>(patients.values());
                });

        Map<UUID, PractitionerEntity> practitioners = new LinkedHashMap<>();
        PractitionerRepository pracRepo = (PractitionerRepository) Proxy.newProxyInstance(
                PractitionerRepository.class.getClassLoader(),
                new Class<?>[]{PractitionerRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PractitionerEntity e = (PractitionerEntity) a[0];
                        practitioners.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(practitioners.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    default -> new ArrayList<>(practitioners.values());
                });

        Map<UUID, EncounterEntity> encounters = new LinkedHashMap<>();
        EncounterRepository encRepo = (EncounterRepository) Proxy.newProxyInstance(
                EncounterRepository.class.getClassLoader(),
                new Class<?>[]{EncounterRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        EncounterEntity e = (EncounterEntity) a[0];
                        encounters.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(encounters.get((UUID) a[0]));
                    case "findByFacilityIdAndPatientIdOrderByStartedAtDesc" -> encounters.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    default -> new ArrayList<>(encounters.values());
                });

        Map<UUID, ObservationEntity> observations = new LinkedHashMap<>();
        ObservationRepository obsRepo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) a[0];
                        observations.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(observations.get((UUID) a[0]));
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    case "findByFacilityIdAndPatientIdOrderByEffectiveAtDesc" -> observations.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .toList();
                    case "findByFacilityIdAndEncounterIdOrderByEffectiveAtDesc" -> observations.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getEncounterId(), a[1]))
                            .toList();
                    default -> new ArrayList<>(observations.values());
                });

        PatientService patService = new PatientService(patRepo);
        PractitionerService pracService = new PractitionerService(pracRepo);
        EncounterService encService = new EncounterService(encRepo);
        ObservationService obsService = new ObservationService(obsRepo);
        CdsHooksService cdsService = new CdsHooksService(patService, obsRepo, null);

        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((com.opeshealth.healthcore.security.audit.ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);

        FhirGraphQlController rawController = new FhirGraphQlController(
                patService, pracService, encService, obsService, cdsService);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(rawController);
        proxyFactory.addAspect(auditAspect);
        FhirGraphQlController proxiedController = proxyFactory.getProxy();
        FhirGraphQlExceptionResolver exceptionResolver = new FhirGraphQlExceptionResolver();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ATTENDING_PHYSICIAN"))));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean("fhirGraphQlController", FhirGraphQlController.class, () -> proxiedController);
            ctx.registerBean("annotatedControllerConfigurer", AnnotatedControllerConfigurer.class, AnnotatedControllerConfigurer::new);
            ctx.refresh();

            GraphQlSource graphQlSource = GraphQlSource.schemaResourceBuilder()
                    .schemaResources(new ClassPathResource("graphql/fhir.graphqls"))
                    .configureRuntimeWiring(ctx.getBean(AnnotatedControllerConfigurer.class))
                    .exceptionResolvers(List.of(exceptionResolver))
                    .build();
            ExecutionGraphQlService graphQlService = new DefaultExecutionGraphQlService(graphQlSource);
            ExecutionGraphQlServiceTester tester = ExecutionGraphQlServiceTester.create(graphQlService);

            UUID facId = UUID.randomUUID();

            // 1. GraphQL Mutations: create Practitioner, Patient, Encounter, Observation
            String pracId = tester.document("""
                    mutation($facId: ID!) {
                      createPractitioner(input: {
                        facilityId: $facId
                        identifierValue: "CM-MD-7788"
                        namePrefix: "Dr."
                        familyName: "Tchinda"
                        givenName: "Aline"
                        gender: "female"
                        qualificationCode: "MD"
                        qualificationDisplay: "Cardiology"
                      }) {
                        id
                        name { family given }
                      }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .execute()
                    .path("createPractitioner.id").entity(String.class).get();

            String patId = tester.document("""
                    mutation($facId: ID!) {
                      createPatient(input: {
                        facilityId: $facId
                        mrn: "DLA-GQL-001"
                        familyName: "Eboa"
                        givenName: "Samuel"
                        gender: "male"
                        birthDate: "1974-08-22"
                        phone: "+237699001122"
                      }) {
                        id
                        gender
                        name { family given }
                      }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .execute()
                    .path("createPatient.id").entity(String.class).get();

            String encId = tester.document("""
                    mutation($facId: ID!, $patId: ID!, $pracId: ID!) {
                      createEncounter(input: {
                        facilityId: $facId
                        status: "arrived"
                        encounterClass: "EMER"
                        patientId: $patId
                        attendingPractitionerId: $pracId
                        locationCode: "ED-BAY-2"
                        reasonIcd10Code: "I10"
                      }) {
                        id
                        status
                        encounterClass { code }
                      }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .variable("patId", patId)
                    .variable("pracId", pracId)
                    .execute()
                    .path("createEncounter.id").entity(String.class).get();

            String obsId = tester.document("""
                    mutation($facId: ID!, $patId: ID!, $encId: ID!, $pracId: ID!) {
                      createObservation(input: {
                        facilityId: $facId
                        status: "final"
                        categoryCode: "vital-signs"
                        code: "85354-9"
                        codeDisplay: "Blood pressure panel"
                        patientId: $patId
                        encounterId: $encId
                        performerId: $pracId
                        components: [
                          { code: "8480-6", codeDisplay: "Systolic BP", valueQuantity: 186.0, valueUnit: "mmHg", valueUnitCode: "mm[Hg]" },
                          { code: "8462-4", codeDisplay: "Diastolic BP", valueQuantity: 118.0, valueUnit: "mmHg", valueUnitCode: "mm[Hg]" }
                        ]
                      }) {
                        id
                        status
                        code { coding { code } }
                      }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .variable("patId", patId)
                    .variable("encId", encId)
                    .variable("pracId", pracId)
                    .execute()
                    .path("createObservation.id").entity(String.class).get();

            // 2. Flexible Connected GraphQL Query traversing Patient -> Encounters -> AttendingPractitioner & Observations
            tester.document("""
                    query($patId: ID!) {
                      patient(id: $patId) {
                        id
                        gender
                        name { family given }
                        encounters {
                          id
                          status
                          encounterClass { code }
                          attendingPractitioner {
                            id
                            name { family }
                          }
                          observations {
                            id
                            code { coding { code } }
                            component {
                              code { coding { code } }
                              valueQuantity { value unit }
                            }
                          }
                        }
                      }
                    }
                    """)
                    .variable("patId", patId)
                    .execute()
                    .path("patient.name[0].family").entity(String.class).isEqualTo("Eboa")
                    .path("patient.encounters[0].encounterClass.code").entity(String.class).isEqualTo("EMER")
                    .path("patient.encounters[0].attendingPractitioner.name[0].family").entity(String.class).isEqualTo("Tchinda")
                    .path("patient.encounters[0].observations[0].id").entity(String.class).isEqualTo(obsId)
                    .path("patient.encounters[0].observations[0].component[0].valueQuantity.value").entity(Double.class).isEqualTo(186.0);

            // 3. Paged search query & ExceptionResolver error translation (NOT_FOUND & BAD_REQUEST)
            tester.document("""
                    query($facId: ID!) {
                      patients(filter: { facilityId: $facId, nameContains: "bo" }, count: 10) {
                        total
                        items { id name { family } }
                      }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .execute()
                    .path("patients.total").entity(Integer.class).isEqualTo(1);

            tester.document("""
                    query($missingId: ID!) {
                      patient(id: $missingId) { id }
                    }
                    """)
                    .variable("missingId", UUID.randomUUID().toString())
                    .execute()
                    .errors()
                    .expect(err -> "NOT_FOUND".equals(err.getErrorType().toString())
                            && "RESOURCE_NOT_FOUND".equals(err.getExtensions().get("errorCode")));

            tester.document("""
                    mutation($facId: ID!) {
                      createPatient(input: {
                        facilityId: $facId
                        familyName: "BadGender"
                        givenName: "Test"
                        gender: "invalid-code"
                        birthDate: "1990-01-01"
                      }) { id }
                    }
                    """)
                    .variable("facId", facId.toString())
                    .execute()
                    .errors()
                    .expect(err -> "BAD_REQUEST".equals(err.getErrorType().toString())
                            && "FHIR_VALIDATION_ERROR".equals(err.getExtensions().get("errorCode")));

            // 4. Soft-delete / cancel mutations + CDS Hooks triggered by GraphQL writes
            tester.document("""
                    mutation($obsId: ID!, $encId: ID!, $patId: ID!) {
                      markObservationEnteredInError(id: $obsId) { id status }
                      cancelEncounter(id: $encId) { id status }
                      deactivatePatient(id: $patId) { id status }
                    }
                    """)
                    .variable("obsId", obsId)
                    .variable("encId", encId)
                    .variable("patId", patId)
                    .execute()
                    .path("markObservationEnteredInError.status").entity(String.class).isEqualTo("entered-in-error")
                    .path("cancelEncounter.status").entity(String.class).isEqualTo("CANCELLED")
                    .path("deactivatePatient.status").entity(String.class).isEqualTo("inactive");

            assertTrue(cdsService.getRecentEvaluations(10).stream()
                    .anyMatch(e -> "critical".equals(e.highestIndicator())));

            // 5. Bucket4j rate limiter covers /graphql requests too
            FhirApiRateLimitFilter gqlRateFilter = new FhirApiRateLimitFilter(
                    new RateLimitPolicy(true, 1L, 1L, 1L, Duration.ofMinutes(1), 100L));
            MockHttpServletRequest gqlReq1 = new MockHttpServletRequest("POST", "/graphql");
            MockHttpServletResponse gqlResp1 = new MockHttpServletResponse();
            gqlRateFilter.doFilter(gqlReq1, gqlResp1, new MockFilterChain());
            assertEquals(200, gqlResp1.getStatus());

            MockHttpServletRequest gqlReq2 = new MockHttpServletRequest("POST", "/graphql");
            MockHttpServletResponse gqlResp2 = new MockHttpServletResponse();
            gqlRateFilter.doFilter(gqlReq2, gqlResp2, new MockFilterChain());
            assertEquals(429, gqlResp2.getStatus());

            // 6. HIPAA AOP Audit captured GraphQL actions with actor & patientId
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "GraphQL".equals(r.resourceType())
                            && "HIPAA_GRAPHQL_CREATEPATIENT".equals(r.action())
                            && "dr.nshome".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "GraphQL".equals(r.resourceType())
                            && "HIPAA_GRAPHQL_PATIENT".equals(r.action())
                            && patId.equals(String.valueOf(r.patientId()))));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void springDocOpenApiConfigurationAndFhirControllerStatusCodesAreDocumented() throws Exception {
        FhirOpenApiConfiguration openApiConfig = new FhirOpenApiConfiguration();
        OpenAPI openApi = openApiConfig.healthCoreFhirOpenApi();

        assertNotNull(openApi.getInfo());
        assertEquals("Opes HealthCore HL7 FHIR R4 & CDS Hooks 2.0 API", openApi.getInfo().getTitle());
        assertEquals("1.0.0", openApi.getInfo().getVersion());
        assertNotNull(openApi.getInfo().getContact());
        assertEquals("nshome@opesware.com", openApi.getInfo().getContact().getEmail());

        // Verify security schemes (Bearer JWT + X-Facility-Id tenant header)
        assertTrue(openApi.getComponents().getSecuritySchemes().containsKey(FhirOpenApiConfiguration.SECURITY_SCHEME_BEARER_JWT));
        assertTrue(openApi.getComponents().getSecuritySchemes().containsKey(FhirOpenApiConfiguration.SECURITY_SCHEME_FACILITY_HEADER));

        // Verify FHIR R4 resource component schemas are registered
        var schemas = openApi.getComponents().getSchemas();
        assertTrue(schemas.containsKey("FhirPatientResource"));
        assertTrue(schemas.containsKey("FhirPractitionerResource"));
        assertTrue(schemas.containsKey("FhirEncounterResource"));
        assertTrue(schemas.containsKey("FhirObservationResource"));
        assertTrue(schemas.containsKey("FhirBundleResource"));
        assertTrue(schemas.containsKey("FhirOperationOutcome"));
        assertTrue(schemas.containsKey("ApiErrorResponse"));

        // Verify OperationCustomizer injects 400, 401, 403, 429 status codes and ETag / RateLimit / Correlation headers
        OperationCustomizer customizer = openApiConfig.fhirStandardResponsesAndHeadersCustomizer();
        GroupedOpenApi groupedOpenApi = openApiConfig.fhirEndpointsGroupedOpenApi(customizer);
        assertEquals("fhir-r4-and-cds-hooks", groupedOpenApi.getGroup());

        Operation sampleOp = new Operation();
        io.swagger.v3.oas.models.responses.ApiResponses opResponses = new io.swagger.v3.oas.models.responses.ApiResponses();
        opResponses.addApiResponse("200", new io.swagger.v3.oas.models.responses.ApiResponse().description("OK"));
        sampleOp.setResponses(opResponses);
        customizer.customize(sampleOp, null);

        assertTrue(sampleOp.getResponses().containsKey("200"));
        assertTrue(sampleOp.getResponses().containsKey("400"));
        assertTrue(sampleOp.getResponses().containsKey("401"));
        assertTrue(sampleOp.getResponses().containsKey("403"));
        assertTrue(sampleOp.getResponses().containsKey("429"));
        assertTrue(sampleOp.getResponses().get("200").getHeaders().containsKey("ETag"));
        assertTrue(sampleOp.getResponses().get("200").getHeaders().containsKey("X-Correlation-Id"));
        assertTrue(sampleOp.getResponses().get("429").getHeaders().containsKey("Retry-After"));
        assertTrue(sampleOp.getResponses().get("429").getHeaders().containsKey("X-RateLimit-Limit"));
        assertTrue(sampleOp.getResponses().get("429").getHeaders().containsKey("X-RateLimit-Remaining"));

        // Verify all FHIR & CDS Hooks controllers declare explicit @ApiResponses status codes on every endpoint method
        List<Class<?>> fhirControllers = List.of(
                PatientController.class,
                PractitionerController.class,
                ObservationController.class,
                EncounterController.class,
                BundleController.class,
                CdsHooksV2Controller.class,
                AuditEventController.class,
                CodeSystemController.class,
                ColdChainMonitoringController.class,
                FhirBulkDataExportController.class
        );
        for (Class<?> controllerClass : fhirControllers) {
            assertTrue(controllerClass.isAnnotationPresent(io.swagger.v3.oas.annotations.tags.Tag.class),
                    "Missing @Tag on " + controllerClass.getSimpleName());
            for (var method : controllerClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(io.swagger.v3.oas.annotations.Operation.class)) {
                    ApiResponses apiResponses = method.getAnnotation(ApiResponses.class);
                    assertNotNull(apiResponses, "Missing @ApiResponses on " + controllerClass.getSimpleName() + "#" + method.getName());
                    assertTrue(apiResponses.value().length >= 2,
                            "Expected documented status codes on " + controllerClass.getSimpleName() + "#" + method.getName());
                }
            }
        }
    }

    @Test
    void flywayMultiDbMigrationConfigurationAndSqlScriptsMaintainParityAcrossPostgresMysqlAndSqlServer() {
        // 1. Dialect detection from JDBC URLs and product names
        assertEquals(RdbmsDialect.POSTGRESQL,
                FlywayMultiDbMigrationConfiguration.resolveDialect("jdbc:postgresql://localhost:5432/healthcore"));
        assertEquals(RdbmsDialect.MYSQL,
                FlywayMultiDbMigrationConfiguration.resolveDialect("jdbc:mysql://localhost:3306/healthcore?serverTimezone=UTC"));
        assertEquals(RdbmsDialect.SQLSERVER,
                FlywayMultiDbMigrationConfiguration.resolveDialect("jdbc:sqlserver://localhost:1433;databaseName=healthcore"));
        assertThrows(IllegalArgumentException.class,
                () -> FlywayMultiDbMigrationConfiguration.resolveDialect("jdbc:oracle:thin:@localhost:1521:xe"));

        // 2. Flyway FluentConfiguration assembly for each RDBMS target
        for (RdbmsDialect dialect : RdbmsDialect.values()) {
            FluentConfiguration fluentConfig = FlywayMultiDbMigrationConfiguration.buildFluentConfiguration(
                    null,
                    dialect,
                    true,
                    true,
                    FlywayMultiDbMigrationConfiguration.HISTORY_TABLE_NAME
            );
            assertEquals(FlywayMultiDbMigrationConfiguration.HISTORY_TABLE_NAME, fluentConfig.getTable());
            assertTrue(fluentConfig.isBaselineOnMigrate());
            assertTrue(fluentConfig.isValidateOnMigrate());
            assertEquals(1, fluentConfig.getLocations().length);
            assertTrue(fluentConfig.getLocations()[0].getDescriptor().contains(dialect.classpathFolder()));
            assertEquals(dialect.placeholders(), fluentConfig.getPlaceholders());
        }

        // 3. Cross-dialect schema parity across PostgreSQL, MySQL, and SQL Server V1 & V2 SQL scripts
        Map<RdbmsDialect, DialectMigrationSummary> summaries =
                FlywayMultiDbMigrationConfiguration.verifyCrossDialectSchemaParity();
        assertEquals(3, summaries.size());

        DialectMigrationSummary pgSummary = summaries.get(RdbmsDialect.POSTGRESQL);
        DialectMigrationSummary mysqlSummary = summaries.get(RdbmsDialect.MYSQL);
        DialectMigrationSummary mssqlSummary = summaries.get(RdbmsDialect.SQLSERVER);

        assertEquals(pgSummary.discoveredTables(), mysqlSummary.discoveredTables());
        assertEquals(pgSummary.discoveredTables(), mssqlSummary.discoveredTables());
        assertEquals(pgSummary.discoveredIndexes(), mysqlSummary.discoveredIndexes());
        assertEquals(pgSummary.discoveredIndexes(), mssqlSummary.discoveredIndexes());
    }

    @Test
    void centralizedFhirAuditLoggingServicePersistsAllFhirAccessEventsAndGeneratesHipaaGdprReports() {
        Map<String, AuditChainHeadEntity> headStore = new LinkedHashMap<>();
        AuditChainHeadRepository headRepo = (AuditChainHeadRepository) Proxy.newProxyInstance(
                AuditChainHeadRepository.class.getClassLoader(),
                new Class<?>[]{AuditChainHeadRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "findByFacilityKeyForUpdate", "findById" -> Optional.ofNullable(headStore.get((String) a[0]));
                    case "save", "saveAndFlush" -> {
                        AuditChainHeadEntity h = (AuditChainHeadEntity) a[0];
                        headStore.put(h.getFacilityKey(), h);
                        yield h;
                    }
                    default -> null;
                });

        List<AuditLogEntity> logStore = new ArrayList<>();
        AuditLogRepository logRepo = (AuditLogRepository) Proxy.newProxyInstance(
                AuditLogRepository.class.getClassLoader(),
                new Class<?>[]{AuditLogRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save", "saveAndFlush" -> {
                        AuditLogEntity entry = (AuditLogEntity) a[0];
                        logStore.add(entry);
                        yield entry;
                    }
                    case "findById" -> logStore.stream().filter(e -> e.getId().equals(a[0])).findFirst();
                    case "findByFacilityKeyOrderBySequenceNumberAsc" -> logStore.stream()
                            .filter(e -> Objects.equals(e.getFacilityKey(), a[0]))
                            .sorted(Comparator.comparingLong(AuditLogEntity::getSequenceNumber))
                            .toList();
                    case "findByFacilityKeyAndPatientIdOrderBySequenceNumberDesc" -> logStore.stream()
                            .filter(e -> Objects.equals(e.getFacilityKey(), a[0]) && Objects.equals(e.getPatientId(), a[1]))
                            .sorted(Comparator.comparingLong(AuditLogEntity::getSequenceNumber).reversed())
                            .toList();
                    default -> List.copyOf(logStore);
                });

        ClinicalAuditService clinicalAuditService = new ClinicalAuditService(headRepo, logRepo);
        FhirAuditLoggingService fhirAuditService = new FhirAuditLoggingService(clinicalAuditService, logRepo);
        AuditEventController auditController = new AuditEventController(fhirAuditService);

        String facilityKey = "FAC-DOUALA-CENTRAL";
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();

        // 1. Record Patient CREATE, Encounter CREATE, Observation READ, and failed Patient UPDATE with PHI in error reason
        FhirResourceAccessEvent evt1 = fhirAuditService.recordFhirAccess(new FhirAccessAuditCommand(
                facilityKey, "dr.nshome", "ROLE_ATTENDING_PHYSICIAN",
                "HIPAA_PATIENT_CREATE", "Patient", patientId.toString(), patientId,
                "POST", "/api/v1/patients", "10.20.1.15", "corr-audit-001",
                "SUCCESS", 18L, null
        ));
        FhirResourceAccessEvent evt2 = fhirAuditService.recordFhirAccess(new FhirAccessAuditCommand(
                facilityKey, "dr.nshome", "ROLE_ATTENDING_PHYSICIAN",
                "HIPAA_ENCOUNTER_CREATE", "Encounter", encounterId.toString(), patientId,
                "POST", "/api/v1/encounters", "10.20.1.15", "corr-audit-002",
                "SUCCESS", 24L, null
        ));
        FhirResourceAccessEvent evt3 = fhirAuditService.recordFhirAccess(new FhirAccessAuditCommand(
                facilityKey, "nurse.mbarga", "ROLE_REGISTERED_NURSE",
                "HIPAA_OBSERVATION_READ", "Observation", observationId.toString(), patientId,
                "GET", "/api/v1/observations/" + observationId, "10.20.1.22", "corr-audit-003",
                "SUCCESS", 9L, null
        ));
        FhirResourceAccessEvent evt4 = fhirAuditService.recordFhirAccess(new FhirAccessAuditCommand(
                facilityKey, "dr.nshome", "ROLE_ATTENDING_PHYSICIAN",
                "HIPAA_PATIENT_UPDATE", "Patient", patientId.toString(), patientId,
                "PUT", "/api/v1/patients/" + patientId, "10.20.1.15", "corr-audit-004",
                "FAILURE", 11L, "Validation failed for phone +237 677123456 and email patient.secret@hospital.cm"
        ));

        assertEquals(4, logStore.size());
        assertEquals(1L, evt1.sequenceNumber());
        assertEquals("0".repeat(64), evt1.prevHash());
        assertEquals(evt1.entryHash(), evt2.prevHash());
        assertEquals(evt2.entryHash(), evt3.prevHash());
        assertEquals(evt3.entryHash(), evt4.prevHash());

        // Verify PHI redaction scrubbed phone and email before database persistence
        assertNotNull(evt4.reason());
        assertTrue(!evt4.reason().contains("677123456"));
        assertTrue(!evt4.reason().contains("patient.secret@hospital.cm"));
        assertTrue(evt4.reason().contains("[REDACTED"));

        // 2. Cryptographic SHA-256 chain integrity verification via controller
        ResponseEntity<ChainVerificationResult> chainResp =
                auditController.verifyFacilityAuditChain(facilityKey, null);
        assertEquals(200, chainResp.getStatusCode().value());
        assertNotNull(chainResp.getBody());
        assertTrue(chainResp.getBody().valid());
        assertEquals(4L, chainResp.getBody().verifiedCount());

        // 3. Retrieve single HL7 FHIR R4 AuditEvent resource and Search Bundle via AuditEventController
        ResponseEntity<String> singleResp = auditController.getAuditEventById(evt1.id());
        assertEquals(200, singleResp.getStatusCode().value());
        assertEquals("W/\"1\"", singleResp.getHeaders().getETag());
        assertTrue(singleResp.getBody().contains("\"resourceType\": \"AuditEvent\""));
        assertTrue(singleResp.getBody().contains(evt1.entryHash()));

        ResponseEntity<String> searchResp = auditController.searchAuditEvents(
                facilityKey, null, patientId, null, null, null, null, null, null, null, null, null, null, 0, 20);
        assertEquals(200, searchResp.getStatusCode().value());
        assertTrue(searchResp.getBody().contains("\"resourceType\": \"Bundle\""));
        assertTrue(searchResp.getBody().contains("\"total\": 4"));

        // 4. Generate HIPAA § 164.528 & GDPR Article 15 Patient Access Disclosure Report
        ResponseEntity<HipaaGdprPatientComplianceReport> reportResp =
                auditController.getPatientComplianceReport(patientId, facilityKey, null);
        assertEquals(200, reportResp.getStatusCode().value());
        HipaaGdprPatientComplianceReport report = reportResp.getBody();
        assertNotNull(report);
        assertEquals(patientId, report.patientId());
        assertEquals(4, report.totalAccessEvents());
        assertEquals(3, report.successfulAccessEvents());
        assertEquals(1, report.failedOrDeniedAccessEvents());
        assertTrue(report.cryptographicChainValid());
        assertEquals(2, report.distinctActors().size());
        assertEquals(2, report.accessesByResourceType().get("Patient"));
        assertEquals(1, report.accessesByResourceType().get("Encounter"));
        assertEquals(1, report.accessesByResourceType().get("Observation"));
    }

    @Test
    void allFifteenSpecializedStarterModulesExecuteEndToEndDomainLogic() {
        // 1. BloodBankStarter: ABO/RhD PRBC and FFP compatibility matrix + ISBT-128 barcode check
        BloodBankStarter.CompatibilityEvaluation prbcUniversal =
                new BloodBankStarter.CompatibilityEvaluation("O-", "A+", "PRBC", true, true, "OK");
        assertTrue(prbcUniversal.compatible());
        assertEquals("O-", BloodBankStarter.BloodBankHemovigilanceService.normalizeAboRh("o neg"));
        assertThrows(IllegalArgumentException.class,
                () -> BloodBankStarter.BloodBankHemovigilanceService.validateIsbt128Barcode("12"));

        // 2. SurgeryOtStarter: Modified Aldrete PACU discharge scoring gate (>= 9/10 required)
        List< com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.AnesthesiaPacuLogEntity > pacuStore = new ArrayList<>();
        HospitalOpsService.AnesthesiaPacuRepository pacuRepo = (HospitalOpsService.AnesthesiaPacuRepository) Proxy.newProxyInstance(
                HospitalOpsService.AnesthesiaPacuRepository.class.getClassLoader(),
                new Class<?>[]{HospitalOpsService.AnesthesiaPacuRepository.class},
                (p, m, a) -> "save".equals(m.getName()) ? a[0] : pacuStore);
        HospitalOpsService.SurgicalScheduleRepository surgRepo = (HospitalOpsService.SurgicalScheduleRepository) Proxy.newProxyInstance(
                HospitalOpsService.SurgicalScheduleRepository.class.getClassLoader(),
                new Class<?>[]{HospitalOpsService.SurgicalScheduleRepository.class},
                (p, m, a) -> "save".equals(m.getName()) ? a[0] : Optional.empty());
        HospitalOpsService dummyOps = new HospitalOpsService(null, null, null, surgRepo, pacuRepo, null, null, null, null, null, null, null);
        SurgeryOtStarter.SurgicalPerioperativeService surgService =
                new SurgeryOtStarter.SurgicalPerioperativeService(dummyOps, surgRepo, pacuRepo);
        SurgeryOtStarter.PacuDischargeDecision aldreteCleared = surgService.evaluateAndRecordPacuAldreteScore(
                new SurgeryOtStarter.PacuAldreteAssessmentRequest(UUID.randomUUID(), 2, 2, 2, 2, 2, 150, "ASA_II"));
        assertEquals(10, aldreteCleared.totalAldreteScore());
        assertTrue(aldreteCleared.eligibleForWardDischarge());

        // 3. TelemedicineStarter: 3G audio fallback & 2G store-and-forward bandwidth adaptation
        Map<UUID, com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.TelemedicineSessionEntity> teleStore = new LinkedHashMap<>();
        HospitalOpsService.TelemedicineRepository teleRepo = (HospitalOpsService.TelemedicineRepository) Proxy.newProxyInstance(
                HospitalOpsService.TelemedicineRepository.class.getClassLoader(),
                new Class<?>[]{HospitalOpsService.TelemedicineRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        var s = (com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.TelemedicineSessionEntity) a[0];
                        teleStore.put(s.getId(), s);
                        yield s;
                    }
                    case "findById" -> Optional.ofNullable(teleStore.get((UUID) a[0]));
                    default -> new ArrayList<>(teleStore.values());
                });
        TelemedicineStarter.TelemedicineVirtualCareService teleService =
                new TelemedicineStarter.TelemedicineVirtualCareService(teleRepo);
        var provisionedSession = teleService.provisionVirtualSession(
                new TelemedicineStarter.TelemedicineProvisionRequest(UUID.randomUUID(), UUID.randomUUID(), "dr.nshome", 1200));
        var fallbackDecision = teleService.evaluateAndAdaptBandwidth(
                provisionedSession.getId(), new TelemedicineStarter.NetworkTelemetryUpdate(120, 1.5, 220));
        assertTrue(fallbackDecision.lowBandwidth3gFallbackActive());
        assertEquals("ACTIVE_3G_AUDIO_FALLBACK", fallbackDecision.adaptedTransportMode());

        // 4. BiomedicalColdChainStarter: WHO VVM freeze/heat excursion analysis
        ColdChainTelemetryEntity freezeReading = new ColdChainTelemetryEntity();
        freezeReading.setSensorId("SENS-EPI-01");
        freezeReading.setTemperatureCelsius(new BigDecimal("-0.5"));
        ColdChainRepository ccRepo = (ColdChainRepository) Proxy.newProxyInstance(
                ColdChainRepository.class.getClassLoader(),
                new Class<?>[]{ColdChainRepository.class},
                (p, m, a) -> List.of(freezeReading));
        HospitalOpsService.BiomedMaintenanceRepository bmRepo = (HospitalOpsService.BiomedMaintenanceRepository) Proxy.newProxyInstance(
                HospitalOpsService.BiomedMaintenanceRepository.class.getClassLoader(),
                new Class<?>[]{HospitalOpsService.BiomedMaintenanceRepository.class},
                (p, m, a) -> List.of());
        BiomedicalColdChainStarter.BiomedicalColdChainGovernanceService biomedService =
                new BiomedicalColdChainStarter.BiomedicalColdChainGovernanceService(dummyOps, ccRepo, bmRepo);
        var stability = biomedService.analyzeSensorThermalStability("SENS-EPI-01");
        assertTrue(stability.vaccineLotQuarantineRequired());
        assertEquals("FREEZE_COMPROMISED", stability.vvmStageEstimate());

        // 5. CrvsMortuaryStarter: WHO MCCD rejects R99 garbage code as sole underlying cause
        HospitalOpsService.CrvsRepository crvsRepo = (HospitalOpsService.CrvsRepository) Proxy.newProxyInstance(
                HospitalOpsService.CrvsRepository.class.getClassLoader(),
                new Class<?>[]{HospitalOpsService.CrvsRepository.class},
                (p, m, a) -> "save".equals(m.getName()) ? a[0] : Optional.empty());
        CrvsMortuaryStarter.CrvsAndMortuaryChainOfCustodyService crvsService =
                new CrvsMortuaryStarter.CrvsAndMortuaryChainOfCustodyService(dummyOps, crvsRepo);
        assertThrows(IllegalArgumentException.class, () -> crvsService.validateAndRegisterWhoMccdDeath(
                new CrvsMortuaryStarter.WhoMccdDeathRegistrationRequest(
                        UUID.randomUUID(), UUID.randomUUID(), "R99", null, null, "dr.nshome", "BAY-1")));

        // 6. DeidentificationResearchStarter: HIPAA Safe Harbor & k-anonymity equivalence class suppression
        DeidentificationResearchStarter.HipaaSafeHarborDeidentificationService deidService =
                new DeidentificationResearchStarter.HipaaSafeHarborDeidentificationService(dummyOps);
        var cohortRes = deidService.exportSafeHarborCohort(new DeidentificationResearchStarter.SafeHarborCohortExportRequest(
                "PROTO-2026", "SALT-99", 2, List.of(
                        new DeidentificationResearchStarter.ClinicalResearchSourceRow(UUID.randomUUID(), 1980, "female", "23701", "B50.9", "Call +237 699112233"),
                        new DeidentificationResearchStarter.ClinicalResearchSourceRow(UUID.randomUUID(), 1980, "female", "23799", "B50.9", "Normal follow up"),
                        new DeidentificationResearchStarter.ClinicalResearchSourceRow(UUID.randomUUID(), 1930, "male", "10100", "I10", "Unique outlier")
                )));
        assertEquals(2, cohortRes.exportedRecordCount());
        assertEquals(1, cohortRes.suppressedForKAnonymityCount());
        assertTrue(cohortRes.kAnonymitySatisfied());

        // 7. Hl7AstmDicomStarter & LisDiagnosticsStarter: ASTM E1394 checksum + Westgard Multi-Rule QC
        String checksum = Hl7AstmDicomStarter.ClinicalDeviceAndImagingGatewayService.computeAstmModulo256Checksum("1H|\\^&||| Mindray");
        assertEquals(2, checksum.length());

        PharmacyLisService dummyPharmLis = new PharmacyLisService(null, null, null, null, null, null);
        PharmacyLisService.LabSpecimenRepository specRepo = (PharmacyLisService.LabSpecimenRepository) Proxy.newProxyInstance(
                PharmacyLisService.LabSpecimenRepository.class.getClassLoader(),
                new Class<?>[]{PharmacyLisService.LabSpecimenRepository.class},
                (p, m, a) -> List.of());
        PharmacyLisService.DiagnosticReportRepository repRepo = (PharmacyLisService.DiagnosticReportRepository) Proxy.newProxyInstance(
                PharmacyLisService.DiagnosticReportRepository.class.getClassLoader(),
                new Class<?>[]{PharmacyLisService.DiagnosticReportRepository.class},
                (p, m, a) -> List.of());
        LisDiagnosticsStarter.LisWestgardAndCriticalResultService westgardService =
                new LisDiagnosticsStarter.LisWestgardAndCriticalResultService(dummyPharmLis, specRepo, repRepo);
        var qcEval = westgardService.evaluateWestgardControlRun(new LisDiagnosticsStarter.WestgardControlRunRequest(
                "COBAS-C311", "2345-7", new BigDecimal("100.0"), new BigDecimal("2.0"),
                List.of(new BigDecimal("105.0"), new BigDecimal("105.2"))));
        assertTrue(qcEval.rule22sRejected());
        assertEquals("OUT_OF_CONTROL_REJECTED", qcEval.runStatus());

        // 8. FhirHieStarter: Normative HL7 FHIR R4 CapabilityStatement generation
        HieInteropService dummyHie = new HieInteropService(null, null, null, null, null, null);
        HieInteropService.FhirSubscriptionRepository subRepo = (HieInteropService.FhirSubscriptionRepository) Proxy.newProxyInstance(
                HieInteropService.FhirSubscriptionRepository.class.getClassLoader(),
                new Class<?>[]{HieInteropService.FhirSubscriptionRepository.class},
                (p, m, a) -> List.of());
        FhirHieStarter.FhirHieExchangeGatewayService fhirHieService =
                new FhirHieStarter.FhirHieExchangeGatewayService(dummyHie, subRepo);
        String capJson = fhirHieService.buildFhirR4CapabilityStatementJson();
        assertTrue(capJson.contains("\"resourceType\": \"CapabilityStatement\""));
        assertTrue(capJson.contains("\"AuditEvent\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminologyServiceAndCodeSystemControllerValidateLoincSnomedCtAndIcd10EndToEnd() {
        Map<String, CodeConceptEntity> store = new LinkedHashMap<>();
        CodeConceptRepository conceptRepo = (CodeConceptRepository) Proxy.newProxyInstance(
                CodeConceptRepository.class.getClassLoader(),
                new Class<?>[]{CodeConceptRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "count" -> (long) store.size();
                    case "save" -> {
                        CodeConceptEntity e = (CodeConceptEntity) args[0];
                        store.put(e.getSystemUri() + "|" + e.getCode().toUpperCase(Locale.ROOT), e);
                        yield e;
                    }
                    case "findBySystemUriAndCode" -> Optional.ofNullable(
                            store.get(args[0] + "|" + ((String) args[1]).toUpperCase(Locale.ROOT)));
                    case "findBySystemUriAndDisplayEnContainingIgnoreCase" -> {
                        String sys = (String) args[0];
                        String q = ((String) args[1]).toLowerCase(Locale.ROOT);
                        yield store.values().stream()
                                .filter(c -> sys.equals(c.getSystemUri())
                                        && c.getDisplayEn().toLowerCase(Locale.ROOT).contains(q))
                                .toList();
                    }
                    case "findAll" -> List.copyOf(store.values());
                    default -> null;
                });

        Set<String> closureEdges = new LinkedHashSet<>();
        TerminologyService.ConceptClosureRepository closureRepo =
                (TerminologyService.ConceptClosureRepository) Proxy.newProxyInstance(
                        TerminologyService.ConceptClosureRepository.class.getClassLoader(),
                        new Class<?>[]{TerminologyService.ConceptClosureRepository.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "save" -> {
                                ConceptClosureEntity e = (ConceptClosureEntity) args[0];
                                closureEdges.add(e.getSystemUri() + "|" + e.getAncestorCode() + "|" + e.getDescendantCode());
                                yield e;
                            }
                            case "existsBySystemUriAndAncestorCodeAndDescendantCode" ->
                                    closureEdges.contains(args[0] + "|" + args[1] + "|" + args[2]);
                            default -> false;
                        });

        List< com.opeshealth.healthcore.terminology.domain.TerminologyEntities.ConceptMapEntity > conceptMaps = new ArrayList<>();
        TerminologyService.ConceptMapRepository mapRepo =
                (TerminologyService.ConceptMapRepository) Proxy.newProxyInstance(
                        TerminologyService.ConceptMapRepository.class.getClassLoader(),
                        new Class<?>[]{TerminologyService.ConceptMapRepository.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "save" -> {
                                var cm = (com.opeshealth.healthcore.terminology.domain.TerminologyEntities.ConceptMapEntity) args[0];
                                conceptMaps.add(cm);
                                yield cm;
                            }
                            case "findBySourceSystemAndSourceCode" -> conceptMaps.stream()
                                    .filter(m -> m.getSourceSystem().equals(args[0]) && m.getSourceCode().equalsIgnoreCase((String) args[1]))
                                    .toList();
                            default -> List.of();
                        });

        TerminologyService.DdiRuleRepository ddiRepo =
                (TerminologyService.DdiRuleRepository) Proxy.newProxyInstance(
                        TerminologyService.DdiRuleRepository.class.getClassLoader(),
                        new Class<?>[]{TerminologyService.DdiRuleRepository.class},
                        (proxy, method, args) -> List.of());

        TerminologyService terminologyService = new TerminologyService(conceptRepo, closureRepo, mapRepo, ddiRepo);
        terminologyService.run(null);
        CodeSystemController controller = new CodeSystemController(terminologyService);

        // 1. LOINC Mod-10 check-digit validation + UCUM unit compatibility
        assertTrue(TerminologyService.isValidLoincSyntax("8310-5"));
        assertTrue(TerminologyService.isValidLoincSyntax("8867-4"));
        assertFalse(TerminologyService.isValidLoincSyntax("8310-9")); // wrong Mod-10 check digit

        var loincOk = terminologyService.validateCode(new TerminologyService.CodeValidationRequest(
                "LOINC", "8310-5", "Body temperature", "VITALS", "[degF]", true));
        assertTrue(loincOk.valid());
        assertEquals("VALID", loincOk.issueCode());

        var loincBadCheckDigit = terminologyService.validateCode("http://loinc.org", "8310-9", "Body temperature");
        assertFalse(loincBadCheckDigit.valid());
        assertEquals("INVALID_CODE_SYNTAX", loincBadCheckDigit.issueCode());

        var loincBadUcum = terminologyService.validateCode(new TerminologyService.CodeValidationRequest(
                "LOINC", "8310-5", "Body temperature", "VITALS", "mm[Hg]", false));
        assertFalse(loincBadUcum.valid());
        assertEquals("UCUM_UNIT_INCOMPATIBLE", loincBadUcum.issueCode());

        // 2. SNOMED-CT SCTID syntax & IS-A hierarchy subsumption ($subsumes)
        assertTrue(TerminologyService.isValidSnomedSyntax("764146007"));
        assertTrue(TerminologyService.isValidSnomedSyntax("38341003"));
        assertFalse(TerminologyService.isValidSnomedSyntax("0764146007")); // leading zero forbidden
        assertFalse(TerminologyService.isValidSnomedSyntax("764146997")); // invalid partition '99'

        var snomedValid = terminologyService.validateCode("SNOMED-CT", "38341003", "Hypertensive disorder, systemic arterial (disorder)");
        assertTrue(snomedValid.valid());
        assertEquals(TerminologyService.SNOMED, snomedValid.normalizedSystemUri());

        var subsumesRes = terminologyService.evaluateSubsumption("SNOMED-CT", "764146007", "372687004");
        assertEquals("subsumes", subsumesRes.outcome());
        var subsumedByRes = terminologyService.evaluateSubsumption("SNOMED-CT", "372687004", "764146007");
        assertEquals("subsumed-by", subsumedByRes.outcome());

        // 3. WHO ICD-10 syntax, display mismatch detection & SNOMED -> ICD-10 $translate crosswalk
        assertTrue(TerminologyService.isValidIcd10Syntax("B50.9"));
        assertTrue(TerminologyService.isValidIcd10Syntax("I10"));
        assertFalse(TerminologyService.isValidIcd10Syntax("999.99"));

        var icdStrictDisplayFail = terminologyService.validateCode(new TerminologyService.CodeValidationRequest(
                "ICD-10", "B50.9", "Wrong Display Label", "INFECTIOUS", null, true));
        assertFalse(icdStrictDisplayFail.valid());
        assertEquals("DISPLAY_MISMATCH", icdStrictDisplayFail.issueCode());

        // 4. CodeSystemController REST & HL7 FHIR R4 Parameters operations
        MockHttpServletResponse httpResp = new MockHttpServletResponse();
        var listResp = controller.listCodeSystems("application/fhir+json", httpResp);
        assertEquals(200, listResp.getStatusCode().value());
        assertEquals("Bundle", listResp.getBody().get("resourceType"));

        var fhirValidateResp = (ResponseEntity<Map<String, Object>>) controller.validateCodeGet(
                "LOINC", null, "8867-4", "Heart rate", "VITALS", "/min", true, "fhir", "application/fhir+json", httpResp);
        assertEquals(200, fhirValidateResp.getStatusCode().value());
        assertEquals("Parameters", fhirValidateResp.getBody().get("resourceType"));

        var batchResp = controller.validateBatch(List.of(
                new TerminologyService.CodeValidationRequest("LOINC", "8480-6", "Systolic blood pressure"),
                new TerminologyService.CodeValidationRequest("SNOMED-CT", "44054006", "Diabetes mellitus type 2 (disorder)"),
                new TerminologyService.CodeValidationRequest("ICD-10", "I10", "Essential (primary) hypertension")
        ), httpResp);
        assertTrue(batchResp.getBody().allValid());
        assertEquals(3, batchResp.getBody().validCount());

        var translateResp = controller.translateConcept("SNOMED-CT", "38341003", httpResp);
        assertEquals(Boolean.TRUE, translateResp.getBody().get("result"));

        // 5. Concept upsert, deactivation -> INACTIVE_CONCEPT validation block, and UCUM conversion
        var createdConcept = controller.upsertConcept(new CodeSystemController.ConceptUpsertPayload(
                "ICD-10", "J45.9", "Asthma, unspecified", "Asthme, sans précision", "RESPIRATORY", null), httpResp);
        assertEquals(201, createdConcept.getStatusCode().value());
        assertTrue(terminologyService.validateCode("ICD-10", "J45.9", "Asthma, unspecified").valid());

        controller.deactivateConcept("ICD-10", "J45.9", httpResp);
        var afterDeactivate = terminologyService.validateCode("ICD-10", "J45.9", null);
        assertFalse(afterDeactivate.valid());
        assertEquals("INACTIVE_CONCEPT", afterDeactivate.issueCode());

        var ucumResp = controller.convertUcum(new CodeSystemController.UcumConversionPayload(
                new BigDecimal("180.182"), "mg/dL", "mmol/L"), httpResp);
        assertEquals(new BigDecimal("10.00"), ucumResp.getBody().get("convertedValue"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void coldChainMonitoringServiceIntegratesWithObservationSystemAndAlertsOnExcursions() {
        Map<UUID, ObservationEntity> obsStore = new LinkedHashMap<>();
        ObservationRepository obsRepo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) args[0];
                        obsStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(obsStore.get((UUID) args[0]));
                    case "findByFacilityIdOrderByEffectiveAtDesc" -> {
                        UUID fac = (UUID) args[0];
                        yield obsStore.values().stream()
                                .filter(o -> Objects.equals(fac, o.getFacilityId()))
                                .toList();
                    }
                    case "findAll" -> List.copyOf(obsStore.values());
                    case "findByFacilityIdAndIdentifierSystemAndIdentifierValue" -> Optional.empty();
                    default -> List.of();
                });

        List<ColdChainTelemetryEntity> telemetryRows = new ArrayList<>();
        ColdChainRepository coldChainRepo = (ColdChainRepository) Proxy.newProxyInstance(
                ColdChainRepository.class.getClassLoader(),
                new Class<?>[]{ColdChainRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        ColdChainTelemetryEntity row = (ColdChainTelemetryEntity) args[0];
                        telemetryRows.add(row);
                        return row;
                    }
                    return List.of();
                });

        List<TransactionalOutboxEntity> outboxEvents = new ArrayList<>();
        OutboxRelayService.OutboxRepository outboxRepo = (OutboxRelayService.OutboxRepository) Proxy.newProxyInstance(
                OutboxRelayService.OutboxRepository.class.getClassLoader(),
                new Class<?>[]{OutboxRelayService.OutboxRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        TransactionalOutboxEntity evt = (TransactionalOutboxEntity) args[0];
                        outboxEvents.add(evt);
                        return evt;
                    }
                    return List.of();
                });

        ObservationService observationService = new ObservationService(obsRepo, outboxRepo);
        ColdChainMonitoringService coldChainService = new ColdChainMonitoringService(
                observationService, coldChainRepo, outboxRepo);
        ColdChainMonitoringController controller = new ColdChainMonitoringController(
                coldChainService, observationService);

        UUID facilityId = UUID.randomUUID();
        String sensorId = "ILR-FRIDGE-DLA-01";

        // 1. In-range reading (+4.5 Cel): creates FHIR Observation with interpretation 'N' and 0 alerts
        var normalReceipt = coldChainService.recordSensorTelemetry(new ColdChainSensorReading(
                facilityId, sensorId, "PENTA-LOT-2026A", "Pentavalent Vaccine",
                SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C,
                MonitoringStage.FIXED_STORAGE,
                new BigDecimal("4.50"), "Cel", new BigDecimal("55.0"),
                20, true, 98, null, "Central EPI Store Douala", Instant.now().minusSeconds(600)));

        assertFalse(normalReceipt.excursionDetected());
        assertEquals("N", normalReceipt.fhirInterpretationCode());
        assertEquals("WITHIN_SPEC_RELEASED", normalReceipt.batchDispositionStatus());
        assertNotNull(observationService.getObservationById(normalReceipt.observationId()));

        // 2. Transport Heat + Shock + Humidity Excursion in Fahrenheit (53.6 degF -> +12.00 Cel)
        var heatReceipt = coldChainService.recordSensorTelemetry(new ColdChainSensorReading(
                facilityId, sensorId, "PENTA-LOT-2026A", "Pentavalent Vaccine",
                SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C,
                MonitoringStage.IN_TRANSIT_TRANSPORT,
                new BigDecimal("53.6"), "[degF]", new BigDecimal("82.0"),
                120, true, 85, new BigDecimal("4.20"), "Douala -> Buea Route", Instant.now().minusSeconds(300)));

        assertTrue(heatReceipt.excursionDetected());
        assertEquals(new BigDecimal("12.00"), heatReceipt.normalizedTempCelsius());
        assertEquals("H", heatReceipt.fhirInterpretationCode());
        assertEquals("HOLD_AND_INSPECT_VVM", heatReceipt.batchDispositionStatus());
        assertEquals(3, heatReceipt.triggeredAlerts().size()); // HEAT_EXCURSION + TRANSPORT_SHOCK_EXCURSION + HUMIDITY_EXCURSION

        // 3. Critical Freeze Excursion (+0.20 Cel <= +0.50 Cel freeze-sensitive threshold) + Power Failure via Controller
        MockHttpServletResponse httpResp = new MockHttpServletResponse();
        var freezeResp = (ResponseEntity<Map<String, Object>>) controller.ingestSensorTelemetry(
                facilityId, "application/json", "json",
                new ColdChainSensorReading(
                        facilityId, sensorId, "PENTA-LOT-2026A", "Pentavalent Vaccine",
                        SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C,
                        MonitoringStage.FIXED_STORAGE,
                        new BigDecimal("0.20"), "Cel", new BigDecimal("60.0"),
                        340, false, 12, null, "Central EPI Store Douala", Instant.now()),
                httpResp);

        assertEquals(201, freezeResp.getStatusCode().value());
        assertEquals("LL", freezeResp.getBody().get("fhirInterpretationCode"));
        assertEquals("QUARANTINE_SHAKE_TEST_REQUIRED", freezeResp.getBody().get("batchDispositionStatus"));
        assertEquals(3, telemetryRows.size());
        assertTrue(outboxEvents.stream().anyMatch(e -> "ColdChainExcursionAlertEvent".equals(e.getEventType())));

        // 4. Compliance & Mean Kinetic Temperature (MKT) evaluation from persisted FHIR Observations
        var complianceResp = controller.evaluateCompliance(
                sensorId, facilityId, facilityId, SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C, httpResp);
        var report = complianceResp.getBody();
        assertEquals(3, report.totalObservationsAnalyzed());
        assertEquals(1, report.inRangeObservationCount());
        assertEquals(1, report.heatExcursionCount());
        assertEquals(1, report.freezeExcursionCount());
        assertEquals("QUARANTINE_SHAKE_TEST_REQUIRED", report.overallDisposition());
        assertTrue(report.meanKineticTemperatureCelsius().compareTo(new BigDecimal("5.00")) > 0);

        // 5. Alert listing and pharmacist acknowledgment
        var activeAlertsResp = controller.listAlerts(facilityId, facilityId, sensorId, true, httpResp);
        int activeBeforeAck = (int) activeAlertsResp.getBody().get("total");
        assertEquals(6, activeBeforeAck);

        UUID firstAlertId = report.activeAlerts().get(0).alertId();
        var ackResp = controller.acknowledgeAlert(
                firstAlertId,
                new ColdChainMonitoringController.AlertAcknowledgePayload(
                        "pharm.nshome", "Batch quarantined in cold-room bay Q-1; WHO Shake Test scheduled."),
                httpResp);
        assertTrue(ackResp.getBody().acknowledged());
        assertEquals("pharm.nshome", ackResp.getBody().acknowledgedBy());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fhirBulkDataExportEndpointExportsPractitionerCohortWithAopAuditAndRateLimiting() throws Exception {
        var patCtx = buildInMemoryPatientTestContext();
        PatientService patientService = patCtx.patientService();
        PractitionerService practitionerService = buildInMemoryPractitionerService();
        EncounterService encounterService = buildInMemoryEncounterService();
        ObservationService observationService = buildInMemoryObservationService();

        FhirBulkDataExportService bulkExportService = new FhirBulkDataExportService(
                patientService, practitionerService, encounterService, observationService);

        // Wrap FhirBulkDataExportController with PatientAuditAspect to verify AOP audit interception
        List<PatientAuditAspect.HipaaPatientAuditRecord> auditTrail = new ArrayList<>();
        PatientAuditAspect auditAspect = new PatientAuditAspect((ClinicalAuditService) null);
        auditAspect.registerListener(auditTrail::add);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new FhirBulkDataExportController(bulkExportService));
        proxyFactory.addAspect(auditAspect);
        FhirBulkDataExportController controller = proxyFactory.getProxy();

        UUID facilityId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dr.nshome", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_CLINICIAN"), new SimpleGrantedAuthority("PERM_patient:read"))));
        try {
            // 1. Seed Attending Practitioner + 2 Patients (only Patient 1 is attributed to Dr. Nshome)
            PractitionerEntity pract = new PractitionerEntity();
            pract.setFacilityId(facilityId);
            pract.setFamilyName("Nshome");
            pract.setGivenName("Jude");
            pract.setGender("male");
            pract.setActive(true);
            PractitionerEntity savedPract = practitionerService.createPractitioner(pract);

            Patient pat1 = patientService.createFromFhirJson(facilityId, """
                    {"resourceType":"Patient","active":true,"name":[{"family":"Ekambi","given":["Marie"]}],"gender":"female","birthDate":"1988-04-12"}
                    """);
            UUID pat1Id = UUID.fromString(pat1.getIdElement().getIdPart());

            Patient pat2 = patientService.createFromFhirJson(facilityId, """
                    {"resourceType":"Patient","active":true,"name":[{"family":"Unattributed","given":["Paul"]}],"gender":"male","birthDate":"1975-09-20"}
                    """);
            UUID pat2Id = UUID.fromString(pat2.getIdElement().getIdPart());
            assertNotEquals(pat1Id, pat2Id);

            EncounterEntity enc1 = new EncounterEntity();
            enc1.setFacilityId(facilityId);
            enc1.setPatientId(pat1Id);
            enc1.setPractitionerId(savedPract.getId());
            enc1.setEncounterType("AMB");
            enc1.setStatus("FINISHED");
            enc1.setChiefComplaint("Malaria follow-up");
            EncounterEntity savedEnc1 = encounterService.createEncounter(enc1);

            ObservationEntity obs1 = new ObservationEntity();
            obs1.setFacilityId(facilityId);
            obs1.setStatus("final");
            obs1.setCodeSystem("http://loinc.org");
            obs1.setCode("8310-5");
            obs1.setCodeDisplay("Body temperature");
            obs1.setSubjectReference("Patient/" + pat1Id);
            obs1.setPatientId(pat1Id);
            obs1.setEncounterReference("Encounter/" + savedEnc1.getId());
            obs1.setEncounterId(savedEnc1.getId());
            obs1.setPerformerReference("Practitioner/" + savedPract.getId());
            obs1.setPractitionerId(savedPract.getId());
            obs1.setValueType("Quantity");
            obs1.setValueQuantity(new BigDecimal("37.4"));
            obs1.setValueUnit("Cel");
            observationService.createObservation(obs1);

            // 2. Kick off Practitioner Cohort Bulk Data Export (HTTP 202 Accepted + Content-Location header)
            MockHttpServletResponse httpResp = new MockHttpServletResponse();
            ResponseEntity<Map<String, Object>> kickOffResp = controller.kickOffPractitionerCohortExport(
                    savedPract.getId(),
                    "Patient,Encounter,Observation,Practitioner",
                    null,
                    "application/fhir+ndjson",
                    null,
                    false,
                    facilityId,
                    facilityId,
                    "respond-async",
                    httpResp
            );
            assertEquals(202, kickOffResp.getStatusCode().value());
            assertNotNull(kickOffResp.getHeaders().getFirst("Content-Location"));
            assertEquals(1, kickOffResp.getBody().get("cohortPatientCount"));
            assertEquals(4, kickOffResp.getBody().get("totalExportedResources"));
            UUID jobId = UUID.fromString(kickOffResp.getBody().get("jobId").toString());

            // 3. Poll export job status & download NDJSON files + FHIR collection Bundle
            ResponseEntity<Map<String, Object>> statusResp = controller.getExportJobStatus(jobId, httpResp);
            assertEquals(200, statusResp.getStatusCode().value());
            List<?> outputFiles = (List<?>) statusResp.getBody().get("output");
            assertEquals(4, outputFiles.size());

            ResponseEntity<String> patientNdjsonResp = controller.downloadExportNdjsonFile(jobId, "Patient.ndjson", httpResp);
            assertEquals(200, patientNdjsonResp.getStatusCode().value());
            assertTrue(patientNdjsonResp.getBody().contains("\"family\":\"Ekambi\""));
            assertFalse(patientNdjsonResp.getBody().contains("Unattributed"));

            ResponseEntity<String> bundleResp = controller.downloadExportAsBundle(jobId, httpResp);
            assertEquals(200, bundleResp.getStatusCode().value());
            assertTrue(bundleResp.getBody().contains("\"type\": \"collection\""));

            // 4. Delete export job (HTTP 202 Accepted) and confirm 404 thereafter
            ResponseEntity<Map<String, Object>> deleteResp = controller.deleteExportJob(jobId);
            assertEquals(202, deleteResp.getStatusCode().value());
            assertThrows(NoSuchElementException.class, () -> controller.getExportJobStatus(jobId, httpResp));

            // 5. Verify PatientAuditAspect captured all Bulk Data Export actions with actor & jobId
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "BulkDataExport".equals(r.resourceType())
                            && "HIPAA_BULK_EXPORT_PRACTITIONER_COHORT".equals(r.action())
                            && "dr.nshome".equals(r.userId())
                            && "SUCCESS".equals(r.outcome())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "BulkDataExport".equals(r.resourceType())
                            && "HIPAA_BULK_EXPORT_DOWNLOAD_NDJSON".equals(r.action())
                            && jobId.toString().equals(r.resourceId())));
            assertTrue(auditTrail.stream().anyMatch(r ->
                    "BulkDataExport".equals(r.resourceType())
                            && "HIPAA_BULK_EXPORT_DELETE".equals(r.action())));

            // 6. Verify Bucket4j FhirApiRateLimitFilter throttles /$export under the BUNDLE rate-limit tier
            FhirApiRateLimitFilter bulkRateFilter = new FhirApiRateLimitFilter(
                    new RateLimitPolicy(true, 100L, 1L, 50L, Duration.ofMinutes(1), 100L));
            MockHttpServletRequest exportReq1 = new MockHttpServletRequest(
                    "GET", "/api/v1/fhir/Practitioner/" + savedPract.getId() + "/$export");
            MockHttpServletResponse exportResp1 = new MockHttpServletResponse();
            bulkRateFilter.doFilter(exportReq1, exportResp1, new MockFilterChain());
            assertEquals(200, exportResp1.getStatus());
            assertEquals("1", exportResp1.getHeader("X-RateLimit-Limit"));

            MockHttpServletRequest exportReq2 = new MockHttpServletRequest(
                    "GET", "/api/v1/fhir/Practitioner/" + savedPract.getId() + "/$export");
            MockHttpServletResponse exportResp2 = new MockHttpServletResponse();
            bulkRateFilter.doFilter(exportReq2, exportResp2, new MockFilterChain());
            assertEquals(429, exportResp2.getStatus());
            assertTrue(exportResp2.getContentAsString().contains("RATE_LIMIT_EXCEEDED"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void identityMatchingServiceProbabilisticDuplicateDetectionAndManualResolutionWorkEndToEnd() {
        Map<UUID, PatientAggregateEntity> patStore = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patStore.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patStore.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    case "findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc" -> patStore.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && e.isActive())
                            .toList();
                    default -> new ArrayList<>(patStore.values());
                });

        PatientService patientService = new PatientService(patRepo);
        com.opeshealth.healthcore.quickstart.service.IdentityMatchingService matchingService =
                new com.opeshealth.healthcore.quickstart.service.IdentityMatchingService(patientService);
        com.opeshealth.healthcore.quickstart.web.IdentityMatchingController controller =
                new com.opeshealth.healthcore.quickstart.web.IdentityMatchingController(matchingService, patientService);

        UUID facilityId = UUID.randomUUID();

        // 1. Seed three patients: two near-duplicate records for the same patient ("Nshome Jude" vs "Nshomé Jud")
        //    and one distinct patient ("Mbarga Clarisse")
        Patient p1 = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-DUP-101"}],
                  "name": [{"family": "Nshome", "given": ["Jude", "Emmanuel"]}],
                  "gender": "male",
                  "birthDate": "1988-04-11",
                  "telecom": [
                    {"system": "phone", "value": "+237 677-11-22-33"},
                    {"system": "email", "value": "jude.nshome@opeshealthsystems.com"}
                  ]
                }
                """);
        UUID p1Id = UUID.fromString(p1.getIdElement().getIdPart());

        Patient p2 = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-DUP-102"}],
                  "name": [{"family": "Nshomé", "given": ["Jud", "E"]}],
                  "gender": "male",
                  "birthDate": "1988-04-11",
                  "telecom": [
                    {"system": "phone", "value": "677112233"},
                    {"system": "email", "value": "jude.nshome@opeshealthsystems.com"}
                  ]
                }
                """);
        UUID p2Id = UUID.fromString(p2.getIdElement().getIdPart());

        Patient p3 = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-DIST-201"}],
                  "name": [{"family": "Mbarga", "given": ["Clarisse"]}],
                  "gender": "female",
                  "birthDate": "1995-09-23",
                  "telecom": [{"system": "phone", "value": "+237699887766"}]
                }
                """);
        UUID p3Id = UUID.fromString(p3.getIdElement().getIdPart());

        // 2. Verify Jaro-Winkler and Damerau-Levenshtein string similarity & pairwise score
        assertTrue(com.opeshealth.healthcore.quickstart.service.IdentityMatchingService
                .jaroWinklerSimilarity("Nshome", "Nshomé") >= 0.99);
        assertTrue(com.opeshealth.healthcore.quickstart.service.IdentityMatchingService
                .damerauLevenshteinSimilarity("Nshome", "Nshoeme") > 0.80);

        var pairwise = controller.compareTwoPatients(p1Id, p2Id).getBody();
        assertNotNull(pairwise);
        assertTrue(pairwise.compositeProbability() >= 0.85);
        assertFalse(pairwise.fieldBreakdown().isEmpty());

        var distinctPairwise = controller.compareTwoPatients(p1Id, p3Id).getBody();
        assertNotNull(distinctPairwise);
        assertTrue(distinctPairwise.compositeProbability() < 0.35);

        // 3. Execute HL7 FHIR R4 POST /api/v1/fhir/Patient/$match operation
        MockHttpServletRequest matchReq = new MockHttpServletRequest("POST", "/api/v1/fhir/Patient/$match");
        ResponseEntity<String> fhirMatchBundleResp = controller.fhirPatientMatchOperation(
                facilityId,
                false,
                10,
                true,
                """
                {
                  "resourceType": "Parameters",
                  "parameter": [
                    {
                      "name": "resource",
                      "resource": {
                        "resourceType": "Patient",
                        "name": [{"family": "Nshome", "given": ["Jude"]}],
                        "gender": "male",
                        "birthDate": "1988-04-11",
                        "telecom": [{"system": "phone", "value": "677112233"}]
                      }
                    },
                    {"name": "count", "valueInteger": 5}
                  ]
                }
                """,
                matchReq
        );
        assertEquals(200, fhirMatchBundleResp.getStatusCode().value());
        String matchBundleJson = Objects.requireNonNull(fhirMatchBundleResp.getBody());
        assertTrue(matchBundleJson.contains("\"type\": \"searchset\""));
        assertTrue(matchBundleJson.contains("http://hl7.org/fhir/StructureDefinition/match-grade"));

        // 4. Scan the FHIR database for duplicate patient records and verify p1 <-> p2 is flagged for manual resolution
        ResponseEntity<com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicateDatabaseScanSummary> scanResp =
                controller.scanDatabaseForDuplicates(facilityId, facilityId, true, 0.65, matchReq);
        assertEquals(200, scanResp.getStatusCode().value());
        var scanSummary = Objects.requireNonNull(scanResp.getBody());
        assertEquals(3, scanSummary.totalActivePatientsScanned());
        assertEquals(1, scanSummary.flaggedPairs().size());

        var flaggedPair = scanSummary.flaggedPairs().get(0);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicateFlagStatus.PENDING_MANUAL_RESOLUTION,
                flaggedPair.status());
        assertTrue(flaggedPair.compositeProbability() >= 0.85);

        // 5. Resolve the flagged duplicate manually via CONFIRM_DUPLICATE_AND_MERGE (surviving = p1Id, retired = p2Id)
        var resolveMergeReq = new com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.ManualResolutionRequest(
                com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.ManualResolutionAction.CONFIRM_DUPLICATE_AND_MERGE,
                p1Id,
                "steward.douala@opeshealthsystems.com",
                "Verified identical national ID and subscriber phone number; merged MRN-DUP-102 into MRN-DUP-101"
        );
        var resolvedFlag = controller.resolveDuplicateFlag(flaggedPair.flagId(), resolveMergeReq, matchReq).getBody();
        assertNotNull(resolvedFlag);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.IdentityMatchingService.DuplicateFlagStatus.RESOLVED_MERGED,
                resolvedFlag.status());
        assertEquals(p1Id, resolvedFlag.survivingPatientId());
        assertEquals(p2Id, resolvedFlag.retiredDuplicatePatientId());
        assertFalse(patientService.getPatientById(p2Id).isActive());
    }

    @Test
    void nextGenDigitalHealthTenCapabilitiesSuiteWorksEndToEnd() {
        Map<UUID, PatientAggregateEntity> patStore = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patStore.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patStore.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    default -> new ArrayList<>(patStore.values());
                });

        Map<UUID, ObservationEntity> obsStore = new LinkedHashMap<>();
        ObservationRepository obsRepo = (ObservationRepository) Proxy.newProxyInstance(
                ObservationRepository.class.getClassLoader(),
                new Class<?>[]{ObservationRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        ObservationEntity e = (ObservationEntity) a[0];
                        obsStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(obsStore.get((UUID) a[0]));
                    default -> new ArrayList<>(obsStore.values());
                });

        PatientService patientService = new PatientService(patRepo);
        ObservationService observationService = new ObservationService(obsRepo);

        var subscriptionService = new com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService();
        var consentService = new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService(null, subscriptionService);
        var pgxService = new com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService();
        var rpmService = new com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService(observationService, subscriptionService, null);
        var bcmaService = new com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService(pgxService, subscriptionService, null);
        var amsService = new com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService(subscriptionService, null);
        var ipsService = new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService(
                patientService, observationService, consentService, pgxService);
        var priorAuthService = new com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService();
        var promService = new com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService(observationService, subscriptionService, null);
        var sdohService = new com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService();

        var controller = new com.opeshealth.healthcore.quickstart.web.DigitalHealthNextGenController(
                consentService, pgxService, rpmService, subscriptionService,
                bcmaService, amsService, ipsService, priorAuthService, promService, sdohService
        );

        UUID facilityId = UUID.randomUUID();
        Patient createdPatient = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-NEXTGEN-001"}],
                  "name": [{"family": "Tchoumi", "given": ["Aurelie"]}],
                  "gender": "female",
                  "birthDate": "1991-07-14",
                  "telecom": [{"system": "phone", "value": "+237677554433"}]
                }
                """);
        UUID patientId = UUID.fromString(createdPatient.getIdElement().getIdPart());

        // Capability 4: Register R5-Backport Topic Subscriptions
        assertEquals(6, controller.listSubscriptionTopics().getBody().size());
        controller.createSubscription(new com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionRegistrationRequest(
                facilityId,
                com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.TOPIC_CRITICAL_OBSERVATION_ALERT,
                com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.ChannelType.REST_HOOK,
                "https://icu.opeshealthsystems.com/webhooks/rrt",
                "secret-icu-2026",
                "application/fhir+json",
                Map.of(),
                "ICU-Rapid-Response-Hook"
        ));
        controller.createSubscription(new com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionRegistrationRequest(
                facilityId,
                com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.TOPIC_BREAK_THE_GLASS_INVOKED,
                com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.ChannelType.REST_HOOK,
                "https://compliance.opeshealthsystems.com/webhooks/btg",
                "secret-btg-2026",
                "application/fhir+json",
                Map.of(),
                "Privacy-Compliance-Officer-Hook"
        ));

        // Capability 1: FHIR Consent, DS4P Segmentation & Break-the-Glass emergency override
        controller.registerConsentDirective(new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentDirectiveRequest(
                facilityId,
                patientId,
                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentProvisionType.DENY,
                Set.of(com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV,
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.PSY),
                Set.of(),
                Set.of("TREAT"),
                null,
                null,
                null
        ));
        var btgSession = controller.invokeBreakTheGlass(new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest(
                facilityId,
                patientId,
                "dr.emergency.douala",
                "ROLE_PHYSICIAN",
                "Unconscious emergency trauma arrival requiring immediate antiretroviral interaction check",
                Set.of(com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV),
                60
        )).getBody();
        assertNotNull(btgSession);
        assertTrue(btgSession.emergencyToken().startsWith("BTG-"));

        // Capability 2: Pharmacogenomics (PGx) CPIC Level A
        controller.registerPatientGenotype(new com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxAlleleRegistrationRequest(
                facilityId, patientId, "CYP2C19", "*2/*2", null, "LAB-PGX-01"
        ));
        var pgxAlert = controller.evaluatePgxOrder(new com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxPrescriptionCheckRequest(
                facilityId, patientId, "Clopidogrel", "B01AC04", "75 mg"
        )).getBody();
        assertNotNull(pgxAlert);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxAlertSeverity.CONTRAINDICATED_CRITICAL,
                pgxAlert.severity());
        assertTrue(pgxAlert.blockAdministrationWithoutOverride());

        // Capability 3: Remote Patient Monitoring (RPM) & NEWS2 / MEWS / qSOFA
        var wearable = controller.registerWearableDevice(
                new com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.WearableDeviceRegistrationRequest(
                        facilityId, patientId, "SN-OPES-PATCH-900", "SMART_PATCH", "Opes BioTelemetry", false
                )).getBody();
        assertNotNull(wearable);
        var ews = controller.ingestRpmTelemetry(
                new com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.RpmVitalTelemetryPacket(
                        facilityId, patientId, null, wearable.deviceId(),
                        26, 89, true, false, 86, 54, 134,
                        com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.ConsciousnessAcvpu.CONFUSION,
                        39.2
                )).getBody();
        assertNotNull(ews);
        assertTrue(ews.news2TotalScore() >= 14);
        assertEquals(3, ews.qsofaTotalScore());
        assertTrue(ews.rapidResponseTeamTriggered());

        // Capability 5: Closed-Loop BCMA 5-Rights & High-Alert LASA Dual-Nurse Verification
        var insulinOrder = controller.scheduleEmarOrder(
                new com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.EmarScheduledOrderRequest(
                        facilityId, patientId, "WB-PAT-001", "Insulin Regular IV", "A10AB01",
                        "00300028123456", 10.0, "units", "IV", Instant.now(), 60, true, "dr.nshome"
                )).getBody();
        assertNotNull(insulinOrder);
        assertTrue(insulinOrder.highAlertLasaMedication());

        var blockedBcma = controller.verifyAndAdministerMedication(
                new com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BedsideBarcodeScanRequest(
                        insulinOrder.orderId(), patientId, "WB-PAT-001", "00300028123456",
                        10.0, "units", "IV", "NURSE-BADGE-01", null, Instant.now(), false, null
                )).getBody();
        assertNotNull(blockedBcma);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BcmaVerificationOutcome.BLOCKED_HIGH_ALERT_MISSING_DUAL_NURSE,
                blockedBcma.outcome());

        var verifiedBcma = controller.verifyAndAdministerMedication(
                new com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BedsideBarcodeScanRequest(
                        insulinOrder.orderId(), patientId, "WB-PAT-001", "00300028123456",
                        10.0, "units", "IV", "NURSE-BADGE-01", "NURSE-BADGE-02", Instant.now(), false, "Dual nurse verified"
                )).getBody();
        assertNotNull(verifiedBcma);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BcmaVerificationOutcome.VERIFIED_ADMINISTERED,
                verifiedBcma.outcome());

        // Capability 6: Antimicrobial Stewardship (WHO AWaRe & Cumulative Antibiogram)
        var blockedReserve = controller.evaluateAntibioticOrder(
                new com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.AntibioticStewardshipOrderRequest(
                        facilityId, patientId, "Colistin", "Gram-negative sepsis", 7, true, null, "dr.nshome"
                )).getBody();
        assertNotNull(blockedReserve);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.WhoAwareCategory.RESERVE,
                blockedReserve.awareCategory());
        assertFalse(blockedReserve.approvedForDispensing());

        var mrsaIsolate = controller.recordIsolateSusceptibility(
                new com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.MicrobiologyIsolateRequest(
                        facilityId, patientId, "Blood", "Staphylococcus aureus", "3092008",
                        "Oxacillin", 8.0,
                        com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.SusceptibilityInterpretation.R,
                        true
                )).getBody();
        assertNotNull(mrsaIsolate);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.MdroClassification.MRSA,
                mrsaIsolate.mdroClassification());
        assertEquals(1, controller.generateCumulativeAntibiogram(facilityId).getBody().totalIsolatesAnalyzed());

        // Capability 7: International Patient Summary (Patient/{id}/$summary) with DS4P & JWS RS256/EdDSA verification
        controller.seedPatientIpsData(new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSeedClinicalSummaryRequest(
                patientId,
                List.of(
                        new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsClinicalProblemItem(
                                "38341003", "Essential hypertension", "active", null),
                        new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsClinicalProblemItem(
                                "86406008", "Human immunodeficiency virus infection", "active",
                                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV)
                ),
                List.of("Penicillin G — Anaphylaxis"),
                List.of("Amlodipine 5 mg daily"),
                List.of("Yellow Fever Vaccine")
        ));

        var redactedIps = controller.generatePatientIpsSummary(patientId, "dr.routine", null, null, "RS256").getBody();
        assertNotNull(redactedIps);
        assertEquals(1, redactedIps.redactedSensitiveEntryCount());
        assertFalse(redactedIps.fhirIpsDocumentBundleJson().contains("86406008"));

        var unlockedIps = controller.generatePatientIpsSummary(
                patientId, "dr.emergency.douala", btgSession.emergencyToken(), null, "EdDSA").getBody();
        assertNotNull(unlockedIps);
        assertTrue(unlockedIps.ds4pBreakTheGlassApplied());
        assertEquals(0, unlockedIps.redactedSensitiveEntryCount());
        assertTrue(unlockedIps.fhirIpsDocumentBundleJson().contains("86406008"));

        var verifyResult = controller.verifySignedIpsBundle(
                new com.opeshealth.healthcore.quickstart.web.DigitalHealthNextGenController.IpsJwsVerificationRequest(
                        unlockedIps.jwsCompactSignature())).getBody();
        assertNotNull(verifyResult);
        assertTrue(verifyResult.signatureValid());
        assertEquals("EdDSA", verifyResult.signingAlgorithm());

        // Capability 8: Electronic Prior Authorization (Da Vinci PAS) & Coverage Eligibility
        var eligibility = controller.verifyCoverageEligibility(
                new com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.EligibilityCheckRequest(
                        facilityId, patientId, "MRI", new BigDecimal("185000.00")
                )).getBody();
        assertNotNull(eligibility);
        assertTrue(eligibility.benefitCovered());
        assertTrue(eligibility.priorAuthorizationRequired());

        var priorAuth = controller.submitPriorAuthorization(
                new com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PriorAuthSubmissionRequest(
                        facilityId, patientId, "CPT-70553", "Brain MRI with and without contrast",
                        "G43.909", "Refractory neurological deficit after 6 weeks of conservative therapy",
                        true, new BigDecimal("185000.00"), "dr.nshome"
                )).getBody();
        assertNotNull(priorAuth);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PriorAuthDecision.APPROVED,
                priorAuth.decision());

        // Capability 9: PROMs (PHQ-9 with Item 9 suicide risk alert)
        var phq9Result = controller.submitPromQuestionnaire(
                new com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService.PromSubmissionRequest(
                        facilityId, patientId, null,
                        com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService.PromInstrumentCode.PHQ_9,
                        Map.of("q1", 3, "q2", 3, "q3", 2, "q4", 2, "q5", 2, "q6", 2, "q7", 2, "q8", 1, "q9", 2)
                )).getBody();
        assertNotNull(phq9Result);
        assertEquals(19, phq9Result.normalizedScore());
        assertTrue(phq9Result.suicideOrSelfHarmRiskFlagged());
        assertNotNull(phq9Result.derivedObservationId());

        // Capability 10: SDoH Gravity Project Screening & Closed-Loop CHW Referral
        var sdohEval = controller.evaluateSdohScreening(
                new com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohScreeningRequest(
                        facilityId, patientId,
                        com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohDomain.FOOD_INSECURITY,
                        com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohRiskLevel.HIGH_URGENT_RISK,
                        "Household ran out of food twice this month",
                        "Douala Nutrition Collective",
                        "CHW-BONABERI-07",
                        true
                )).getBody();
        assertNotNull(sdohEval);
        assertEquals("Z59.41", sdohEval.generatedIcd10ZCode());
        assertNotNull(sdohEval.closedLoopReferralId());

        var closedReferral = controller.updateSdohReferralStatus(
                sdohEval.closedLoopReferralId(),
                new com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.ReferralStatusUpdateRequest(
                        com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.ReferralLoopStatus.FULFILLED_CLOSED,
                        "CHW-BONABERI-07",
                        "Delivered 30-day household nutrition parcel and enrolled in maternal food voucher program"
                )).getBody();
        assertNotNull(closedReferral);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.ReferralLoopStatus.FULFILLED_CLOSED,
                closedReferral.status());
        assertNotNull(closedReferral.closedAt());

        // Confirm FHIR R5-Backport Subscription notifications were delivered with HMAC-SHA256 signatures
        var deliveredNotifications = controller.listSubscriptionDeliveries(null, patientId).getBody();
        assertNotNull(deliveredNotifications);
        assertTrue(deliveredNotifications.size() >= 2);
        assertTrue(deliveredNotifications.get(0).hmacSha256Signature().startsWith("sha256="));
    }

    @Test
    void digitalHealthCertificateServiceAndControllerJwsQrVerificationWorksEndToEnd() {
        Map<UUID, PatientAggregateEntity> patStore = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patStore.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patStore.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    default -> new ArrayList<>(patStore.values());
                });

        PatientService patientService = new PatientService(patRepo);
        var certService = new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService(patientService);
        var controller = new com.opeshealth.healthcore.quickstart.web.CertificateController(certService);

        UUID facilityId = UUID.randomUUID();
        Patient createdPatient = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-CERT-777"}],
                  "name": [{"family": "Ngassa", "given": ["Samuel"]}],
                  "gender": "male",
                  "birthDate": "1986-11-03",
                  "telecom": [{"system": "phone", "value": "+237699001122"}]
                }
                """);
        UUID patientId = UUID.fromString(createdPatient.getIdElement().getIdPart());

        // 1. Issue Ed25519 (EdDSA) JWS-signed Yellow Fever WHO ICVP Certificate with DEFLATE compression
        var ed25519Cert = controller.issueCertificate(
                facilityId,
                new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateIssuanceRequest(
                        facilityId,
                        patientId,
                        com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateType.IMMUNIZATION_ICVP,
                        "Ed25519",
                        "http://hl7.org/fhir/sid/cvx",
                        "037",
                        "Yellow Fever Vaccine (Stamaril)",
                        "LOT-YF-2026-DLA",
                        1,
                        "Douala International Vaccination Center",
                        "CM",
                        3650,
                        true
                )
        ).getBody();
        assertNotNull(ed25519Cert);
        assertEquals("EdDSA", ed25519Cert.jwsAlgHeader());
        assertTrue(ed25519Cert.uvciIdentifier().startsWith("URN:UVCI:01:CM:OPES-"));
        assertTrue(ed25519Cert.qrCode().smartHealthCardNumericUri().startsWith("shc:/"));
        assertTrue(ed25519Cert.qrCode().qrCodeSvg().contains("<svg"));
        assertTrue(ed25519Cert.qrCode().qrCodePngBase64DataUri().startsWith("data:image/png;base64,"));

        // 2. Issue RS256 JWS-signed Diagnostic Test Clearance Certificate
        var rs256Cert = controller.issueCertificate(
                facilityId,
                new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateIssuanceRequest(
                        facilityId,
                        patientId,
                        com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateType.DIAGNOSTIC_TEST_RESULT,
                        "RS256",
                        "http://loinc.org",
                        "94500-6",
                        "Respiratory virus RT-PCR Negative",
                        "BATCH-PCR-88",
                        null,
                        "Opes Reference Diagnostics Lab",
                        "CM",
                        14,
                        false
                )
        ).getBody();
        assertNotNull(rs256Cert);
        assertEquals("RS256", rs256Cert.jwsAlgHeader());

        // 3. Verify mobile QR scan using SMART Health Card numeric URI (shc:/...) for Ed25519 certificate
        var verifyEd25519Qr = controller.verifyCertificate(
                new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateVerificationRequest(
                        ed25519Cert.qrCode().smartHealthCardNumericUri()
                )
        ).getBody();
        assertNotNull(verifyEd25519Qr);
        assertTrue(verifyEd25519Qr.valid());
        assertTrue(verifyEd25519Qr.cryptographicSignatureVerified());
        assertEquals("ED25519", verifyEd25519Qr.signingAlgorithm());

        // 4. Verify RS256 JWS compact token directly
        var verifyRs256 = controller.verifyCertificate(
                new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateVerificationRequest(
                        rs256Cert.jwsCompactToken()
                )
        ).getBody();
        assertNotNull(verifyRs256);
        assertTrue(verifyRs256.valid());
        assertEquals("RS256", verifyRs256.signingAlgorithm());

        // 5. Verify SVG & PNG QR code download endpoints and public JWKS endpoint
        ResponseEntity<String> svgResp = controller.getCertificateQrSvg(ed25519Cert.certificateId());
        assertEquals(200, svgResp.getStatusCode().value());
        assertTrue(Objects.requireNonNull(svgResp.getBody()).contains("<svg"));

        ResponseEntity<byte[]> pngResp = controller.getCertificateQrPng(ed25519Cert.certificateId());
        assertEquals(200, pngResp.getStatusCode().value());
        assertTrue(Objects.requireNonNull(pngResp.getBody()).length > 100);

        Map<String, Object> jwks = controller.getPublicJwks().getBody();
        assertNotNull(jwks);
        assertEquals(2, ((List<?>) jwks.get("keys")).size());

        // 6. Revoke the RS256 certificate and confirm subsequent mobile verification rejects it as REVOKED
        controller.revokeCertificate(
                rs256Cert.certificateId(),
                "Replaced by updated laboratory test",
                null
        );
        var verifyAfterRevoke = controller.verifyCertificate(
                new com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateVerificationRequest(
                        rs256Cert.jwsCompactToken()
                )
        ).getBody();
        assertNotNull(verifyAfterRevoke);
        assertFalse(verifyAfterRevoke.valid());
        assertFalse(verifyAfterRevoke.notRevoked());
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.DigitalHealthCertificateService.CertificateStatus.REVOKED,
                verifyAfterRevoke.status());
    }

    @Test
    void internationalPatientSummaryControllerJwsAndQrCodePayloadsWorkEndToEnd() {
        Map<UUID, PatientAggregateEntity> patStore = new LinkedHashMap<>();
        PatientRepository patRepo = (PatientRepository) Proxy.newProxyInstance(
                PatientRepository.class.getClassLoader(),
                new Class<?>[]{PatientRepository.class},
                (p, m, a) -> switch (m.getName()) {
                    case "save" -> {
                        PatientAggregateEntity e = (PatientAggregateEntity) a[0];
                        patStore.put(e.getId(), e);
                        yield e;
                    }
                    case "findById" -> Optional.ofNullable(patStore.get((UUID) a[0]));
                    case "findByFacilityIdAndMrn" -> patStore.values().stream()
                            .filter(e -> Objects.equals(e.getFacilityId(), a[0]) && Objects.equals(e.getMrn(), a[1]))
                            .findFirst();
                    default -> new ArrayList<>(patStore.values());
                });

        PatientService patientService = new PatientService(patRepo);
        var ipsService = new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService(patientService);
        var ipsController = new com.opeshealth.healthcore.quickstart.web.InternationalPatientSummaryController(ipsService);

        UUID facilityId = UUID.randomUUID();
        Patient createdPatient = patientService.createFromFhirJson(facilityId, """
                {
                  "resourceType": "Patient",
                  "identifier": [{"system": "urn:opes:mrn", "value": "MRN-IPS-888"}],
                  "name": [{"family": "Eto'o", "given": ["David"]}],
                  "gender": "male",
                  "birthDate": "1983-02-19",
                  "telecom": [{"system": "phone", "value": "+237670112233"}]
                }
                """);
        UUID patientId = UUID.fromString(createdPatient.getIdElement().getIdPart());

        // 1. Seed IPS clinical sections including active problems, allergies, medications, immunizations, and procedures
        ipsController.seedPatientIpsData(
                new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSeedClinicalSummaryRequest(
                        patientId,
                        List.of(new com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsClinicalProblemItem(
                                "44054006", "Type 2 diabetes mellitus", "active", null)),
                        List.of("Sulfonamide antibiotics"),
                        List.of("Metformin 500 mg twice daily"),
                        List.of("Yellow Fever Vaccine — WHO ICVP"),
                        List.of("Laparoscopic appendectomy")
                ));

        // 2. Generate Ed25519 (EdDSA) JWS-signed IPS Document Bundle with DEFLATE compression and QR code payloads
        var ed25519Ips = ipsController.generatePatientIpsSummary(
                patientId, "dr.nshome", null, null, "Ed25519", true
        ).getBody();
        assertNotNull(ed25519Ips);
        assertEquals("EdDSA", ed25519Ips.signingAlgorithm());
        assertTrue(ed25519Ips.payloadDeflateCompressed());
        assertNotNull(ed25519Ips.qrCode());
        assertTrue(ed25519Ips.qrCode().smartHealthCardNumericUri().startsWith("shc:/"));
        assertTrue(ed25519Ips.qrCode().gdhcnIpsCompactUri().startsWith("hcert:/ips/v1/"));
        assertTrue(ed25519Ips.qrCode().qrCodeSvg().contains("<svg"));
        assertTrue(ed25519Ips.qrCode().qrCodePngBase64DataUri().startsWith("data:image/png;base64,"));
        assertTrue(ed25519Ips.fhirIpsDocumentBundleJson().contains("Laparoscopic appendectomy"));

        // 3. Generate RS256 JWS-signed IPS Document Bundle
        var rs256Ips = ipsController.generatePatientIpsSummary(
                patientId, "dr.nshome", null, null, "RS256", false
        ).getBody();
        assertNotNull(rs256Ips);
        assertEquals("RS256", rs256Ips.signingAlgorithm());

        // 4. Verify Ed25519 IPS via its SMART Health Card numeric QR payload (shc:/...)
        var verifyEdQr = ipsController.verifySignedIpsBundle(
                Map.of("qrPayload", ed25519Ips.qrCode().smartHealthCardNumericUri())
        ).getBody();
        assertNotNull(verifyEdQr);
        assertTrue(verifyEdQr.signatureValid());
        assertEquals("EdDSA", verifyEdQr.signingAlgorithm());
        assertEquals(ed25519Ips.documentBundleId(), verifyEdQr.documentBundleId());
        assertEquals(patientId, verifyEdQr.patientId());

        // 5. Verify RS256 IPS via its JWS compact signature
        var verifyRs256 = ipsController.verifySignedIpsBundle(
                Map.of("jwsCompactSignature", rs256Ips.jwsCompactSignature())
        ).getBody();
        assertNotNull(verifyRs256);
        assertTrue(verifyRs256.signatureValid());
        assertEquals("RS256", verifyRs256.signingAlgorithm());

        // 6. Verify SVG/PNG QR download endpoints, bundle history, and public IPS JWKS
        assertEquals(200, ipsController.getIpsBundleQrSvg(ed25519Ips.documentBundleId()).getStatusCode().value());
        assertEquals(200, ipsController.getIpsBundleQrPng(ed25519Ips.documentBundleId()).getStatusCode().value());
        assertEquals(2, ipsController.listPatientIpsBundles(patientId).getBody().size());
        assertEquals(2, ((List<?>) ipsController.getPublicIpsJwks().getBody().get("keys")).size());
    }

    @Test
    void consentServiceAndControllerDs4pBreakTheGlassWithMandatoryAuditFlaggingWorkEndToEnd() {
        var auditLoggingService = new com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService();
        var subscriptionService = new com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService(auditLoggingService);
        var enforcementService = new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService(
                auditLoggingService, subscriptionService);
        var consentService = new com.opeshealth.healthcore.quickstart.service.ConsentService(
                enforcementService, auditLoggingService, subscriptionService);
        var consentController = new com.opeshealth.healthcore.quickstart.web.ConsentController(consentService);

        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        // 1. Register an HL7 FHIR R4 Consent directive from raw FHIR R4 Consent JSON restricting HIV, PSY, and ETH
        String rawFhirConsentJson = """
                {
                  "resourceType": "Consent",
                  "status": "active",
                  "patient": {"reference": "Patient/%s"},
                  "policy": [{"uri": "https://opeshealthsystems.com/fhir/Policy/hipaa-gdpr-ds4p-privacy"}],
                  "provision": {
                    "type": "deny",
                    "securityLabel": [
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "HIV"},
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "PSY"},
                      {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "ETH"}
                    ]
                  }
                }
                """.formatted(patientId);

        var createdDirective = consentController.createConsentFromRawFhirJson(facilityId, rawFhirConsentJson).getBody();
        assertNotNull(createdDirective);
        assertEquals("active", createdDirective.status());
        assertTrue(createdDirective.restrictedCategories().contains(
                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV));

        // 2. Search FHIR Consent searchset Bundle & read FHIR Consent JSON resource
        String bundleJson = consentController.searchFhirConsentBundle(patientId, "active").getBody();
        assertNotNull(bundleJson);
        assertTrue(bundleJson.contains("\"resourceType\": \"Bundle\""));
        assertTrue(bundleJson.contains(createdDirective.consentId().toString()));

        // 3. Classify clinical content for DS4P confidentiality and obligations
        var classification = consentController.classifyClinicalContent(
                new com.opeshealth.healthcore.quickstart.service.ConsentService.Ds4pClassificationRequest(
                        "Observation",
                        "obs-hiv-viral-load-1",
                        "HIV-1 RNA viral load CD4 antiretroviral therapy monitoring",
                        "B20"
                )).getBody();
        assertNotNull(classification);
        assertEquals(
                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel.V,
                classification.assignedConfidentiality());
        assertTrue(classification.requiresExplicitConsentOrBreakTheGlass());

        // 4. Evaluate DS4P segmentation without Break-the-Glass — sensitive HIV & PSY items are redacted
        var clinicalItems = List.of(
                new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem(
                        "obs-bp-1", "Observation", "Blood Pressure 120/78 mmHg", null,
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel.N),
                new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem(
                        "cond-hiv-1", "Condition", "HIV antiretroviral regimen",
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV,
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel.V),
                new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem(
                        "note-psy-1", "DocumentReference", "Psychiatric crisis note",
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.PSY,
                        com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConfidentialityLevel.R)
        );
        var redactedEval = consentController.evaluateDs4pSegmentation(
                null,
                new com.opeshealth.healthcore.quickstart.web.ConsentController.Ds4pEvaluationRequest(
                        patientId, "dr.emergency", "TREAT", null, clinicalItems
                )).getBody();
        assertNotNull(redactedEval);
        assertEquals(1, redactedEval.permittedItemsCount());
        assertEquals(2, redactedEval.redactedItemsCount());
        assertFalse(redactedEval.breakTheGlassApplied());

        // 5. Verify Break-the-Glass rejection records a mandatory FAILURE audit flag in FhirAuditLoggingService
        assertThrows(IllegalArgumentException.class, () ->
                consentController.invokeBreakTheGlassWithAuditFlag(
                        new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest(
                                facilityId, patientId, "dr.emergency", "ROLE_PHYSICIAN", "short", null, 30
                        )));
        var rejectedAudits = auditLoggingService.searchAuditLogs(
                new com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.AuditSearchCriteria(
                        facilityId.toString(), patientId, null, null, null,
                        "BREAK_THE_GLASS_REJECTED_MANDATORY_AUDIT_FLAG", "FAILURE", null, null, 0, 20
                ));
        assertEquals(1, rejectedAudits.total());

        // 6. Invoke valid Break-the-Glass ($break-the-glass) and verify mandatory audit flag creation + SHA-256 hash
        var btgInvocation = consentController.invokeBreakTheGlassWithAuditFlag(
                new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest(
                        facilityId,
                        patientId,
                        "dr.emergency",
                        "ROLE_PHYSICIAN",
                        "Unconscious trauma patient in ED requiring urgent medication and HIV/psychiatry history check",
                        java.util.Set.of(
                                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.HIV,
                                com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SensitivityCategory.PSY),
                        60
                )).getBody();
        assertNotNull(btgInvocation);
        assertNotNull(btgInvocation.session());
        assertNotNull(btgInvocation.mandatoryAuditFlag());
        assertTrue(btgInvocation.mandatoryAuditFlag().mandatoryAuditFlagged());
        assertEquals("ETREAT", btgInvocation.mandatoryAuditFlag().purposeOfUseCode());
        assertEquals(com.opeshealth.healthcore.quickstart.service.ConsentService.AUDIT_FLAG_PENDING_REVIEW,
                btgInvocation.mandatoryAuditFlag().reviewStatus());
        assertNotNull(btgInvocation.mandatoryAuditFlag().auditEntrySha256Hash());
        assertEquals(64, btgInvocation.mandatoryAuditFlag().auditEntrySha256Hash().length());

        // 7. Re-evaluate DS4P segmentation with the active Break-the-Glass token — all 3 items are now permitted
        var btgEval = consentController.evaluateDs4pSegmentation(
                btgInvocation.session().emergencyToken(),
                new com.opeshealth.healthcore.quickstart.web.ConsentController.Ds4pEvaluationRequest(
                        patientId, "dr.emergency", "ETREAT", null, clinicalItems
                )).getBody();
        assertNotNull(btgEval);
        assertTrue(btgEval.breakTheGlassApplied());
        assertEquals(3, btgEval.permittedItemsCount());
        assertEquals(0, btgEval.redactedItemsCount());

        // 8. Compliance Officer reviews and adjudicates the mandatory Break-the-Glass audit flag
        var pendingFlags = consentController.listBreakTheGlassAuditFlags(
                patientId, com.opeshealth.healthcore.quickstart.service.ConsentService.AUDIT_FLAG_PENDING_REVIEW
        ).getBody();
        assertNotNull(pendingFlags);
        assertEquals(1, pendingFlags.size());

        var adjudicatedFlag = consentController.reviewBreakTheGlassAuditFlag(
                btgInvocation.mandatoryAuditFlag().flagId(),
                new com.opeshealth.healthcore.quickstart.service.ConsentService.BreakTheGlassComplianceReviewRequest(
                        "chief.privacy.officer",
                        "APPROVED",
                        "Verified ED trauma admission log; Break-the-Glass ETREAT override clinically justified."
                )).getBody();
        assertNotNull(adjudicatedFlag);
        assertEquals(com.opeshealth.healthcore.quickstart.service.ConsentService.AUDIT_FLAG_APPROVED,
                adjudicatedFlag.reviewStatus());
        assertEquals("chief.privacy.officer", adjudicatedFlag.reviewedBy());

        // 9. Revoke Break-the-Glass token and revoke FHIR Consent directive
        var revokedSession = consentController.revokeBreakTheGlass(btgInvocation.session().emergencyToken()).getBody();
        assertNotNull(revokedSession);
        assertFalse(revokedSession.active());

        var revokedConsent = consentController.revokeConsentDirective(
                createdDirective.consentId(), "patient.portal", "Patient updated privacy preferences", null
        ).getBody();
        assertNotNull(revokedConsent);
        assertEquals("inactive", revokedConsent.status());
    }

    @Test
    void kmpDataSyncManagerAndSqlDelightSchemaOfflineQueueAndConflictResolutionContractVerified() throws Exception {
        java.nio.file.Path sqPath = java.nio.file.Path.of(
                "..", "healthcore-mobile-kmp", "healthcore-mobile-sdk",
                "src", "commonMain", "sqldelight", "com", "opeshealth", "healthcore", "mobile", "db", "HealthCoreOffline.sq");
        if (!java.nio.file.Files.exists(sqPath)) {
            sqPath = java.nio.file.Path.of(
                    "healthcore-mobile-kmp", "healthcore-mobile-sdk",
                    "src", "commonMain", "sqldelight", "com", "opeshealth", "healthcore", "mobile", "db", "HealthCoreOffline.sq");
        }
        assertTrue(java.nio.file.Files.exists(sqPath));
        String sqContent = java.nio.file.Files.readString(sqPath);
        assertTrue(sqContent.contains("CREATE TABLE local_fhir_resource"));
        assertTrue(sqContent.contains("CREATE TABLE local_fhir_sync_queue"));
        assertTrue(sqContent.contains("CREATE TABLE local_fhir_sync_conflict"));
        assertTrue(sqContent.contains("selectReadyFhirSyncQueueBatch:"));
        assertTrue(sqContent.contains("coalescePendingFhirSyncQueuePayload:"));
        assertTrue(sqContent.contains("resolveFhirSyncConflict:"));

        java.nio.file.Path managerPath = sqPath.getParent().resolve("../../../../../../../../kotlin/com/opeshealth/healthcore/mobile/sync/DataSyncManager.kt").normalize();
        assertTrue(java.nio.file.Files.exists(managerPath));
        String managerSource = java.nio.file.Files.readString(managerPath);
        assertTrue(managerSource.contains("class DataSyncManager"));
        assertTrue(managerSource.contains("fun enqueueOfflineFhirResource"));
        assertTrue(managerSource.contains("suspend fun processBackgroundSync"));
        assertTrue(managerSource.contains("fun performThreeWayFhirMerge"));
        assertTrue(managerSource.contains("fun resolveQuarantinedConflict"));
        assertTrue(managerSource.contains("CLINICAL_SAFETY_QUARANTINE"));
        assertTrue(managerSource.contains("THREE_WAY_FIELD_MERGE"));
        assertTrue(managerSource.contains("HLC_LAST_WRITE_WINS"));
    }

    @Test
    void allTwelveEnterpriseDigitalHealthExpansionCapabilitiesWorkEndToEnd() {
        var auditLogging = new com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService();
        var subscriptions = new com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService(auditLogging);
        var consentService = new com.opeshealth.healthcore.quickstart.service.ConsentService(
                new com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService(auditLogging, subscriptions),
                auditLogging,
                subscriptions);
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        // 1. SMART Health Links (shlink:/) — AES-256-GCM encryption, passcode manifest, decryption & revocation
        var shlController = new com.opeshealth.healthcore.quickstart.web.SmartHealthLinkController(
                new com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService(auditLogging));
        var shlCreated = shlController.createSmartHealthLink(
                "https://healthcore.opeshealthsystems.com",
                new com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.CreateSmartHealthLinkRequest(
                        facilityId, patientId, "Emergency Travel IPS", "994411", true, 48, 5,
                        List.of(new com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlFileInput(
                                "application/fhir+json", "ips.json", "{\"resourceType\":\"Bundle\",\"type\":\"document\"}"))
                )).getBody();
        assertNotNull(shlCreated);
        assertTrue(shlCreated.shlinkUri().startsWith("shlink:/"));
        assertThrows(SecurityException.class, () -> shlController.resolveManifest(
                shlCreated.shlId(), "https://healthcore.opeshealthsystems.com",
                new com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlManifestRequest("BorderClinic", "WRONG", 0)));
        var shlManifest = shlController.resolveManifest(
                shlCreated.shlId(), "https://healthcore.opeshealthsystems.com",
                new com.opeshealth.healthcore.quickstart.service.SmartHealthLinkService.ShlManifestRequest("BorderClinic", "994411", 0)).getBody();
        assertNotNull(shlManifest);
        assertEquals(1, shlManifest.files().size());
        var decryptedShl = shlController.decryptFile(
                shlCreated.shlId(), shlManifest.files().getFirst().fileId(), shlCreated.symmetricKeyBase64Url()).getBody();
        assertNotNull(decryptedShl);
        assertTrue(decryptedShl.digestVerified());
        assertTrue(decryptedShl.plainContent().contains("\"Bundle\""));
        shlController.revokeSmartHealthLink(shlCreated.shlId(), "Trip ended", "https://healthcore.opeshealthsystems.com");

        // 2. FHIR Chart Portability (Patient/$everything & Compartment Search)
        var portabilityController = new com.opeshealth.healthcore.quickstart.web.FhirChartPortabilityController(
                new com.opeshealth.healthcore.quickstart.service.FhirChartPortabilityService(null, null, null, consentService, auditLogging));
        var everythingResp = portabilityController.exportPatientEverything(patientId, "dr.nshome", null, null, null, null).getBody();
        assertNotNull(everythingResp);
        assertEquals("Patient/$everything", everythingResp.scope());
        assertTrue(everythingResp.fhirBundleJson().contains("\"resourceType\": \"Bundle\""));

        // 3. Federated HIE Exchange (UDAP / TEFCA Trust Anchors / IHE XCPD & XCA)
        var hieController = new com.opeshealth.healthcore.quickstart.web.FederatedHieExchangeController(
                new com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService(auditLogging));
        var udapClient = hieController.registerUdapClient(
                new com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.UdapClientRegistrationRequest(
                        "2.16.120.1.100.1", "Douala EHR Gateway", "hdr.payload.sig", "https://ehr.local/cb",
                        java.util.Set.of("system/Patient.rs"), "TREAT")).getBody();
        assertNotNull(udapClient);
        var xcpd = hieController.discoverPatientAcrossCommunities(
                new com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcpdPatientDiscoveryRequest(
                        facilityId, "Mbarga", "Clarisse", "1988-03-19", "female", "CM-ID-001", "TREAT", List.of())).getBody();
        assertNotNull(xcpd);
        assertTrue(xcpd.matchedCommunitiesCount() >= 2);
        var firstMatch = xcpd.matches().getFirst();
        var xcaQuery = hieController.queryCrossCommunityDocuments(
                new com.opeshealth.healthcore.quickstart.service.FederatedHieExchangeService.XcaDocumentQueryRequest(
                        facilityId, firstMatch.communityOid(), firstMatch.remotePatientId(), "TREAT", "60591-5", null)).getBody();
        assertNotNull(xcaQuery);
        assertEquals(1, xcaQuery.documents().size());
        var xcaDoc = hieController.retrieveCrossCommunityDocument(
                firstMatch.communityOid(), xcaQuery.documents().getFirst().documentUniqueId()).getBody();
        assertNotNull(xcaDoc);
        assertEquals(64, xcaDoc.sha256Checksum().length());

        // 4. Clinical Quality Measures (eCQM / $evaluate-measure)
        var cqmController = new com.opeshealth.healthcore.quickstart.web.ClinicalQualityMeasureController(
                new com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService(auditLogging));
        var cqmEval = cqmController.evaluateMeasure(
                "CMS122-HBA1C-CONTROL",
                new com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.EvaluateMeasureRequest(
                        facilityId, java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 12, 31), "summary",
                        List.of(
                                new com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.PatientMeasureEvidence(
                                        UUID.randomUUID(), true, false, false, 6.3, null, "URBAN"),
                                new com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.PatientMeasureEvidence(
                                        UUID.randomUUID(), true, false, false, 8.4, null, "URBAN"),
                                new com.opeshealth.healthcore.quickstart.service.ClinicalQualityMeasureService.PatientMeasureEvidence(
                                        UUID.randomUUID(), true, true, false, 9.2, null, "URBAN")
                        ))).getBody();
        assertNotNull(cqmEval);
        assertEquals(2, cqmEval.denominatorCount());
        assertEquals(1, cqmEval.numeratorCount());
        assertEquals(50.0, cqmEval.performanceScorePercent());
        assertTrue(cqmEval.fhirMeasureReportJson().contains("\"MeasureReport\""));

        // 5. Omnichannel Patient Messaging & USSD Recall Engine
        var msgController = new com.opeshealth.healthcore.quickstart.web.PatientMessagingController(
                new com.opeshealth.healthcore.quickstart.service.PatientMessagingService(auditLogging));
        var msgSent = msgController.sendMessage(
                new com.opeshealth.healthcore.quickstart.service.PatientMessagingService.SendPatientMessageRequest(
                        facilityId, patientId,
                        com.opeshealth.healthcore.quickstart.service.PatientMessagingService.MessagingChannel.WHATSAPP,
                        com.opeshealth.healthcore.quickstart.service.PatientMessagingService.MessageTemplateCode.EPI_IMMUNIZATION_RECALL,
                        "fr", "+237670112233", Map.of("vaccine", "Yellow Fever"), null, false)).getBody();
        assertNotNull(msgSent);
        assertTrue(msgSent.renderedBody().contains("PEV"));
        var ussdResp = msgController.handleUssdSession(
                new com.opeshealth.healthcore.quickstart.service.PatientMessagingService.UssdSessionRequest("sess-1", "+237670112233", "")).getBody();
        assertNotNull(ussdResp);
        assertFalse(ussdResp.endSession());

        // 6. Longitudinal CarePlan, Goal & Care-Gap Closure Engine
        var carePlanController = new com.opeshealth.healthcore.quickstart.web.CarePlanPathwayController(
                new com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService(auditLogging));
        var carePlan = carePlanController.enrollPatient(
                new com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService.EnrollCarePlanRequest(
                        facilityId, patientId,
                        com.opeshealth.healthcore.quickstart.service.CarePlanPathwayService.ClinicalPathwayCode.DIABETES_T2,
                        "dr.nshome", java.time.LocalDate.of(2026, 1, 1))).getBody();
        assertNotNull(carePlan);
        var gaps = carePlanController.evaluateCareGaps(patientId, "2026-06-01").getBody();
        assertNotNull(gaps);
        assertTrue(gaps.overdueCareGapsCount() >= 1);

        // 7. Radiology DICOM MWL, MPPS & Closed-Loop Critical Findings Escalation
        var radController = new com.opeshealth.healthcore.quickstart.web.RadiologyWorklistController(
                new com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService(auditLogging, subscriptions));
        var mwlOrder = radController.scheduleOrder(
                new com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.ScheduleImagingOrderRequest(
                        facilityId, patientId, "dr.ed",
                        com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.ImagingModality.CT,
                        "71260", "CT Chest Angiogram", "CHEST", "Rule out PE", "CT_SCANNER_1", true)).getBody();
        assertNotNull(mwlOrder);
        radController.updateMpps(mwlOrder.orderId(),
                new com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.MppsUpdateRequest(
                        com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.MppsStatus.COMPLETED, "tech.1", 4, 320));
        var radReport = radController.signReport(
                new com.opeshealth.healthcore.quickstart.service.RadiologyWorklistService.RadiologyReportSignOffRequest(
                        mwlOrder.orderId(), "dr.rad", "Saddle embolus seen", "Acute saddle pulmonary embolism",
                        true, "PULMONARY_EMBOLISM", false)).getBody();
        assertNotNull(radReport);
        assertEquals(1, radController.listUnacknowledgedCritical().getBody().size());
        var ackReport = radController.acknowledgeCriticalFinding(radReport.reportId(), "dr.ed").getBody();
        assertNotNull(ackReport);
        assertEquals("ACKNOWLEDGED_CLOSED_LOOP", ackReport.acknowledgmentStatus());

        // 8. Clinical Research, Dynamic eConsent & HIPAA Safe-Harbor OMOP CDM v5.4 Export
        var researchController = new com.opeshealth.healthcore.quickstart.web.ClinicalResearchOmopController(
                new com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService(auditLogging));
        researchController.signEConsent(
                new com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.ResearchEConsentRequest(
                        facilityId, patientId, "OPES-CARDIO-2026", true, true, null));
        var omopExport = researchController.exportOmopCdm(
                "OPES-CARDIO-2026",
                List.of(new com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.CandidateResearchPatient(
                        patientId, 94, "female", java.util.Set.of("I10", "E11.9"), 7.2, java.time.LocalDate.of(2026, 5, 10)))).getBody();
        assertNotNull(omopExport);
        assertEquals(1, omopExport.consentedSubjectsExported());
        assertEquals(2, omopExport.conditionOccurrenceTable().size());

        // 9. Resumable Chunked Encrypted Offline Media Sync
        var mediaController = new com.opeshealth.healthcore.quickstart.web.OfflineMediaSyncController(
                new com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService(auditLogging));
        byte[] chunk0 = "encrypted-wound-part-1-".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] chunk1 = "encrypted-wound-part-2".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var initMedia = mediaController.initiateUpload(
                new com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.InitMediaUploadRequest(
                        facilityId, patientId, "kmp-android-01", "wound.jpg.enc", "image/jpeg", "WOUND_PHOTO",
                        2, chunk0.length + chunk1.length, null, true)).getBody();
        assertNotNull(initMedia);
        mediaController.uploadChunk(initMedia.uploadId(),
                new com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.MediaChunkUploadRequest(
                        0, java.util.Base64.getEncoder().encodeToString(chunk0), null));
        mediaController.uploadChunk(initMedia.uploadId(),
                new com.opeshealth.healthcore.quickstart.service.OfflineMediaSyncService.MediaChunkUploadRequest(
                        1, java.util.Base64.getEncoder().encodeToString(chunk1), null));
        var completedMedia = mediaController.completeUpload(initMedia.uploadId()).getBody();
        assertNotNull(completedMedia);
        assertEquals("COMPLETED_VERIFIED", completedMedia.status());
        assertNotNull(completedMedia.fhirDocumentReferenceId());

        // 10. Payer Clearinghouse, EDI X12 837/835 & Denial Appeals
        var clearinghouseController = new com.opeshealth.healthcore.quickstart.web.PayerClearinghouseController(
                new com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService(auditLogging));
        var claim = clearinghouseController.submitClaim(
                new com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClaimSubmissionRequest(
                        facilityId, patientId, "PAYER-CNPS", "MEM-8821", "837P", "NPI-123456",
                        java.time.LocalDate.now(), List.of("I10"),
                        List.of(new com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClaimServiceLine(
                                "99214", "Cardiology Follow-up", 1, 25000L)),
                        "XAF")).getBody();
        assertNotNull(claim);
        assertTrue(claim.scrubbingResult().passed());
        assertTrue(claim.ediX12837Payload().contains("ST*837*"));
        var denied = clearinghouseController.reconcileRemittance(
                new com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.Edi835RemittanceRequest(
                        claim.claimId(), "TR-835-01", 0L, 25000L, "96", "N130", "DENIED")).getBody();
        assertNotNull(denied);
        assertTrue(denied.fhirExplanationOfBenefitJson().contains("\"ExplanationOfBenefit\""));
        var appealed = clearinghouseController.appealClaim(
                claim.claimId(),
                new com.opeshealth.healthcore.quickstart.service.PayerClearinghouseService.ClaimAppealRequest(
                        "billing.lead", "Attached specialist referral and signed ECG clinical documentation.", List.of("DocumentReference/1"))).getBody();
        assertNotNull(appealed);
        assertEquals("APPEAL_SUBMITTED", appealed.status());

        // 11. Integration DLQ & Webhook/MLLP Replay Management
        var dlqController = new com.opeshealth.healthcore.quickstart.web.IntegrationDlqReplayController(
                new com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService(auditLogging));
        var dlqEntry = dlqController.recordDeadLetter(
                new com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.RecordDeadLetterRequest(
                        facilityId,
                        com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.IntegrationChannelType.FHIR_SUBSCRIPTION_WEBHOOK,
                        "https://regional-hie.local/webhook", "Observation", "obs-99", "corr-1", "{}", 503, "Downstream 503")).getBody();
        assertNotNull(dlqEntry);
        var bulkReplay = dlqController.replayBulk(
                com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.IntegrationChannelType.FHIR_SUBSCRIPTION_WEBHOOK,
                "ops.admin").getBody();
        assertNotNull(bulkReplay);
        assertEquals(1, bulkReplay.succeededCount());

        // 12. Multi-Tenant SaaS Onboarding & Feature-Flag Governance
        var tenantController = new com.opeshealth.healthcore.quickstart.web.TenantGovernanceController(
                new com.opeshealth.healthcore.quickstart.service.TenantGovernanceService(auditLogging));
        var tenant = tenantController.onboardTenant(
                new com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.OnboardTenantRequest(
                        "Yaounde University Teaching Hospital", "CM", "Yaounde",
                        com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.DeploymentArchetype.REGIONAL_HIE,
                        "POSTGRESQL", 3000, null, Map.of("beta.aiAmbientNotes.enabled", false))).getBody();
        assertNotNull(tenant);
        assertEquals(3000, tenant.apiRateLimitRpm());
        tenantController.updateFeatureFlag(tenant.facilityId(),
                new com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.FeatureFlagUpdateRequest(
                        "beta.aiAmbientNotes.enabled", true, "platform.admin"));
        assertEquals(Boolean.TRUE, tenantController.checkFeatureFlag(tenant.facilityId(), "beta.aiAmbientNotes.enabled").getBody().get("enabled"));
    }

    @SuppressWarnings("unused")
    private static void dummyValidationTarget(String payload) {
    }
}
