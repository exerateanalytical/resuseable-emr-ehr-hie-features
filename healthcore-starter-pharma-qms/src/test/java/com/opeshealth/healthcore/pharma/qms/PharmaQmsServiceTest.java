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
    void enforcesSopMakerCheckerOosCoaBlockAndCriticalCapaEffectiveness() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        ControlledDocumentRepository docRepo = mock(ControlledDocumentRepository.class);
        QcCertificateRepository qcRepo = mock(QcCertificateRepository.class);
        DeviationCapaRepository capaRepo = mock(DeviationCapaRepository.class);
        when(docRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(qcRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(capaRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmaQmsService service = new PharmaQmsService(docRepo, qcRepo, capaRepo, null);

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
    }
}
