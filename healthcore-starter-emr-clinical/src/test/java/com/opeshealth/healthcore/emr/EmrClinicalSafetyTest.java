/*
 * =============================================================================
 * Opes HealthCore SDK — EMR Clinical Safety (CLIN-001, CLIN-002, CLIN-003) Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.emr;

import com.opeshealth.healthcore.emr.domain.EmrEntities.*;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.*;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Clinical patient-safety tests for {@link EmrClinicalWorkflowService} verifying:
 * <ul>
 *   <li>CLIN-001: Practitioner credential verification fails closed on expired/suspended/blank clinician IDs</li>
 *   <li>CLIN-002: CPOE medication ordering requires allergy verification (or NKDA/override) and enforces critical CDSS overrides</li>
 *   <li>CLIN-003: 5-Rights Barcode eMAR blocks wristband, drug, dose, route, and inactive-order mismatches</li>
 *   <li>Deterministic RCP NEWS2 Scale 1 scoring thresholds</li>
 * </ul>
 */
class EmrClinicalSafetyTest {

    private AppointmentRepository appointmentRepo;
    private EncounterRepository encounterRepo;
    private TriageRepository triageRepo;
    private VitalSignsRepository vitalsRepo;
    private ClinicalNoteRepository noteRepo;
    private ClinicalOrderRepository orderRepo;
    private WardBedRepository bedRepo;
    private AdmissionRepository admissionRepo;
    private EmarRepository emarRepo;
    private CarePlanRepository carePlanRepo;
    private SpecialtyRecordRepository specialtyRepo;
    private FluidBalanceRepository fluidRepo;
    private ShiftHandoverRepository handoverRepo;
    private MedReconciliationRepository medRecRepo;
    private ClinicalReferralRepository referralRepo;
    private CdsHooksEngine cdsHooksEngine;
    private OutboxRelayService.OutboxRepository outboxRepo;
    private WorkforceCredentialSpi workforceSpi;
    private ObjectProvider<WorkforceCredentialSpi> workforceProvider;
    private EmrClinicalWorkflowService service;
    private UUID facilityId;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        appointmentRepo = mock(AppointmentRepository.class);
        encounterRepo = mock(EncounterRepository.class);
        triageRepo = mock(TriageRepository.class);
        vitalsRepo = mock(VitalSignsRepository.class);
        noteRepo = mock(ClinicalNoteRepository.class);
        orderRepo = mock(ClinicalOrderRepository.class);
        bedRepo = mock(WardBedRepository.class);
        admissionRepo = mock(AdmissionRepository.class);
        emarRepo = mock(EmarRepository.class);
        carePlanRepo = mock(CarePlanRepository.class);
        specialtyRepo = mock(SpecialtyRecordRepository.class);
        fluidRepo = mock(FluidBalanceRepository.class);
        handoverRepo = mock(ShiftHandoverRepository.class);
        medRecRepo = mock(MedReconciliationRepository.class);
        referralRepo = mock(ClinicalReferralRepository.class);
        cdsHooksEngine = mock(CdsHooksEngine.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        workforceSpi = mock(WorkforceCredentialSpi.class);
        workforceProvider = mock(ObjectProvider.class);
        when(workforceProvider.getIfAvailable()).thenReturn(workforceSpi);

        service = new EmrClinicalWorkflowService(
                appointmentRepo, encounterRepo, triageRepo, vitalsRepo, noteRepo, orderRepo,
                bedRepo, admissionRepo, emarRepo, carePlanRepo, specialtyRepo, fluidRepo,
                handoverRepo, medRecRepo, referralRepo, cdsHooksEngine, outboxRepo,
                workforceProvider, List.of());
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void clin001BlocksOrderWhenPractitionerLicenseExpiredOrBlank() {
        when(workforceSpi.isPractitionerActive(facilityId, "dr-expired")).thenReturn(false);

        ClinicalOrderEntity expiredOrder = buildMedicationOrder("RX-100", "500 mg PO", "dr-expired");
        assertThrows(SecurityException.class, () ->
                service.placeOrderWithCdss(expiredOrder, List.of(), List.of(), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));

        ClinicalOrderEntity blankAuthorOrder = buildMedicationOrder("RX-100", "500 mg PO", "   ");
        assertThrows(SecurityException.class, () ->
                service.placeOrderWithCdss(blankAuthorOrder, List.of(), List.of(), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));
    }

    @Test
    void clin002BlocksMedicationOrderWithoutAllergyContextOrNkdaVerification() {
        ClinicalOrderEntity order = buildMedicationOrder("723", "500 mg PO TID", "dr-active");
        CpoeOrderRequest missingAllergyReq = new CpoeOrderRequest(
                order, List.of(), List.of(), BigDecimal.valueOf(72), BigDecimal.valueOf(85), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.placeOrderWithLoadedPatientContext(missingAllergyReq));
        assertTrue(ex.getMessage().contains("MISSING_PATIENT_SAFETY_CONTEXT"));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void clin002EmitsIncompleteContextWarningCardWhenWeightOrEgfrMissingButNkdaVerified() {
        when(workforceSpi.isPractitionerActive(facilityId, "dr-active")).thenReturn(true);
        when(orderRepo.findByFacilityIdAndPatientIdAndOrderCategoryAndStatus(
                eq(facilityId), any(UUID.class), eq("MEDICATION"), eq("ACTIVE"))).thenReturn(List.of());
        when(vitalsRepo.findByPatientIdOrderByRecordedAtDesc(any(UUID.class))).thenReturn(List.of());
        when(cdsHooksEngine.evaluateOrderSafety(any())).thenReturn(List.of());
        when(orderRepo.save(any(ClinicalOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClinicalOrderEntity order = buildMedicationOrder("723", "500 mg PO TID", "dr-active");
        CpoeOrderRequest nkdaReq = new CpoeOrderRequest(order, List.of(), List.of(), null, null, true);

        Map<String, Object> result = service.placeOrderWithLoadedPatientContext(nkdaReq);

        @SuppressWarnings("unchecked")
        List<CdsHooksEngine.CdsCard> cards = (List<CdsHooksEngine.CdsCard>) result.get("cdssCards");
        assertEquals(1, cards.size());
        assertEquals("INCOMPLETE_SAFETY_CONTEXT", cards.getFirst().uuid());
        assertEquals("warning", cards.getFirst().indicator());
        assertFalse(cards.getFirst().overrideRequired());
        verify(outboxRepo).save(any(TransactionalOutboxEntity.class));
    }

    @Test
    void clin002RequiresClinicalOverrideReasonForCriticalCdssBlocker() {
        when(workforceSpi.isPractitionerActive(facilityId, "dr-active")).thenReturn(true);
        CdsHooksEngine.CdsCard critical = new CdsHooksEngine.CdsCard(
                UUID.randomUUID().toString(),
                "Drug-Allergy Cross-Reactivity Alert",
                "Penicillin allergy contraindicates Amoxicillin",
                "critical",
                "Opes HealthCore CDSS",
                List.of("Switch to Macrolide"),
                true);
        when(cdsHooksEngine.evaluateOrderSafety(any())).thenReturn(List.of(critical));
        when(orderRepo.save(any(ClinicalOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ClinicalOrderEntity unoverridden = buildMedicationOrder("723", "500 mg PO", "dr-active");
        assertThrows(IllegalStateException.class, () ->
                service.placeOrderWithCdss(unoverridden, List.of(), List.of("PENICILLIN"), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));

        unoverridden.setCdssOverrideReason("Desensitization protocol approved by ID attending");
        Map<String, Object> allowed = service.placeOrderWithCdss(
                unoverridden, List.of(), List.of("PENICILLIN"), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90));
        assertNotNull(allowed.get("order"));
    }

    @Test
    void clin003FiveRightsEmarRejectsMismatchesAndAcceptsValidScan() {
        when(workforceSpi.isPractitionerActive(facilityId, "rn-01")).thenReturn(true);

        UUID patientId = UUID.randomUUID();
        UUID admissionId = UUID.randomUUID();
        ClinicalOrderEntity order = buildMedicationOrder("RXNORM-197361", "500 mg PO", "dr-active");
        order.setPatientId(patientId);
        order.setStatus("ACTIVE");

        AdmissionEntity adm = new AdmissionEntity();
        adm.setFacilityId(facilityId);
        adm.setPatientId(patientId);
        adm.setEncounterId(order.getEncounterId());

        when(orderRepo.findById(order.getId())).thenReturn(Optional.of(order));
        when(admissionRepo.findById(admissionId)).thenReturn(Optional.of(adm));
        when(emarRepo.save(any(EmarAdministrationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Wrong wristband (Right Patient)
        EmarAdministrationEntity wrongPatient = buildEmar(admissionId, order.getId(), patientId,
                "WB:" + UUID.randomUUID(), "RX:RXNORM-197361", "500 mg PO", "rn-01");
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(wrongPatient));

        // 2. Wrong drug barcode (Right Drug)
        EmarAdministrationEntity wrongDrug = buildEmar(admissionId, order.getId(), patientId,
                "WB:" + patientId, "RX:WRONG-DRUG", "500 mg PO", "rn-01");
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(wrongDrug));

        // 3. Wrong dose (Right Dose)
        EmarAdministrationEntity wrongDose = buildEmar(admissionId, order.getId(), patientId,
                "WB:" + patientId, "RX:RXNORM-197361", "1000 mg PO", "rn-01");
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(wrongDose));

        // 4. Wrong route (Right Route)
        EmarAdministrationEntity wrongRoute = buildEmar(admissionId, order.getId(), patientId,
                "WB:" + patientId, "RX:RXNORM-197361", "500 mg IV", "rn-01");
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(wrongRoute));

        // 5. All 5 Rights satisfied
        EmarAdministrationEntity valid = buildEmar(admissionId, order.getId(), patientId,
                "WB:" + patientId, "RX:RXNORM-197361", "500 mg PO", "rn-01");
        EmarAdministrationEntity saved = service.administerMedicationFiveRights(valid);
        assertEquals(order.getId(), saved.getOrderId());
        verify(emarRepo).save(valid);
    }

    @Test
    void news2ComputesNormalAndCriticalDeteriorationAccurately() {
        VitalSignsEntity normal = new VitalSignsEntity();
        normal.setRespRatePerMin(16);
        normal.setSpo2Percent(98);
        normal.setSystolicBp(120);
        normal.setHeartRateBpm(72);
        normal.setTempCelsius(new BigDecimal("37.0"));
        assertEquals(0, service.computeNews2(normal, false, "A"));

        VitalSignsEntity critical = new VitalSignsEntity();
        critical.setRespRatePerMin(28);   // +3
        critical.setSpo2Percent(89);      // +3
        critical.setSystolicBp(85);       // +3
        critical.setHeartRateBpm(136);    // +3
        critical.setTempCelsius(new BigDecimal("39.5")); // +2
        // +2 supplemental O2 + 3 confusion = 19
        assertEquals(19, service.computeNews2(critical, true, "C"));
    }

    @Test
    void deniesCrossFacilityAppointmentsOrdersAndAdmissionsBeforePersistence() {
        UUID foreignFacility = UUID.randomUUID();
        FacilityContext.setFacilityId(foreignFacility);

        AppointmentEntity appointment = new AppointmentEntity();
        appointment.setFacilityId(facilityId);
        appointment.setPatientId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.bookAppointment(appointment));

        ClinicalOrderEntity order = buildMedicationOrder("RX-123", "500 mg PO", "dr-active");
        assertThrows(SecurityException.class, () -> service.placeOrderWithCdss(
                order, List.of(), List.of(), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));

        UUID encounterId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        assertThrows(SecurityException.class,
                () -> service.admitPatient(facilityId, encounterId, patientId, UUID.randomUUID()));

        verify(appointmentRepo, never()).save(any());
        verify(orderRepo, never()).save(any());
        verify(admissionRepo, never()).save(any());
    }

    @Test
    void deniesForeignWardBedAndUnconfiguredPractitionerVerifier() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID bedId = UUID.randomUUID();

        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(patientId);
        when(encounterRepo.findById(encounterId)).thenReturn(Optional.of(encounter));

        WardBedEntity foreignBed = new WardBedEntity();
        foreignBed.setFacilityId(UUID.randomUUID());
        when(bedRepo.findById(bedId)).thenReturn(Optional.of(foreignBed));
        assertThrows(SecurityException.class,
                () -> service.admitPatient(facilityId, encounterId, patientId, bedId));
        verify(admissionRepo, never()).save(any());

        when(workforceProvider.getIfAvailable()).thenReturn(null);
        ClinicalOrderEntity order = buildMedicationOrder("RX-123", "500 mg PO", "dr-active");
        assertThrows(SecurityException.class, () -> service.placeOrderWithCdss(
                order, List.of(), List.of(), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));
        verify(orderRepo, never()).save(any());
    }

    @Test
    void rejectsCrossPatientEncounterAtOrderSignatureBeforeCdssOrOutbox() {
        when(workforceSpi.isPractitionerActive(facilityId, "dr-active")).thenReturn(true);
        ClinicalOrderEntity order = buildMedicationOrder("RX-123", "500 mg PO", "dr-active");
        EncounterEntity foreignPatientEncounter = new EncounterEntity();
        foreignPatientEncounter.setFacilityId(facilityId);
        foreignPatientEncounter.setPatientId(UUID.randomUUID());
        when(encounterRepo.findById(order.getEncounterId()))
                .thenReturn(Optional.of(foreignPatientEncounter));

        assertThrows(SecurityException.class, () -> service.placeOrderWithCdss(
                order, List.of(), List.of(), List.of(), BigDecimal.valueOf(70), BigDecimal.valueOf(90)));
        assertThrows(SecurityException.class, () -> service.placeOrderWithLoadedPatientContext(
                new CpoeOrderRequest(order, List.of(), List.of(), BigDecimal.valueOf(70),
                        BigDecimal.valueOf(90), true)));
        verify(orderRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
        verifyNoInteractions(cdsHooksEngine);
    }

    @Test
    void medicationAdministrationRequiresPresentMatchingActiveAdmission() {
        when(workforceSpi.isPractitionerActive(facilityId, "rn-01")).thenReturn(true);
        ClinicalOrderEntity order = buildMedicationOrder("RX-123", "500 mg PO", "dr-active");
        UUID admissionId = UUID.randomUUID();
        when(orderRepo.findById(order.getId())).thenReturn(Optional.of(order));
        EmarAdministrationEntity administration = buildEmar(
                admissionId, order.getId(), order.getPatientId(),
                "WB:" + order.getPatientId(), "RX:RX-123", "500 mg PO", "rn-01");

        // Absence of an inpatient record must never be treated as passing five-rights.
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(administration));

        AdmissionEntity otherVisit = new AdmissionEntity();
        otherVisit.setFacilityId(facilityId);
        otherVisit.setPatientId(order.getPatientId());
        otherVisit.setEncounterId(UUID.randomUUID());
        when(admissionRepo.findById(admissionId)).thenReturn(Optional.of(otherVisit));
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(administration));

        otherVisit.setEncounterId(order.getEncounterId());
        otherVisit.setFacilityId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.administerMedicationFiveRights(administration));

        otherVisit.setFacilityId(facilityId);
        otherVisit.setDischargedAt(java.time.Instant.now());
        assertThrows(IllegalStateException.class, () -> service.administerMedicationFiveRights(administration));

        verify(emarRepo, never()).save(any());
    }

    @Test
    void news2FailsClosedWhenCoreVitalsOrConsciousnessAreMissing() {
        VitalSignsEntity incomplete = new VitalSignsEntity();
        incomplete.setHeartRateBpm(70);
        assertThrows(IllegalArgumentException.class, () -> service.computeNews2(incomplete));
        assertThrows(IllegalArgumentException.class, () -> service.computeNews2(null));

        VitalSignsEntity otherwiseComplete = new VitalSignsEntity();
        otherwiseComplete.setHeartRateBpm(70);
        otherwiseComplete.setRespRatePerMin(16);
        otherwiseComplete.setSpo2Percent(98);
        otherwiseComplete.setSystolicBp(120);
        otherwiseComplete.setTempCelsius(BigDecimal.valueOf(37));
        assertThrows(IllegalArgumentException.class,
                () -> service.computeNews2(otherwiseComplete, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.computeNews2(otherwiseComplete, false, "NOT_AN_ACVPU_LEVEL"));
        // This overload requires oxygen and consciousness to be recorded, not inferred.
        assertThrows(IllegalArgumentException.class, () -> service.computeNews2(otherwiseComplete));
        otherwiseComplete.setSupplementalOxygen(false);
        otherwiseComplete.setConsciousnessAcvpu("A");
        assertEquals(0, service.computeNews2(otherwiseComplete));
        otherwiseComplete.setConsciousnessAcvpu("INVALID");
        assertThrows(IllegalArgumentException.class, () -> service.computeNews2(otherwiseComplete));
        assertEquals(0, service.computeNews2(otherwiseComplete, false, "A"));
    }

    @Test
    void clinicalReferralCannotCrossTenantsAndCompletionIsReplaySafe() {
        UUID patient = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(patient);
        when(encounterRepo.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(referralRepo.save(any(ClinicalReferralEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClinicalReferralEntity referral = new ClinicalReferralEntity();
        referral.setFacilityId(facilityId);
        referral.setEncounterId(encounterId);
        referral.setPatientId(patient);
        referral.setTargetFacility("Synthetic specialist clinic");
        referral.setSpecialty("CARDIOLOGY");
        referral.setReason("Synthetic specialist review");
        ClinicalReferralEntity created = service.createReferral(referral);
        when(referralRepo.findById(created.getId())).thenReturn(Optional.of(created));

        FacilityContext.setFacilityId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.completeReferral(
                created.getId(), "Specialist assessment complete"));
        FacilityContext.setFacilityId(facilityId);
        ClinicalReferralEntity completed = service.completeReferral(
                created.getId(), "Specialist assessment complete");
        java.time.Instant recordedAt = completed.getCompletedAt();
        assertNotNull(recordedAt);
        assertSame(completed, service.completeReferral(
                created.getId(), "Specialist assessment complete"));
        assertEquals(recordedAt, completed.getCompletedAt());
        assertThrows(IllegalStateException.class, () -> service.completeReferral(
                created.getId(), "Conflicting second outcome"));
        verify(referralRepo, times(2)).save(created); // creation and first closure only
    }

    @Test
    void dischargeCannotReleaseForeignBedAndSecondCallNeverDuplicatesFollowup() {
        UUID patient = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        AdmissionEntity adm = new AdmissionEntity();
        adm.setFacilityId(facilityId);
        adm.setEncounterId(encounterId);
        adm.setPatientId(patient);
        WardBedEntity bed = new WardBedEntity();
        bed.setFacilityId(facilityId);
        adm.setWardBedId(bed.getId());
        bed.setOccupied(true);
        bed.setActiveAdmissionId(adm.getId());

        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(patient);
        when(encounterRepo.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(admissionRepo.findById(adm.getId())).thenReturn(Optional.of(adm));
        when(bedRepo.findById(bed.getId())).thenReturn(Optional.of(bed));

        FacilityContext.setFacilityId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.dischargePatient(
                adm.getId(), "HOME", "Synthetic discharge", null));
        FacilityContext.setFacilityId(facilityId);

        bed.setActiveAdmissionId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.dischargePatient(
                adm.getId(), "HOME", "Synthetic discharge", null));
        bed.setActiveAdmissionId(adm.getId());
        assertThrows(IllegalArgumentException.class, () -> service.dischargePatient(
                adm.getId(), "HOME", " ", null));

        AdmissionEntity discharged = service.dischargePatient(
                adm.getId(), "HOME", "Synthetic discharge and follow-up", null);
        assertNotNull(discharged.getDischargedAt());
        assertFalse(bed.isOccupied());
        java.time.Instant dischargedAt = discharged.getDischargedAt();
        assertSame(discharged, service.dischargePatient(
                adm.getId(), "HOME", "A repeated callback", null));
        assertEquals(dischargedAt, discharged.getDischargedAt());
        verify(admissionRepo, times(1)).save(adm);
        verify(bedRepo, times(1)).save(bed);
        verify(carePlanRepo, times(1)).save(any(CarePlanEntity.class));
        verify(outboxRepo, times(1)).save(any(TransactionalOutboxEntity.class));
    }

    @Test
    void inpatientNursingRecordsRequireActiveMatchingAdmission() {
        UUID patient = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        AdmissionEntity adm = new AdmissionEntity();
        adm.setFacilityId(facilityId);
        adm.setEncounterId(encounterId);
        adm.setPatientId(patient);
        when(admissionRepo.findById(adm.getId())).thenReturn(Optional.of(adm));
        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(patient);
        when(encounterRepo.findById(encounterId)).thenReturn(Optional.of(encounter));

        FluidBalanceEntity fluid = new FluidBalanceEntity();
        fluid.setFacilityId(facilityId);
        fluid.setAdmissionId(adm.getId());
        fluid.setPatientId(UUID.randomUUID());
        fluid.setVolumeMl(200);
        assertThrows(SecurityException.class, () -> service.recordFluidBalance(fluid));

        fluid.setPatientId(patient);
        adm.setDischargedAt(java.time.Instant.now());
        assertThrows(IllegalStateException.class, () -> service.recordFluidBalance(fluid));
        adm.setDischargedAt(null);

        ShiftHandoverEntity handover = new ShiftHandoverEntity();
        handover.setFacilityId(UUID.randomUUID());
        handover.setAdmissionId(adm.getId());
        assertThrows(SecurityException.class, () -> service.recordShiftHandover(handover));

        MedReconciliationEntity reconciliation = new MedReconciliationEntity();
        reconciliation.setFacilityId(facilityId);
        reconciliation.setAdmissionId(adm.getId());
        reconciliation.setPatientId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.reconcileMedication(reconciliation));

        verify(fluidRepo, never()).save(any());
        verify(handoverRepo, never()).save(any());
        verify(medRecRepo, never()).save(any());
    }

    @Test
    void cosignRequiresSamePatientFacilityAndPendingCosignStatus() {
        UUID encounterId = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        ClinicalNoteRevisionEntity note = new ClinicalNoteRevisionEntity();
        note.setEncounterId(encounterId);
        note.setPatientId(patient);
        note.setAuthorId("resident-01");
        note.setNoteType("SOAP");
        note.setStatus("PENDING_COSIGN");
        when(noteRepo.findById(note.getId())).thenReturn(Optional.of(note));

        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(patient);
        when(encounterRepo.findById(encounterId)).thenReturn(Optional.of(encounter));
        when(workforceSpi.isPractitionerActive(facilityId, "dr-active")).thenReturn(true);
        when(noteRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        FacilityContext.setFacilityId(UUID.randomUUID());
        assertThrows(SecurityException.class, () -> service.cosignNote(note.getId(), "dr-active"));
        FacilityContext.setFacilityId(facilityId);

        ClinicalNoteRevisionEntity signed = service.cosignNote(note.getId(), "dr-active");
        assertEquals("SIGNED", signed.getStatus());
        assertEquals("dr-active", signed.getCosignedBy());
        assertThrows(IllegalStateException.class, () -> service.cosignNote(note.getId(), "dr-active"));
        verify(noteRepo, times(1)).save(note);
    }

    private ClinicalOrderEntity buildMedicationOrder(String code, String dosage, String orderedBy) {
        ClinicalOrderEntity order = new ClinicalOrderEntity();
        order.setFacilityId(facilityId);
        order.setEncounterId(UUID.randomUUID());
        order.setPatientId(UUID.randomUUID());
        EncounterEntity encounter = new EncounterEntity();
        encounter.setFacilityId(facilityId);
        encounter.setPatientId(order.getPatientId());
        when(encounterRepo.findById(order.getEncounterId())).thenReturn(Optional.of(encounter));
        order.setOrderCategory("MEDICATION");
        order.setCodeSystem("RxNorm");
        order.setOrderCode(code);
        order.setOrderDisplay("Medication " + code);
        order.setDosageOrInstructions(dosage);
        order.setStatus("ACTIVE");
        order.setOrderedBy(orderedBy);
        return order;
    }

    private EmarAdministrationEntity buildEmar(
            UUID admissionId, UUID orderId, UUID patientId,
            String wristband, String drugBarcode, String dose, String nurseId) {
        EmarAdministrationEntity mar = new EmarAdministrationEntity();
        mar.setAdmissionId(admissionId);
        mar.setOrderId(orderId);
        mar.setPatientId(patientId);
        mar.setScannedPatientWristband(wristband);
        mar.setScannedDrugBarcode(drugBarcode);
        mar.setDoseAdministered(dose);
        mar.setAdministeredBy(nurseId);
        return mar;
    }
}
