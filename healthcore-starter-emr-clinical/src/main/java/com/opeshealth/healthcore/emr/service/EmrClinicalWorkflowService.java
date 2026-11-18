/*
 * =============================================================================
 * Opes HealthCore SDK — 10-Stage Clinical Workflow Service & REST Gateway
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.emr.domain.EmrEntities.*;
import com.opeshealth.healthcore.emr.specialty.SpecialtyPackSpi;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientMergedApplicationEvent;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Orchestrates the end-to-end 10-stage clinical patient journey:
 * Appointment State Machine -> ESI Triage -> LOINC Vitals/NEWS2 -> Signed & Co-Signed SOAP Notes ->
 * CDSS/Workforce-Gated CPOE Orders -> Inpatient Admission & ADT^A02 Bed Transfer ->
 * 5-Rights eMAR, Fluid I&O Balance & SBAR Shift Handovers -> Discharge & Med Reconciliation ->
 * CarePlans & Closed-Loop Referrals -> Specialty Clinic Packs.
 */
public class EmrClinicalWorkflowService {

    public interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {
        List<AppointmentEntity> findByFacilityIdAndScheduledStartBetween(UUID facilityId, Instant from, Instant to);
        @Modifying
        @Query("UPDATE AppointmentEntity a SET a.patientId = :survivorId WHERE a.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface EncounterRepository extends JpaRepository<EncounterEntity, UUID> {
        List<EncounterEntity> findByPatientIdOrderByStartedAtDesc(UUID patientId);
        @Modifying
        @Query("UPDATE EncounterEntity e SET e.patientId = :survivorId WHERE e.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface TriageRepository extends JpaRepository<TriageAssessmentEntity, UUID> {}
    public interface VitalSignsRepository extends JpaRepository<VitalSignsEntity, UUID> {
        List<VitalSignsEntity> findByPatientIdOrderByRecordedAtDesc(UUID patientId);
    }
    public interface ClinicalNoteRepository extends JpaRepository<ClinicalNoteRevisionEntity, UUID> {}
    public interface ClinicalOrderRepository extends JpaRepository<ClinicalOrderEntity, UUID> {
        @Modifying
        @Query("UPDATE ClinicalOrderEntity o SET o.patientId = :survivorId WHERE o.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface WardBedRepository extends JpaRepository<WardBedEntity, UUID> {}
    public interface AdmissionRepository extends JpaRepository<AdmissionEntity, UUID> {}
    public interface EmarRepository extends JpaRepository<EmarAdministrationEntity, UUID> {}
    public interface CarePlanRepository extends JpaRepository<CarePlanEntity, UUID> {
        @Modifying
        @Query("UPDATE CarePlanEntity c SET c.patientId = :survivorId WHERE c.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface SpecialtyRecordRepository extends JpaRepository<SpecialtyRecordEntity, UUID> {}
    public interface FluidBalanceRepository extends JpaRepository<FluidBalanceEntity, UUID> {
        List<FluidBalanceEntity> findByAdmissionIdOrderByRecordedAtDesc(UUID admissionId);
    }
    public interface ShiftHandoverRepository extends JpaRepository<ShiftHandoverEntity, UUID> {
        List<ShiftHandoverEntity> findByAdmissionIdOrderByRecordedAtDesc(UUID admissionId);
    }
    public interface MedReconciliationRepository extends JpaRepository<MedReconciliationEntity, UUID> {
        List<MedReconciliationEntity> findByAdmissionIdOrderByReconciledAtDesc(UUID admissionId);
    }
    public interface ClinicalReferralRepository extends JpaRepository<ClinicalReferralEntity, UUID> {
        List<ClinicalReferralEntity> findByPatientIdOrderByCreatedAtDesc(UUID patientId);
    }

    private static final Map<String, Set<String>> APPOINTMENT_TRANSITIONS = Map.of(
            "REQUESTED", Set.of("CONFIRMED", "CANCELLED"),
            "BOOKED", Set.of("CONFIRMED", "CHECKED_IN", "CANCELLED"),
            "CONFIRMED", Set.of("CHECKED_IN", "NO_SHOW", "CANCELLED"),
            "CHECKED_IN", Set.of("IN_QUEUE", "NO_SHOW", "CANCELLED"),
            "IN_QUEUE", Set.of("COMPLETED", "NO_SHOW"),
            "COMPLETED", Set.of(),
            "NO_SHOW", Set.of(),
            "CANCELLED", Set.of()
    );

    private final AppointmentRepository appointmentRepo;
    private final EncounterRepository encounterRepo;
    private final TriageRepository triageRepo;
    private final VitalSignsRepository vitalsRepo;
    private final ClinicalNoteRepository noteRepo;
    private final ClinicalOrderRepository orderRepo;
    private final WardBedRepository bedRepo;
    private final AdmissionRepository admissionRepo;
    private final EmarRepository emarRepo;
    private final CarePlanRepository carePlanRepo;
    private final SpecialtyRecordRepository specialtyRepo;
    private final FluidBalanceRepository fluidRepo;
    private final ShiftHandoverRepository handoverRepo;
    private final MedReconciliationRepository medRecRepo;
    private final ClinicalReferralRepository referralRepo;
    private final CdsHooksEngine cdsHooksEngine;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final ObjectProvider<WorkforceCredentialSpi> workforceCredentialProvider;
    private final Map<String, SpecialtyPackSpi> specialtyPacks;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmrClinicalWorkflowService(
            AppointmentRepository appointmentRepo,
            EncounterRepository encounterRepo,
            TriageRepository triageRepo,
            VitalSignsRepository vitalsRepo,
            ClinicalNoteRepository noteRepo,
            ClinicalOrderRepository orderRepo,
            WardBedRepository bedRepo,
            AdmissionRepository admissionRepo,
            EmarRepository emarRepo,
            CarePlanRepository carePlanRepo,
            SpecialtyRecordRepository specialtyRepo,
            FluidBalanceRepository fluidRepo,
            ShiftHandoverRepository handoverRepo,
            MedReconciliationRepository medRecRepo,
            ClinicalReferralRepository referralRepo,
            CdsHooksEngine cdsHooksEngine,
            OutboxRelayService.OutboxRepository outboxRepo,
            ObjectProvider<WorkforceCredentialSpi> workforceCredentialProvider,
            List<SpecialtyPackSpi> packs) {
        this.appointmentRepo = appointmentRepo;
        this.encounterRepo = encounterRepo;
        this.triageRepo = triageRepo;
        this.vitalsRepo = vitalsRepo;
        this.noteRepo = noteRepo;
        this.orderRepo = orderRepo;
        this.bedRepo = bedRepo;
        this.admissionRepo = admissionRepo;
        this.emarRepo = emarRepo;
        this.carePlanRepo = carePlanRepo;
        this.specialtyRepo = specialtyRepo;
        this.fluidRepo = fluidRepo;
        this.handoverRepo = handoverRepo;
        this.medRecRepo = medRecRepo;
        this.referralRepo = referralRepo;
        this.cdsHooksEngine = cdsHooksEngine;
        this.outboxRepo = outboxRepo;
        this.workforceCredentialProvider = workforceCredentialProvider;
        Map<String, SpecialtyPackSpi> map = new HashMap<>();
        packs.forEach(p -> map.put(p.specialtyCode().toUpperCase(Locale.ROOT), p));
        this.specialtyPacks = Collections.unmodifiableMap(map);
    }

    /** Automatically re-links longitudinal clinical records when MPI merges duplicate patients. */
    @EventListener
    @Transactional
    public void onPatientMerged(PatientMergedApplicationEvent event) {
        appointmentRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
        encounterRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
        orderRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
        carePlanRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
    }

    @Transactional
    public AppointmentEntity bookAppointment(AppointmentEntity appt) {
        if (appt.getStatus() == null || appt.getStatus().isBlank()) {
            appt.setStatus("REQUESTED");
        }
        return appointmentRepo.save(appt);
    }

    /** Enforces strict appointment workflow: REQUESTED -> CONFIRMED -> CHECKED_IN -> IN_QUEUE -> COMPLETED | NO_SHOW. */
    @Transactional
    public AppointmentEntity transitionAppointment(UUID appointmentId, String nextStatus) {
        AppointmentEntity appt = appointmentRepo.findById(appointmentId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        String current = appt.getStatus().toUpperCase(Locale.ROOT);
        String target = nextStatus.toUpperCase(Locale.ROOT);
        Set<String> allowed = APPOINTMENT_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException("Invalid appointment transition " + current + " -> " + target);
        }
        appt.setStatus(target);
        return appointmentRepo.save(appt);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_START_ENCOUNTER", resourceType = "Encounter")
    public EncounterEntity startEncounter(UUID facilityId, UUID patientId, String encounterClass, String department) {
        EncounterEntity enc = new EncounterEntity();
        enc.setFacilityId(facilityId);
        enc.setPatientId(patientId);
        enc.setEncounterClass(encounterClass);
        enc.setDepartmentCode(department);
        enc.setStatus("IN_PROGRESS");
        return encounterRepo.save(enc);
    }

    @Transactional
    public TriageAssessmentEntity recordTriage(TriageAssessmentEntity triage) {
        if (triage.getEsiLevel() < 1 || triage.getEsiLevel() > 5) {
            throw new IllegalArgumentException("ESI triage level must be between 1 (Resuscitation) and 5 (Non-urgent)");
        }
        return triageRepo.save(triage);
    }

    @Transactional
    public Map<String, Object> recordVitalsAndCheckPanic(VitalSignsEntity vitals) {
        int news2 = computeNews2(vitals);
        vitals.setNews2Score(news2);
        VitalSignsEntity saved = vitalsRepo.save(vitals);
        List<CdsHooksEngine.CdsCard> alerts = cdsHooksEngine.evaluateVitalsAndLabPanic(
                new CdsHooksEngine.VitalsPanicRequest(
                        vitals.getTempCelsius(), vitals.getHeartRateBpm(), vitals.getRespRatePerMin(),
                        vitals.getSystolicBp(), vitals.getSpo2Percent(), null, null));
        return Map.of("vitals", saved, "cdssCards", alerts);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_SIGN_NOTE", resourceType = "ClinicalNote")
    public ClinicalNoteRevisionEntity signOrAmendNote(ClinicalNoteRevisionEntity note, boolean signNow) {
        if (signNow) {
            UUID facId = encounterRepo.findById(note.getEncounterId())
                    .map(EncounterEntity::getFacilityId)
                    .or(FacilityContext::getFacilityId)
                    .orElse(null);
            enforceActivePractitionerLicense(facId, note.getAuthorId());
        }
        if (note.getParentNoteId() != null) {
            ClinicalNoteRevisionEntity parent = noteRepo.findById(note.getParentNoteId())
                    .orElseThrow(() -> new NoSuchElementException("Parent note not found"));
            note.setRevisionNumber(parent.getRevisionNumber() + 1);
            note.setNoteType("ADDENDUM");
        }
        if (signNow) {
            note.setStatus("SIGNED");
            note.setSignedAt(Instant.now());
        }
        return noteRepo.save(note);
    }

    /** Attending physician co-signs a resident/trainee clinical note pending co-signature. */
    @Transactional
    @ClinicalAudit(action = "EMR_COSIGN_NOTE", resourceType = "ClinicalNote")
    public ClinicalNoteRevisionEntity cosignNote(UUID noteId, String attendingId) {
        ClinicalNoteRevisionEntity note = noteRepo.findById(noteId)
                .orElseThrow(() -> new NoSuchElementException("Clinical note not found: " + noteId));
        UUID facId = encounterRepo.findById(note.getEncounterId())
                .map(EncounterEntity::getFacilityId)
                .or(FacilityContext::getFacilityId)
                .orElse(null);
        enforceActivePractitionerLicense(facId, attendingId);
        note.setCosignedBy(attendingId);
        note.setStatus("SIGNED");
        note.setSignedAt(Instant.now());
        return noteRepo.save(note);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_PLACE_ORDER", resourceType = "ClinicalOrder")
    public Map<String, Object> placeOrderWithCdss(
            ClinicalOrderEntity order,
            List<String> activeMedCodes,
            List<String> allergyCodes,
            List<String> conditionCodes,
            BigDecimal patientWeightKg,
            BigDecimal patientEgfr) {

        enforceActivePractitionerLicense(order.getFacilityId(), order.getOrderedBy());

        List<CdsHooksEngine.CdsCard> cards = cdsHooksEngine.evaluateOrderSafety(
                new CdsHooksEngine.OrderSafetyRequest(
                        order.getPatientId(), patientWeightKg, patientEgfr, false,
                        activeMedCodes, allergyCodes, conditionCodes, order.getOrderCode(), null));

        boolean hasCriticalBlocker = cards.stream().anyMatch(CdsHooksEngine.CdsCard::overrideRequired);
        if (hasCriticalBlocker && (order.getCdssOverrideReason() == null || order.getCdssOverrideReason().isBlank())) {
            throw new IllegalStateException("Critical CDSS safety alert requires explicit clinical overrideReason: "
                    + cards.getFirst().detail());
        }

        ClinicalOrderEntity saved = orderRepo.save(order);
        emitOutbox("ClinicalOrder", saved.getId(),
                "MEDICATION".equals(saved.getOrderCategory()) ? "MedicationOrderedEvent" : "DiagnosticOrderedEvent",
                "{\"orderId\":\"" + saved.getId() + "\",\"patientId\":\"" + saved.getPatientId() + "\"}");
        return Map.of("order", saved, "cdssCards", cards);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_ADMIT_INPATIENT", resourceType = "Admission")
    public AdmissionEntity admitPatient(UUID facilityId, UUID encounterId, UUID patientId, UUID wardBedId) {
        WardBedEntity bed = bedRepo.findById(wardBedId)
                .orElseThrow(() -> new NoSuchElementException("Ward bed not found: " + wardBedId));
        if (bed.isOccupied()) {
            throw new IllegalStateException("Ward bed " + bed.getBedNumber() + " is already occupied");
        }
        AdmissionEntity adm = new AdmissionEntity();
        adm.setFacilityId(facilityId);
        adm.setEncounterId(encounterId);
        adm.setPatientId(patientId);
        adm.setWardBedId(wardBedId);
        adm = admissionRepo.save(adm);

        bed.setOccupied(true);
        bed.setActiveAdmissionId(adm.getId());
        bedRepo.save(bed);
        emitOutbox("Admission", adm.getId(), "PatientAdmittedEvent", "{\"admissionId\":\"" + adm.getId() + "\"}");
        return adm;
    }

    /** Executes ADT^A02 Bed/Ward Transfer freeing the previous bed and locking the target bed. */
    @Transactional
    @ClinicalAudit(action = "EMR_TRANSFER_BED", resourceType = "Admission")
    public AdmissionEntity transferBed(UUID admissionId, UUID newWardBedId) {
        AdmissionEntity adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new NoSuchElementException("Admission not found: " + admissionId));
        if (adm.getDischargedAt() != null) {
            throw new IllegalStateException("Cannot transfer bed for an already discharged admission");
        }
        WardBedEntity targetBed = bedRepo.findById(newWardBedId)
                .orElseThrow(() -> new NoSuchElementException("Target bed not found: " + newWardBedId));
        if (targetBed.isOccupied()) {
            throw new IllegalStateException("Target ward bed " + targetBed.getBedNumber() + " is already occupied");
        }
        bedRepo.findById(adm.getWardBedId()).ifPresent(oldBed -> {
            oldBed.setOccupied(false);
            oldBed.setActiveAdmissionId(null);
            bedRepo.save(oldBed);
        });
        targetBed.setOccupied(true);
        targetBed.setActiveAdmissionId(adm.getId());
        bedRepo.save(targetBed);

        adm.setWardBedId(newWardBedId);
        AdmissionEntity saved = admissionRepo.save(adm);
        emitOutbox("Admission", saved.getId(), "PatientBedTransferredEvent",
                "{\"admissionId\":\"" + saved.getId() + "\",\"newWardBedId\":\"" + newWardBedId + "\"}");
        return saved;
    }

    /** Enforces 5-Rights Barcode Medication Administration (eMAR). */
    @Transactional
    @ClinicalAudit(action = "EMR_ADMINISTER_EMAR", resourceType = "EmarAdministration")
    public EmarAdministrationEntity administerMedicationFiveRights(EmarAdministrationEntity mar) {
        if (!mar.getPatientId().toString().equals(mar.getScannedPatientWristband())) {
            throw new SecurityException("5-Rights Check FAILED: Wristband barcode does not match patient ID");
        }
        return emarRepo.save(mar);
    }

    @Transactional
    public FluidBalanceEntity recordFluidBalance(FluidBalanceEntity entry) {
        if (entry.getVolumeMl() <= 0) {
            throw new IllegalArgumentException("Fluid volume must be > 0 mL");
        }
        return fluidRepo.save(entry);
    }

    @Transactional
    public ShiftHandoverEntity recordShiftHandover(ShiftHandoverEntity handover) {
        return handoverRepo.save(handover);
    }

    @Transactional
    public MedReconciliationEntity reconcileMedication(MedReconciliationEntity medRec) {
        return medRecRepo.save(medRec);
    }

    @Transactional
    public ClinicalReferralEntity createReferral(ClinicalReferralEntity referral) {
        ClinicalReferralEntity saved = referralRepo.save(referral);
        emitOutbox("ClinicalReferral", saved.getId(), "ServiceRequestCreatedEvent",
                "{\"referralId\":\"" + saved.getId() + "\",\"targetFacility\":\"" + saved.getTargetFacility() + "\"}");
        return saved;
    }

    @Transactional
    public ClinicalReferralEntity completeReferral(UUID referralId, String outcomeNotes) {
        ClinicalReferralEntity ref = referralRepo.findById(referralId)
                .orElseThrow(() -> new NoSuchElementException("Referral not found: " + referralId));
        ref.setStatus("COMPLETED");
        ref.setOutcomeNotes(outcomeNotes);
        ref.setCompletedAt(Instant.now());
        return referralRepo.save(ref);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_DISCHARGE_PATIENT", resourceType = "Admission")
    public AdmissionEntity dischargePatient(UUID admissionId, String disposition, String summary, LocalDate followUpDate) {
        AdmissionEntity adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new NoSuchElementException("Admission not found"));
        adm.setDischargedAt(Instant.now());
        adm.setDischargeDisposition(disposition);
        adm.setDischargeSummary(summary);
        admissionRepo.save(adm);

        bedRepo.findById(adm.getWardBedId()).ifPresent(bed -> {
            bed.setOccupied(false);
            bed.setActiveAdmissionId(null);
            bedRepo.save(bed);
        });

        CarePlanEntity followUp = new CarePlanEntity();
        followUp.setFacilityId(adm.getFacilityId());
        followUp.setPatientId(adm.getPatientId());
        followUp.setCareCategory("POST_DISCHARGE");
        followUp.setGoalSummary("Post-discharge follow-up: " + disposition);
        followUp.setNextFollowupDate(followUpDate != null ? followUpDate : LocalDate.now().plusDays(7));
        carePlanRepo.save(followUp);

        emitOutbox("Admission", adm.getId(), "PatientDischargedEvent", "{\"admissionId\":\"" + adm.getId() + "\"}");
        return adm;
    }

    @Transactional
    public SpecialtyRecordEntity recordSpecialtyExam(
            UUID facilityId, UUID encounterId, UUID patientId, String specialtyCode,
            Map<String, Object> payload, String recordedBy) {
        SpecialtyPackSpi pack = specialtyPacks.get(specialtyCode.toUpperCase(Locale.ROOT));
        if (pack == null) {
            throw new IllegalArgumentException("Unsupported specialty pack: " + specialtyCode);
        }
        pack.validatePayload(payload);
        try {
            SpecialtyRecordEntity rec = new SpecialtyRecordEntity();
            rec.setFacilityId(facilityId);
            rec.setEncounterId(encounterId);
            rec.setPatientId(patientId);
            rec.setSpecialtyCode(pack.specialtyCode());
            rec.setStructuredPayloadJson(objectMapper.writeValueAsString(payload));
            rec.setRecordedBy(recordedBy);
            return specialtyRepo.save(rec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize specialty exam payload", ex);
        }
    }

    private void enforceActivePractitionerLicense(UUID facilityId, String practitionerId) {
        WorkforceCredentialSpi spi = workforceCredentialProvider.getIfAvailable();
        if (spi != null && facilityId != null && practitionerId != null
                && !spi.isPractitionerActive(facilityId, practitionerId)) {
            throw new SecurityException("Clinical action blocked: Practitioner credential/license is expired or suspended for " + practitionerId);
        }
    }

    private int computeNews2(VitalSignsEntity v) {
        int s = 0;
        if (v.getSpo2Percent() != null && v.getSpo2Percent() <= 91) s += 3;
        if (v.getSystolicBp() != null && v.getSystolicBp() <= 90) s += 3;
        if (v.getHeartRateBpm() != null && (v.getHeartRateBpm() >= 131 || v.getHeartRateBpm() <= 40)) s += 3;
        if (v.getRespRatePerMin() != null && (v.getRespRatePerMin() >= 25 || v.getRespRatePerMin() <= 8)) s += 3;
        if (v.getTempCelsius() != null && v.getTempCelsius().compareTo(new BigDecimal("39.1")) >= 0) s += 2;
        return s;
    }

    private void emitOutbox(String type, UUID id, String eventType, String payload) {
        TransactionalOutboxEntity e = new TransactionalOutboxEntity();
        e.setIdempotencyKey(type + ":" + eventType + ":" + id + ":" + e.getId());
        e.setAggregateType(type);
        e.setAggregateId(id);
        e.setEventType(eventType);
        e.setPayloadJson(payload);
        outboxRepo.save(e);
    }

    @RestController
    @RequestMapping("/api/v1/emr")
    @Tag(name = "EMR 10-Stage Clinical Workflows", description = "Appointments, Triage, Vitals, SOAP Notes, CPOE, Ward/eMAR, I&O, SBAR, MedRec, Referrals & Specialty Packs")
    public static class EmrClinicalController {
        private final EmrClinicalWorkflowService service;

        public EmrClinicalController(EmrClinicalWorkflowService service) {
            this.service = service;
        }

        @PostMapping("/appointments")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_self:appointment:request')")
        @Operation(summary = "Book outpatient or specialty clinic appointment")
        public AppointmentEntity book(@RequestBody AppointmentEntity appt) {
            return service.bookAppointment(appt);
        }

        @PostMapping("/appointments/{appointmentId}/transition")
        @Operation(summary = "Advance appointment state (CONFIRMED -> CHECKED_IN -> IN_QUEUE -> COMPLETED | NO_SHOW)")
        public AppointmentEntity transition(@PathVariable UUID appointmentId, @RequestParam String nextStatus) {
            return service.transitionAppointment(appointmentId, nextStatus);
        }

        @PostMapping("/encounters")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_triage:write')")
        @Operation(summary = "Start clinical encounter")
        public EncounterEntity startEncounter(
                @RequestParam UUID facilityId, @RequestParam UUID patientId,
                @RequestParam String encounterClass, @RequestParam String department) {
            return service.startEncounter(facilityId, patientId, encounterClass, department);
        }

        @PostMapping("/triage")
        @PreAuthorize("hasAuthority('PERM_triage:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Record ESI Level 1-5 emergency/OPD triage")
        public TriageAssessmentEntity triage(@RequestBody TriageAssessmentEntity triage) {
            return service.recordTriage(triage);
        }

        @PostMapping("/vitals")
        @PreAuthorize("hasAuthority('PERM_vitals:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Record LOINC vital signs with NEWS2 & panic alerts")
        public Map<String, Object> vitals(@RequestBody VitalSignsEntity vitals) {
            return service.recordVitalsAndCheckPanic(vitals);
        }

        @PostMapping("/notes")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft')")
        @Operation(summary = "Create, sign, or append immutable clinical SOAP note revision")
        public ClinicalNoteRevisionEntity note(
                @RequestBody ClinicalNoteRevisionEntity note,
                @RequestParam(defaultValue = "false") boolean sign) {
            return service.signOrAmendNote(note, sign);
        }

        @PostMapping("/notes/{noteId}/cosign")
        @PreAuthorize("hasAuthority('PERM_note:cosign')")
        @Operation(summary = "Attending physician co-signs trainee clinical note")
        public ClinicalNoteRevisionEntity cosign(@PathVariable UUID noteId, @RequestParam String attendingId) {
            return service.cosignNote(noteId, attendingId);
        }

        @PostMapping("/orders")
        @PreAuthorize("hasAuthority('PERM_order:sign') or hasAuthority('PERM_rx:prescribe')")
        @Operation(summary = "Place CPOE order gated by CDSS DDI/allergy/renal & practitioner license checks")
        public Map<String, Object> order(@RequestBody ClinicalOrderEntity order) {
            return service.placeOrderWithCdss(order, List.of(), List.of(), List.of(), null, null);
        }

        @PostMapping("/admissions")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_ward:handover')")
        @Operation(summary = "Admit patient to inpatient ward bed (ADT^A01)")
        public AdmissionEntity admit(
                @RequestParam UUID facilityId, @RequestParam UUID encounterId,
                @RequestParam UUID patientId, @RequestParam UUID wardBedId) {
            return service.admitPatient(facilityId, encounterId, patientId, wardBedId);
        }

        @PostMapping("/admissions/{admissionId}/transfer-bed")
        @PreAuthorize("hasAuthority('PERM_ward:handover') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Transfer admitted inpatient to a new ward bed (ADT^A02)")
        public AdmissionEntity transferBed(@PathVariable UUID admissionId, @RequestParam UUID newWardBedId) {
            return service.transferBed(admissionId, newWardBedId);
        }

        @PostMapping("/emar")
        @PreAuthorize("hasAuthority('PERM_emar:administer')")
        @Operation(summary = "Record 5-Rights barcode-verified eMAR medication administration")
        public EmarAdministrationEntity emar(@RequestBody EmarAdministrationEntity mar) {
            return service.administerMedicationFiveRights(mar);
        }

        @PostMapping("/ward/fluid-balance")
        @PreAuthorize("hasAuthority('PERM_io:write') or hasAuthority('PERM_emar:administer')")
        @Operation(summary = "Record inpatient nursing Intake & Output (I&O) fluid balance")
        public FluidBalanceEntity fluidBalance(@RequestBody FluidBalanceEntity entry) {
            return service.recordFluidBalance(entry);
        }

        @PostMapping("/ward/shift-handover")
        @PreAuthorize("hasAuthority('PERM_ward:handover') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Record structured SBAR nursing/physician shift handover")
        public ShiftHandoverEntity shiftHandover(@RequestBody ShiftHandoverEntity handover) {
            return service.recordShiftHandover(handover);
        }

        @PostMapping("/admissions/{admissionId}/med-reconciliation")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_rx:dispense')")
        @Operation(summary = "Record inpatient-to-home discharge medication reconciliation")
        public MedReconciliationEntity medRec(@PathVariable UUID admissionId, @RequestBody MedReconciliationEntity medRec) {
            medRec.setAdmissionId(admissionId);
            return service.reconcileMedication(medRec);
        }

        @PostMapping("/referrals")
        @PreAuthorize("hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Create closed-loop FHIR ServiceRequest clinical referral")
        public ClinicalReferralEntity createReferral(@RequestBody ClinicalReferralEntity referral) {
            return service.createReferral(referral);
        }

        @PostMapping("/referrals/{referralId}/complete")
        @PreAuthorize("hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Close clinical referral loop with receiving specialist outcome summary")
        public ClinicalReferralEntity completeReferral(@PathVariable UUID referralId, @RequestParam String outcomeNotes) {
            return service.completeReferral(referralId, outcomeNotes);
        }

        @PostMapping("/admissions/{admissionId}/discharge")
        @PreAuthorize("hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Discharge patient and schedule post-discharge care plan (ADT^A03)")
        public AdmissionEntity discharge(
                @PathVariable UUID admissionId, @RequestParam String disposition,
                @RequestParam String summary, @RequestParam(required = false) LocalDate followUpDate) {
            return service.dischargePatient(admissionId, disposition, summary, followUpDate);
        }

        @PostMapping("/specialty/{specialtyCode}")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_specialty:dental:write') or hasAuthority('PERM_specialty:eye:write') or hasAuthority('PERM_specialty:cardiac:write') or hasAuthority('PERM_specialty:peds:write') or hasAuthority('PERM_specialty:derm:write')")
        @Operation(summary = "Record structured exam using Dental/Eye/Cardiac/Pediatrics/Dermatology pack")
        public SpecialtyRecordEntity specialtyExam(
                @PathVariable String specialtyCode,
                @RequestParam UUID facilityId, @RequestParam UUID encounterId,
                @RequestParam UUID patientId, @RequestParam String recordedBy,
                @RequestBody Map<String, Object> payload) {
            return service.recordSpecialtyExam(facilityId, encounterId, patientId, specialtyCode, payload, recordedBy);
        }
    }
}
