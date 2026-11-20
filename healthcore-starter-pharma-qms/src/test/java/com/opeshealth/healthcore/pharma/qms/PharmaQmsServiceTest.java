package com.opeshealth.healthcore.pharma.qms;

import com.opeshealth.healthcore.pharma.qms.domain.PharmaQmsEntities.*;
import com.opeshealth.healthcore.pharma.qms.service.PharmaQmsService;
import com.opeshealth.healthcore.pharma.qms.service.PharmaQmsService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PharmaQmsServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesSopMakerCheckerOosCoaBlockCapaEquipmentGateStabilitySupplierAndPqr() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        ControlledDocumentRepository docRepo = mock(ControlledDocumentRepository.class);
        QcCertificateRepository qcRepo = mock(QcCertificateRepository.class);
        DeviationCapaRepository capaRepo = mock(DeviationCapaRepository.class);
        EquipmentQualificationRepository equipRepo = mock(EquipmentQualificationRepository.class);
        StabilitySampleRepository stabilityRepo = mock(StabilitySampleRepository.class);
        SupplierQualificationRepository supplierRepo = mock(SupplierQualificationRepository.class);
        when(docRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(qcRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(capaRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(stabilityRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(supplierRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmaQmsService service = new PharmaQmsService(
                docRepo, qcRepo, capaRepo, equipRepo, stabilityRepo, supplierRepo, null);

        ControlledDocumentEntity sop = service.createControlledDocument(new CreateControlledDocRequest(
                "SOP-QC-001", "v2.0", "SOP", "HPLC Assay Calibration", "Procedure text", "author.jean", LocalDate.now().plusYears(1)));
        when(docRepo.findByIdAndFacilityId(sop.getId(), facId)).thenReturn(Optional.of(sop));
        assertThrows(SecurityException.class, () -> service.approveControlledDocument(sop.getId(), "author.jean", LocalDate.now()));
        ControlledDocumentEntity eff = service.approveControlledDocument(sop.getId(), "qa.director", LocalDate.now());
        assertEquals("EFFECTIVE", eff.getStatus());

        QcCertificateEntity oosQc = service.recordQcTest(new RecordQcTestRequest(
                "COA-2026-01", "BN-01", "FG-AMOX-500", "FINISHED_GOOD", "{\"assayPct\":82.1}", true, "analyst.paul"));
        when(qcRepo.findByIdAndFacilityId(oosQc.getId(), facId)).thenReturn(Optional.of(oosQc));
        assertEquals("OOS_HOLD", oosQc.getQcDisposition());
        assertThrows(IllegalStateException.class, () -> service.issueCertificateOfAnalysis(oosQc.getId(), "qa.director"));

        QcCertificateEntity passQc = service.recordQcTest(new RecordQcTestRequest(
                "COA-2026-02", "BN-02", "FG-AMOX-500", "FINISHED_GOOD", "{\"assayPct\":99.4}", false, "analyst.paul"));
        when(qcRepo.findByIdAndFacilityId(passQc.getId(), facId)).thenReturn(Optional.of(passQc));
        QcCertificateEntity issued = service.issueCertificateOfAnalysis(passQc.getId(), "qa.director");
        assertEquals("PASS_COA_ISSUED", issued.getQcDisposition());
        assertNotNull(issued.getCoaSigSha256());

        DeviationCapaEntity dev = service.openDeviationOrCapa(new OpenDeviationCapaRequest(
                "DEV-2026-09", "DEVIATION", "CRITICAL", "BN-01", "Assay below 90% specification", "analyst.paul"));
        when(capaRepo.findByIdAndFacilityId(dev.getId(), facId)).thenReturn(Optional.of(dev));
        assertThrows(IllegalStateException.class, () -> service.closeDeviationOrCapa(dev.getId(),
                new CloseDeviationCapaRequest("Granulation moisture drift", "Recalibrate dryer & retrain", false, "qa.director")));

        DeviationCapaEntity closed = service.closeDeviationOrCapa(dev.getId(),
                new CloseDeviationCapaRequest("Granulation moisture drift", "Recalibrate dryer & retrain", true, "qa.director"));
        assertEquals("CLOSED", closed.getStatus());
        assertNotNull(closed.getClosureSigSha256());

        // Complaint-to-Recall escalation
        DeviationCapaEntity complaint = service.openDeviationOrCapa(new OpenDeviationCapaRequest(
                "CMP-2026-01", "COMPLAINT", "MAJOR", "BN-01", "Discolored blister pack reported", "pv.officer"));
        when(capaRepo.findByIdAndFacilityId(complaint.getId(), facId)).thenReturn(Optional.of(complaint));
        DeviationCapaEntity recall = service.escalateComplaintToRecall(complaint.getId(), "RCL-2026-01", "qa.director", "Confirmed moisture ingress");
        assertEquals("RECALL", recall.getRecordCategory());
        assertEquals("CRITICAL", recall.getSeverity());

        // Equipment calibration gate (Workflow #8)
        EquipmentQualificationEntity expiredEq = service.registerEquipmentQualification(new RegisterEquipmentRequest(
                "TBL-PRESS-02", "Rotary Tablet Press #2", "PQ",
                LocalDate.now().minusYears(1), LocalDate.now().minusDays(2), "metrology.tech", "qa.engineer"));
        when(equipRepo.findByFacilityIdAndAssetCode(facId, "TBL-PRESS-02")).thenReturn(Optional.of(expiredEq));
        assertEquals("CALIBRATION_EXPIRED", expiredEq.getCalibrationStatus());
        assertThrows(IllegalStateException.class, () -> service.assertEquipmentQualifiedForProduction("TBL-PRESS-02"));

        EquipmentQualificationEntity validEq = service.registerEquipmentQualification(new RegisterEquipmentRequest(
                "HPLC-01", "Agilent HPLC #1", "PQ",
                LocalDate.now().minusDays(10), LocalDate.now().plusMonths(6), "metrology.tech", "qa.engineer"));
        when(equipRepo.findByFacilityIdAndAssetCode(facId, "HPLC-01")).thenReturn(Optional.of(validEq));
        assertEquals("QUALIFIED", service.assertEquipmentQualifiedForProduction("HPLC-01").getCalibrationStatus());

        // Stability sample tracking
        StabilitySampleEntity stab = service.scheduleStabilitySample(new ScheduleStabilitySampleRequest(
                "STAB-AMOX-26", "FG-AMOX-500", "BN-02", "STABILITY_STUDY", "40C/75%RH", 6, LocalDate.now()));
        when(stabilityRepo.findByIdAndFacilityId(stab.getId(), facId)).thenReturn(Optional.of(stab));
        StabilitySampleEntity pulled = service.recordStabilityPullResult(stab.getId(),
                new RecordStabilityResultRequest("{\"assayPct\":98.2}", "PASS", "analyst.paul"));
        assertEquals("PASS", pulled.getDisposition());

        // Supplier qualification
        SupplierQualificationEntity sup = service.qualifySupplier(new QualifySupplierRequest(
                "SUP-API-01", "Global API Labs", "Amoxicillin Trihydrate API",
                LocalDate.now().minusDays(5), LocalDate.now().plusYears(2), "qa.director", "qa.auditor"));
        when(supplierRepo.findByFacilityIdAndSupplierCode(facId, "SUP-API-01")).thenReturn(Optional.of(sup));
        assertEquals("APPROVED", service.assertSupplierApproved("SUP-API-01").getQualificationStatus());

        // Product Quality Review (PQR) summary
        when(qcRepo.findByFacilityIdAndProductCode(facId, "FG-AMOX-500")).thenReturn(List.of(oosQc, passQc));
        when(stabilityRepo.findByFacilityIdAndProductCodeOrderByScheduledPullDateAsc(facId, "FG-AMOX-500")).thenReturn(List.of(pulled));
        when(capaRepo.findByFacilityIdOrderByCreatedAtDesc(facId)).thenReturn(List.of(closed, recall));
        when(equipRepo.findByFacilityIdOrderByAssetCodeAsc(facId)).thenReturn(List.of(expiredEq, validEq));
        Map<String, Object> pqr = service.generateProductQualityReview("FG-AMOX-500");
        assertEquals(2, pqr.get("totalQcBatchesEvaluated"));
        assertEquals(1L, pqr.get("coaPassedCount"));
        assertEquals(1L, pqr.get("oosBatchCount"));
        assertEquals(1L, pqr.get("expiredCalibrationAssetCount"));
    }
}
