/*
 * =============================================================================
 * Opes HealthCore SDK — HIE FHIR R4 & Full HL7v2 Conformance Regression Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import com.opeshealth.healthcore.hie.domain.HieEntities.*;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Conformance and safety regression tests for {@link HieInteropService} covering
 * FHIR-001 (transaction/batch persistence & honest 501 rejection), FHIR-003 (honest
 * CapabilityStatement), HL7-001 (full ADT, ORM, ORU, SIU, DFT persistence, idempotency,
 * AR rejection for unsupported message types, AE on malformed payloads), and SEC-002.
 */
class HieFhirAndHl7ConformanceTest {

    private MpiCommandService.PatientRepository patientRepo;
    private HieInteropService.FhirSubscriptionRepository subscriptionRepo;
    private HieInteropService.DicomStudyRepository dicomRepo;
    private HieInteropService.HieDlqRepository dlqRepo;
    private HieInteropService.IheDocumentRepository docRepo;
    private JdbcTemplate jdbcTemplate;
    private HieInteropService.Hl7OrderRepository hl7OrderRepo;
    private HieInteropService.Hl7ResultRepository hl7ResultRepo;
    private HieInteropService.Hl7ScheduleRepository hl7ScheduleRepo;
    private HieInteropService.Hl7FinancialTxnRepository hl7FinancialTxnRepo;
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
        hl7OrderRepo = mock(HieInteropService.Hl7OrderRepository.class);
        hl7ResultRepo = mock(HieInteropService.Hl7ResultRepository.class);
        hl7ScheduleRepo = mock(HieInteropService.Hl7ScheduleRepository.class);
        hl7FinancialTxnRepo = mock(HieInteropService.Hl7FinancialTxnRepository.class);

        service = new HieInteropService(
                patientRepo, subscriptionRepo, dicomRepo, dlqRepo, docRepo, jdbcTemplate,
                hl7OrderRepo, hl7ResultRepo, hl7ScheduleRepo, hl7FinancialTxnRepo);
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
    void hl7OrmO01CreatesAndCancelsTenantScopedOrder() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(patientRepo.findByFacilityIdAndMrn(facilityId, "MRN-ORD-01")).thenReturn(Optional.empty());
        when(hl7OrderRepo.findByFacilityIdAndPlacerOrderNumber(facilityId, "PLC-9001")).thenReturn(Optional.empty());
        when(hl7OrderRepo.save(any(Hl7OrderRecordEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String ormNew = "MSH|^~\\&|CPOE|DLA|OPES|DLA|20261119121000||ORM^O01|MSG-ORM-1|P|2.5.1\r"
                + "PID|1||MRN-ORD-01^^^OPES^MR||Kamga^Aline||19890312|F\r"
                + "ORC|NW|PLC-9001|FIL-101\r"
                + "OBR|1|PLC-9001|FIL-101|2823-3^Serum Potassium^LN\r";

        Map<String, Object> created = service.ingestHl7v2Message(facilityId, ormNew);
        assertEquals("APPLIED_TO_ORDERS", created.get("status"));
        assertEquals("AA", created.get("ackCode"));
        assertEquals("ACTIVE", created.get("orderStatus"));

        String ormCancel = "MSH|^~\\&|CPOE|DLA|OPES|DLA|20261119121500||ORM^O01|MSG-ORM-2|P|2.5.1\r"
                + "PID|1||MRN-ORD-01^^^OPES^MR||Kamga^Aline||19890312|F\r"
                + "ORC|CA|PLC-9001|FIL-101\r"
                + "OBR|1|PLC-9001|FIL-101|2823-3^Serum Potassium^LN\r";

        Map<String, Object> cancelled = service.ingestHl7v2Message(facilityId, ormCancel);
        assertEquals("APPLIED_TO_ORDERS", cancelled.get("status"));
        assertEquals("CANCELLED", cancelled.get("orderStatus"));
    }

    @Test
    void hl7OruR01PersistsLabResultWithAbnormalFlag() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(patientRepo.findByFacilityIdAndMrn(facilityId, "MRN-LAB-01")).thenReturn(Optional.empty());
        when(hl7ResultRepo.save(any(Hl7ObservationResultEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String oru = "MSH|^~\\&|LIS|DLA|OPES|DLA|20261119122000||ORU^R01|MSG-ORU-1|P|2.5.1\r"
                + "PID|1||MRN-LAB-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r"
                + "OBR|1|PLC-9001|FIL-101|2823-3^Serum Potassium^LN\r"
                + "OBX|1|NM|2823-3^Potassium^LN||6.7|mmol/L|3.5-5.1|HH|||F\r";

        Map<String, Object> result = service.ingestHl7v2Message(facilityId, oru);

        assertEquals("APPLIED_TO_RESULTS", result.get("status"));
        assertEquals("AA", result.get("ackCode"));
        assertEquals("2823-3", result.get("observationCode"));
        assertEquals("HH", result.get("abnormalFlag"));
        verify(hl7ResultRepo).save(any(Hl7ObservationResultEntity.class));
    }

    @Test
    void hl7SiuBooksAndCancelsAppointmentState() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(patientRepo.findByFacilityIdAndMrn(facilityId, "MRN-SCH-01")).thenReturn(Optional.empty());
        when(hl7ScheduleRepo.findByFacilityIdAndExternalAppointmentId(facilityId, "APPT-77")).thenReturn(Optional.empty());
        when(hl7ScheduleRepo.save(any(Hl7ScheduleEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String siuBook = "MSH|^~\\&|SCHED|DLA|OPES|DLA|20261119123000||SIU^S12|MSG-SIU-1|P|2.5.1\r"
                + "SCH|APPT-77||||||CONSULT^Cardiology Follow-up||||^^^20261125090000\r"
                + "PID|1||MRN-SCH-01^^^OPES^MR||Mbianda^Clarisse||19910614|F\r";

        Map<String, Object> booked = service.ingestHl7v2Message(facilityId, siuBook);
        assertEquals("APPLIED_TO_SCHEDULING", booked.get("status"));
        assertEquals("BOOKED", booked.get("appointmentStatus"));

        String siuCancel = "MSH|^~\\&|SCHED|DLA|OPES|DLA|20261119124000||SIU^S15|MSG-SIU-2|P|2.5.1\r"
                + "SCH|APPT-77||||||CANCEL^Patient Rescheduled\r"
                + "PID|1||MRN-SCH-01^^^OPES^MR||Mbianda^Clarisse||19910614|F\r";

        Map<String, Object> cancelled = service.ingestHl7v2Message(facilityId, siuCancel);
        assertEquals("CANCELLED", cancelled.get("appointmentStatus"));
    }

    @Test
    void hl7DftP03PostsChargeAndHandlesCreditReversal() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(patientRepo.findByFacilityIdAndMrn(facilityId, "MRN-BIL-01")).thenReturn(Optional.empty());
        when(hl7FinancialTxnRepo.findByFacilityIdAndTransactionId(eq(facilityId), anyString())).thenReturn(Optional.empty());
        when(hl7FinancialTxnRepo.save(any(Hl7FinancialTransactionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        String dftCharge = "MSH|^~\\&|BILL|DLA|OPES|DLA|20261119125000||DFT^P03|MSG-DFT-1|P|2.5.1\r"
                + "PID|1||MRN-BIL-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r"
                + "FT1|1|TXN-501||20261119||CG|CPT-99213^OPD Consultation||||15000.00\r";

        Map<String, Object> charged = service.ingestHl7v2Message(facilityId, dftCharge);
        assertEquals("APPLIED_TO_BILLING", charged.get("status"));
        assertEquals("15000.00", charged.get("amount"));
        assertEquals(Boolean.FALSE, charged.get("reversed"));

        String dftCredit = "MSH|^~\\&|BILL|DLA|OPES|DLA|20261119125500||DFT^P03|MSG-DFT-2|P|2.5.1\r"
                + "PID|1||MRN-BIL-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r"
                + "FT1|1|TXN-501-REV||20261119||CD|CPT-99213^OPD Reversal||||15000.00\r";

        Map<String, Object> reversed = service.ingestHl7v2Message(facilityId, dftCredit);
        assertEquals("REVERSED_FINANCIAL_TXN", reversed.get("status"));
        assertEquals(new BigDecimal("-15000.00"), new BigDecimal((String) reversed.get("amount")));
        assertEquals(Boolean.TRUE, reversed.get("reversed"));
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
    void hl7UnsupportedMessageTypeIsRejectedWithArAndQuarantinedToDlq() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);

        String hl7 = "MSH|^~\\&|DOC|DLA|OPES|DLA|20261119130000||MDM^T02|MSG-MDM-9|P|2.5.1\r"
                + "PID|1||MRN-ADT-01^^^OPES^MR||Ngoh^Samuel||19840522|M\r";

        Map<String, Object> result = service.ingestHl7v2Message(facilityId, hl7);

        assertEquals("UNSUPPORTED_MESSAGE_TYPE", result.get("status"));
        assertEquals("AR", result.get("ackCode"));
        verify(dlqRepo).save(any(HieDlqMessageEntity.class));
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
