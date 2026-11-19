/*
 * =============================================================================
 * Opes HealthCore SDK — Audit Remediation Regression Suite (Findings #1–#22)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger.AiModelInteractionEvent;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditLogger.AuditChainVerificationReport;
import com.opeshealth.healthcore.ai.audit.ClinicalAuditPersistenceException;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiAuditEntryRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.ClinicalAiDraftRepository;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyEntity;
import com.opeshealth.healthcore.ai.persistence.ClinicalAiPersistenceEntities.TenantAiPolicyRepository;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactionContext;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppEntity;
import com.opeshealth.healthcore.apiproduct.service.ApiProductService.DeveloperAppRepository;
import com.opeshealth.healthcore.emr.domain.EmrEntities.AdmissionEntity;
import com.opeshealth.healthcore.emr.domain.EmrEntities.ClinicalOrderEntity;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EmarAdministrationEntity;
import com.opeshealth.healthcore.emr.domain.EmrEntities.VitalSignsEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.AdmissionRepository;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.ClinicalOrderRepository;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.CpoeOrderRequest;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EmarRepository;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.VitalSignsRepository;
import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.FhirSubscriptionEntity;
import com.opeshealth.healthcore.hie.domain.HieEntities.IheDocumentEntity;
import com.opeshealth.healthcore.hie.service.DicomPacsProxyService;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.FhirSubscriptionRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.HieDlqRepository;
import com.opeshealth.healthcore.hie.service.HieInteropService.IheDocumentRepository;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.PractitionerCredentialEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CredentialRepository;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.tenancy.FacilityContext;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.MedicationBatchRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.PharmacyDispenseRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import com.opeshealth.healthcore.security.spi.WorkforceCredentialSpi;
import com.opeshealth.healthcore.starters.BloodBankStarter.BloodBankHemovigilanceService;
import com.opeshealth.healthcore.starters.BloodBankStarter.CompatibilityEvaluation;
import com.opeshealth.healthcore.starters.FhirHieStarter.FhirHieExchangeGatewayService;
import com.opeshealth.healthcore.sync.service.OfflineSyncService;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.CbhiPolicyEntity;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.LedgerEntryEntity;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.PatientAccountEntity;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.PaymentTransactionEntity;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.CbhiPolicyRepository;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.LedgerEntryRepository;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.PatientAccountRepository;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.PaymentTransactionRepository;
import com.opeshealth.healthcore.workflow.service.WorkflowOrchestrationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests verifying remediation of all security, tenant isolation, interoperability,
 * clinical safety, financial ledger, privacy, durable governance, audit fail-closed, and secret
 * externalization audit findings (#1–#22).
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
class AuditRemediationRegressionTest {

    @AfterEach
    void clearTenantContext() {
        FacilityContext.clear();
    }

    // -------------------------------------------------------------------------
    // Finding #1 — No broad "or isAuthenticated()" or bare "isAuthenticated()" bypasses
    // -------------------------------------------------------------------------

    @Test
    void finding01_patientAndClinicalControllersMustEnforceLeastPrivilegePermissions() {
        List<Class<?>> controllers = List.of(
                HieInteropService.FhirHieController.class,
                HieInteropService.InteroperabilityIheController.class,
                HieInteropService.Hl7AstmDicomController.class,
                HospitalOpsService.WorkforceRosterController.class,
                OfflineSyncService.OfflineSyncController.class,
                WorkflowOrchestrationService.WorkflowGatewayController.class,
                EmrClinicalWorkflowService.EmrClinicalController.class
        );

        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize preAuth = method.getAnnotation(PreAuthorize.class);
                if (preAuth != null) {
                    String expr = preAuth.value().trim();
                    assertFalse(expr.contains("or isAuthenticated()"),
                            "Auth bypass 'or isAuthenticated()' found on " + controller.getSimpleName() + "." + method.getName());
                    assertNotEquals("isAuthenticated()", expr,
                            "Bare 'isAuthenticated()' found on " + controller.getSimpleName() + "." + method.getName());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Finding #2 — Mandatory tenant isolation on HIE bulk export, IPS, IHE, DICOM
    // -------------------------------------------------------------------------

    @Test
    void finding02_tenantIsolationFailsClosedWithoutFacilityContextAndBlocksCrossTenantReads() {
        PatientRepository patientRepo = mock(PatientRepository.class);
        FhirSubscriptionRepository subRepo = mock(FhirSubscriptionRepository.class);
        DicomStudyRepository dicomRepo = mock(DicomStudyRepository.class);
        HieDlqRepository dlqRepo = mock(HieDlqRepository.class);
        IheDocumentRepository docRepo = mock(IheDocumentRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        HieInteropService service = new HieInteropService(patientRepo, subRepo, dicomRepo, dlqRepo, docRepo, jdbc);

        // Missing FacilityContext must fail closed
        assertThrows(AccessDeniedException.class, service::exportPatientsNdjson);
        assertThrows(AccessDeniedException.class, () -> service.generateIpsSummaryBundle(UUID.randomUUID()));

        UUID facilityA = UUID.randomUUID();
        UUID facilityB = UUID.randomUUID();
        PatientAggregateEntity patientA = new PatientAggregateEntity();
        patientA.setFacilityId(facilityA);
        patientA.setFamilyName("Ndi");
        patientA.setGivenName("Alice");

        when(patientRepo.findByFacilityId(facilityA)).thenReturn(List.of(patientA));
        when(patientRepo.findByIdAndFacilityId(patientA.getId(), facilityB)).thenReturn(Optional.empty());

        FacilityContext.setFacilityId(facilityA);
        String ndjson = service.exportPatientsNdjson();
        assertTrue(ndjson.contains("Ndi"));
        verify(patientRepo, never()).findAll();

        FacilityContext.setFacilityId(facilityB);
        assertThrows(AccessDeniedException.class, () -> service.generateIpsSummaryBundle(patientA.getId()));
    }

    // -------------------------------------------------------------------------
    // Finding #3 — FHIR Transaction/Batch applies real persistence or rejects honestly
    // -------------------------------------------------------------------------

    @Test
    void finding03_fhirTransactionPersistsRealPatientAndBatchRejectsUnsupportedEntries() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PatientRepository patientRepo = mock(PatientRepository.class);
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        HieInteropService service = new HieInteropService(
                patientRepo, mock(FhirSubscriptionRepository.class), mock(DicomStudyRepository.class),
                mock(HieDlqRepository.class), mock(IheDocumentRepository.class), mock(JdbcTemplate.class));

        String txBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "transaction",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "name": [{"family": "Mbappe", "given": ["Paul"]}],
                        "gender": "male",
                        "birthDate": "1990-05-12"
                      },
                      "request": {"method": "POST", "url": "Patient"}
                    }
                  ]
                }
                """;
        String txResp = service.processFhirTransactionBundle(txBundle);
        assertTrue(txResp.contains("201 Created"));
        verify(patientRepo, times(1)).save(argThat(p -> facId.equals(p.getFacilityId()) && "Mbappe".equals(p.getFamilyName())));

        String unsupportedBatch = """
                {
                  "resourceType": "Bundle",
                  "type": "batch",
                  "entry": [
                    {
                      "resource": {"resourceType": "MedicationRequest"},
                      "request": {"method": "POST", "url": "MedicationRequest"}
                    }
                  ]
                }
                """;
        String batchResp = service.processFhirTransactionBundle(unsupportedBatch);
        assertTrue(batchResp.contains("501 Not Implemented"));
        assertTrue(batchResp.contains("OperationOutcome"));
        assertFalse(batchResp.contains("200 OK"));
    }

    // -------------------------------------------------------------------------
    // Finding #4 — CapabilityStatement reconciles with implemented interactions
    // -------------------------------------------------------------------------

    @Test
    void finding04_capabilityStatementOnlyAdvertisesImplementedInteractions() {
        HieInteropService service = new HieInteropService(
                mock(PatientRepository.class), mock(FhirSubscriptionRepository.class),
                mock(DicomStudyRepository.class), mock(HieDlqRepository.class),
                mock(IheDocumentRepository.class), mock(JdbcTemplate.class));
        FhirHieExchangeGatewayService starterGateway = new FhirHieExchangeGatewayService(
                service, mock(FhirSubscriptionRepository.class));

        String capJson = starterGateway.buildFhirR4CapabilityStatementJson();
        assertEquals(service.generateCapabilityStatementJson(), capJson);
        assertTrue(capJson.contains("\"type\": \"Patient\""));
        assertTrue(capJson.contains("\"code\": \"transaction\""));
        assertFalse(capJson.contains("\"code\": \"delete\""),
                "CapabilityStatement must not advertise unimplemented DELETE interaction");
    }

    // -------------------------------------------------------------------------
    // Finding #5 — IPS Summary assembles persisted clinical docs/imaging & marks empty sections
    // -------------------------------------------------------------------------

    @Test
    void finding05_ipsSummaryIncludesPersistedDocsAndImagingAndMarksUnpopulatedSections() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PatientAggregateEntity patient = new PatientAggregateEntity();
        patient.setFacilityId(facId);
        patient.setFamilyName("Fotso");
        patient.setGivenName("Claire");
        patient.setGender("female");

        IheDocumentEntity doc = new IheDocumentEntity();
        doc.setFacilityId(facId);
        doc.setPatientId(patient.getId());
        doc.setDocumentTypeLoinc("34133-9");
        doc.setTitle("Discharge Summary");
        doc.setContentHashSha256("abc123");

        DicomStudyEntity study = new DicomStudyEntity();
        study.setFacilityId(facId);
        study.setPatientId(patient.getId());
        study.setStudyInstanceUid("1.2.840.10008.5.1.4.1.1.2.999");
        study.setModality("CR");
        study.setStudyDescription("Chest X-Ray");

        PatientRepository patientRepo = mock(PatientRepository.class);
        IheDocumentRepository docRepo = mock(IheDocumentRepository.class);
        DicomStudyRepository dicomRepo = mock(DicomStudyRepository.class);
        when(patientRepo.findByIdAndFacilityId(patient.getId(), facId)).thenReturn(Optional.of(patient));
        when(docRepo.findByFacilityIdAndPatientIdOrderByIndexedAtDesc(facId, patient.getId())).thenReturn(List.of(doc));
        when(dicomRepo.findByFacilityIdAndPatientId(facId, patient.getId())).thenReturn(List.of(study));

        HieInteropService service = new HieInteropService(
                patientRepo, mock(FhirSubscriptionRepository.class), dicomRepo,
                mock(HieDlqRepository.class), docRepo, mock(JdbcTemplate.class));

        String ips = service.generateIpsSummaryBundle(patient.getId());
        assertTrue(ips.contains("Discharge Summary"));
        assertTrue(ips.contains("ImagingStudy"));
        assertTrue(ips.contains("preliminary"));
        assertTrue(ips.contains("notasked"));
    }

    // -------------------------------------------------------------------------
    // Finding #6 — HL7v2 persists ADT into MPI and rejects unsupported types with AR
    // -------------------------------------------------------------------------

    @Test
    void finding06_hl7IngestPersistsAdtIntoMpiAndRejectsUnsupportedMessageTypesWithAr() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PatientRepository patientRepo = mock(PatientRepository.class);
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        HieDlqRepository dlqRepo = mock(HieDlqRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        HieInteropService service = new HieInteropService(
                patientRepo, mock(FhirSubscriptionRepository.class), mock(DicomStudyRepository.class),
                dlqRepo, mock(IheDocumentRepository.class), jdbc);

        String adtA01 = "MSH|^~\\&|HIS|FAC1|HIE|NAT|202611191200||ADT^A01|MSG-ADT-1|P|2.5.1\r"
                + "PID|1||MRN-42^^^FAC1||Tchoua^Jean||19880415|M\r";
        Map<String, Object> adtResult = service.ingestHl7v2(adtA01);
        assertEquals("AA", adtResult.get("ackCode"));
        assertEquals("APPLIED_TO_MPI", adtResult.get("status"));
        verify(patientRepo, times(1)).save(argThat(p -> facId.equals(p.getFacilityId()) && "Tchoua".equals(p.getFamilyName())));

        String oruR01 = "MSH|^~\\&|LIS|FAC1|HIE|NAT|202611191205||ORU^R01|MSG-ORU-1|P|2.5.1\r"
                + "PID|1||MRN-42^^^FAC1||Tchoua^Jean||19880415|M\r";
        Map<String, Object> oruResult = service.ingestHl7v2(oruR01);
        assertEquals("AR", oruResult.get("ackCode"));
        assertEquals("UNSUPPORTED_MESSAGE_TYPE", oruResult.get("status"));
        verify(dlqRepo, times(1)).save(any());
    }

    // -------------------------------------------------------------------------
    // Finding #7 — DICOM STOW-RS rejects metadata-only JSON (501) and PACS proxy fails closed
    // -------------------------------------------------------------------------

    @Test
    void finding07_dicomStowRejectsMetadataOnlyPayloadAndPacsProxyFailsClosedWhenDisabled() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        HieInteropService service = new HieInteropService(
                mock(PatientRepository.class), mock(FhirSubscriptionRepository.class),
                mock(DicomStudyRepository.class), mock(HieDlqRepository.class),
                mock(IheDocumentRepository.class), mock(JdbcTemplate.class));
        HieInteropService.Hl7AstmDicomController controller =
                new HieInteropService.Hl7AstmDicomController(service, mock(DicomStudyRepository.class));

        DicomStudyEntity metadataOnly = new DicomStudyEntity();
        metadataOnly.setStudyInstanceUid("1.2.840.999");
        ResponseEntity<Map<String, Object>> stowResp = controller.stowRs(metadataOnly);
        assertEquals(HttpStatus.NOT_IMPLEMENTED, stowResp.getStatusCode());
        assertNotEquals("STORED", stowResp.getBody().get("status"));

        DicomPacsProxyService proxy = new DicomPacsProxyService(
                mock(DicomStudyRepository.class), mock(HieDlqRepository.class));
        assertThrows(UnsupportedOperationException.class, () -> proxy.storeBinaryStudy(
                facId, UUID.randomUUID(), "1.2.840.999", "CT", "Head CT", "application/dicom", new byte[]{1, 2, 3}));
        assertEquals(HttpStatus.NOT_IMPLEMENTED, proxy.retrieveWadoBinary("1.2.840.999", null, null).getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Finding #8 — IHE mCSD Organizations & Locations are DB-backed (no hardcoded samples)
    // -------------------------------------------------------------------------

    @Test
    void finding08_iheMcsdReturnsTenantScopedDbDataInsteadOfHardcodedSamples() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("hc_facility_nodes"), eq(facId))).thenReturn(List.of(
                Map.of("id", facId, "code", "CM-DLA-01", "name", "Douala General Hospital", "facility_type", "TERTIARY")
        ));
        when(jdbc.queryForList(contains("hc_ward_beds"), eq(facId))).thenReturn(List.of());

        HieInteropService service = new HieInteropService(
                mock(PatientRepository.class), mock(FhirSubscriptionRepository.class),
                mock(DicomStudyRepository.class), mock(HieDlqRepository.class),
                mock(IheDocumentRepository.class), jdbc);

        Map<String, Object> orgs = service.queryTenantOrganizations();
        assertEquals(1, orgs.get("total"));
        assertTrue(orgs.toString().contains("Douala General Hospital"));
        assertFalse(orgs.toString().contains("opes-healthcore-network"));

        Map<String, Object> locs = service.queryTenantLocations();
        assertEquals(0, locs.get("total"));
        assertFalse(locs.toString().contains("opes-douala-main"));
    }

    // -------------------------------------------------------------------------
    // Finding #9 — Practitioner credentials fail closed on missing/expired/suspended
    // -------------------------------------------------------------------------

    @Test
    void finding09_practitionerCredentialsFailClosedAndBlockPermissiveModeInProduction() {
        UUID facId = UUID.randomUUID();
        CredentialRepository credRepo = mock(CredentialRepository.class);
        HospitalOpsService ops = new HospitalOpsService(
                mock(HospitalOpsService.BloodUnitRepository.class),
                mock(HospitalOpsService.BloodDonorRepository.class),
                mock(HospitalOpsService.TransfusionReactionRepository.class),
                mock(HospitalOpsService.SurgicalScheduleRepository.class),
                mock(HospitalOpsService.AnesthesiaPacuRepository.class),
                mock(HospitalOpsService.TelemedicineRepository.class),
                mock(HospitalOpsService.ColdChainRepository.class),
                mock(HospitalOpsService.BiomedMaintenanceRepository.class),
                mock(HospitalOpsService.CrvsRepository.class),
                credRepo,
                mock(HospitalOpsService.WorkforceRosterRepository.class),
                mock(OutboxRelayService.OutboxRepository.class)
        );

        // Missing -> fail closed
        when(credRepo.findByFacilityIdAndPractitionerId(facId, "PRAC-MISSING")).thenReturn(Optional.empty());
        assertFalse(ops.isPractitionerActive(facId, "PRAC-MISSING"));

        // Expired -> fail closed
        PractitionerCredentialEntity expired = new PractitionerCredentialEntity();
        expired.setFacilityId(facId);
        expired.setPractitionerId("PRAC-EXPIRED");
        expired.setExpiresOn(LocalDate.now().minusDays(1));
        expired.setSuspended(false);
        when(credRepo.findByFacilityIdAndPractitionerId(facId, "PRAC-EXPIRED")).thenReturn(Optional.of(expired));
        assertFalse(ops.isPractitionerActive(facId, "PRAC-EXPIRED"));

        // Suspended -> fail closed
        PractitionerCredentialEntity suspended = new PractitionerCredentialEntity();
        suspended.setFacilityId(facId);
        suspended.setPractitionerId("PRAC-SUSP");
        suspended.setExpiresOn(LocalDate.now().plusYears(1));
        suspended.setSuspended(true);
        when(credRepo.findByFacilityIdAndPractitionerId(facId, "PRAC-SUSP")).thenReturn(Optional.of(suspended));
        assertFalse(ops.isPractitionerActive(facId, "PRAC-SUSP"));

        // Valid -> allowed
        PractitionerCredentialEntity valid = new PractitionerCredentialEntity();
        valid.setFacilityId(facId);
        valid.setPractitionerId("PRAC-VALID");
        valid.setExpiresOn(LocalDate.now().plusYears(1));
        valid.setSuspended(false);
        when(credRepo.findByFacilityIdAndPractitionerId(facId, "PRAC-VALID")).thenReturn(Optional.of(valid));
        assertTrue(ops.isPractitionerActive(facId, "PRAC-VALID"));

        // Enabling permissive dev mode in production must throw
        assertThrows(IllegalStateException.class, () -> ops.configurePermissiveDevMode(true, true));
    }

    // -------------------------------------------------------------------------
    // Finding #10 & #11 & #14(NEWS2) — CPOE patient context, 5-Rights eMAR, RCP NEWS2
    // -------------------------------------------------------------------------

    @Test
    void finding10_11_14_cpoeLoadsContextEmarEnforcesFiveRightsAndNews2MatchesRcpBands() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        VitalSignsRepository vitalsRepo = mock(VitalSignsRepository.class);
        ClinicalOrderRepository orderRepo = mock(ClinicalOrderRepository.class);
        AdmissionRepository admissionRepo = mock(AdmissionRepository.class);
        EmarRepository emarRepo = mock(EmarRepository.class);
        CdsHooksEngine cdsEngine = mock(CdsHooksEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkforceCredentialSpi> credProvider = mock(ObjectProvider.class);
        WorkforceCredentialSpi credSpi = mock(WorkforceCredentialSpi.class);
        when(credProvider.getIfAvailable()).thenReturn(credSpi);
        when(credSpi.isPractitionerCredentialActive(facId, "NURSE-01")).thenReturn(true);
        when(credSpi.isPractitionerCredentialActive(facId, "NURSE-EXPIRED")).thenReturn(false);

        EmrClinicalWorkflowService emr = new EmrClinicalWorkflowService(
                mock(EmrClinicalWorkflowService.AppointmentRepository.class),
                mock(EmrClinicalWorkflowService.EncounterRepository.class),
                mock(EmrClinicalWorkflowService.TriageRepository.class),
                vitalsRepo,
                mock(EmrClinicalWorkflowService.ClinicalNoteRepository.class),
                orderRepo,
                mock(EmrClinicalWorkflowService.WardBedRepository.class),
                admissionRepo,
                emarRepo,
                mock(EmrClinicalWorkflowService.CarePlanRepository.class),
                mock(EmrClinicalWorkflowService.SpecialtyRecordRepository.class),
                mock(EmrClinicalWorkflowService.FluidBalanceRepository.class),
                mock(EmrClinicalWorkflowService.ShiftHandoverRepository.class),
                mock(EmrClinicalWorkflowService.MedReconciliationRepository.class),
                mock(EmrClinicalWorkflowService.ClinicalReferralRepository.class),
                cdsEngine,
                mock(OutboxRelayService.OutboxRepository.class),
                credProvider,
                List.of()
        );

        // #10: Medication order with no allergy list & allergiesVerifiedAsNone=false must be blocked
        ClinicalOrderEntity medOrder = new ClinicalOrderEntity();
        medOrder.setFacilityId(facId);
        medOrder.setPatientId(patientId);
        medOrder.setOrderCategory("MEDICATION");
        medOrder.setOrderCode("RX-AMOX-500");
        medOrder.setOrderDisplay("Amoxicillin 500mg PO TID");
        medOrder.setStatus("ACTIVE");

        when(orderRepo.findByFacilityIdAndPatientIdAndOrderCategoryAndStatus(facId, patientId, "MEDICATION", "ACTIVE"))
                .thenReturn(List.of());
        when(vitalsRepo.findTop20ByPatientIdOrderByRecordedAtDesc(patientId)).thenReturn(List.of());
        when(cdsEngine.evaluateOrderSign(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Map<String, Object> blockedResp = emr.placeOrderWithLoadedPatientContext(
                new CpoeOrderRequest(medOrder, List.of(), List.of(), null, null, false));
        assertEquals(true, blockedResp.get("blocked"));

        // #11: 5-Rights eMAR rejects wrong drug barcode & expired nurse license, accepts valid 5-rights
        AdmissionEntity adm = new AdmissionEntity();
        adm.setFacilityId(facId);
        adm.setPatientId(patientId);
        when(admissionRepo.findById(adm.getId())).thenReturn(Optional.of(adm));
        when(orderRepo.findById(medOrder.getId())).thenReturn(Optional.of(medOrder));
        when(emarRepo.save(any(EmarAdministrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        EmarAdministrationEntity wrongDrug = new EmarAdministrationEntity();
        wrongDrug.setAdmissionId(adm.getId());
        wrongDrug.setOrderId(medOrder.getId());
        wrongDrug.setPatientId(patientId);
        wrongDrug.setScannedPatientWristband(patientId.toString());
        wrongDrug.setScannedDrugBarcode("RX-WRONG-DRUG");
        wrongDrug.setDoseAdministered("500mg PO");
        wrongDrug.setAdministeredBy("NURSE-01");
        assertThrows(SecurityException.class, () -> emr.administerMedicationFiveRights(wrongDrug));

        EmarAdministrationEntity expiredNurse = new EmarAdministrationEntity();
        expiredNurse.setAdmissionId(adm.getId());
        expiredNurse.setOrderId(medOrder.getId());
        expiredNurse.setPatientId(patientId);
        expiredNurse.setScannedPatientWristband(patientId.toString());
        expiredNurse.setScannedDrugBarcode("RX-AMOX-500");
        expiredNurse.setDoseAdministered("500mg PO");
        expiredNurse.setAdministeredBy("NURSE-EXPIRED");
        assertThrows(SecurityException.class, () -> emr.administerMedicationFiveRights(expiredNurse));

        EmarAdministrationEntity validMar = new EmarAdministrationEntity();
        validMar.setAdmissionId(adm.getId());
        validMar.setOrderId(medOrder.getId());
        validMar.setPatientId(patientId);
        validMar.setScannedPatientWristband(patientId.toString());
        validMar.setScannedDrugBarcode("RX-AMOX-500");
        validMar.setDoseAdministered("500mg PO");
        validMar.setAdministeredBy("NURSE-01");
        assertNotNull(emr.administerMedicationFiveRights(validMar));

        // #14: Full RCP NEWS2 Scale 1 bands
        VitalSignsEntity normal = new VitalSignsEntity();
        normal.setRespRatePerMin(16);
        normal.setSpo2Percent(98);
        normal.setSystolicBp(120);
        normal.setHeartRateBpm(72);
        normal.setTempCelsius(new BigDecimal("36.8"));
        assertEquals(0, emr.computeNews2(normal, false, "A"));

        VitalSignsEntity intermediate = new VitalSignsEntity();
        intermediate.setRespRatePerMin(22);    // +2
        intermediate.setSpo2Percent(94);       // +1
        intermediate.setSystolicBp(105);       // +1
        intermediate.setHeartRateBpm(100);     // +1
        intermediate.setTempCelsius(new BigDecimal("38.5")); // +1
        assertEquals(8, emr.computeNews2(intermediate, true, "A")); // +2 for supplemental O2

        VitalSignsEntity critical = new VitalSignsEntity();
        critical.setRespRatePerMin(28);        // +3
        critical.setSpo2Percent(90);           // +3
        critical.setSystolicBp(85);            // +3
        critical.setHeartRateBpm(135);         // +3
        critical.setTempCelsius(new BigDecimal("34.8")); // +3
        assertEquals(20, emr.computeNews2(critical, true, "V")); // +2 O2 + 3 ACVPU(V)
    }

    // -------------------------------------------------------------------------
    // Finding #14 (Blood Bank) — ABO/RhD compatibility matrix & unsupported component rejection
    // -------------------------------------------------------------------------

    @Test
    void finding14_bloodBankCompatibilityMatrixAndUnsupportedComponentRejection() {
        BloodBankHemovigilanceService bloodBank = new BloodBankHemovigilanceService(
                mock(HospitalOpsService.class),
                mock(HospitalOpsService.BloodUnitRepository.class),
                mock(HospitalOpsService.BloodDonorRepository.class),
                mock(HospitalOpsService.TransfusionReactionRepository.class)
        );

        // PRBC: O- universal donor; AB+ cannot donate to O-
        assertTrue(bloodBank.evaluateCompatibility("AB+", "O-", "PRBC").compatible());
        assertFalse(bloodBank.evaluateCompatibility("O-", "AB+", "PRBC").compatible());
        assertFalse(bloodBank.evaluateCompatibility("A-", "A+", "PRBC").compatible());

        // FFP: AB plasma is universal plasma donor; O plasma only compatible with O recipient
        CompatibilityEvaluation abFfpToO = bloodBank.evaluateCompatibility("O-", "AB+", "FFP");
        assertTrue(abFfpToO.compatible());
        assertFalse(bloodBank.evaluateCompatibility("AB+", "O-", "FFP").compatible());

        // Unsupported component type must fail explicitly rather than falling back to PRBC
        assertThrows(IllegalArgumentException.class,
                () -> bloodBank.evaluateCompatibility("A+", "A+", "PLATELETS"));
    }

    // -------------------------------------------------------------------------
    // Finding #12 & #18 — Balanced double-entry ledger, reversal, webhook idempotency & external secrets
    // -------------------------------------------------------------------------

    @Test
    void finding12_and_18_uhcDoubleEntryReversalIdempotencyAndExternalizedSecrets() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        PatientAccountEntity acct = new PatientAccountEntity();
        acct.setFacilityId(facId);
        acct.setPatientId(patientId);
        acct.setCurrency("XAF");
        acct.setBalanceMinor(0L);

        List<LedgerEntryEntity> savedLedger = new ArrayList<>();
        Map<String, PaymentTransactionEntity> savedTxs = new ConcurrentHashMap<>();

        PatientAccountRepository accountRepo = mock(PatientAccountRepository.class);
        LedgerEntryRepository ledgerRepo = mock(LedgerEntryRepository.class);
        CbhiPolicyRepository cbhiRepo = mock(CbhiPolicyRepository.class);
        PaymentTransactionRepository txRepo = mock(PaymentTransactionRepository.class);

        when(accountRepo.findByFacilityIdAndPatientId(facId, patientId)).thenReturn(Optional.of(acct));
        when(accountRepo.findById(acct.getId())).thenReturn(Optional.of(acct));
        when(accountRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledgerRepo.save(any(LedgerEntryEntity.class))).thenAnswer(inv -> {
            LedgerEntryEntity e = inv.getArgument(0);
            savedLedger.add(e);
            return e;
        });
        when(ledgerRepo.findByJournalBatchId(any())).thenAnswer(inv -> {
            UUID batchId = inv.getArgument(0);
            return savedLedger.stream().filter(e -> batchId.equals(e.getJournalBatchId())).toList();
        });
        when(txRepo.findByProviderCodeAndExternalTxRef(anyString(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(savedTxs.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(txRepo.save(any(PaymentTransactionEntity.class))).thenAnswer(inv -> {
            PaymentTransactionEntity tx = inv.getArgument(0);
            savedTxs.put(tx.getProviderCode() + "|" + tx.getExternalTxRef(), tx);
            return tx;
        });

        UhcBillingService billing = new UhcBillingService(
                accountRepo, ledgerRepo, cbhiRepo, mock(UhcBillingService.InsuranceClaimRepository.class),
                txRepo, mock(UhcBillingService.UhcVoucherRepository.class),
                mock(OutboxRelayService.OutboxRepository.class), List.of());

        // #18: Empty secrets must fail closed
        assertThrows(IllegalStateException.class, billing::requireConfiguredMobileMoneyWebhookSecret);
        CbhiPolicyEntity policy = new CbhiPolicyEntity();
        policy.setFacilityId(facId);
        policy.setPatientId(patientId);
        policy.setPolicyNumber("POL-100");
        policy.setCoveragePercent(80);
        policy.setValidFrom(LocalDate.now().minusDays(1));
        policy.setValidUntil(LocalDate.now().plusDays(30));
        policy.setActive(true);
        when(cbhiRepo.findByPolicyNumber("POL-100")).thenReturn(Optional.of(policy));
        assertThrows(IllegalStateException.class, () -> billing.issueOfflineEligibilityQr("POL-100"));

        // #12: Balanced charge posting + reversal
        LedgerEntryEntity charge = billing.postCharge(facId, patientId, 15_000L, "XAF", "INV-1", "Consultation");
        assertNotNull(charge.getJournalBatchId());
        assertEquals(2, savedLedger.size());
        long debits = savedLedger.stream().filter(e -> "DEBIT".equals(e.getEntryType())).mapToLong(LedgerEntryEntity::getAmountMinor).sum();
        long credits = savedLedger.stream().filter(e -> "CREDIT".equals(e.getEntryType())).mapToLong(LedgerEntryEntity::getAmountMinor).sum();
        assertEquals(debits, credits);
        assertEquals(15_000L, acct.getBalanceMinor());

        // Webhook settlement is idempotent on replay
        billing.setMobileMoneyWebhookSecret("unit-test-momo-secret");
        String payload = "{\"tx\":\"TX-99\"}";
        String sig = UhcBillingService.computeHmacSha256(payload, "unit-test-momo-secret");
        PaymentTransactionEntity first = billing.settleMobileMoneyWebhook("MTN_MOMO", acct.getId(), "TX-99", 5_000L, "XAF", payload, sig, "unit-test-momo-secret");
        PaymentTransactionEntity replay = billing.settleMobileMoneyWebhook("MTN_MOMO", acct.getId(), "TX-99", 5_000L, "XAF", payload, sig, "unit-test-momo-secret");
        assertEquals(first.getId(), replay.getId());
        assertEquals(10_000L, acct.getBalanceMinor());

        // Reversal offsets the original charge batch
        List<LedgerEntryEntity> reversalEntries = billing.reverseJournalBatch(charge.getJournalBatchId(), "Duplicate bill");
        assertEquals(2, reversalEntries.size());
        assertEquals(-5_000L, acct.getBalanceMinor());
    }

    // -------------------------------------------------------------------------
    // Finding #13 — Pharmacy FEFO atomic stock decrement prevents race overselling
    // -------------------------------------------------------------------------

    @Test
    void finding13_pharmacyFefoAtomicDecrementPreventsConcurrentNegativeStock() throws Exception {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        MedicationBatchEntity batch = new MedicationBatchEntity();
        batch.setFacilityId(facId);
        batch.setDrugCode("RX-ARTEM");
        batch.setLotNumber("LOT-2026");
        batch.setExpiryDate(LocalDate.now().plusMonths(6));
        batch.setQuantityOnHand(5);
        AtomicInteger remainingStock = new AtomicInteger(5);

        MedicationBatchRepository batchRepo = mock(MedicationBatchRepository.class);
        PharmacyDispenseRepository dispenseRepo = mock(PharmacyDispenseRepository.class);
        when(batchRepo.findFefoAvailable(eq(facId), eq("RX-ARTEM"), any(LocalDate.class))).thenReturn(List.of(batch));
        when(batchRepo.decrementStockAtomically(eq(batch.getId()), eq(facId), anyInt())).thenAnswer(inv -> {
            int qty = inv.getArgument(2);
            while (true) {
                int current = remainingStock.get();
                if (current < qty) {
                    return 0;
                }
                if (remainingStock.compareAndSet(current, current - qty)) {
                    batch.setQuantityOnHand(current - qty);
                    return 1;
                }
            }
        });
        when(dispenseRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PharmacyLisService pharmacy = new PharmacyLisService(
                batchRepo, dispenseRepo,
                mock(PharmacyLisService.LabSpecimenRepository.class),
                mock(PharmacyLisService.DiagnosticReportRepository.class),
                mock(CdsHooksEngine.class),
                mock(OutboxRelayService.OutboxRepository.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> t1 = pool.submit(() -> {
            FacilityContext.setFacilityId(facId);
            start.await();
            try {
                pharmacy.dispenseFefo(facId, patientId, UUID.randomUUID(), "RX-ARTEM", "RX-ARTEM", 4, "PHARM-1", null);
                return true;
            } catch (OptimisticLockingFailureException ex) {
                return false;
            } finally {
                FacilityContext.clear();
            }
        });
        Future<Boolean> t2 = pool.submit(() -> {
            FacilityContext.setFacilityId(facId);
            start.await();
            try {
                pharmacy.dispenseFefo(facId, patientId, UUID.randomUUID(), "RX-ARTEM", "RX-ARTEM", 4, "PHARM-2", null);
                return true;
            } catch (OptimisticLockingFailureException ex) {
                return false;
            } finally {
                FacilityContext.clear();
            }
        });

        start.countDown();
        int successes = (t1.get() ? 1 : 0) + (t2.get() ? 1 : 0);
        pool.shutdownNow();

        assertEquals(1, successes, "Exactly one concurrent dispense of 4 from stock 5 may succeed");
        assertTrue(remainingStock.get() >= 0, "Stock must never become negative");
    }

    // -------------------------------------------------------------------------
    // Finding #15 — Heuristic PHI pre-filter covers adversarial formats honestly
    // -------------------------------------------------------------------------

    @Test
    void finding15_phiPreFilterRedactsAdversarialDatesHonorificsAndKnownContext() {
        PhiRedactionGateway gateway = new PhiRedactionGateway();
        String note = "Mr. Emmanuel Nkeng visited on 12 Jan 1985 (DOB: 04/19/1982). "
                + "Call +237677112233 or email nkeng@example.cm; MRN: HOSP-99812.";

        RedactedClinicalPayload redacted = gateway.redact(note, new RedactionContext(
                List.of("Emmanuel Nkeng"), List.of("HOSP-99812"), "04/19/1982"));

        assertFalse(redacted.redactedText().contains("Emmanuel Nkeng"));
        assertFalse(redacted.redactedText().contains("12 Jan 1985"));
        assertFalse(redacted.redactedText().contains("04/19/1982"));
        assertFalse(redacted.redactedText().contains("+237677112233"));
        assertFalse(redacted.redactedText().contains("nkeng@example.cm"));
        assertFalse(redacted.redactedText().contains("HOSP-99812"));
        assertTrue(redacted.totalRedactions() >= 5);
    }

    // -------------------------------------------------------------------------
    // Finding #16 & #17 — Durable AI governance/audit persistence & fail-closed audit writes
    // -------------------------------------------------------------------------

    @Test
    void finding16_and_17_aiGovernanceSurvivesRestartAndAuditFailureFailsClosed() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        Map<UUID, ClinicalAiDraftEntity> draftStore = new ConcurrentHashMap<>();
        Map<UUID, TenantAiPolicyEntity> policyStore = new ConcurrentHashMap<>();
        List<ClinicalAiAuditEntryEntity> auditStore = new ArrayList<>();

        ClinicalAiDraftRepository draftRepo = mock(ClinicalAiDraftRepository.class);
        TenantAiPolicyRepository policyRepo = mock(TenantAiPolicyRepository.class);
        ClinicalAiAuditEntryRepository auditRepo = mock(ClinicalAiAuditEntryRepository.class);

        when(draftRepo.save(any())).thenAnswer(inv -> {
            ClinicalAiDraftEntity e = inv.getArgument(0);
            draftStore.put(e.getDraftId(), e);
            return e;
        });
        when(draftRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(draftStore.get(inv.getArgument(0))));
        when(draftRepo.findByFacilityIdOrderByCreatedAtDesc(any())).thenAnswer(inv -> {
            UUID f = inv.getArgument(0);
            return draftStore.values().stream().filter(d -> f.equals(d.getFacilityId())).toList();
        });
        when(policyRepo.save(any())).thenAnswer(inv -> {
            TenantAiPolicyEntity e = inv.getArgument(0);
            policyStore.put(e.getFacilityId(), e);
            return e;
        });
        when(policyRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(policyStore.get(inv.getArgument(0))));
        when(auditRepo.save(any())).thenAnswer(inv -> {
            ClinicalAiAuditEntryEntity e = inv.getArgument(0);
            auditStore.add(e);
            return e;
        });
        when(auditRepo.findTopByFacilityIdOrderBySequenceNumberDesc(any())).thenAnswer(inv -> {
            UUID f = inv.getArgument(0);
            return auditStore.stream().filter(e -> f.equals(e.getFacilityId()))
                    .max(Comparator.comparingLong(ClinicalAiAuditEntryEntity::getSequenceNumber));
        });
        when(auditRepo.findByFacilityIdOrderBySequenceNumberAsc(any())).thenAnswer(inv -> {
            UUID f = inv.getArgument(0);
            return auditStore.stream().filter(e -> f.equals(e.getFacilityId()))
                    .sorted(Comparator.comparingLong(ClinicalAiAuditEntryEntity::getSequenceNumber)).toList();
        });

        ObjectMapper mapper = new ObjectMapper();
        ClinicalAuditLogger instance1Logger = new ClinicalAuditLogger(null, auditRepo, mapper);
        ClinicalAiGovernanceService instance1 = new ClinicalAiGovernanceService(
                null, instance1Logger, draftRepo, policyRepo, mapper);

        ClinicalAiDraftRecord draft = instance1.registerDraft(
                facId, patientId, AiCapabilityType.AMBIENT_CLINICAL_SCRIBE, "gemini-4-hydrogen",
                "a".repeat(64), Map.of("PATIENT_NAME", 1), "{\"soap\":\"ok\"}", null, 120L, "DR-1");
        instance1.setTenantAiEnabled(facId, false);

        // Simulate process restart / second instance reading from the same durable repositories
        ClinicalAuditLogger instance2Logger = new ClinicalAuditLogger(null, auditRepo, mapper);
        ClinicalAiGovernanceService instance2 = new ClinicalAiGovernanceService(
                null, instance2Logger, draftRepo, policyRepo, mapper);

        assertFalse(instance2.isTenantAiEnabled(facId), "Kill-switch state must persist across restarts");
        assertEquals(draft.draftId(), instance2.getDraft(draft.draftId()).draftId(), "Draft must persist across restarts");

        instance2.attestDraft(draft.draftId(), new AttestationCommand("DR-2", DraftAttestationStatus.APPROVED, null, "Reviewed"));
        AuditChainVerificationReport report = instance2Logger.verifyAuditChain(facId);
        assertTrue(report.intact(), "Persisted hash chain must verify cleanly across instances");
        assertTrue(report.verifiedEntries() >= 3);

        // #17: Audit backend failure must fail closed (never swallow) and must not corrupt chain state
        ClinicalAuditService failingAuditService = mock(ClinicalAuditService.class);
        doThrow(new RuntimeException("DB audit write failed"))
                .when(failingAuditService).recordEventWithCorrelation(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        ClinicalAuditLogger failingLogger = new ClinicalAuditLogger(failingAuditService);
        assertThrows(ClinicalAuditPersistenceException.class, () -> failingLogger.recordAiModelInteraction(
                new AiModelInteractionEvent(facId, patientId, UUID.randomUUID(), "SCRIBE", "gemini-4-hydrogen",
                        "DR-1", "DRAFT_PENDING_ATTESTATION", "SUCCESS", "a".repeat(64), "b".repeat(64),
                        Map.of(), 10, 20, 50L, "test")));
        assertEquals(0, failingLogger.queryAuditEntries(facId, null, null, null, null).size(),
                "Failed mandatory audit write must not leave an unpersisted entry in the in-memory chain");
    }

    // -------------------------------------------------------------------------
    // Finding #18 — Externalized secrets fail closed across HIE, ApiProduct, and PII Vault
    // -------------------------------------------------------------------------

    @Test
    void finding18_webhookSecretsAndPiiVaultKeysFailClosedWhenUnconfigured() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        // HIE webhook signing fails closed when neither subscription secret nor global secret is set
        FhirSubscriptionEntity sub = new FhirSubscriptionEntity();
        sub.setFacilityId(facId);
        sub.setEndpointUrl("https://example.org/hook");
        sub.setStatus("ACTIVE");
        FhirSubscriptionRepository subRepo = mock(FhirSubscriptionRepository.class);
        when(subRepo.findByIdAndFacilityId(sub.getId(), facId)).thenReturn(Optional.of(sub));

        HieInteropService hie = new HieInteropService(
                mock(PatientRepository.class), subRepo, mock(DicomStudyRepository.class),
                mock(HieDlqRepository.class), mock(IheDocumentRepository.class), mock(JdbcTemplate.class));
        assertThrows(IllegalStateException.class, () -> hie.dispatchSignedWebhook(sub.getId(), "{\"resourceType\":\"Patient\"}"));

        // ApiProduct webhook signing fails closed when secret is blank
        DeveloperAppEntity app = new DeveloperAppEntity();
        app.setWebhookUrl("https://partner.example.org/events");
        app.setWebhookSecret("");
        DeveloperAppRepository appRepo = mock(DeveloperAppRepository.class);
        when(appRepo.findById(app.getId())).thenReturn(Optional.of(app));
        ApiProductService apiProduct = new ApiProductService(appRepo);
        assertThrows(IllegalStateException.class, () -> apiProduct.deliverSignedWebhook(app.getId(), "TEST", "{}"));

        // PII Vault blocks default dev keys under production profile
        if (PiiVaultCrypto.isUsingDefaultDevKeys()) {
            assertThrows(IllegalStateException.class, () -> PiiVaultCrypto.assertProductionKeysConfigured(true));
        }
    }
}
