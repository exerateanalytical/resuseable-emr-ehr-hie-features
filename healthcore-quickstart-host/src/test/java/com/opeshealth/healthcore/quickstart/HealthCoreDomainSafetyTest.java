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

import com.opeshealth.healthcore.emr.specialty.SpecialtyPackSpi;
import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService;
import com.opeshealth.healthcore.hie.service.FhirOutboxSubscriptionRelay;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
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
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.*;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
}
