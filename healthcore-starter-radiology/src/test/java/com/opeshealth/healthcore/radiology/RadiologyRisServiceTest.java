/*
 * =============================================================================
 * Opes HealthCore SDK — Radiology RIS Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.radiology;

import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RadiologyRisServiceTest {

    private ModalityWorklistRepository worklistRepo;
    private RadiologyReportRepository reportRepo;
    private ClinicalAuditService auditService;
    private RadiologyRisService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        worklistRepo = mock(ModalityWorklistRepository.class);
        reportRepo = mock(RadiologyReportRepository.class);
        auditService = mock(ClinicalAuditService.class);
        service = new RadiologyRisService(worklistRepo, reportRepo, auditService);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void rejectsInvalidDicomUidAndUnsupportedModality() {
        assertThrows(IllegalArgumentException.class, () ->
                service.scheduleImagingExam(new ScheduleExamRequest(
                        UUID.randomUUID(), null, "INVALID", "71046", "Chest X-Ray",
                        "Chest", "Cough", "XR_BAY_1", "dr-1", false, null)));

        assertThrows(IllegalArgumentException.class, () ->
                service.scheduleImagingExam(new ScheduleExamRequest(
                        UUID.randomUUID(), null, "CT", "70450", "Head CT",
                        "Head", "Trauma", "CT_1", "dr-1", true, "bad-uid-001")));
    }

    @Test
    void enforcesMppsCompletionBeforeSigningAndClosedLoopCriticalReadback() {
        when(worklistRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModalityWorklistEntity exam = service.scheduleImagingExam(new ScheduleExamRequest(
                UUID.randomUUID(), null, "CT", "70450", "CT Head Without Contrast",
                "Brain", "Acute headache", "CT_MAIN", "dr-er-1", true, null));
        when(worklistRepo.findByIdAndFacilityId(exam.getId(), facilityId)).thenReturn(Optional.of(exam));

        // Cannot sign before MPPS COMPLETED
        assertThrows(ResponseStatusException.class, () ->
                service.signReport(new SignRadiologyReportRequest(
                        exam.getId(), "Acute hemorrhage", "Intracranial bleed", "dr-rad-1", true, "NEURO_CRITICAL", null)));

        assertThrows(ResponseStatusException.class, () ->
                service.updateMpps(exam.getId(), new MppsUpdateRequest("COMPLETED", "tech-01", 3, 210)));
        service.updateMpps(exam.getId(), new MppsUpdateRequest("IN_PROGRESS", "tech-01", 1, 10));
        service.updateMpps(exam.getId(), new MppsUpdateRequest("COMPLETED", "tech-01", 3, 210));
        assertEquals("COMPLETED", exam.getMppsStatus());

        RadiologyReportEntity report = service.signReport(new SignRadiologyReportRequest(
                exam.getId(), "Acute subdural hematoma", "Subdural hematoma with midline shift",
                "dr-rad-1", true, "NEURO_CRITICAL", null));
        assertTrue(report.isCriticalFinding());
        assertFalse(report.isCriticalAcknowledged());

        when(reportRepo.findByIdAndFacilityId(report.getId(), facilityId)).thenReturn(Optional.of(report));
        assertThrows(IllegalArgumentException.class, () ->
                service.acknowledgeCriticalFinding(report.getId(), new AcknowledgeCriticalRequest("dr-er-1", "")));

        RadiologyReportEntity acked = service.acknowledgeCriticalFinding(
                report.getId(), new AcknowledgeCriticalRequest("dr-er-1", "Verbal read-back completed; neurosurgery consulted."));
        assertTrue(acked.isCriticalAcknowledged());
        assertEquals("dr-er-1", acked.getAcknowledgedBy());
        assertNotNull(acked.getAcknowledgedAt());
        java.time.Instant originalAck = acked.getAcknowledgedAt();
        assertSame(acked, service.acknowledgeCriticalFinding(
                report.getId(), new AcknowledgeCriticalRequest(
                        "dr-er-1", "Verbal read-back completed; neurosurgery consulted.")));
        assertEquals(originalAck, acked.getAcknowledgedAt());
        assertThrows(IllegalStateException.class, () -> service.acknowledgeCriticalFinding(
                report.getId(), new AcknowledgeCriticalRequest(
                        "another-clinician", "I now claim the existing acknowledgment")));
        verify(reportRepo, times(2)).save(any()); // First sign, first ACK only.
    }

    @Test
    void preventsAmendmentOfAnotherPatientsImagingReport() {
        when(worklistRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ModalityWorklistEntity a = service.scheduleImagingExam(new ScheduleExamRequest(
                UUID.randomUUID(), UUID.randomUUID(), "MR", "MR-BRAIN",
                "Synthetic brain MRI", "BRAIN", "Synthetic indication", "MR-01", "dr-1", false, null));
        ModalityWorklistEntity b = service.scheduleImagingExam(new ScheduleExamRequest(
                UUID.randomUUID(), UUID.randomUUID(), "MR", "MR-BRAIN",
                "Synthetic brain MRI", "BRAIN", "Synthetic indication", "MR-01", "dr-1", false, null));
        when(worklistRepo.findByIdAndFacilityId(a.getId(), facilityId)).thenReturn(Optional.of(a));
        when(worklistRepo.findByIdAndFacilityId(b.getId(), facilityId)).thenReturn(Optional.of(b));
        for (ModalityWorklistEntity study : java.util.List.of(a, b)) {
            service.updateMpps(study.getId(), new MppsUpdateRequest("IN_PROGRESS", "tech-01", 1, 10));
            service.updateMpps(study.getId(), new MppsUpdateRequest("COMPLETED", "tech-01", 2, 30));
        }
        RadiologyReportEntity original = service.signReport(new SignRadiologyReportRequest(
                a.getId(), "Synthetic normal findings", "Synthetic benign assessment",
                "radiologist-1", false, null, null));
        when(reportRepo.findByIdAndFacilityId(original.getId(), facilityId)).thenReturn(Optional.of(original));

        assertThrows(SecurityException.class, () -> service.signReport(
                new SignRadiologyReportRequest(b.getId(), "Copied findings", "Wrong patient",
                        "radiologist-1", false, null, original.getId())));
        verify(reportRepo, times(1)).save(any());
    }
}
