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
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.PhiRedactionConverter;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import com.opeshealth.healthcore.templates.service.HospitalTemplateCatalog;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.workflow.catalog.ReusableWorkflowCatalog;
import com.opeshealth.healthcore.workflow.domain.WorkflowEntities.*;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService.*;
import com.opeshealth.healthcore.workflow.spi.WorkflowSpi.*;
import org.junit.jupiter.api.Test;

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
}
