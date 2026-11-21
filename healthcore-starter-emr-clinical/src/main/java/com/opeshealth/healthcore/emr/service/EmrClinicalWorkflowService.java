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
        List<ClinicalOrderEntity> findByFacilityIdAndPatientIdAndOrderCategoryAndStatus(
                UUID facilityId, UUID patientId, String orderCategory, String status);
        @Modifying
        @Query("UPDATE ClinicalOrderEntity o SET o.patientId = :survivorId WHERE o.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }

    /** Structured CPOE request supplying explicit patient clinical context alongside persisted EMR data. */
    public record CpoeOrderRequest(
            ClinicalOrderEntity order,
            List<String> allergyCodes,
            List<String> conditionCodes,
            BigDecimal patientWeightKg,
            BigDecimal patientEgfr,
            boolean allergiesVerifiedAsNone
    ) {}
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
        Objects.requireNonNull(appt, "Appointment required");
        assertActiveFacility(appt.getFacilityId());
        if (appt.getPatientId() == null) {
            throw new IllegalArgumentException("Appointment patient identity required");
        }
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
        assertActiveFacility(appt.getFacilityId());
        String current = appt.getStatus().toUpperCase(Locale.ROOT);
        String target = nextStatus.toUpperCase(Locale.ROOT);
        Set<String> allowed = APPOINTMENT_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException("Invalid appointment transition " + current + " -> " + target);
        }
        appt.setStatus(target);
        return appointmentRepo.save(appt);
    }

    private void assertActiveFacility(UUID facilityId) {
        UUID authenticatedFacility = FacilityContext.getFacilityId()
                .orElseThrow(() -> new SecurityException("Authenticated facility context required"));
        if (facilityId == null || !authenticatedFacility.equals(facilityId)) {
            throw new SecurityException("Cross-facility clinical operation denied");
        }
    }

    /** Enforces the authenticated facility and patient link for encounter-scoped clinical actions. */
    private EncounterEntity requireEncounterInActiveFacility(UUID encounterId, UUID patientId) {
        UUID activeFacility = FacilityContext.getFacilityId()
                .orElseThrow(() -> new SecurityException("Authenticated facility context required"));
        if (encounterId == null || patientId == null) {
            throw new IllegalArgumentException("Encounter and patient identifiers are required");
        }
        EncounterEntity encounter = encounterRepo.findById(encounterId)
                .orElseThrow(() -> new SecurityException("Encounter not accessible in active facility"));
        if (!activeFacility.equals(encounter.getFacilityId())
                || !patientId.equals(encounter.getPatientId())) {
            throw new SecurityException("Encounter, patient and authenticated facility must match");
        }
        return encounter;
    }

    @Transactional
    @ClinicalAudit(action = "EMR_START_ENCOUNTER", resourceType = "Encounter")
    public EncounterEntity startEncounter(UUID facilityId, UUID patientId, String encounterClass, String department) {
        UUID activeFacility = FacilityContext.getFacilityId()
                .orElseThrow(() -> new SecurityException("Authenticated facility context required"));
        if (facilityId == null || !facilityId.equals(activeFacility)) {
            throw new SecurityException("Cannot register an encounter in another facility");
        }
        if (patientId == null || encounterClass == null || encounterClass.isBlank()
                || department == null || department.isBlank()) {
            throw new IllegalArgumentException("Patient, encounter class and department are required");
        }
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
        Objects.requireNonNull(triage, "Triage record required");
        if (triage.getEsiLevel() < 1 || triage.getEsiLevel() > 5) {
            throw new IllegalArgumentException("ESI triage level must be between 1 (Resuscitation) and 5 (Non-urgent)");
        }
        if (triage.getChiefComplaint() == null || triage.getChiefComplaint().isBlank()
                || triage.getTriagedBy() == null || triage.getTriagedBy().isBlank()) {
            throw new IllegalArgumentException("Triage complaint and accountable clinician are required");
        }
        UUID activeFacility = FacilityContext.getFacilityId()
                .orElseThrow(() -> new SecurityException("Authenticated facility context required"));
        EncounterEntity encounter = encounterRepo.findById(triage.getEncounterId())
                .orElseThrow(() -> new SecurityException("Triage encounter inaccessible"));
        if (!activeFacility.equals(encounter.getFacilityId())) {
            throw new SecurityException("Cannot triage an encounter in another facility");
        }
        return triageRepo.save(triage);
    }

    @Transactional
    public Map<String, Object> recordVitalsAndCheckPanic(VitalSignsEntity vitals) {
        Objects.requireNonNull(vitals, "Vital signs record required");
        requireEncounterInActiveFacility(vitals.getEncounterId(), vitals.getPatientId());
        String acvpu = vitals.getConsciousnessAcvpu();
        if (acvpu != null && !acvpu.isBlank()
                && !Set.of("A", "ALERT", "C", "V", "P", "U")
                        .contains(acvpu.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported ACVPU level; use A, C, V, P, or U");
        }
        boolean news2Complete = vitals.getTempCelsius() != null
                && vitals.getHeartRateBpm() != null && vitals.getRespRatePerMin() != null
                && vitals.getSystolicBp() != null && vitals.getSpo2Percent() != null
                && vitals.getSupplementalOxygen() != null
                && acvpu != null && !acvpu.isBlank();
        // Do not persist an apparently normal NEWS2 score when essential measurements are missing.
        vitals.setNews2Score(news2Complete ? computeNews2(vitals) : null);
        VitalSignsEntity saved = vitalsRepo.save(vitals);
        List<CdsHooksEngine.CdsCard> alerts = new ArrayList<>(cdsHooksEngine.evaluateVitalsAndLabPanic(
                new CdsHooksEngine.VitalsPanicRequest(
                        vitals.getTempCelsius(), vitals.getHeartRateBpm(), vitals.getRespRatePerMin(),
                        vitals.getSystolicBp(), vitals.getSpo2Percent(), null, null)));
        if (!news2Complete) {
            alerts.add(new CdsHooksEngine.CdsCard("NEWS2_INCOMPLETE_VITALS",
                    "NEWS2 cannot be calculated from incomplete observations",
                    "Record respiratory rate, oxygen saturation, blood pressure, pulse, temperature, "
                            + "supplemental oxygen status and ACVPU; assess clinically until complete.",
                    "warning", "Opes HealthCore Clinical Safety",
                    List.of("Complete observations and reassess acuity"), false));
        }
        return Map.of("vitals", saved, "cdssCards", List.copyOf(alerts), "news2Complete", news2Complete);
    }

    @Transactional
    @ClinicalAudit(action = "EMR_SIGN_NOTE", resourceType = "ClinicalNote")
    public ClinicalNoteRevisionEntity signOrAmendNote(ClinicalNoteRevisionEntity note, boolean signNow) {
        Objects.requireNonNull(note, "Clinical note required");
        EncounterEntity encounter = requireEncounterInActiveFacility(note.getEncounterId(), note.getPatientId());
        if (note.getAuthorId() == null || note.getAuthorId().isBlank()
                || note.getNoteType() == null || note.getNoteType().isBlank()) {
            throw new IllegalArgumentException("Clinical note type and author are required");
        }
        if (noteRepo.findById(note.getId()).filter(existing -> "SIGNED".equals(existing.getStatus())).isPresent()) {
            throw new IllegalStateException("Signed clinical notes cannot be overwritten; create a linked addendum");
        }
        if (note.getParentNoteId() != null) {
            ClinicalNoteRevisionEntity parent = noteRepo.findById(note.getParentNoteId())
                    .orElseThrow(() -> new NoSuchElementException("Parent note not found"));
            if (!parent.getEncounterId().equals(note.getEncounterId())
                    || !parent.getPatientId().equals(note.getPatientId())
                    || !"SIGNED".equals(parent.getStatus())) {
                throw new SecurityException("Addendum must reference a signed note in the same encounter");
            }
            note.setRevisionNumber(parent.getRevisionNumber() + 1);
            note.setNoteType("ADDENDUM");
        }
        if (signNow) {
            enforceActivePractitionerLicense(encounter.getFacilityId(), note.getAuthorId());
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

    /**
     * Loads persisted patient clinical context (active medications and latest recorded weight)
     * merged with explicit allergy/condition/eGFR context from the CPOE request, enforcing a
     * fail-safe missing-context policy before executing CDSS order safety evaluation.
     */
    @Transactional
    @ClinicalAudit(action = "EMR_PLACE_ORDER", resourceType = "ClinicalOrder")
    public Map<String, Object> placeOrderWithLoadedPatientContext(CpoeOrderRequest request) {
        Objects.requireNonNull(request, "CpoeOrderRequest is required");
        ClinicalOrderEntity order = Objects.requireNonNull(request.order(), "ClinicalOrderEntity is required");
        assertActiveFacility(order.getFacilityId());
        if (order.getPatientId() == null || order.getEncounterId() == null) {
            throw new IllegalArgumentException("Clinical order must identify the patient and encounter");
        }
        requireEncounterInActiveFacility(order.getEncounterId(), order.getPatientId());

        boolean isMedication = "MEDICATION".equalsIgnoreCase(order.getOrderCategory());
        boolean allergyProfileProvided = request.allergyCodes() != null && !request.allergyCodes().isEmpty();
        if (isMedication && !allergyProfileProvided && !request.allergiesVerifiedAsNone()
                && (order.getCdssOverrideReason() == null || order.getCdssOverrideReason().isBlank())) {
            throw new IllegalStateException(
                    "MISSING_PATIENT_SAFETY_CONTEXT: Medication order blocked because patient allergy profile "
                            + "was neither supplied nor explicitly verified as NKDA (allergiesVerifiedAsNone=true). "
                            + "Provide allergy context or document an emergency clinical overrideReason.");
        }

        List<String> persistedActiveMeds = orderRepo != null
                ? orderRepo.findByFacilityIdAndPatientIdAndOrderCategoryAndStatus(
                        order.getFacilityId(), order.getPatientId(), "MEDICATION", "ACTIVE")
                        .stream()
                        .map(ClinicalOrderEntity::getOrderCode)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList()
                : List.of();

        BigDecimal resolvedWeight = request.patientWeightKg();
        if (resolvedWeight == null && vitalsRepo != null && order.getPatientId() != null) {
            resolvedWeight = vitalsRepo.findByPatientIdOrderByRecordedAtDesc(order.getPatientId()).stream()
                    .map(VitalSignsEntity::getWeightKg)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        List<String> allergies = request.allergyCodes() != null ? request.allergyCodes() : List.of();
        List<String> conditions = request.conditionCodes() != null ? request.conditionCodes() : List.of();
        Map<String, Object> result = placeOrderWithCdss(
                order, persistedActiveMeds, allergies, conditions, resolvedWeight, request.patientEgfr());

        if (isMedication && (resolvedWeight == null || request.patientEgfr() == null)) {
            List<CdsHooksEngine.CdsCard> augmented = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<CdsHooksEngine.CdsCard> baseCards = (List<CdsHooksEngine.CdsCard>) result.get("cdssCards");
            if (baseCards != null) {
                augmented.addAll(baseCards);
            }
            augmented.add(new CdsHooksEngine.CdsCard(
                    "INCOMPLETE_SAFETY_CONTEXT",
                    "Missing renal/weight context for medication safety check",
                    "Order placed without "
                            + (resolvedWeight == null ? "patient weight" : "")
                            + (resolvedWeight == null && request.patientEgfr() == null ? " and " : "")
                            + (request.patientEgfr() == null ? "recent eGFR" : "")
                            + "; verify renal and weight-based dosing manually.",
                    "warning",
                    "Opes HealthCore EMR Safety Context",
                    List.of("Verify renal function (eGFR) and current weight before administration"),
                    false
            ));
            return Map.of("order", result.get("order"), "cdssCards", List.copyOf(augmented));
        }
        return result;
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

        Objects.requireNonNull(order, "Clinical order required");
        assertActiveFacility(order.getFacilityId());
        if (order.getPatientId() == null || order.getEncounterId() == null) {
            throw new IllegalArgumentException("Clinical order patient and encounter are mandatory");
        }
        // Validate the persisted encounter and patient together. A valid JWT facility alone
        // must not permit attaching prescriptions to another patient's visit.
        requireEncounterInActiveFacility(order.getEncounterId(), order.getPatientId());
        enforceActivePractitionerLicense(order.getFacilityId(), order.getOrderedBy());

        List<CdsHooksEngine.CdsCard> cards = cdsHooksEngine.evaluateOrderSafety(
                new CdsHooksEngine.OrderSafetyRequest(
                        order.getPatientId(), patientWeightKg, patientEgfr, false,
                        activeMedCodes != null ? activeMedCodes : List.of(),
                        allergyCodes != null ? allergyCodes : List.of(),
                        conditionCodes != null ? conditionCodes : List.of(),
                        order.getOrderCode(), null));

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
        assertActiveFacility(facilityId);
        requireEncounterInActiveFacility(encounterId, patientId);
        WardBedEntity bed = bedRepo.findById(wardBedId)
                .orElseThrow(() -> new NoSuchElementException("Ward bed not found: " + wardBedId));
        if (!facilityId.equals(bed.getFacilityId())) {
            throw new SecurityException("Bed belongs to a different facility");
        }
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
        assertActiveFacility(adm.getFacilityId());
        if (adm.getDischargedAt() != null) {
            throw new IllegalStateException("Cannot transfer bed for an already discharged admission");
        }
        WardBedEntity targetBed = bedRepo.findById(newWardBedId)
                .orElseThrow(() -> new NoSuchElementException("Target bed not found: " + newWardBedId));
        if (!adm.getFacilityId().equals(targetBed.getFacilityId())) {
            throw new SecurityException("Target bed belongs to a different facility");
        }
        if (targetBed.isOccupied()) {
            throw new IllegalStateException("Target ward bed " + targetBed.getBedNumber() + " is already occupied");
        }
        WardBedEntity oldBed = bedRepo.findById(adm.getWardBedId())
                .orElseThrow(() -> new IllegalStateException("Existing admission bed not found"));
        if (!adm.getFacilityId().equals(oldBed.getFacilityId())
                || !adm.getId().equals(oldBed.getActiveAdmissionId())) {
            throw new SecurityException("Existing bed ownership does not match active admission");
        }
        oldBed.setOccupied(false);
        oldBed.setActiveAdmissionId(null);
        bedRepo.save(oldBed);
        targetBed.setOccupied(true);
        targetBed.setActiveAdmissionId(adm.getId());
        bedRepo.save(targetBed);

        adm.setWardBedId(newWardBedId);
        AdmissionEntity saved = admissionRepo.save(adm);
        emitOutbox("Admission", saved.getId(), "PatientBedTransferredEvent",
                "{\"admissionId\":\"" + saved.getId() + "\",\"newWardBedId\":\"" + newWardBedId + "\"}");
        return saved;
    }

    /**
     * Enforces 5-Rights Barcode Medication Administration (eMAR) against the persisted
     * {@link ClinicalOrderEntity} and {@link AdmissionEntity}:
     * <ol>
     *   <li><strong>Right Patient:</strong> Issued wristband identifier matches the ordered patient
     *       and the active inpatient admission.</li>
     *   <li><strong>Right Medication:</strong> Scanned drug barcode matches the active medication
     *       order's normalized medication code (RxNorm / NDC / GTIN code token).</li>
     *   <li><strong>Right Dose:</strong> Administered dose is non-blank and consistent with the
     *       ordered dose quantity when specified on the order.</li>
     *   <li><strong>Right Route:</strong> When an administration route ({@code PO}, {@code IV},
     *       {@code IM}, {@code SC}, {@code TOPICAL}, {@code INH}) is documented on both order and
     *       eMAR payload, they must match.</li>
     *   <li><strong>Right Time &amp; Authority:</strong> Order must be {@code ACTIVE}, admission
     *       must not be discharged, administration timestamp cannot pre-date order timestamp, and
     *       administering clinician license must be active.</li>
     * </ol>
     *
     * <p><strong>Clinical scope notice:</strong> This check validates structured identifiers against
     * stored EMR orders and does not replace GS1/barcode scanner hardware validation or institutional
     * nursing verification SOPs.</p>
     */
    @Transactional
    @ClinicalAudit(action = "EMR_ADMINISTER_EMAR", resourceType = "EmarAdministration")
    public EmarAdministrationEntity administerMedicationFiveRights(EmarAdministrationEntity mar) {
        Objects.requireNonNull(mar, "EmarAdministrationEntity is required");
        if (mar.getPatientId() == null || mar.getOrderId() == null || mar.getAdmissionId() == null) {
            throw new IllegalArgumentException("5-Rights Check FAILED: patientId, orderId, and admissionId are mandatory");
        }
        if (mar.getScannedPatientWristband() == null || mar.getScannedPatientWristband().isBlank()) {
            throw new SecurityException("5-Rights Check FAILED: Scanned patient wristband barcode is required");
        }
        if (mar.getScannedDrugBarcode() == null || mar.getScannedDrugBarcode().isBlank()) {
            throw new SecurityException("5-Rights Check FAILED: Scanned medication barcode is required");
        }
        if (mar.getDoseAdministered() == null || mar.getDoseAdministered().isBlank()) {
            throw new IllegalArgumentException("5-Rights Check FAILED: Administered dose is required");
        }
        if (mar.getAdministeredBy() == null || mar.getAdministeredBy().isBlank()) {
            throw new SecurityException("5-Rights Check FAILED: Administering clinician ID is required");
        }

        // Right Patient wristband token verification (accepts issued "WB:<patientId>" or exact patientId token)
        String wristband = mar.getScannedPatientWristband().trim();
        String expectedId = mar.getPatientId().toString();
        if (!wristband.equals(expectedId) && !wristband.equals("WB:" + expectedId)) {
            throw new SecurityException("5-Rights Check FAILED (Right Patient): Wristband barcode does not match patient ID");
        }

        // Load persisted medication order and verify Right Patient, Right Order Status, Right Drug, Right Dose, Right Route, Right Time
        ClinicalOrderEntity order = orderRepo.findById(mar.getOrderId())
                .orElseThrow(() -> new NoSuchElementException(
                        "5-Rights Check FAILED: Medication order not found: " + mar.getOrderId()));

        assertActiveFacility(order.getFacilityId());
        if (!"MEDICATION".equalsIgnoreCase(order.getOrderCategory())) {
            throw new IllegalStateException("5-Rights Check FAILED: Referenced order is not a MEDICATION order");
        }
        if (!"ACTIVE".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalStateException("5-Rights Check FAILED (Right Time): Order status is " + order.getStatus() + ", not ACTIVE");
        }
        if (!order.getPatientId().equals(mar.getPatientId())) {
            throw new SecurityException("5-Rights Check FAILED (Right Patient): Order belongs to a different patient");
        }
        enforceActivePractitionerLicense(order.getFacilityId(), mar.getAdministeredBy());

        // Right Drug verification against active order code
        String scannedDrug = mar.getScannedDrugBarcode().trim().toUpperCase(Locale.ROOT);
        String orderedCode = order.getOrderCode() != null ? order.getOrderCode().trim().toUpperCase(Locale.ROOT) : "";
        boolean drugMatches = !orderedCode.isEmpty() && (scannedDrug.equals(orderedCode)
                || scannedDrug.equals("RX:" + orderedCode)
                || scannedDrug.equals("NDC:" + orderedCode)
                || scannedDrug.equals("GTIN:" + orderedCode)
                || scannedDrug.endsWith(":" + orderedCode));
        if (!drugMatches) {
            throw new SecurityException("5-Rights Check FAILED (Right Drug): Scanned drug barcode '"
                    + mar.getScannedDrugBarcode() + "' does not match ordered medication code '" + order.getOrderCode() + "'");
        }

        // Right Dose & Right Route consistency check when order specifies dosage/route
        if (order.getDosageOrInstructions() != null && !order.getDosageOrInstructions().isBlank()) {
            String orderedDose = extractFirstDoseQuantity(order.getDosageOrInstructions());
            String givenDose = extractFirstDoseQuantity(mar.getDoseAdministered());
            if (orderedDose != null && givenDose != null && !orderedDose.equals(givenDose)) {
                throw new SecurityException("5-Rights Check FAILED (Right Dose): Administered dose '"
                        + givenDose + "' does not match ordered dose '" + orderedDose + "'");
            }
            String orderedRoute = extractRouteToken(order.getDosageOrInstructions());
            String givenRoute = extractRouteToken(mar.getDoseAdministered());
            if (orderedRoute != null && givenRoute != null && !orderedRoute.equals(givenRoute)) {
                throw new SecurityException("5-Rights Check FAILED (Right Route): Administered route '"
                        + givenRoute + "' does not match ordered route '" + orderedRoute + "'");
            }
        }

        // Right Time & active admission check
        if (order.getOrderedAt() != null && mar.getAdministeredAt() != null
                && mar.getAdministeredAt().isBefore(order.getOrderedAt())) {
            throw new IllegalStateException("5-Rights Check FAILED (Right Time): Administration timestamp precedes order creation");
        }
        // A missing or foreign admission must never be treated as a successful 5-Rights check.
        AdmissionEntity admission = admissionRepo.findById(mar.getAdmissionId())
                .orElseThrow(() -> new SecurityException(
                        "5-Rights Check FAILED: Active inpatient admission is required"));
        assertActiveFacility(admission.getFacilityId());
        if (!mar.getPatientId().equals(admission.getPatientId())
                || !order.getEncounterId().equals(admission.getEncounterId())) {
            throw new SecurityException(
                    "5-Rights Check FAILED (Right Patient): Order, encounter and admission do not match");
        }
        if (admission.getDischargedAt() != null) {
            throw new IllegalStateException(
                    "5-Rights Check FAILED (Right Time): Cannot administer medication after discharge");
        }

        return emarRepo.save(mar);
    }

    private static String extractFirstDoseQuantity(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(mg|mcg|g|ml|iu|units?)\\b")
                .matcher(text);
        return m.find() ? (m.group(1) + m.group(2).toLowerCase(Locale.ROOT)) : null;
    }

    private static String extractRouteToken(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(PO|ORAL|IV|IM|SC|SUBQ|TOPICAL|INH|PR|SL)\\b")
                .matcher(text);
        if (!m.find()) return null;
        String r = m.group(1).toUpperCase(Locale.ROOT);
        if ("ORAL".equals(r)) return "PO";
        if ("SUBQ".equals(r)) return "SC";
        return r;
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
        assertActiveFacility(facilityId);
        requireEncounterInActiveFacility(encounterId, patientId);
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
        WorkforceCredentialSpi spi = workforceCredentialProvider != null
                ? workforceCredentialProvider.getIfAvailable()
                : null;
        if (spi == null) {
            throw new SecurityException("Clinical signing/prescribing requires a configured practitioner credential verifier");
        }
        if (facilityId == null || practitionerId == null || practitionerId.isBlank()) {
            throw new SecurityException(
                    "Clinical action blocked: Practitioner ID and facility tenant are required for license verification.");
        }
        if (!spi.isPractitionerActive(facilityId, practitionerId)) {
            throw new SecurityException(
                    "Clinical action blocked: Practitioner credential/license is expired or suspended for " + practitionerId);
        }
    }

    /**
     * Computes Royal College of Physicians (RCP) NEWS2 Scale 1 aggregate early-warning score
     * from recorded physiological vital signs (assuming room air and Alert consciousness when
     * not supplied explicitly).
     *
     * <p><strong>Clinical validation notice:</strong> Implements RCP NEWS2 Scale 1 thresholds
     * for non-hypercapnic adults. Scale 2 (hypercapnic respiratory failure, e.g., COPD target
     * SpO2 88–92%), pediatric PEWS, and obstetric MEOWS are out of scope here. Independent
     * clinical domain validation is required prior to live patient care deployment.</p>
     */
    public int computeNews2(VitalSignsEntity v) {
        // The old default (room air + alert) produced false reassurance when neither
        // respiratory support nor consciousness was actually documented.
        if (v == null || v.getSupplementalOxygen() == null
                || v.getConsciousnessAcvpu() == null || v.getConsciousnessAcvpu().isBlank()) {
            throw new IllegalArgumentException(
                    "NEWS2 requires documented supplemental oxygen and ACVPU status");
        }
        return computeNews2(v, v.getSupplementalOxygen(), v.getConsciousnessAcvpu());
    }

    /**
     * Computes full RCP NEWS2 Scale 1 aggregate score including supplemental oxygen (+2)
     * and ACVPU consciousness level (C/V/P/U = +3).
     */
    public int computeNews2(VitalSignsEntity v, boolean supplementalOxygen, String consciousnessAcvpu) {
        if (v == null || v.getRespRatePerMin() == null || v.getSpo2Percent() == null
                || v.getSystolicBp() == null || v.getHeartRateBpm() == null
                || v.getTempCelsius() == null) {
            throw new IllegalArgumentException("NEWS2 cannot be scored without all core measured vital signs");
        }
        if (consciousnessAcvpu == null || consciousnessAcvpu.isBlank()
                || !Set.of("A", "ALERT", "C", "V", "P", "U")
                        .contains(consciousnessAcvpu.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("NEWS2 requires a valid recorded ACVPU consciousness status");
        }
        int score = 0;

        // 1. Respiration rate (breaths/min): <=8 (3), 9-11 (1), 12-20 (0), 21-24 (2), >=25 (3)
        if (v.getRespRatePerMin() != null) {
            int rr = v.getRespRatePerMin();
            if (rr <= 8 || rr >= 25) score += 3;
            else if (rr >= 21) score += 2;
            else if (rr <= 11) score += 1;
        }

        // 2. SpO2 Scale 1 (%): <=91 (3), 92-93 (2), 94-95 (1), >=96 (0)
        if (v.getSpo2Percent() != null) {
            int spo2 = v.getSpo2Percent();
            if (spo2 <= 91) score += 3;
            else if (spo2 <= 93) score += 2;
            else if (spo2 <= 95) score += 1;
        }

        // 3. Air or supplemental oxygen: Oxygen (+2), Air (0)
        if (supplementalOxygen) {
            score += 2;
        }

        // 4. Systolic BP (mmHg): <=90 (3), 91-100 (2), 101-110 (1), 111-219 (0), >=220 (3)
        if (v.getSystolicBp() != null) {
            int sbp = v.getSystolicBp();
            if (sbp <= 90 || sbp >= 220) score += 3;
            else if (sbp <= 100) score += 2;
            else if (sbp <= 110) score += 1;
        }

        // 5. Pulse (bpm): <=40 (3), 41-50 (1), 51-90 (0), 91-110 (1), 111-130 (2), >=131 (3)
        if (v.getHeartRateBpm() != null) {
            int hr = v.getHeartRateBpm();
            if (hr <= 40 || hr >= 131) score += 3;
            else if (hr >= 111) score += 2;
            else if (hr <= 50 || hr >= 91) score += 1;
        }

        // 6. Consciousness (ACVPU): Alert = 0; Confusion/Voice/Pain/Unresponsive = 3
        if (consciousnessAcvpu != null && !consciousnessAcvpu.isBlank()) {
            String level = consciousnessAcvpu.trim().toUpperCase(Locale.ROOT);
            if (!level.equals("A") && !level.equals("ALERT")) {
                score += 3;
            }
        }

        // 7. Temperature (°C): <=35.0 (3), 35.1-36.0 (1), 36.1-38.0 (0), 38.1-39.0 (1), >=39.1 (2)
        if (v.getTempCelsius() != null) {
            BigDecimal t = v.getTempCelsius();
            if (t.compareTo(new BigDecimal("35.0")) <= 0) score += 3;
            else if (t.compareTo(new BigDecimal("39.1")) >= 0) score += 2;
            else if (t.compareTo(new BigDecimal("36.0")) <= 0 || t.compareTo(new BigDecimal("38.1")) >= 0) score += 1;
        }

        return score;
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
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_triage:write')")
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
        @Operation(summary = "Place CPOE order gated by persisted patient context, CDSS DDI/allergy/renal & practitioner license checks")
        public Map<String, Object> order(@RequestBody CpoeOrderRequest request) {
            return service.placeOrderWithLoadedPatientContext(request);
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
