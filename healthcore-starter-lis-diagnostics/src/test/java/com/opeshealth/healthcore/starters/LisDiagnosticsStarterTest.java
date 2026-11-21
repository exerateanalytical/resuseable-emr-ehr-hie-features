package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.DiagnosticReportRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.LabSpecimenRepository;
import com.opeshealth.healthcore.starters.LisDiagnosticsStarter.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LisDiagnosticsStarterTest {

    @AfterEach
    void clearFacilityContext() {
        FacilityContext.clear();
    }

    @Test
    void recordsCriticalResultClinicianAcknowledgmentInPersistedField() {
        DiagnosticReportRepository repo = mock(DiagnosticReportRepository.class);
        com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity report =
                new com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity();
        UUID facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        report.setFacilityId(facilityId);
        report.setVerifiedAt(Instant.now());
        report.setWestgardQcPassed(true);
        report.setAbnormalFlag("HH");
        UUID reportId = report.getId();
        when(repo.findByIdAndFacilityId(reportId, facilityId)).thenReturn(Optional.of(report));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LisWestgardAndCriticalResultService service = new LisWestgardAndCriticalResultService(
                mock(PharmacyLisService.class), mock(LabSpecimenRepository.class), repo);
        service.recordCriticalPanicReadBack(new CriticalPanicReadBackRequest(
                reportId, "clinician-A", "scientist-B"));
        assertEquals("clinician-A", report.getPanicAcknowledgedBy());
        org.mockito.Mockito.verify(repo).save(report);
    }

    @Test
    void rejectsRunViolatingWestgard13sRuleAndAcceptsInControlRun() {
        LisWestgardAndCriticalResultService service = new LisWestgardAndCriticalResultService(
                mock(PharmacyLisService.class),
                mock(LabSpecimenRepository.class),
                mock(DiagnosticReportRepository.class));

        WestgardMultiRuleEvaluation fail = service.evaluateWestgardControlRun(new WestgardControlRunRequest(
                "COBAS-6000", "2823-3", new BigDecimal("4.5"), new BigDecimal("0.2"),
                List.of(new BigDecimal("4.6"), new BigDecimal("5.3"))));
        assertTrue(fail.rule13sRejected());
        assertEquals("OUT_OF_CONTROL_REJECTED", fail.runStatus());

        WestgardMultiRuleEvaluation pass = service.evaluateWestgardControlRun(new WestgardControlRunRequest(
                "COBAS-6000", "2823-3", new BigDecimal("4.5"), new BigDecimal("0.2"),
                List.of(new BigDecimal("4.55"), new BigDecimal("4.48"))));
        assertEquals("IN_CONTROL_ACCEPTED", pass.runStatus());
    }


    @Test
    void criticalReadbackRejectsMissingOrForeignFacility() {
        DiagnosticReportRepository repo = mock(DiagnosticReportRepository.class);
        LisWestgardAndCriticalResultService service = new LisWestgardAndCriticalResultService(
                mock(PharmacyLisService.class), mock(LabSpecimenRepository.class), repo);
        UUID reportId = UUID.randomUUID();
        CriticalPanicReadBackRequest request =
                new CriticalPanicReadBackRequest(reportId, "clinician-A", "scientist-B");

        assertThrows(AccessDeniedException.class, () -> service.recordCriticalPanicReadBack(request));
        UUID unauthorizedFacility = UUID.randomUUID();
        FacilityContext.setFacilityId(unauthorizedFacility);
        assertThrows(AccessDeniedException.class, () -> service.recordCriticalPanicReadBack(request));
        verify(repo).findByIdAndFacilityId(reportId, unauthorizedFacility);
        verify(repo, never()).findById(any());
        verify(repo, never()).save(any());
    }

    @Test
    void criticalReadbackRequiresVerifiedPanicResultAndPreservesExistingClinician() {
        DiagnosticReportRepository repo = mock(DiagnosticReportRepository.class);
        com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity report =
                new com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity();
        UUID facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
        report.setFacilityId(facilityId);
        report.setAbnormalFlag("HH");
        when(repo.findByIdAndFacilityId(report.getId(), facilityId)).thenReturn(Optional.of(report));
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        LisWestgardAndCriticalResultService service = new LisWestgardAndCriticalResultService(
                mock(PharmacyLisService.class), mock(LabSpecimenRepository.class), repo);
        CriticalPanicReadBackRequest request =
                new CriticalPanicReadBackRequest(report.getId(), "clinician-A", "scientist-B");

        assertThrows(IllegalStateException.class, () -> service.recordCriticalPanicReadBack(request));
        report.setVerifiedAt(Instant.now());
        report.setAbnormalFlag("N");
        assertThrows(IllegalStateException.class, () -> service.recordCriticalPanicReadBack(request));
        report.setAbnormalFlag("HH");
        report.setWestgardQcPassed(false);
        assertThrows(IllegalStateException.class, () -> service.recordCriticalPanicReadBack(request));
        verify(repo, never()).save(any());

        report.setWestgardQcPassed(true);
        service.recordCriticalPanicReadBack(request);
        assertSame(report, service.recordCriticalPanicReadBack(request)); // retry is idempotent
        assertThrows(IllegalStateException.class, () -> service.recordCriticalPanicReadBack(
                new CriticalPanicReadBackRequest(report.getId(), "clinician-C", "scientist-B")));
        verify(repo, times(1)).save(report);
    }

}
