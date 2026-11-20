/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Analytics & Clinical Quality Measures Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.analytics.service;

import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.ClinicalQualityMeasureEntity;
import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.CohortExportRequestEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public class HospitalAnalyticsService {

    public interface QualityMeasureRepository extends JpaRepository<ClinicalQualityMeasureEntity, UUID> {
        List<ClinicalQualityMeasureEntity> findByFacilityIdOrderByCalculatedAtDesc(UUID facilityId);
    }

    public interface CohortExportRepository extends JpaRepository<CohortExportRequestEntity, UUID> {
        Optional<CohortExportRequestEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<CohortExportRequestEntity> findByFacilityIdOrderByRequestedAtDesc(UUID facilityId);
    }

    public record OperationalKpiSnapshot(
            UUID facilityId,
            Instant generatedAt,
            long totalBeds,
            long occupiedBeds,
            double bedOccupancyRatePct,
            long totalOrders,
            long verifiedResults,
            long unacknowledgedCriticalResults,
            long pendingInsuranceClaims,
            long paidInsuranceClaims,
            String lineageNote
    ) {}

    public record RecordQualityMeasureRequest(
            String measureCode,
            String measureTitle,
            String reportingPeriod,
            long numeratorCount,
            long denominatorCount,
            long exclusionCount,
            String dataLineageSource,
            String calculatedBy
    ) {}

    public record RequestCohortExportCommand(
            String cohortName,
            String filterCriteriaJson,
            String exportPurpose,
            boolean deidentifiedFlag,
            int minimumCellSuppression,
            long matchedPatientCount,
            String requestedBy
    ) {}

    private final QualityMeasureRepository qualityMeasureRepo;
    private final CohortExportRepository cohortExportRepo;
    private final JdbcTemplate jdbcTemplate;
    private final ClinicalAuditService auditService;

    public HospitalAnalyticsService(
            QualityMeasureRepository qualityMeasureRepo,
            CohortExportRepository cohortExportRepo,
            JdbcTemplate jdbcTemplate,
            ClinicalAuditService auditService) {
        this.qualityMeasureRepo = qualityMeasureRepo;
        this.cohortExportRepo = cohortExportRepo;
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Missing authenticated facility context for Analytics."));
    }

    /**
     * Computes live, strictly facility-scoped operational KPIs (bed occupancy, orders/results turnaround backlog,
     * and revenue cycle claim status) directly from relational tables.
     */
    @Transactional(readOnly = true)
    public OperationalKpiSnapshot computeOperationalKpis() {
        UUID facilityId = requireFacilityId();
        long totalBeds = safeCount("SELECT COUNT(*) FROM hc_ward_beds WHERE facility_id = ?", facilityId);
        long occupiedBeds = safeCount("SELECT COUNT(*) FROM hc_ward_beds WHERE facility_id = ? AND status = 'OCCUPIED'", facilityId);
        double occupancyPct = totalBeds > 0 ? Math.round((occupiedBeds * 10000.0) / totalBeds) / 100.0 : 0.0;

        long totalOrders = safeCount("SELECT COUNT(*) FROM hc_unified_orders WHERE facility_id = ?", facilityId);
        long verifiedResults = safeCount("SELECT COUNT(*) FROM hc_clinical_results WHERE facility_id = ? AND status IN ('FINAL','AMENDED')", facilityId);
        long unackedCritical = safeCount("SELECT COUNT(*) FROM hc_clinical_results WHERE facility_id = ? AND critical_ack_required = true AND critical_ack_at IS NULL", facilityId);

        long pendingClaims = safeCount("SELECT COUNT(*) FROM hc_insurance_claims WHERE facility_id = ? AND status IN ('SUBMITTED','PENDING','ADJUDICATING')", facilityId);
        long paidClaims = safeCount("SELECT COUNT(*) FROM hc_insurance_claims WHERE facility_id = ? AND status = 'PAID'", facilityId);

        return new OperationalKpiSnapshot(
                facilityId,
                Instant.now(),
                totalBeds,
                occupiedBeds,
                occupancyPct,
                totalOrders,
                verifiedResults,
                unackedCritical,
                pendingClaims,
                paidClaims,
                "Live tenant-scoped SQL aggregation over hc_ward_beds, hc_unified_orders, hc_clinical_results, hc_insurance_claims"
        );
    }

    @Transactional
    public ClinicalQualityMeasureEntity recordQualityMeasure(RecordQualityMeasureRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.measureCode() == null || req.measureCode().isBlank()
                || req.measureTitle() == null || req.measureTitle().isBlank()
                || req.reportingPeriod() == null || req.reportingPeriod().isBlank()
                || req.dataLineageSource() == null || req.dataLineageSource().isBlank()
                || req.calculatedBy() == null || req.calculatedBy().isBlank()) {
            throw new IllegalArgumentException("measureCode, measureTitle, reportingPeriod, dataLineageSource, and calculatedBy are required.");
        }
        if (req.numeratorCount() < 0 || req.denominatorCount() < 0 || req.exclusionCount() < 0) {
            throw new IllegalArgumentException("Measure counts must be non-negative.");
        }
        long effectiveDenominator = Math.max(0L, req.denominatorCount() - req.exclusionCount());
        if (req.numeratorCount() > effectiveDenominator && effectiveDenominator > 0) {
            throw new IllegalArgumentException("numeratorCount cannot exceed (denominatorCount - exclusionCount).");
        }
        double ratePct = effectiveDenominator > 0
                ? Math.round((req.numeratorCount() * 10000.0) / effectiveDenominator) / 100.0
                : 0.0;

        ClinicalQualityMeasureEntity entity = new ClinicalQualityMeasureEntity();
        entity.setFacilityId(facilityId);
        entity.setMeasureCode(req.measureCode().trim());
        entity.setMeasureTitle(req.measureTitle().trim());
        entity.setReportingPeriod(req.reportingPeriod().trim());
        entity.setNumeratorCount(req.numeratorCount());
        entity.setDenominatorCount(req.denominatorCount());
        entity.setExclusionCount(req.exclusionCount());
        entity.setComplianceRatePct(ratePct);
        entity.setDataLineageSource(req.dataLineageSource().trim());
        entity.setCalculatedBy(req.calculatedBy().trim());

        ClinicalQualityMeasureEntity saved = qualityMeasureRepo.save(entity);
        audit(facilityId, req.calculatedBy(), "ANALYTICS_CQM_RECORDED", saved.getMeasureCode(),
                "period=" + saved.getReportingPeriod() + " ratePct=" + ratePct);
        return saved;
    }

    /**
     * Submits a governed cohort export request enforcing small-cell suppression (k-anonymity threshold >= 5)
     * and dual-party approval (requester cannot self-approve).
     */
    @Transactional
    public CohortExportRequestEntity requestCohortExport(RequestCohortExportCommand cmd) {
        UUID facilityId = requireFacilityId();
        if (cmd == null || cmd.cohortName() == null || cmd.cohortName().isBlank()
                || cmd.filterCriteriaJson() == null || cmd.filterCriteriaJson().isBlank()
                || cmd.exportPurpose() == null || cmd.exportPurpose().isBlank()
                || cmd.requestedBy() == null || cmd.requestedBy().isBlank()) {
            throw new IllegalArgumentException("cohortName, filterCriteriaJson, exportPurpose, and requestedBy are required.");
        }
        int minCell = Math.max(5, cmd.minimumCellSuppression());
        if (cmd.deidentifiedFlag() && cmd.matchedPatientCount() > 0 && cmd.matchedPatientCount() < minCell) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cohort suppressed for re-identification privacy protection: matchedPatientCount ("
                            + cmd.matchedPatientCount() + ") is below minimum cell threshold (" + minCell + ").");
        }

        CohortExportRequestEntity entity = new CohortExportRequestEntity();
        entity.setFacilityId(facilityId);
        entity.setCohortName(cmd.cohortName().trim());
        entity.setFilterCriteriaJson(cmd.filterCriteriaJson().trim());
        entity.setExportPurpose(cmd.exportPurpose().trim());
        entity.setDeidentifiedFlag(cmd.deidentifiedFlag());
        entity.setMinimumCellSuppression(minCell);
        entity.setMatchedPatientCount(Math.max(0L, cmd.matchedPatientCount()));
        entity.setApprovalStatus("PENDING_APPROVAL");
        entity.setRequestedBy(cmd.requestedBy().trim());
        entity.setLineageSha256(sha256(facilityId + "|" + cmd.cohortName() + "|" + cmd.filterCriteriaJson()));

        CohortExportRequestEntity saved = cohortExportRepo.save(entity);
        audit(facilityId, cmd.requestedBy(), "COHORT_EXPORT_REQUESTED", saved.getId().toString(),
                "cohort=" + saved.getCohortName() + " deidentified=" + saved.isDeidentifiedFlag());
        return saved;
    }

    @Transactional
    public CohortExportRequestEntity approveCohortExport(UUID exportId, String approverId) {
        UUID facilityId = requireFacilityId();
        if (approverId == null || approverId.isBlank()) {
            throw new IllegalArgumentException("approverId is required.");
        }
        CohortExportRequestEntity entity = cohortExportRepo.findByIdAndFacilityId(exportId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Cohort export request not found"));
        if (entity.getRequestedBy().equalsIgnoreCase(approverId.trim())) {
            throw new AccessDeniedException("Four-eyes governance policy: requester cannot self-approve cohort export.");
        }
        entity.setApprovalStatus("APPROVED");
        entity.setApprovedBy(approverId.trim());
        entity.setApprovedAt(Instant.now());
        CohortExportRequestEntity saved = cohortExportRepo.save(entity);
        audit(facilityId, approverId, "COHORT_EXPORT_APPROVED", saved.getId().toString(),
                "lineageSha256=" + saved.getLineageSha256());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClinicalQualityMeasureEntity> listQualityMeasures() {
        return qualityMeasureRepo.findByFacilityIdOrderByCalculatedAtDesc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public List<CohortExportRequestEntity> listCohortExports() {
        return cohortExportRepo.findByFacilityIdOrderByRequestedAtDesc(requireFacilityId());
    }

    private long safeCount(String sql, UUID facilityId) {
        if (jdbcTemplate == null) {
            return 0L;
        }
        try {
            Long val = jdbcTemplate.queryForObject(sql, Long.class, facilityId);
            return val != null ? val : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void audit(UUID facilityId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.recordEvent(facilityId.toString(), actor != null ? actor : "ANALYTICS_SYSTEM",
                    "ANALYTICS", action, "HospitalAnalytics", resourceId, null, "SUCCESS", detail, "internal");
        }
    }

    @RestController
    @RequestMapping("/api/v1/analytics")
    @Tag(name = "Hospital Analytics & Quality Measures", description = "Tenant-scoped operational KPIs, clinical quality measures, and governed cohort exports")
    public static class HospitalAnalyticsController {
        private final HospitalAnalyticsService service;

        public HospitalAnalyticsController(HospitalAnalyticsService service) {
            this.service = service;
        }

        @GetMapping("/kpis/operational")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "Compute live tenant-scoped bed occupancy, orders/results, and claims KPIs")
        public OperationalKpiSnapshot operationalKpis() {
            return service.computeOperationalKpis();
        }

        @PostMapping("/quality-measures")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Record a clinical quality measure with explicit data lineage")
        public ClinicalQualityMeasureEntity recordMeasure(@RequestBody RecordQualityMeasureRequest request) {
            return service.recordQualityMeasure(request);
        }

        @GetMapping("/quality-measures")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "List facility clinical quality measures")
        public List<ClinicalQualityMeasureEntity> listMeasures() {
            return service.listQualityMeasures();
        }

        @PostMapping("/cohort-exports")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "Submit a governed cohort export request (enforces k-anonymity cell suppression)")
        public CohortExportRequestEntity requestExport(@RequestBody RequestCohortExportCommand command) {
            return service.requestCohortExport(command);
        }

        @PostMapping("/cohort-exports/{exportId}/approve")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Approve a governed cohort export under four-eyes separation of duties")
        public CohortExportRequestEntity approveExport(@PathVariable UUID exportId, @RequestParam String approverId) {
            return service.approveCohortExport(exportId, approverId);
        }

        @GetMapping("/cohort-exports")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        @Operation(summary = "List facility cohort export requests and lineage hashes")
        public List<CohortExportRequestEntity> listExports() {
            return service.listCohortExports();
        }
    }
}
