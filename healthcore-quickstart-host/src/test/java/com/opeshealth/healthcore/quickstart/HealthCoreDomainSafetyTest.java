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
import com.opeshealth.healthcore.quickstart.service.ObservationService;
import com.opeshealth.healthcore.quickstart.service.PractitionerService;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlController;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlExceptionResolver;
import com.opeshealth.healthcore.quickstart.web.BundleController;
import com.opeshealth.healthcore.quickstart.web.CdsHooksV2Controller;
import com.opeshealth.healthcore.quickstart.web.EncounterController;
import com.opeshealth.healthcore.quickstart.web.HealthCoreApiExceptionAdvice;
import com.opeshealth.healthcore.quickstart.web.ObservationController;
import com.opeshealth.healthcore.quickstart.web.PatientAuditAspect;
import com.opeshealth.healthcore.quickstart.web.PatientController;
import com.opeshealth.healthcore.quickstart.web.PractitionerController;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.*;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.junit.jupiter.api.Test;
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

    @SuppressWarnings("unused")
    private static void dummyValidationTarget(String payload) {
    }
}
