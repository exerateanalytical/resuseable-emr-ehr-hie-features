package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.DiagnosticReportRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.LabSpecimenRepository;
import com.opeshealth.healthcore.starters.LisDiagnosticsStarter.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LisDiagnosticsStarterTest {

    @Test
    void recordsCriticalResultClinicianAcknowledgmentInPersistedField() {
        DiagnosticReportRepository repo = mock(DiagnosticReportRepository.class);
        com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity report =
                new com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity();
        java.util.UUID reportId = report.getId();
        org.mockito.Mockito.when(repo.findById(reportId)).thenReturn(java.util.Optional.of(report));
        org.mockito.Mockito.when(repo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

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
        assertFalse(fail.runAccepted());

        WestgardMultiRuleEvaluation pass = service.evaluateWestgardControlRun(new WestgardControlRunRequest(
                "COBAS-6000", "2823-3", new BigDecimal("4.5"), new BigDecimal("0.2"),
                List.of(new BigDecimal("4.55"), new BigDecimal("4.48"))));
        assertTrue(pass.runAccepted());
    }
}
