/*
 * =============================================================================
 * Opes HealthCore SDK — HIE FHIR R4 & HL7v2 Conformance Regression Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.HieDlqMessageEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Conformance and safety regression tests for {@link HieInteropService} covering
 * FHIR-001 (transaction/batch persistence & honest 501 rejection), FHIR-003 (honest
 * CapabilityStatement), HL7-001 (ADT persistence, idempotency, AR rejection for
 * unsupported message types), and SEC-002 (fail-closed tenant enforcement).
 */
class HieFhirAndHl7ConformanceTest {

    private MpiCommandService.PatientRepository patientRepo;
    private HieInteropService.FhirSubscriptionRepository subscriptionRepo;
    private HieInteropService.DicomStudyRepository dicomRepo;
    private HieInteropService.HieDlqRepository dlqRepo;
    private HieInteropService.IheDocumentRepository docRepo;
    private JdbcTemplate jdbcTemplate;
    private HieInteropService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        patientRepo = mock(MpiCommandService.PatientRepository.class);
        subscriptionRepo = mock(HieInteropService.FhirSubscriptionRepository.class);
        dicomRepo = mock(HieInteropService.DicomStudyRepository.class);
        dlqRepo = mock(HieInteropService.HieDlqRepository.class);
        docRepo = mock(HieInteropService.IheDocumentRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new HieInteropService(patientRepo, subscriptionRepo, dicomRepo, dlqRepo, docRepo, jdbcTemplate);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void fhirTransactionBundlePersistsPatientAndReturnsRealLocation() {
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String bundleJson = """
                {
                  "resourceType": "Bundle",
                  "type": "transaction",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Patient",
                        "identifier": [{ "system": "urn:opes:mrn", "value": "MRN-FHIR-101" }],
                        "name": [{ "family": "Mbianda", "given": ["Clarisse"] }],
                        "gender": "female",
                        "birthDate": "1991-06-14"
                      },
                      "request": { "method": "POST", "url": "Patient" }
                    }
                  ]
                }
                """;

        String responseJson = service.processFhirTransactionBundle(bundleJson);

        ArgumentCaptor<PatientAggregateEntity> captor = ArgumentCaptor.forClass(PatientAggregateEntity.class);
        verify(patientRepo).save(captor.capture());
        PatientAggregateEntity persisted = captor.getValue();
        assertEquals(facilityId, persisted.getFacilityId());
        assertEquals("MRN-FHIR-101", persisted.getMrn());
        assertEquals("Mbianda", persisted.getFamilyName());

        assertTrue(responseJson.contains("transaction-response"));
        assertTrue(responseJson.contains("201 Created"));
        assertTrue(responseJson.contains("Patient/" + persisted.getId()));
    }

    @Test
    void fhirTransactionBundleThrowsOnUnsupportedResourceForAtomicRollback() {
        String bundleJson = """
                {
                  "resourceType": "Bundle",
                  "type": "transaction",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": { "text": "Systolic BP" }
                      },
                      "request": { "method": "POST", "url": "Observation" }
                    }
                  ]
                }
                """;

        assertThrows(UnsupportedOperationException.class, () -> service.processFhirTransactionBundle(bundleJson));
        verify(patientRepo, never()).save(any());
    }

    @Test
    void fhirBatchBundleReturns501NotImplementedWithOperationOutcomeForUnsupportedResource() {
        String bundleJson = """
                {
                  "resourceType": "Bundle",
                  "type": "batch",
                  "entry": [
                    {
                      "resource": {
                        "resourceType": "Observation",
                        "status": "final",
                        "code": { "text": "Heart Rate" }
                      },
                      "request": { "method": "POST", "url": "Observation" }
                    }
                  ]
                }
                """;

        String responseJson = service.processFhirTransactionBundle(bundleJson);

        assertTrue(responseJson.contains("batch-response"));
        assertTrue(responseJson.contains("501 Not Implemented"));
        assertTrue(responseJson.contains("OperationOutcome"));
        assertFalse(responseJson.contains("200 OK"));
    }

    @Test
    void hl7AdtA01PersistsPatientAndRecordsTenantIdempotency() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(patientRepo.findByFacilityIdAndMrn(facilityId, "MRN-ADT-01")).thenReturn(Optional.empty());
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String hl7 = "MSH|^~\\&|HIS|DLA|OPES|DLA|20261119120000||ADT^A01|MSG-0001|P|2.5.1\r"
                + "EVN|A01|20261119120000\r"
                + "PID|1||MRN-ADT-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r";

        Map<String, Object> result = service.ingestHl7v2Message(facilityId, hl7);

        assertEquals("APPLIED_TO_MPI", result.get("status"));
        assertEquals("AA", result.get("ackCode"));
        assertEquals("MRN-ADT-01", result.get("mrn"));
        verify(patientRepo).save(any(PatientAggregateEntity.class));
        verify(jdbcTemplate).update(contains("INSERT INTO hc_inbound_idempotency"),
                any(), eq("HL7V2:" + facilityId + ":MSG-0001"), any(), eq(200), eq("AA"), any());
    }

    @Test
    void hl7DuplicateControlIdReturnsDuplicateAckWithoutRewritingMpi() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("HL7V2:" + facilityId + ":MSG-DUP")))
                .thenReturn(1);

        String hl7 = "MSH|^~\\&|HIS|DLA|OPES|DLA|20261119120000||ADT^A01|MSG-DUP|P|2.5.1\r"
                + "PID|1||MRN-ADT-02^^^OPES^MR||Ngoh^Samuel||19840522|M\r";

        Map<String, Object> result = service.ingestHl7v2Message(facilityId, hl7);

        assertEquals("DUPLICATE_ACK", result.get("status"));
        assertEquals("AA", result.get("ackCode"));
        verify(patientRepo, never()).save(any());
    }

    @Test
    void hl7UnsupportedOruMessageIsRejectedWithArAndQuarantinedToDlq() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        String hl7 = "MSH|^~\\&|LIS|DLA|OPES|DLA|20261119120500||ORU^R01|MSG-ORU-9|P|2.5.1\r"
                + "PID|1||MRN-ADT-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r";

        Map<String, Object> result = service.ingestHl7v2Message(facilityId, hl7);

        assertEquals("UNSUPPORTED_MESSAGE_TYPE", result.get("status"));
        assertEquals("AR", result.get("ackCode"));
        verify(dlqRepo).save(any(HieDlqMessageEntity.class));
        verify(patientRepo, never()).save(any());
    }

    @Test
    void hl7EmptyPayloadReturnsApplicationErrorAe() {
        Map<String, Object> result = service.ingestHl7v2Message(facilityId, "   ");
        assertEquals("REJECTED", result.get("status"));
        assertEquals("AE", result.get("ackCode"));
    }

    @Test
    void capabilityStatementAdvertisesOnlyImplementedResources() {
        String capJson = service.generateCapabilityStatementJson();

        assertTrue(capJson.contains("\"type\": \"Patient\""));
        assertTrue(capJson.contains("\"type\": \"DocumentReference\""));
        assertTrue(capJson.contains("\"type\": \"Subscription\""));
        assertTrue(capJson.contains("\"type\": \"Organization\""));
        assertTrue(capJson.contains("\"type\": \"Location\""));
        assertFalse(capJson.contains("\"type\": \"MedicationRequest\""));
        assertFalse(capJson.contains("\"type\": \"Observation\""));
    }

    @Test
    void missingFacilityContextFailsClosedWithAccessDenied() {
        FacilityContext.clear();
        assertThrows(AccessDeniedException.class, () -> service.exportPatientsNdjson());
    }
}
