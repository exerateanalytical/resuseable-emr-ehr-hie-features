/*
 * =============================================================================
 * Opes HealthCore SDK — Emergency Triage & Inpatient Nursing Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emergency.service;

import com.opeshealth.healthcore.emergency.domain.EmergencyNursingEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

public class EmergencyNursingService {

    public interface EmergencyVisitRepository extends JpaRepository<EmergencyVisitEntity, UUID> {
        Optional<EmergencyVisitEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<EmergencyVisitEntity> findByFacilityIdAndVisitStatusNotOrderByEsiLevelAscArrivedAtAsc(UUID facilityId, String excludedStatus);
    }

    public interface NursingAssignmentRepository extends JpaRepository<NursingAssignmentEntity, UUID> {
        List<NursingAssignmentEntity> findByFacilityIdAndActiveFlagTrueOrderByWardCodeAscBedCodeAsc(UUID facilityId);
        long countByFacilityIdAndNurseIdAndActiveFlagTrue(UUID facilityId, String nurseId);
    }

    public interface News2ObservationRepository extends JpaRepository<News2ObservationEntity, UUID> {
        List<News2ObservationEntity> findByFacilityIdAndPatientIdOrderByRecordedAtDesc(UUID facilityId, UUID patientId);
        List<News2ObservationEntity> findByFacilityIdAndEscalationRequiredTrueOrderByRecordedAtDesc(UUID facilityId);
    }

    public interface DischargeReadinessRepository extends JpaRepository<DischargeReadinessEntity, UUID> {
        Optional<DischargeReadinessEntity> findByFacilityIdAndEncounterId(UUID facilityId, UUID encounterId);
    }

    public record EdArrivalTriageRequest(
            UUID patientId,
            UUID encounterId,
            String chiefComplaint,
            int esiLevel,
            String arrivalMode,
            String assignedBay,
            String triageNurseId
    ) {}

    public record EdDispositionRequest(
            String disposition,
            String attendingClinicianId
    ) {}

    public record NurseAssignmentRequest(
            String wardCode,
            String bedCode,
            UUID patientId,
            String nurseId,
            String shiftCode,
            int maxPatientsPerNurse
    ) {}

    public record News2RecordingRequest(
            UUID patientId,
            UUID encounterId,
            int respiratoryRate,
            int spo2Percent,
            boolean supplementalO2,
            int systolicBp,
            int heartRate,
            double temperatureC,
            String consciousnessAcvpu,
            String recordedBy
    ) {}

    public record DischargeReadinessRequest(
            UUID patientId,
            UUID encounterId,
            boolean medReconciliationComplete,
            boolean dischargeSummarySigned,
            boolean patientEducationDocumented,
            boolean followUpScheduled,
            boolean billingCleared,
            String updatedBy
    ) {}

    private final EmergencyVisitRepository edVisitRepo;
    private final NursingAssignmentRepository assignmentRepo;
    private final News2ObservationRepository news2Repo;
    private final DischargeReadinessRepository dischargeRepo;
    private final ClinicalAuditService auditService;

    public EmergencyNursingService(
            EmergencyVisitRepository edVisitRepo,
            NursingAssignmentRepository assignmentRepo,
            News2ObservationRepository news2Repo,
            DischargeReadinessRepository dischargeRepo,
            ClinicalAuditService auditService) {
        this.edVisitRepo = edVisitRepo;
        this.assignmentRepo = assignmentRepo;
        this.news2Repo = news2Repo;
        this.dischargeRepo = dischargeRepo;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Missing authenticated facility context."));
    }

    @Transactional
    public EmergencyVisitEntity registerEdTriage(EdArrivalTriageRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.chiefComplaint() == null || req.chiefComplaint().isBlank()) {
            throw new IllegalArgumentException("patientId and chiefComplaint are required for ED triage.");
        }
        if (req.esiLevel() < 1 || req.esiLevel() > 5) {
            throw new IllegalArgumentException("ESI acuity level must be between 1 (Resuscitation) and 5 (Non-urgent).");
        }
        if (req.triageNurseId() == null || req.triageNurseId().isBlank()) {
            throw new IllegalArgumentException("triageNurseId is required.");
        }

        EmergencyVisitEntity visit = new EmergencyVisitEntity();
        visit.setFacilityId(facilityId);
        visit.setPatientId(req.patientId());
        visit.setEncounterId(req.encounterId());
        visit.setChiefComplaint(req.chiefComplaint().trim());
        visit.setEsiLevel(req.esiLevel());
        visit.setArrivalMode(req.arrivalMode() != null && !req.arrivalMode().isBlank()
                ? req.arrivalMode().trim().toUpperCase(Locale.ROOT) : "WALK_IN");
        visit.setAssignedBay(req.assignedBay());
        visit.setTriageNurseId(req.triageNurseId().trim());
        visit.setVisitStatus(req.esiLevel() <= 2 ? "RESUSCITATION_PRIORITY" : "TRIAGED");

        EmergencyVisitEntity saved = edVisitRepo.save(visit);
        audit(facilityId, req.patientId(), req.triageNurseId(), "ED_TRIAGE_REGISTERED",
                saved.getId().toString(), "esi=" + req.esiLevel() + " status=" + saved.getVisitStatus());
        return saved;
    }

    @Transactional
    public EmergencyVisitEntity recordEdDisposition(UUID visitId, EdDispositionRequest req) {
        UUID facilityId = requireFacilityId();
        EmergencyVisitEntity visit = edVisitRepo.findByIdAndFacilityId(visitId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ED visit not found"));
        String disp = req.disposition() == null ? "" : req.disposition().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ADMITTED", "DISCHARGED", "TRANSFERRED", "LWBS", "EXPIRED").contains(disp)) {
            throw new IllegalArgumentException("Invalid ED disposition: " + req.disposition());
        }
        visit.setDisposition(disp);
        visit.setAttendingClinicianId(req.attendingClinicianId());
        visit.setVisitStatus("DISPOSITIONED");
        visit.setDispositionAt(Instant.now());
        EmergencyVisitEntity saved = edVisitRepo.save(visit);
        audit(facilityId, saved.getPatientId(), req.attendingClinicianId(), "ED_DISPOSITION_" + disp,
                saved.getId().toString(), "esi=" + saved.getEsiLevel());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<EmergencyVisitEntity> getActiveEdBoard() {
        UUID facilityId = requireFacilityId();
        return edVisitRepo.findByFacilityIdAndVisitStatusNotOrderByEsiLevelAscArrivedAtAsc(facilityId, "DISPOSITIONED");
    }

    @Transactional
    public NursingAssignmentEntity assignNurseToPatient(NurseAssignmentRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.nurseId() == null || req.nurseId().isBlank()
                || req.wardCode() == null || req.wardCode().isBlank() || req.bedCode() == null || req.bedCode().isBlank()) {
            throw new IllegalArgumentException("wardCode, bedCode, patientId, and nurseId are required.");
        }
        int safeCap = req.maxPatientsPerNurse() > 0 ? req.maxPatientsPerNurse() : 6;
        long currentLoad = assignmentRepo.countByFacilityIdAndNurseIdAndActiveFlagTrue(facilityId, req.nurseId().trim());
        if (currentLoad >= safeCap) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Safe nurse-to-patient ratio exceeded for nurse " + req.nurseId() + " (current=" + currentLoad + ", max=" + safeCap + ").");
        }

        NursingAssignmentEntity entity = new NursingAssignmentEntity();
        entity.setFacilityId(facilityId);
        entity.setWardCode(req.wardCode().trim());
        entity.setBedCode(req.bedCode().trim());
        entity.setPatientId(req.patientId());
        entity.setNurseId(req.nurseId().trim());
        entity.setShiftCode(req.shiftCode() != null && !req.shiftCode().isBlank() ? req.shiftCode().trim() : "DAY");
        entity.setActiveFlag(true);
        NursingAssignmentEntity saved = assignmentRepo.save(entity);
        audit(facilityId, req.patientId(), req.nurseId(), "NURSING_ASSIGNMENT_CREATED",
                saved.getId().toString(), "ward=" + saved.getWardCode() + " bed=" + saved.getBedCode());
        return saved;
    }

    /**
     * Deterministic Royal College of Physicians NEWS2 early-warning vital-sign calculator.
     * Note: Clinical decision support aid requiring local clinical governance review before production deployment.
     */
    @Transactional
    public News2ObservationEntity recordNews2Observation(News2RecordingRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.recordedBy() == null || req.recordedBy().isBlank()) {
            throw new IllegalArgumentException("patientId and recordedBy are required for NEWS2 recording.");
        }
        String acvpu = req.consciousnessAcvpu() == null ? "A" : req.consciousnessAcvpu().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("A", "C", "V", "P", "U").contains(acvpu)) {
            throw new IllegalArgumentException("consciousnessAcvpu must be one of A, C, V, P, U.");
        }

        int rrScore = scoreRespRate(req.respiratoryRate());
        int spo2Score = scoreSpo2(req.spo2Percent());
        int o2Score = req.supplementalO2() ? 2 : 0;
        int bpScore = scoreSystolicBp(req.systolicBp());
        int hrScore = scoreHeartRate(req.heartRate());
        int tempScore = scoreTemperature(req.temperatureC());
        int neuroScore = "A".equals(acvpu) ? 0 : 3;

        int total = rrScore + spo2Score + o2Score + bpScore + hrScore + tempScore + neuroScore;
        boolean singleExtremeParameter = (rrScore == 3 || spo2Score == 3 || bpScore == 3 || hrScore == 3 || tempScore == 3 || neuroScore == 3);

        String riskBand;
        boolean escalate;
        if (total >= 7) {
            riskBand = "HIGH";
            escalate = true;
        } else if (total >= 5) {
            riskBand = "MEDIUM";
            escalate = true;
        } else if (singleExtremeParameter) {
            riskBand = "LOW_MEDIUM_RED";
            escalate = true;
        } else {
            riskBand = "LOW";
            escalate = false;
        }

        News2ObservationEntity entity = new News2ObservationEntity();
        entity.setFacilityId(facilityId);
        entity.setPatientId(req.patientId());
        entity.setEncounterId(req.encounterId());
        entity.setRespiratoryRate(req.respiratoryRate());
        entity.setSpo2Percent(req.spo2Percent());
        entity.setSupplementalO2(req.supplementalO2());
        entity.setSystolicBp(req.systolicBp());
        entity.setHeartRate(req.heartRate());
        entity.setTemperatureC(req.temperatureC());
        entity.setConsciousnessAcvpu(acvpu);
        entity.setTotalScore(total);
        entity.setRiskBand(riskBand);
        entity.setEscalationRequired(escalate);
        entity.setRecordedBy(req.recordedBy().trim());

        News2ObservationEntity saved = news2Repo.save(entity);
        audit(facilityId, req.patientId(), req.recordedBy(),
                escalate ? "NEWS2_ESCALATION_TRIGGERED" : "NEWS2_RECORDED",
                saved.getId().toString(), "score=" + total + " riskBand=" + riskBand);
        return saved;
    }

    @Transactional
    public DischargeReadinessEntity updateDischargeReadiness(DischargeReadinessRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.encounterId() == null
                || req.updatedBy() == null || req.updatedBy().isBlank()) {
            throw new IllegalArgumentException("patientId, encounterId, and updatedBy are required.");
        }
        DischargeReadinessEntity entity = dischargeRepo.findByFacilityIdAndEncounterId(facilityId, req.encounterId())
                .orElseGet(DischargeReadinessEntity::new);
        entity.setFacilityId(facilityId);
        entity.setPatientId(req.patientId());
        entity.setEncounterId(req.encounterId());
        entity.setMedReconciliationComplete(req.medReconciliationComplete());
        entity.setDischargeSummarySigned(req.dischargeSummarySigned());
        entity.setPatientEducationDocumented(req.patientEducationDocumented());
        entity.setFollowUpScheduled(req.followUpScheduled());
        entity.setBillingCleared(req.billingCleared());
        boolean ready = req.medReconciliationComplete()
                && req.dischargeSummarySigned()
                && req.patientEducationDocumented()
                && req.followUpScheduled()
                && req.billingCleared();
        entity.setReadyForDischarge(ready);
        entity.setUpdatedBy(req.updatedBy().trim());
        entity.setUpdatedAt(Instant.now());

        DischargeReadinessEntity saved = dischargeRepo.save(entity);
        audit(facilityId, req.patientId(), req.updatedBy(), "DISCHARGE_READINESS_UPDATED",
                req.encounterId().toString(), "ready=" + ready);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<News2ObservationEntity> listActiveEscalations() {
        UUID facilityId = requireFacilityId();
        return news2Repo.findByFacilityIdAndEscalationRequiredTrueOrderByRecordedAtDesc(facilityId);
    }

    private static int scoreRespRate(int rr) {
        if (rr <= 8 || rr >= 25) return 3;
        if (rr >= 21) return 2;
        if (rr <= 11) return 1;
        return 0;
    }

    private static int scoreSpo2(int spo2) {
        if (spo2 <= 91) return 3;
        if (spo2 <= 93) return 2;
        if (spo2 <= 95) return 1;
        return 0;
    }

    private static int scoreSystolicBp(int sbp) {
        if (sbp <= 90 || sbp >= 220) return 3;
        if (sbp <= 100) return 2;
        if (sbp <= 110) return 1;
        return 0;
    }

    private static int scoreHeartRate(int hr) {
        if (hr <= 40 || hr >= 131) return 3;
        if (hr >= 111) return 2;
        if (hr <= 50 || hr >= 91) return 1;
        return 0;
    }

    private static int scoreTemperature(double tempC) {
        if (tempC <= 35.0) return 3;
        if (tempC >= 39.1) return 2;
        if (tempC <= 36.0 || tempC >= 38.1) return 1;
        return 0;
    }

    private void audit(UUID facilityId, UUID patientId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.record(facilityId, patientId, actor != null ? actor : "NURSING_SYSTEM", action,
                    "EmergencyNursing", resourceId, "SUCCESS", detail);
        }
    }

    @RestController
    @RequestMapping("/api/v1/emergency-nursing")
    @Tag(name = "Emergency Triage & Inpatient Nursing", description = "ESI triage board, ED disposition, safe nurse staffing ratios, NEWS2 early warning, and discharge readiness")
    public static class EmergencyNursingController {
        private final EmergencyNursingService service;

        public EmergencyNursingController(EmergencyNursingService service) {
            this.service = service;
        }

        @PostMapping("/ed/triage")
        @PreAuthorize("hasAuthority('PERM_patient:write')")
        @Operation(summary = "Register emergency arrival with ESI 1-5 acuity triage")
        public EmergencyVisitEntity triage(@RequestBody EdArrivalTriageRequest request) {
            return service.registerEdTriage(request);
        }

        @GetMapping("/ed/board")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Retrieve active ED waiting/treatment board sorted by ESI acuity and wait time")
        public List<EmergencyVisitEntity> activeBoard() {
            return service.getActiveEdBoard();
        }

        @PostMapping("/ed/{visitId}/disposition")
        @PreAuthorize("hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Record ED disposition (ADMITTED, DISCHARGED, TRANSFERRED, LWBS, EXPIRED)")
        public EmergencyVisitEntity disposition(@PathVariable UUID visitId, @RequestBody EdDispositionRequest request) {
            return service.recordEdDisposition(visitId, request);
        }

        @PostMapping("/nursing/assignments")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:write')")
        @Operation(summary = "Assign nurse to inpatient bed with safe nurse-to-patient ratio guard")
        public NursingAssignmentEntity assignNurse(@RequestBody NurseAssignmentRequest request) {
            return service.assignNurseToPatient(request);
        }

        @PostMapping("/nursing/news2")
        @PreAuthorize("hasAuthority('PERM_patient:write')")
        @Operation(summary = "Record NEWS2 early-warning vitals and evaluate automatic escalation trigger")
        public News2ObservationEntity recordNews2(@RequestBody News2RecordingRequest request) {
            return service.recordNews2Observation(request);
        }

        @GetMapping("/nursing/escalations")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "List active NEWS2 clinical deterioration escalations for facility")
        public List<News2ObservationEntity> escalations() {
            return service.listActiveEscalations();
        }

        @PutMapping("/nursing/discharge-readiness")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Update multi-disciplinary inpatient discharge readiness checklist gate")
        public DischargeReadinessEntity dischargeReadiness(@RequestBody DischargeReadinessRequest request) {
            return service.updateDischargeReadiness(request);
        }
    }
}
