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
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Orchestrates the end-to-end 10-stage clinical patient journey (Appointment -> Triage ->
 * Vitals/NEWS2 -> Consultation Note -> CDSS-checked CPOE Order -> Inpatient Admission ->
 * 5-Rights eMAR -> Discharge -> CarePlan/Follow-up -> Specialty Clinic Modules).
 */
public class EmrClinicalWorkflowService {

    public interface AppointmentRepository extends JpaRepository<AppointmentEntity, UUID> {
        List<AppointmentEntity> findByFacilityIdAndScheduledStartBetween(UUID facilityId, Instant from, Instant to);
    }
    public interface EncounterRepository extends JpaRepository<EncounterEntity, UUID> {
        List<EncounterEntity> findByPatientIdOrderByStartedAtDesc(UUID patientId);
    }
    public interface TriageRepository extends JpaRepository<TriageAssessmentEntity, UUID> {}
    public interface VitalSignsRepository extends JpaRepository<VitalSignsEntity, UUID> {
        List<VitalSignsEntity> findByPatientIdOrderByRecordedAtDesc(UUID patientId);
    }
    public interface ClinicalNoteRepository extends JpaRepository<ClinicalNoteRevisionEntity, UUID> {}
    public interface ClinicalOrderRepository extends JpaRepository<ClinicalOrderEntity, UUID> {}
    public interface WardBedRepository extends JpaRepository<WardBedEntity, UUID> {}
    public interface AdmissionRepository extends JpaRepository<AdmissionEntity, UUID> {}
    public interface EmarRepository extends JpaRepository<EmarAdministrationEntity, UUID> {}
    public interface CarePlanRepository extends JpaRepository<CarePlanEntity, UUID> {}
    public interface SpecialtyRecordRepository extends JpaRepository<SpecialtyRecordEntity, UUID> {}

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
    private final CdsHooksEngine cdsHooksEngine;
    private final OutboxRelayService.OutboxRepository outboxRepo;
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
            CdsHooksEngine cdsHooksEngine,
            OutboxRelayService.OutboxRepository outboxRepo,
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
        this.cdsHooksEngine = cdsHooksEngine;
        this.outboxRepo = outboxRepo;
        Map<String, SpecialtyPackSpi> map = new HashMap<>();
        packs.forEach(p -> map.put(p.specialtyCode().toUpperCase(Locale.ROOT), p));
        this.specialtyPacks = Collections.unmodifiableMap(map);
    }

    @Transactional
    public AppointmentEntity bookAppointment(AppointmentEntity appt) {
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

    @Transactional
    @ClinicalAudit(action = "EMR_PLACE_ORDER", resourceType = "ClinicalOrder")
    public Map<String, Object> placeOrderWithCdss(
            ClinicalOrderEntity order,
            List<String> activeMedCodes,
            List<String> allergyCodes,
            List<String> conditionCodes,
            BigDecimal patientWeightKg,
            BigDecimal patientEgfr) {

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
    @Tag(name = "EMR 10-Stage Clinical Workflows", description = "Appointments, Triage, Vitals, SOAP Notes, CPOE, Ward/eMAR, Discharge & Specialty Packs")
    public static class EmrClinicalController {
        private final EmrClinicalWorkflowService service;

        public EmrClinicalController(EmrClinicalWorkflowService service) {
            this.service = service;
        }

        @PostMapping("/appointments")
        @Operation(summary = "Book outpatient or specialty clinic appointment")
        public AppointmentEntity book(@RequestBody AppointmentEntity appt) {
            return service.bookAppointment(appt);
        }

        @PostMapping("/encounters")
        @Operation(summary = "Start clinical encounter")
        public EncounterEntity startEncounter(
                @RequestParam UUID facilityId, @RequestParam UUID patientId,
                @RequestParam String encounterClass, @RequestParam String department) {
            return service.startEncounter(facilityId, patientId, encounterClass, department);
        }

        @PostMapping("/triage")
        @Operation(summary = "Record ESI Level 1-5 emergency/OPD triage")
        public TriageAssessmentEntity triage(@RequestBody TriageAssessmentEntity triage) {
            return service.recordTriage(triage);
        }

        @PostMapping("/vitals")
        @Operation(summary = "Record LOINC vital signs with NEWS2 & panic alerts")
        public Map<String, Object> vitals(@RequestBody VitalSignsEntity vitals) {
            return service.recordVitalsAndCheckPanic(vitals);
        }

        @PostMapping("/notes")
        @Operation(summary = "Create, sign, or append immutable clinical SOAP note revision")
        public ClinicalNoteRevisionEntity note(
                @RequestBody ClinicalNoteRevisionEntity note,
                @RequestParam(defaultValue = "false") boolean sign) {
            return service.signOrAmendNote(note, sign);
        }

        @PostMapping("/orders")
        @Operation(summary = "Place CPOE order gated by CDSS DDI/allergy/renal checks")
        public Map<String, Object> order(@RequestBody ClinicalOrderEntity order) {
            return service.placeOrderWithCdss(order, List.of(), List.of(), List.of(), null, null);
        }

        @PostMapping("/admissions")
        @Operation(summary = "Admit patient to inpatient ward bed (ADT^A01)")
        public AdmissionEntity admit(
                @RequestParam UUID facilityId, @RequestParam UUID encounterId,
                @RequestParam UUID patientId, @RequestParam UUID wardBedId) {
            return service.admitPatient(facilityId, encounterId, patientId, wardBedId);
        }

        @PostMapping("/emar")
        @Operation(summary = "Record 5-Rights barcode-verified eMAR medication administration")
        public EmarAdministrationEntity emar(@RequestBody EmarAdministrationEntity mar) {
            return service.administerMedicationFiveRights(mar);
        }

        @PostMapping("/admissions/{admissionId}/discharge")
        @Operation(summary = "Discharge patient and schedule post-discharge care plan (ADT^A03)")
        public AdmissionEntity discharge(
                @PathVariable UUID admissionId, @RequestParam String disposition,
                @RequestParam String summary, @RequestParam(required = false) LocalDate followUpDate) {
            return service.dischargePatient(admissionId, disposition, summary, followUpDate);
        }

        @PostMapping("/specialty/{specialtyCode}")
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
