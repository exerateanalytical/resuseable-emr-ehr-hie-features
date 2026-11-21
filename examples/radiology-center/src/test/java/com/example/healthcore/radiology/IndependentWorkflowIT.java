package com.example.healthcore.radiology;

import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL and Liquibase consumer verifying DICOM MWL/MPPS, report
 * persistence, critical read-back and tenant isolation.
 * Does not simulate actual DICOM devices, PACS, radiologist licensing or provider delivery.
 */
@Tag("workflow-it")
@Testcontainers
@SpringBootTest(classes = RadiologyCenterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IndependentWorkflowIT.RejectAllJwtFixture.class)
class IndependentWorkflowIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.liquibase.enabled", () -> "true");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RejectAllJwtFixture {
        @Bean
        JwtDecoder domainOnlyDecoder() {
            return token -> {
                throw new JwtException("Synthetic domain workflow test does not authenticate API requests");
            };
        }
    }

    @Autowired
    private RadiologyRisService radiology;

    @AfterEach
    void clearFacility() {
        FacilityContext.clear();
    }

    @Test
    void persistsUrgentWorklistMppsCriticalReportAndVerifiedReadbackAcrossConnections() throws Exception {
        UUID facility = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        UUID visit = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);

        ModalityWorklistEntity exam = radiology.scheduleImagingExam(new ScheduleExamRequest(
                patient, visit, "CT", "70450", "Synthetic CT Head",
                "HEAD", "Synthetic suspected hemorrhage", "CT-ROOM-01", "doctor-ordering", true, null));
        assertEquals("SCHEDULED", exam.getMppsStatus());
        assertNotNull(exam.getStudyInstanceUid());
        assertEquals(1L, countRowsForPatient("hc_ris_modality_worklist", facility, patient));

        assertThrows(ResponseStatusException.class, () -> radiology.signReport(
                new SignRadiologyReportRequest(exam.getId(), "Preliminary observations",
                        "Cannot sign before acquisition", "radiologist-synthetic", true,
                        "NEURO_CRITICAL", null)));
        assertThrows(ResponseStatusException.class, () -> radiology.updateMpps(
                exam.getId(), new MppsUpdateRequest("COMPLETED", "tech-synthetic", 2, 45)));

        radiology.updateMpps(exam.getId(), new MppsUpdateRequest(
                "IN_PROGRESS", "tech-synthetic", 1, 10));
        ModalityWorklistEntity completed = radiology.updateMpps(exam.getId(), new MppsUpdateRequest(
                "COMPLETED", "tech-synthetic", 2, 45));
        assertEquals("COMPLETED", completed.getMppsStatus());
        assertNotNull(completed.getCompletedAt());
        assertEquals("COMPLETED", persistedWorklistStatus(facility, exam.getId()));

        RadiologyReportEntity signed = radiology.signReport(new SignRadiologyReportRequest(
                exam.getId(), "Synthetic acute hematoma", "Synthetic critical finding",
                "radiologist-synthetic", true, "NEURO_CRITICAL", null));
        assertEquals("FINAL", signed.getReportStatus());
        assertFalse(signed.isCriticalAcknowledged());
        assertEquals(1, radiology.listUnacknowledgedCriticalFindings().size());

        assertThrows(IllegalArgumentException.class,
                () -> radiology.acknowledgeCriticalFinding(signed.getId(),
                        new AcknowledgeCriticalRequest("doctor-accepting", "")));
        RadiologyReportEntity acknowledged = radiology.acknowledgeCriticalFinding(
                signed.getId(), new AcknowledgeCriticalRequest(
                        "doctor-accepting", "Synthetic two-way critical finding read-back"));
        Instant firstAcknowledgedAt = acknowledged.getAcknowledgedAt();
        assertNotNull(firstAcknowledgedAt);
        assertEquals(0, radiology.listUnacknowledgedCriticalFindings().size());
        assertEquals(0L, countUnacknowledgedCritical(facility, patient));
        assertSame(acknowledged, radiology.acknowledgeCriticalFinding(
                signed.getId(), new AcknowledgeCriticalRequest(
                        "doctor-accepting", "Synthetic two-way critical finding read-back")));
        assertEquals(firstAcknowledgedAt, acknowledged.getAcknowledgedAt());
        assertThrows(IllegalStateException.class,
                () -> radiology.acknowledgeCriticalFinding(signed.getId(),
                        new AcknowledgeCriticalRequest("another-clinician", "Claiming another's acknowledgment")));

        FacilityContext.setFacilityId(UUID.randomUUID());
        assertThrows(ResponseStatusException.class,
                () -> radiology.acknowledgeCriticalFinding(signed.getId(),
                        new AcknowledgeCriticalRequest("intruder", "Unauthorized cross-site review")));
    }

    @Test
    void rejectsInvalidStudyAndForeignPatientAmendmentAndPreservesOriginalSignedReport() throws Exception {
        UUID facility = UUID.randomUUID();
        FacilityContext.setFacilityId(facility);
        assertThrows(IllegalArgumentException.class,
                () -> radiology.scheduleImagingExam(new ScheduleExamRequest(
                        UUID.randomUUID(), UUID.randomUUID(), "UNKNOWN", "PROC", "Synthetic procedure",
                        "CHEST", "Synthetic indication", "XR01", "doctor-ordering", false, null)));
        assertThrows(IllegalArgumentException.class,
                () -> radiology.scheduleImagingExam(new ScheduleExamRequest(
                        UUID.randomUUID(), UUID.randomUUID(), "MR", "PROC", "Synthetic procedure",
                        "BRAIN", "Synthetic indication", "MR01", "doctor-ordering", false, "not.valid.uid")));

        ModalityWorklistEntity first = completedStudy(UUID.randomUUID(), "MR");
        ModalityWorklistEntity second = completedStudy(UUID.randomUUID(), "MR");
        RadiologyReportEntity original = radiology.signReport(new SignRadiologyReportRequest(
                first.getId(), "Synthetic normal findings", "Synthetic normal report",
                "radiologist-synthetic", false, null, null));
        assertEquals("FINAL", original.getReportStatus());
        assertTrue(original.isCriticalAcknowledged());

        assertThrows(SecurityException.class, () -> radiology.signReport(new SignRadiologyReportRequest(
                second.getId(), "Copied first report", "Wrong patient's report",
                "radiologist-synthetic", false, null, original.getId())));
        assertEquals(1L, countReportsForWorklist(facility, first.getId()));
        assertEquals(0L, countReportsForWorklist(facility, second.getId()));

        RadiologyReportEntity amendment = radiology.signReport(new SignRadiologyReportRequest(
                first.getId(), "Corrected synthetic findings", "Corrected synthetic impression",
                "radiologist-synthetic", false, null, original.getId()));
        assertEquals("AMENDED", amendment.getReportStatus());
        assertEquals(original.getId(), amendment.getSupersedesReportId());
        assertEquals("FINAL", persistedReportStatus(facility, original.getId()),
                "Original signed report must remain immutable");
        assertEquals("AMENDED", persistedReportStatus(facility, amendment.getId()));
        assertEquals(2L, countReportsForWorklist(facility, first.getId()));
    }

    private ModalityWorklistEntity completedStudy(UUID patient, String modality) {
        ModalityWorklistEntity study = radiology.scheduleImagingExam(new ScheduleExamRequest(
                patient, UUID.randomUUID(), modality, "PROC-SYN", "Synthetic imaging study",
                "BRAIN", "Synthetic indication", "MR-01", "doctor-ordering", false, null));
        radiology.updateMpps(study.getId(), new MppsUpdateRequest("IN_PROGRESS", "tech-synthetic", 1, 10));
        radiology.updateMpps(study.getId(), new MppsUpdateRequest("COMPLETED", "tech-synthetic", 2, 20));
        return study;
    }

    private long countRowsForPatient(String table, UUID facility, UUID patient) throws Exception {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE facility_id = ? AND patient_id = ?")) {
            ps.setObject(1, facility);
            ps.setObject(2, patient);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    private long countUnacknowledgedCritical(UUID facility, UUID patient) throws Exception {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM hc_ris_diagnostic_reports WHERE facility_id = ? "
                + "AND patient_id = ? AND critical_finding = true AND critical_acknowledged = false")) {
            ps.setObject(1, facility);
            ps.setObject(2, patient);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    private long countReportsForWorklist(UUID facility, UUID worklist) throws Exception {
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM hc_ris_diagnostic_reports WHERE facility_id = ? AND worklist_item_id = ?")) {
            ps.setObject(1, facility);
            ps.setObject(2, worklist);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                return r.getLong(1);
            }
        }
    }

    private String persistedWorklistStatus(UUID facility, UUID study) throws Exception {
        return status("hc_ris_modality_worklist", "mpps_status", facility, study);
    }

    private String persistedReportStatus(UUID facility, UUID report) throws Exception {
        return status("hc_ris_diagnostic_reports", "report_status", facility, report);
    }

    private String status(String table, String column, UUID facility, UUID id) throws Exception {
        // Table and column are constants from internal test helper call sites.
        try (Connection c = connection(); PreparedStatement ps = c.prepareStatement(
                "SELECT " + column + " FROM " + table + " WHERE facility_id = ? AND id = ?")) {
            ps.setObject(1, facility);
            ps.setObject(2, id);
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
