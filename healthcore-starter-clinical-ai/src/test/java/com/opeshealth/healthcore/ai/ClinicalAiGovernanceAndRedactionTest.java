/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical AI Governance & PHI Redaction Tests (AI-001/AI-002/AUDIT-001)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditPersistenceException;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyRepository;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClinicalAiGovernanceAndRedactionTest {

    @Test
    void draftAndTenantKillSwitchSurviveAcrossFreshPodInstances() {
        Map<UUID, ClinicalAiDraftEntity> draftDb = new ConcurrentHashMap<>();
        Map<UUID, TenantAiPolicyEntity> policyDb = new ConcurrentHashMap<>();

        ClinicalAiDraftRepository draftRepo = mock(ClinicalAiDraftRepository.class);
        when(draftRepo.save(any())).thenAnswer(inv -> {
            ClinicalAiDraftEntity e = inv.getArgument(0);
            draftDb.put(e.getDraftId(), e);
            return e;
        });
        when(draftRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(draftDb.get(inv.getArgument(0))));

        TenantAiPolicyRepository policyRepo = mock(TenantAiPolicyRepository.class);
        when(policyRepo.save(any())).thenAnswer(inv -> {
            TenantAiPolicyEntity e = inv.getArgument(0);
            policyDb.put(e.getFacilityId(), e);
            return e;
        });
        when(policyRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(policyDb.get(inv.getArgument(0))));

        ObjectMapper mapper = new ObjectMapper();
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        // Pod A registers a draft and flips the tenant AI kill-switch
        ClinicalAiGovernanceService podA = new ClinicalAiGovernanceService(null, new ClinicalAuditLogger(), draftRepo, policyRepo, mapper);
        ClinicalAiDraftRecord draft = podA.registerDraft(
                facilityId, patientId, AiCapabilityType.CHART_AND_IPS_SUMMARIZER,
                "gemini-2.5-pro", "abc123", Map.of("EMAIL", 1),
                "{\"summary\":\"Hypertension stable\"}", new TokenUsage(120, 45, 165), 250L, "dr-ngono");
        assertEquals(DraftAttestationStatus.DRAFT_PENDING_ATTESTATION, draft.status());

        podA.setTenantAiEnabled(facilityId, false);

        // Pod B (fresh memory) reads persisted draft and enforces persisted kill-switch
        ClinicalAiGovernanceService podB = new ClinicalAiGovernanceService(null, new ClinicalAuditLogger(), draftRepo, policyRepo, mapper);
        assertFalse(podB.isTenantAiEnabled(facilityId));
        assertThrows(IllegalStateException.class, () -> podB.assertTenantAiAllowed(facilityId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE));

        ClinicalAiDraftRecord attested = podB.attestDraft(draft.draftId(),
                new AttestationCommand("dr-mbarga", DraftAttestationStatus.APPROVED, null, "Verified against EHR"));
        assertEquals(DraftAttestationStatus.APPROVED, attested.status());
        assertEquals("dr-mbarga", attested.attestedByPractitionerId());
        assertNotNull(attested.attestedAt());
    }

    @Test
    void auditOutageFailsClosedInsteadOfSwallowingMandatoryAuditEvents() {
        ClinicalAiAuditEntryRepository failingAuditRepo = mock(ClinicalAiAuditEntryRepository.class);
        when(failingAuditRepo.findTopByFacilityIdOrderBySequenceNumberDesc(any())).thenReturn(Optional.empty());
        when(failingAuditRepo.save(any())).thenThrow(new RuntimeException("Database connection lost"));

        ClinicalAuditLogger strictLogger = new ClinicalAuditLogger(null, failingAuditRepo, new ObjectMapper());
        ClinicalAiGovernanceService service = new ClinicalAiGovernanceService(null, strictLogger, null, null, new ObjectMapper());

        assertThrows(ClinicalAuditPersistenceException.class, () ->
                service.registerDraft(UUID.randomUUID(), UUID.randomUUID(), AiCapabilityType.MEDICATION_RECONCILIATION,
                        "gemini-2.5-pro", "hash", Map.of(), "{}", new TokenUsage(10, 10, 20), 50L, "dr-test"));
    }

    @Test
    void adversarialPhiRedactionRemovesDirectIdentifiersAndSupportsRehydration() {
        PhiRedactionGateway gateway = new PhiRedactionGateway();
        RedactionContext ctx = new RedactionContext(
                List.of("Jean-Baptiste Nguema"),
                List.of("MRN-8841209", "+237-699-123-456", "42 Rue de la Joie, Akwa"),
                "1984-07-19"
        );
        String rawClinicalNote = "Patient Jean-Baptiste Nguema (MRN-8841209, DOB 1984-07-19) "
                + "called from +237-699-123-456 / jb.nguema@douala-clinic.cm living at 42 Rue de la Joie, Akwa. "
                + "SSN 123-45-6789. Alternate date 07/19/1984. Adversarial unicode: N\u00e9e O'Connor; DROP TABLE patients;--";

        RedactedClinicalPayload redacted = gateway.redact(rawClinicalNote, ctx);

        assertFalse(redacted.redactedText().contains("Jean-Baptiste Nguema"));
        assertFalse(redacted.redactedText().contains("MRN-8841209"));
        assertFalse(redacted.redactedText().contains("1984-07-19"));
        assertFalse(redacted.redactedText().contains("jb.nguema@douala-clinic.cm"));
        assertFalse(redacted.redactedText().contains("123-45-6789"));
        assertTrue(redacted.totalRedactions() >= 5);
        assertNotNull(redacted.redactedSha256Hex());

        String rehydrated = redacted.rehydrateForAuthorizedClinician("Follow up with " + redacted.redactedText());
        assertTrue(rehydrated.contains("Jean-Baptiste Nguema"));
    }
}
