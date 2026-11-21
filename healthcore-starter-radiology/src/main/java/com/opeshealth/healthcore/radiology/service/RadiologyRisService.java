/*
 * =============================================================================
 * Opes HealthCore SDK — Radiology RIS Service & REST API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.radiology.service;

import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
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
import java.util.regex.Pattern;

public class RadiologyRisService {

    private static final Pattern DICOM_UID_PATTERN = Pattern.compile("^(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*$");
    private static final Set<String> ALLOWED_MODALITIES = Set.of("CT", "MR", "DX", "CR", "XR", "US", "MG", "RF", "NM", "PT");
    private static final String OPES_DICOM_OID_ROOT = "1.2.826.0.1.3680043.10.942";

    public interface ModalityWorklistRepository extends JpaRepository<ModalityWorklistEntity, UUID> {
        Optional<ModalityWorklistEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ModalityWorklistEntity> findByFacilityIdOrderByStatUrgentDescScheduledAtAsc(UUID facilityId);
        List<ModalityWorklistEntity> findByFacilityIdAndMppsStatusOrderByStatUrgentDescScheduledAtAsc(UUID facilityId, String mppsStatus);
        List<ModalityWorklistEntity> findByFacilityIdAndPatientIdOrderByScheduledAtDesc(UUID facilityId, UUID patientId);
    }

    public interface RadiologyReportRepository extends JpaRepository<RadiologyReportEntity, UUID> {
        Optional<RadiologyReportEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<RadiologyReportEntity> findByFacilityIdAndCriticalFindingTrueAndCriticalAcknowledgedFalseOrderBySignedAtAsc(UUID facilityId);
        List<RadiologyReportEntity> findByFacilityIdAndPatientIdOrderBySignedAtDesc(UUID facilityId, UUID patientId);
    }

    public record ScheduleExamRequest(
            UUID patientId,
            UUID encounterId,
            String modality,
            String procedureCode,
            String procedureDescription,
            String bodySite,
            String clinicalIndication,
            String scheduledStationAe,
            String orderingClinicianId,
            boolean statUrgent,
            String customStudyInstanceUid
    ) {}

    public record MppsUpdateRequest(
            String status,
            String performingTechnologistId,
            int seriesCount,
            int instanceCount
    ) {}

    public record SignRadiologyReportRequest(
            UUID worklistItemId,
            String findingsText,
            String impressionText,
            String radiologistId,
            boolean criticalFinding,
            String criticalCategory,
            UUID supersedesReportId
    ) {}

    public record AcknowledgeCriticalRequest(
            String acknowledgedBy,
            String readbackNotes
    ) {}

    private final ModalityWorklistRepository worklistRepo;
    private final RadiologyReportRepository reportRepo;
    private final ClinicalAuditService auditService;

    public RadiologyRisService(
            ModalityWorklistRepository worklistRepo,
            RadiologyReportRepository reportRepo,
            ClinicalAuditService auditService) {
        this.worklistRepo = worklistRepo;
        this.reportRepo = reportRepo;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Missing authenticated facility context for Radiology RIS."));
    }

    @Transactional
    public ModalityWorklistEntity scheduleImagingExam(ScheduleExamRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null) {
            throw new IllegalArgumentException("patientId is required to schedule an imaging exam.");
        }
        String modality = req.modality() == null ? "" : req.modality().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_MODALITIES.contains(modality)) {
            throw new IllegalArgumentException("Unsupported DICOM modality: " + req.modality());
        }
        if (req.procedureCode() == null || req.procedureCode().isBlank()
                || req.procedureDescription() == null || req.procedureDescription().isBlank()
                || req.orderingClinicianId() == null || req.orderingClinicianId().isBlank()) {
            throw new IllegalArgumentException("procedureCode, procedureDescription, and orderingClinicianId are required.");
        }

        String studyUid = (req.customStudyInstanceUid() != null && !req.customStudyInstanceUid().isBlank())
                ? req.customStudyInstanceUid().trim()
                : generateDicomStudyUid();
        if (studyUid.length() > 64 || !DICOM_UID_PATTERN.matcher(studyUid).matches()) {
            throw new IllegalArgumentException("Invalid DICOM StudyInstanceUID: " + studyUid);
        }

        ModalityWorklistEntity item = new ModalityWorklistEntity();
        item.setFacilityId(facilityId);
        item.setPatientId(req.patientId());
        item.setEncounterId(req.encounterId());
        item.setAccessionNumber("ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        item.setStudyInstanceUid(studyUid);
        item.setModality(modality);
        item.setProcedureCode(req.procedureCode().trim());
        item.setProcedureDescription(req.procedureDescription().trim());
        item.setBodySite(req.bodySite());
        item.setClinicalIndication(req.clinicalIndication());
        item.setScheduledStationAe(req.scheduledStationAe());
        item.setOrderingClinicianId(req.orderingClinicianId().trim());
        item.setStatUrgent(req.statUrgent());
        item.setMppsStatus("SCHEDULED");

        ModalityWorklistEntity saved = worklistRepo.save(item);
        audit(facilityId, req.patientId(), req.orderingClinicianId(), "RIS_MWL_SCHEDULED",
                saved.getAccessionNumber(), "modality=" + modality + " stat=" + req.statUrgent());
        return saved;
    }

    @Transactional
    public ModalityWorklistEntity updateMpps(UUID worklistId, MppsUpdateRequest req) {
        UUID facilityId = requireFacilityId();
        ModalityWorklistEntity item = worklistRepo.findByIdAndFacilityId(worklistId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worklist item not found"));

        if (req == null || req.status() == null) {
            throw new IllegalArgumentException("MPPS status and accountable technologist are required");
        }
        String next = req.status().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("IN_PROGRESS", "COMPLETED", "DISCONTINUED").contains(next)) {
            throw new IllegalArgumentException("Invalid MPPS transition status: " + req.status());
        }
        if (req.seriesCount() < 0 || req.instanceCount() < 0) {
            throw new IllegalArgumentException("MPPS series and instance counts cannot be negative");
        }
        String previous = item.getMppsStatus();
        if ("COMPLETED".equals(previous) || "DISCONTINUED".equals(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot modify terminal MPPS state: " + previous);
        }
        if ("COMPLETED".equals(next) && !"IN_PROGRESS".equals(previous)
                || "IN_PROGRESS".equals(next) && !"SCHEDULED".equals(previous)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Invalid MPPS clinical transition: " + previous + " to " + next);
        }
        if (req.performingTechnologistId() == null || req.performingTechnologistId().isBlank()) {
            throw new IllegalArgumentException("MPPS transition requires an accountable technologist");
        }
        if ("IN_PROGRESS".equals(next)) {
            item.setStartedAt(Instant.now());
        }
        if ("COMPLETED".equals(next) || "DISCONTINUED".equals(next)) {
            item.setCompletedAt(Instant.now());
        }
        item.setMppsStatus(next);
        item.setPerformingTechnologistId(req.performingTechnologistId().trim());
        item.setSeriesCount(req.seriesCount());
        item.setInstanceCount(req.instanceCount());

        ModalityWorklistEntity saved = worklistRepo.save(item);
        audit(facilityId, saved.getPatientId(), saved.getPerformingTechnologistId(), "RIS_MPPS_" + next,
                saved.getAccessionNumber(), "series=" + saved.getSeriesCount() + " instances=" + saved.getInstanceCount());
        return saved;
    }

    @Transactional
    public RadiologyReportEntity signReport(SignRadiologyReportRequest req) {
        UUID facilityId = requireFacilityId();
        ModalityWorklistEntity item = worklistRepo.findByIdAndFacilityId(req.worklistItemId(), facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Worklist item not found"));
        if (!"COMPLETED".equals(item.getMppsStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Radiology report can only be signed after MPPS status is COMPLETED (current=" + item.getMppsStatus() + ").");
        }
        if (req.findingsText() == null || req.findingsText().isBlank()
                || req.impressionText() == null || req.impressionText().isBlank()
                || req.radiologistId() == null || req.radiologistId().isBlank()) {
            throw new IllegalArgumentException("findingsText, impressionText, and radiologistId are required.");
        }
        if (req.criticalFinding() && (req.criticalCategory() == null || req.criticalCategory().isBlank())) {
            throw new IllegalArgumentException("criticalCategory is required when criticalFinding=true.");
        }

        String status = "FINAL";
        if (req.supersedesReportId() != null) {
            RadiologyReportEntity prior = reportRepo.findByIdAndFacilityId(req.supersedesReportId(), facilityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Prior report to amend not found"));
            if (!item.getId().equals(prior.getWorklistItemId())
                    || !item.getPatientId().equals(prior.getPatientId())
                    || !item.getStudyInstanceUid().equals(prior.getStudyInstanceUid())
                    || !Set.of("FINAL", "AMENDED").contains(prior.getReportStatus())) {
                throw new SecurityException("Amendment must reference a signed report for the same patient and imaging study");
            }
            status = "AMENDED";
        }

        RadiologyReportEntity report = new RadiologyReportEntity();
        report.setFacilityId(facilityId);
        report.setWorklistItemId(item.getId());
        report.setPatientId(item.getPatientId());
        report.setAccessionNumber(item.getAccessionNumber());
        report.setStudyInstanceUid(item.getStudyInstanceUid());
        report.setReportStatus(status);
        report.setFindingsText(req.findingsText().trim());
        report.setImpressionText(req.impressionText().trim());
        report.setRadiologistId(req.radiologistId().trim());
        report.setCriticalFinding(req.criticalFinding());
        report.setCriticalCategory(req.criticalCategory());
        report.setCriticalAcknowledged(!req.criticalFinding());
        report.setSupersedesReportId(req.supersedesReportId());

        RadiologyReportEntity saved = reportRepo.save(report);
        audit(facilityId, saved.getPatientId(), saved.getRadiologistId(), "RIS_REPORT_SIGNED_" + status,
                saved.getAccessionNumber(), "critical=" + saved.isCriticalFinding());
        return saved;
    }

    @Transactional
    public RadiologyReportEntity acknowledgeCriticalFinding(UUID reportId, AcknowledgeCriticalRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.acknowledgedBy() == null || req.acknowledgedBy().isBlank()
                || req.readbackNotes() == null || req.readbackNotes().isBlank()) {
            throw new IllegalArgumentException("acknowledgedBy and readbackNotes are mandatory for closed-loop critical finding acknowledgment.");
        }
        RadiologyReportEntity report = reportRepo.findByIdAndFacilityId(reportId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Radiology report not found"));
        if (!report.isCriticalFinding()) {
            throw new IllegalArgumentException("Report does not have an active critical finding.");
        }
        String responsibleClinician = req.acknowledgedBy().trim();
        String notes = req.readbackNotes().trim();
        if (report.isCriticalAcknowledged()) {
            if (responsibleClinician.equals(report.getAcknowledgedBy())
                    && notes.equals(report.getReadbackNotes())
                    && report.getAcknowledgedAt() != null) {
                return report; // Exact replay preserves clinician and first acknowledgment timestamp.
            }
            throw new IllegalStateException("Previously acknowledged critical report cannot be reattributed or rewritten");
        }
        report.setCriticalAcknowledged(true);
        report.setAcknowledgedBy(responsibleClinician);
        report.setReadbackNotes(notes);
        report.setAcknowledgedAt(Instant.now());

        RadiologyReportEntity saved = reportRepo.save(report);
        audit(facilityId, saved.getPatientId(), req.acknowledgedBy(), "RIS_CRITICAL_FINDING_ACKNOWLEDGED",
                saved.getAccessionNumber(), "category=" + saved.getCriticalCategory());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ModalityWorklistEntity> listWorklist(String mppsStatus) {
        UUID facilityId = requireFacilityId();
        if (mppsStatus != null && !mppsStatus.isBlank()) {
            return worklistRepo.findByFacilityIdAndMppsStatusOrderByStatUrgentDescScheduledAtAsc(
                    facilityId, mppsStatus.trim().toUpperCase(Locale.ROOT));
        }
        return worklistRepo.findByFacilityIdOrderByStatUrgentDescScheduledAtAsc(facilityId);
    }

    @Transactional(readOnly = true)
    public List<RadiologyReportEntity> listUnacknowledgedCriticalFindings() {
        UUID facilityId = requireFacilityId();
        return reportRepo.findByFacilityIdAndCriticalFindingTrueAndCriticalAcknowledgedFalseOrderBySignedAtAsc(facilityId);
    }

    public static String generateDicomStudyUid() {
        long epochMillis = Instant.now().toEpochMilli();
        long suffix = Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000L);
        return OPES_DICOM_OID_ROOT + "." + epochMillis + "." + suffix;
    }

    private void audit(UUID facilityId, UUID patientId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.recordEvent(facilityId.toString(), actor != null ? actor : "RIS_SYSTEM",
                    "RIS", action, "RadiologyRis", resourceId, patientId, "SUCCESS", detail, "internal");
        }
    }

    @RestController
    @RequestMapping("/api/v1/ris")
    @Tag(name = "Radiology RIS & Critical Findings", description = "Durable DICOM MWL, MPPS, structured reporting, and closed-loop critical finding notification")
    public static class RadiologyRisController {
        private final RadiologyRisService service;

        public RadiologyRisController(RadiologyRisService service) {
            this.service = service;
        }

        @PostMapping("/worklist")
        @PreAuthorize("hasAuthority('PERM_order:sign')")
        @Operation(summary = "Schedule imaging exam on DICOM Modality Worklist")
        public ModalityWorklistEntity schedule(@RequestBody ScheduleExamRequest request) {
            return service.scheduleImagingExam(request);
        }

        @GetMapping("/worklist")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Query tenant-scoped Modality Worklist prioritized by STAT urgency")
        public List<ModalityWorklistEntity> listWorklist(@RequestParam(required = false) String status) {
            return service.listWorklist(status);
        }

        @PatchMapping("/worklist/{id}/mpps")
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')")
        @Operation(summary = "Update Modality Performed Procedure Step (MPPS) state")
        public ModalityWorklistEntity updateMpps(@PathVariable UUID id, @RequestBody MppsUpdateRequest request) {
            return service.updateMpps(id, request);
        }

        @PostMapping("/reports")
        @PreAuthorize("hasAuthority('PERM_imaging:report:sign')")
        @Operation(summary = "Sign structured radiology report or immutable addendum")
        public RadiologyReportEntity signReport(@RequestBody SignRadiologyReportRequest request) {
            return service.signReport(request);
        }

        @PostMapping("/reports/{reportId}/acknowledge-critical")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_order:sign')")
        @Operation(summary = "Record closed-loop clinician verbal read-back acknowledgment for critical imaging finding")
        public RadiologyReportEntity acknowledgeCritical(
                @PathVariable UUID reportId,
                @RequestBody AcknowledgeCriticalRequest request) {
            return service.acknowledgeCriticalFinding(reportId, request);
        }

        @GetMapping("/reports/critical-unacknowledged")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "List unacknowledged critical imaging findings for active facility")
        public List<RadiologyReportEntity> unacknowledgedCritical() {
            return service.listUnacknowledgedCriticalFindings();
        }
    }
}
