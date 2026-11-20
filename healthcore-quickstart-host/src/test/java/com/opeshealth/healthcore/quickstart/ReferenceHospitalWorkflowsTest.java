/*
 * =============================================================================
 * Opes HealthCore SDK — End-to-End Reference Hospital 5-Workflow Test Suite
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.*;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.*;
import com.opeshealth.healthcore.emr.domain.EmrEntities.*;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.*;
import com.opeshealth.healthcore.hie.domain.HieEntities.*;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.*;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.*;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.*;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.*;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.*;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies all 5 required Reference Hospital end-to-end clinical workflows defined in
 * {@code HOSPITAL_INFORMATION_SYSTEM_COMPLETE_SPEC.json}:
 * <ol>
 *   <li>Outpatient registration, scheduling, clinical visit, pharmacy dispense &amp; billing</li>
 *   <li>Inpatient admission, nursing ratio/NEWS2, 5-Rights eMAR, discharge readiness &amp; reconciliation</li>
 *   <li>Laboratory critical result QC gate, panic escalation &amp; closed-loop verbal read-back</li>
 *   <li>MPI duplicate detection, authorized merge cascade &amp; reversible unmerge</li>
 *   <li>Tenant-scoped FHIR R4 transaction + HL7v2 ORM/ORU interoperability</li>
 * </ol>
 */
class ReferenceHospitalWorkflowsTest {

    private UUID facilityId;
    private ClinicalAuditService auditService;
    private OutboxRelayService.OutboxRepository outboxRepo;

    @BeforeEach
    void setUp() {
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        SecurityContextHolder.clearContext();
        auditService = mock(ClinicalAuditService.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void workflow1OutpatientRegistrationVisitDispenseAndBalancedBilling() {
        // 1. MPI Registration
        Map<UUID, PatientAggregateEntity> patients = new HashMap<>();
        PatientRepository patientRepo = mock(PatientRepository.class);
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> {
            PatientAggregateEntity p = inv.getArgument(0);
            patients.put(p.getId(), p);
            return p;
        });
        MpiCommandService mpi = new MpiCommandService(
                patientRepo, mock(PatientIdentifierRepository.class), mock(PatientDemographicsRepository.class),
                mock(PatientPhoneticRepository.class), mock(PatientMergeRepository.class),
                outboxRepo, mock(ApplicationEventPublisher.class));

        PatientAggregateEntity patient = mpi.registerPatient(new RegisterPatientCommand(
                facilityId, "DLA-OPD-101", "Ndiaye", "Amina", LocalDate.of(1990, 5, 10),
                "female", "O+", "+237690112233", "fr", "CM-CNI", "CNI-101",
                "Douala", "Moussa", "+237690119999"));
        assertNotNull(patients.get(patient.getId()));

        // 2. Scheduling book + check-in
        ResourceCalendarRepository calRepo = mock(ResourceCalendarRepository.class);
        AppointmentTypeRepository typeRepo = mock(AppointmentTypeRepository.class);
        ScheduledAppointmentRepository apptRepo = mock(ScheduledAppointmentRepository.class);
        WaitlistRepository waitRepo = mock(WaitlistRepository.class);

        Map<UUID, ResourceCalendarEntity> cals = new HashMap<>();
        Map<UUID, ScheduledAppointmentEntity> appts = new HashMap<>();
        when(calRepo.save(any())).thenAnswer(inv -> { ResourceCalendarEntity c = inv.getArgument(0); cals.put(c.getId(), c); return c; });
        when(calRepo.findByIdAndFacilityId(any(), eq(facilityId))).thenAnswer(inv -> Optional.ofNullable(cals.get(inv.getArgument(0))));
        when(apptRepo.findOverlapping(any(), any(), any(), any())).thenReturn(List.of());
        when(apptRepo.save(any())).thenAnswer(inv -> { ScheduledAppointmentEntity a = inv.getArgument(0); appts.put(a.getId(), a); return a; });
        when(apptRepo.findByIdAndFacilityId(any(), eq(facilityId))).thenAnswer(inv -> Optional.ofNullable(appts.get(inv.getArgument(0))));
        when(apptRepo.countByFacilityIdAndDepartmentCodeAndScheduledStartBetween(eq(facilityId), anyString(), any(), any())).thenReturn(0L);

        SchedulingService scheduling = new SchedulingService(calRepo, typeRepo, apptRepo, waitRepo, auditService);
        ResourceCalendarEntity cal = scheduling.createCalendar(facilityId, new CreateCalendarRequest(
                "OPD-MED", ResourceKind.PROVIDER, "dr.ekane", "Dr. Ekane", 20, 1));
        Instant start = Instant.now().plus(Duration.ofHours(1));
        ScheduledAppointmentEntity booked = scheduling.bookAppointment(facilityId, new BookAppointmentRequest(
                cal.getId(), null, patient.getId(), "CONSULT", start, start.plus(Duration.ofMinutes(20)), "Fever"), "reception-1");
        ScheduledAppointmentEntity checkedIn = scheduling.checkIn(facilityId, booked.getId());
        assertEquals(AppointmentStatus.CHECKED_IN, checkedIn.getStatus());

        // 3. Pharmacy FEFO dispense
        PharmacyLisService.MedicationBatchRepository batchRepo = mock(PharmacyLisService.MedicationBatchRepository.class);
        PharmacyLisService.PharmacyDispenseRepository dispRepo = mock(PharmacyLisService.PharmacyDispenseRepository.class);
        MedicationBatchEntity lot = new MedicationBatchEntity();
        lot.setFacilityId(facilityId);
        lot.setDrugCode("RX-AMOX-500");
        lot.setDrugName("Amoxicillin 500mg");
        lot.setLotNumber("LOT-2026-01");
        lot.setExpiryDate(LocalDate.now().plusMonths(6));
        lot.setQuantityOnHand(50);
        when(batchRepo.findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                eq(facilityId), eq("RX-AMOX-500"), any(LocalDate.class))).thenReturn(List.of(lot));
        when(batchRepo.decrementStockAtomically(lot.getId(), facilityId, 14)).thenReturn(1);
        when(dispRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PharmacyLisService pharmacy = new PharmacyLisService(
                batchRepo, dispRepo, mock(PharmacyLisService.LabSpecimenRepository.class),
                mock(PharmacyLisService.DiagnosticReportRepository.class), mock(CdsHooksEngine.class), outboxRepo);
        PharmacyDispenseEntity dispensed = pharmacy.dispenseFefo(
                facilityId, patient.getId(), UUID.randomUUID(), "RX-AMOX-500", "RX-AMOX-500", 14, false, "pharm-1", null);
        assertEquals(14, dispensed.getQuantityDispensed());
        assertEquals(36, lot.getQuantityOnHand());

        // 4. Double-Entry Billing Charge & Reconciliation
        UhcBillingService.PatientAccountRepository acctRepo = mock(UhcBillingService.PatientAccountRepository.class);
        UhcBillingService.LedgerEntryRepository ledgerRepo = mock(UhcBillingService.LedgerEntryRepository.class);
        List<LedgerEntryEntity> postedEntries = new ArrayList<>();
        Map<UUID, PatientAccountEntity> accounts = new HashMap<>();

        when(acctRepo.findByFacilityIdAndPatientId(facilityId, patient.getId()))
                .thenAnswer(inv -> accounts.values().stream().filter(a -> a.getPatientId().equals(patient.getId())).findFirst());
        when(acctRepo.save(any())).thenAnswer(inv -> { PatientAccountEntity a = inv.getArgument(0); accounts.put(a.getId(), a); return a; });
        when(ledgerRepo.findByAccountIdAndReferenceCode(any(), anyString())).thenReturn(List.of());
        when(ledgerRepo.save(any())).thenAnswer(inv -> { LedgerEntryEntity e = inv.getArgument(0); postedEntries.add(e); return e; });
        when(ledgerRepo.findByAccountIdOrderByPostedAtAsc(any())).thenReturn(postedEntries);

        UhcBillingService billing = new UhcBillingService(
                acctRepo, ledgerRepo, mock(UhcBillingService.CbhiPolicyRepository.class),
                mock(UhcBillingService.InsuranceClaimRepository.class), mock(UhcBillingService.PaymentTransactionRepository.class),
                mock(UhcBillingService.UhcVoucherRepository.class), outboxRepo, List.of());

        billing.postCharge(facilityId, patient.getId(), 15000L, "XAF", "INV-OPD-101", "OPD visit + Amoxicillin");
        UhcBillingService.AccountReconciliationReport report = billing.generateAccountReconciliationReport(facilityId, patient.getId());
        assertTrue(report.balanced());
        assertEquals(15000L, report.accountBalanceMinor());
    }

    @Test
    void workflow2InpatientAdmissionNursingNews2EmarAndDischargeReadiness() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        // 1. Inpatient Bed Admission & 5-Rights eMAR
        WardBedEntity bed = new WardBedEntity();
        bed.setFacilityId(facilityId);
        bed.setWardCode("WARD-A");
        bed.setBedNumber("A-04");
        bed.setOccupied(false);

        WardBedRepository bedRepo = mock(WardBedRepository.class);
        AdmissionRepository admRepo = mock(AdmissionRepository.class);
        ClinicalOrderRepository orderRepo = mock(ClinicalOrderRepository.class);
        EmarRepository emarRepo = mock(EmarRepository.class);
        WorkforceCredentialSpi credSpi = mock(WorkforceCredentialSpi.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkforceCredentialSpi> credProvider = mock(ObjectProvider.class);
        when(credProvider.getIfAvailable()).thenReturn(credSpi);
        when(credSpi.isPractitionerActive(eq(facilityId), anyString())).thenReturn(true);
        when(bedRepo.findById(bed.getId())).thenReturn(Optional.of(bed));
        when(bedRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(admRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emarRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmrClinicalWorkflowService emr = new EmrClinicalWorkflowService(
                mock(AppointmentRepository.class), mock(EncounterRepository.class), mock(TriageRepository.class),
                mock(VitalSignsRepository.class), mock(ClinicalNoteRepository.class), orderRepo,
                bedRepo, admRepo, emarRepo, mock(CarePlanRepository.class), mock(SpecialtyRecordRepository.class),
                mock(FluidBalanceRepository.class), mock(ShiftHandoverRepository.class),
                mock(MedReconciliationRepository.class), mock(ClinicalReferralRepository.class),
                mock(CdsHooksEngine.class), outboxRepo, credProvider, List.of());

        AdmissionEntity admission = emr.admitPatient(facilityId, encounterId, patientId, bed.getId());
        assertTrue(bed.isOccupied());
        when(admRepo.findById(admission.getId())).thenReturn(Optional.of(admission));

        // 2. Nursing Assignment, NEWS2 Escalation & 5-Gate Discharge Readiness
        EmergencyVisitRepository edRepo = mock(EmergencyVisitRepository.class);
        NursingAssignmentRepository assignRepo = mock(NursingAssignmentRepository.class);
        News2ObservationRepository news2Repo = mock(News2ObservationRepository.class);
        DischargeReadinessRepository dischRepo = mock(DischargeReadinessRepository.class);

        when(assignRepo.countByFacilityIdAndNurseIdAndActiveFlagTrue(facilityId, "rn-ward-1")).thenReturn(2L);
        when(assignRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(news2Repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(dischRepo.findByFacilityIdAndEncounterId(facilityId, encounterId)).thenReturn(Optional.empty());
        when(dischRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EmergencyNursingService nursing = new EmergencyNursingService(edRepo, assignRepo, news2Repo, dischRepo, auditService);
        NursingAssignmentEntity assignment = nursing.assignNurseToPatient(
                new NurseAssignmentRequest("WARD-A", "A-04", patientId, "rn-ward-1", "DAY", 6));
        assertEquals("WARD-A", assignment.getWardCode());

        News2ObservationEntity obs = nursing.recordNews2Observation(
                new News2RecordingRequest(patientId, encounterId, 26, 90, true, 88, 128, 39.2, "A", "rn-ward-1"));
        assertTrue(obs.isEscalationRequired());
        assertTrue(obs.getTotalScore() >= 7);

        // 3. 5-Rights Barcode eMAR Administration
        ClinicalOrderEntity medOrder = new ClinicalOrderEntity();
        medOrder.setFacilityId(facilityId);
        medOrder.setEncounterId(encounterId);
        medOrder.setPatientId(patientId);
        medOrder.setOrderCategory("MEDICATION");
        medOrder.setOrderCode("RX-CEFTRIAXONE-1G");
        medOrder.setDosageOrInstructions("1000 mg IV Q24H");
        medOrder.setStatus("ACTIVE");
        medOrder.setOrderedBy("dr.ngono");
        when(orderRepo.findById(medOrder.getId())).thenReturn(Optional.of(medOrder));

        EmarAdministrationEntity mar = new EmarAdministrationEntity();
        mar.setAdmissionId(admission.getId());
        mar.setOrderId(medOrder.getId());
        mar.setPatientId(patientId);
        mar.setScannedPatientWristband("WB:" + patientId);
        mar.setScannedDrugBarcode("RX:RX-CEFTRIAXONE-1G");
        mar.setDoseAdministered("1000 mg IV");
        mar.setAdministeredBy("rn-ward-1");
        assertNotNull(emr.administerMedicationFiveRights(mar));

        // 4. 5-Gate Discharge Readiness Checklist + Discharge Bed Release
        DischargeReadinessEntity ready = nursing.updateDischargeReadiness(
                new DischargeReadinessRequest(patientId, encounterId, true, true, true, true, true, "dr.ngono"));
        assertTrue(ready.isReadyForDischarge());

        emr.dischargePatient(admission.getId(), "HOME_IMPROVED", "Completed IV antibiotics", LocalDate.now().plusDays(7));
        assertFalse(bed.isOccupied());
    }

    @Test
    void workflow3LaboratoryCriticalResultQcGateAndClosedLoopReadBack() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        // 1. LIS Westgard QC failure blocks release
        DiagnosticReportEntity qcFailed = new DiagnosticReportEntity();
        qcFailed.setFacilityId(facilityId);
        qcFailed.setSpecimenId(UUID.randomUUID());
        qcFailed.setPatientId(patientId);
        qcFailed.setLoincCode("2823-3");
        qcFailed.setTestName("Serum Potassium");
        qcFailed.setNumericValue(new BigDecimal("6.6"));
        qcFailed.setWestgardQcPassed(false);

        PharmacyLisService lis = new PharmacyLisService(
                mock(PharmacyLisService.MedicationBatchRepository.class),
                mock(PharmacyLisService.PharmacyDispenseRepository.class),
                mock(PharmacyLisService.LabSpecimenRepository.class),
                mock(PharmacyLisService.DiagnosticReportRepository.class),
                mock(CdsHooksEngine.class), outboxRepo);
        assertThrows(IllegalStateException.class, () -> lis.verifyLabResult(qcFailed));

        // 2. Unified Orders & Results closed-loop critical acknowledgment with read-back
        UnifiedOrderRepository uOrderRepo = mock(UnifiedOrderRepository.class);
        OrderSpecimenRepository uSpecRepo = mock(OrderSpecimenRepository.class);
        ClinicalResultRepository uResRepo = mock(ClinicalResultRepository.class);
        ReflexOrderRuleRepository uReflexRepo = mock(ReflexOrderRuleRepository.class);

        Map<UUID, UnifiedClinicalOrderEntity> orderStore = new HashMap<>();
        Map<UUID, ClinicalResultEntity> resStore = new HashMap<>();
        when(uOrderRepo.save(any())).thenAnswer(inv -> { UnifiedClinicalOrderEntity o = inv.getArgument(0); orderStore.put(o.getId(), o); return o; });
        when(uOrderRepo.findByIdAndFacilityId(any(), eq(facilityId))).thenAnswer(inv -> Optional.ofNullable(orderStore.get(inv.getArgument(0))));
        when(uSpecRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(uResRepo.save(any())).thenAnswer(inv -> { ClinicalResultEntity r = inv.getArgument(0); resStore.put(r.getId(), r); return r; });
        when(uResRepo.findByIdAndFacilityId(any(), eq(facilityId))).thenAnswer(inv -> Optional.ofNullable(resStore.get(inv.getArgument(0))));
        when(uReflexRepo.findByFacilityIdAndTriggerResultCodeAndTriggerAbnormalFlagAndActiveTrue(any(), any(), any())).thenReturn(List.of());

        UnifiedOrdersResultsService orders = new UnifiedOrdersResultsService(
                mock(OrderSetRepository.class), uOrderRepo, uSpecRepo, uResRepo, uReflexRepo, auditService);

        UnifiedClinicalOrderEntity kOrder = orders.placeOrder(facilityId, new PlaceOrderRequest(
                patientId, encounterId, OrderDomain.LABORATORY, "http://loinc.org",
                "2823-3", "Serum Potassium", OrderPriority.STAT, "Hyperkalemia watch"), "dr.ngono");
        orders.collectSpecimen(facilityId, kOrder.getId(), new CollectSpecimenRequest("ACC-K-99", "SERUM", "OK"), "rn-1");
        ClinicalResultEntity kRes = orders.enterResult(facilityId, new EnterResultRequest(
                kOrder.getId(), "2823-3", "Serum Potassium", new BigDecimal("6.6"), null,
                "mmol/L", "3.5-5.1", AbnormalFlag.CRITICAL_HIGH), "lab.tech1");
        orders.verifyResult(facilityId, kRes.getId(), "lab.director");
        assertTrue(kRes.isCriticalAckRequired());

        ClinicalResultEntity acked = orders.acknowledgeCriticalResult(
                facilityId, kRes.getId(), "dr.ngono", "Verbal read-back confirmed: K+ 6.6 mmol/L; IV calcium gluconate ordered");
        assertNotNull(acked.getCriticalAckAt());
        assertEquals("dr.ngono", acked.getCriticalAckBy());
    }

    @Test
    void workflow4MpiDuplicateDetectionAuthorizedMergeAndReversibleUnmerge() {
        PatientRepository patientRepo = mock(PatientRepository.class);
        PatientPhoneticRepository phoneticRepo = mock(PatientPhoneticRepository.class);
        PatientMergeRepository mergeRepo = mock(PatientMergeRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);

        PatientAggregateEntity p1 = new PatientAggregateEntity();
        p1.setFacilityId(facilityId);
        p1.setMrn("MRN-A");
        p1.setFamilyName("Fotso");
        p1.setGivenName("Jean");
        p1.setBirthDate(LocalDate.of(1975, 2, 14));
        p1.setGender("male");
        p1.setPrimaryPhone("+237677123456");

        PatientAggregateEntity p2 = new PatientAggregateEntity();
        p2.setFacilityId(facilityId);
        p2.setMrn("MRN-B");
        p2.setFamilyName("Fotso");
        p2.setGivenName("Jean");
        p2.setBirthDate(LocalDate.of(1975, 2, 14));
        p2.setGender("male");
        p2.setPrimaryPhone("+237677123456");

        PatientPhoneticIndexEntity block = new PatientPhoneticIndexEntity();
        block.setPatientId(p1.getId());
        block.setFamilyMetaphone(FellegiSunterMatcher.phoneticKey("Fotso"));
        block.setGivenMetaphone(FellegiSunterMatcher.phoneticKey("Jean"));
        block.setBirthYear(1975);

        when(phoneticRepo.findByFamilyMetaphoneAndBirthYearBetween(anyString(), anyInt(), anyInt())).thenReturn(List.of(block));
        when(patientRepo.findByIdIn(List.of(p1.getId()))).thenReturn(List.of(p1));
        when(patientRepo.findById(p1.getId())).thenReturn(Optional.of(p1));
        when(patientRepo.findById(p2.getId())).thenReturn(Optional.of(p2));
        when(patientRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mergeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MpiCommandService mpi = new MpiCommandService(
                patientRepo, mock(PatientIdentifierRepository.class), mock(PatientDemographicsRepository.class),
                phoneticRepo, mergeRepo, outboxRepo, publisher);

        List<FellegiSunterMatcher.MatchCandidate> candidates = mpi.findProbabilisticMatches(
                "Fotso", "Jean", LocalDate.of(1975, 2, 14), "male", "+237677123456");
        assertEquals(1, candidates.size());
        assertTrue(candidates.getFirst().score() >= 0.85);

        PatientMergeHistoryEntity merged = mpi.mergePatients(facilityId, p2.getId(), p1.getId(), "him.officer", "Duplicate registration");
        assertFalse(p2.isGoldenRecord());
        assertEquals(p1.getId(), p2.getSurvivingPatientId());
        verify(publisher).publishEvent(any(PatientMergedApplicationEvent.class));

        when(mergeRepo.findById(merged.getId())).thenReturn(Optional.of(merged));
        mpi.unmergePatients(merged.getId(), "him.lead", "Verified distinct patients");
        assertTrue(p2.isGoldenRecord());
        verify(publisher).publishEvent(any(PatientUnmergedApplicationEvent.class));
    }

    @Test
    void workflow5FhirTransactionAndHl7OrmOruInteropWithTenantIsolation() {
        PatientRepository patientRepo = mock(PatientRepository.class);
        HieInteropService.Hl7OrderRepository hl7OrderRepo = mock(HieInteropService.Hl7OrderRepository.class);
        HieInteropService.Hl7ResultRepository hl7ResultRepo = mock(HieInteropService.Hl7ResultRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(patientRepo.findByFacilityIdAndMrn(eq(facilityId), anyString())).thenReturn(Optional.empty());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(0);
        when(hl7OrderRepo.findByFacilityIdAndPlacerOrderNumber(eq(facilityId), anyString())).thenReturn(Optional.empty());
        when(hl7OrderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hl7ResultRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HieInteropService hie = new HieInteropService(
                patientRepo, mock(HieInteropService.FhirSubscriptionRepository.class),
                mock(HieInteropService.DicomStudyRepository.class), mock(HieInteropService.HieDlqRepository.class),
                mock(HieInteropService.IheDocumentRepository.class), jdbc,
                hl7OrderRepo, hl7ResultRepo,
                mock(HieInteropService.Hl7ScheduleRepository.class),
                mock(HieInteropService.Hl7FinancialTxnRepository.class));

        String txBundle = """
                {
                  "resourceType": "Bundle",
                  "type": "transaction",
                  "entry": [{
                    "resource": {
                      "resourceType": "Patient",
                      "identifier": [{"system": "urn:opes:mrn", "value": "MRN-HIE-500"}],
                      "name": [{"family": "Eboa", "given": ["David"]}],
                      "gender": "male",
                      "birthDate": "1982-08-19"
                    },
                    "request": {"method": "POST", "url": "Patient"}
                  }]
                }
                """;
        String txResp = hie.processFhirTransactionBundle(txBundle);
        assertTrue(txResp.contains("201 Created"));

        String orm = "MSH|^~\\&|CPOE|DLA|OPES|DLA|20261119140000||ORM^O01|WF5-ORM-1|P|2.5.1\r"
                + "PID|1||MRN-HIE-500^^^OPES^MR||Eboa^David||19820819|M\r"
                + "ORC|NW|PLC-WF5|FIL-WF5\r"
                + "OBR|1|PLC-WF5|FIL-WF5|718-7^Hemoglobin^LN\r";
        assertEquals("APPLIED_TO_ORDERS", hie.ingestHl7v2Message(facilityId, orm).get("status"));

        String oru = "MSH|^~\\&|LIS|DLA|OPES|DLA|20261119141000||ORU^R01|WF5-ORU-1|P|2.5.1\r"
                + "PID|1||MRN-HIE-500^^^OPES^MR||Eboa^David||19820819|M\r"
                + "OBR|1|PLC-WF5|FIL-WF5|718-7^Hemoglobin^LN\r"
                + "OBX|1|NM|718-7^Hemoglobin^LN||13.8|g/dL|13.0-17.0|N|||F\r";
        assertEquals("APPLIED_TO_RESULTS", hie.ingestHl7v2Message(facilityId, oru).get("status"));

        FacilityContext.clear();
        assertThrows(AccessDeniedException.class, () -> hie.processFhirTransactionBundle(txBundle));
    }
}
