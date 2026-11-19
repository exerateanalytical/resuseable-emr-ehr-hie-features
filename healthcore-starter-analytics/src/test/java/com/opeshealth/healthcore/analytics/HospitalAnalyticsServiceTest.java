/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Analytics & Quality Measures Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.analytics;

import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.ClinicalQualityMeasureEntity;
import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.CohortExportRequestEntity;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HospitalAnalyticsServiceTest {

    private QualityMeasureRepository cqmRepo;
    private CohortExportRepository cohortRepo;
    private JdbcTemplate jdbc;
    private ClinicalAuditService auditService;
    private HospitalAnalyticsService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        cqmRepo = mock(QualityMeasureRepository.class);
        cohortRepo = mock(CohortExportRepository.class);
        jdbc = mock(JdbcTemplate.class);
        auditService = mock(ClinicalAuditService.class);
        service = new HospitalAnalyticsService(cqmRepo, cohortRepo, jdbc, auditService);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void calculatesComplianceRateAndRejectsInvalidNumerator() {
        when(cqmRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClinicalQualityMeasureEntity measure = service.recordQualityMeasure(new RecordQualityMeasureRequest(
                "CMS165v12", "Controlling High Blood Pressure", "2026-Q4",
                78, 104, 4, "hc_emr_observations:BP_SYSTOLIC_DIASTOLIC", "qa-analyst-1"));
        assertEquals(78.0, measure.getComplianceRatePct());

        assertThrows(IllegalArgumentException.class, () ->
                service.recordQualityMeasure(new RecordQualityMeasureRequest(
                        "CMS165v12", "Invalid Measure", "2026-Q4", 105, 100, 0, "src", "qa-analyst-1")));
    }

    @Test
    void enforcesSmallCellSuppressionAndFourEyesApprovalOnCohortExports() {
        when(cohortRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Fewer than 5 patients must be suppressed for de-identified exports
        assertThrows(ResponseStatusException.class, () ->
                service.requestCohortExport(new RequestCohortExportCommand(
                        "Rare Disease Cohort", "{\"icd10\":\"E70.0\"}", "RESEARCH", true, 5, 3, "researcher-1")));

        CohortExportRequestEntity req = service.requestCohortExport(new RequestCohortExportCommand(
                "Hypertension Cohort", "{\"icd10\":\"I10\"}", "QUALITY_IMPROVEMENT", true, 5, 42, "researcher-1"));
        assertEquals("PENDING_APPROVAL", req.getApprovalStatus());
        assertEquals(64, req.getLineageSha256().length());

        when(cohortRepo.findByIdAndFacilityId(req.getId(), facilityId)).thenReturn(Optional.of(req));

        // Self-approval denied
        assertThrows(AccessDeniedException.class, () -> service.approveCohortExport(req.getId(), "researcher-1"));

        // Second-party governance officer approval succeeds
        CohortExportRequestEntity approved = service.approveCohortExport(req.getId(), "privacy-officer-1");
        assertEquals("APPROVED", approved.getApprovalStatus());
        assertEquals("privacy-officer-1", approved.getApprovedBy());
    }
}
