package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.Hl7AstmDicomStarter.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Hl7AstmDicomFixtureTest {

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void verifiesAstmChecksumHl7AckAndTenantScopedDicomStudyFixture() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        HieInteropService hie = mock(HieInteropService.class);
        DicomStudyRepository dicomRepo = mock(DicomStudyRepository.class);

        when(hie.parseAstmAnalyzerFrame(any())).thenReturn(List.of(
                Map.of("loinc", "2823-3", "value", "4.4", "unit", "mmol/L")));
        when(hie.ingestHl7v2Message(eq(facId), any())).thenReturn(Map.of("ackCode", "AA", "messageType", "ORU^R01"));

        DicomStudyEntity study = new DicomStudyEntity();
        study.setFacilityId(facId);
        study.setPatientId(patientId);
        study.setModality("CT");
        study.setStudyInstanceUid("1.2.826.0.1.3680043.10.942.1001");
        when(dicomRepo.findByFacilityIdAndPatientIdOrderByStudyDateDesc(facId, patientId)).thenReturn(List.of(study));

        ClinicalDeviceAndImagingGatewayService service = new ClinicalDeviceAndImagingGatewayService(hie, dicomRepo);
        String astmFixture = "H|\\^&|||COBAS|||||||P|1\rR|1|^^^2823-3|4.4|mmol/L||N||F\rL|1|N";
        AstmAnalyzerIngestionResult astm = service.verifyAndParseAstmAnalyzerFrame(astmFixture);
        assertEquals(2, astm.calculatedModulo256ChecksumHex().length());
        assertEquals(1, astm.recordCount());

        Map<String, Object> hl7Ack = service.ingestHl7v2MessageWithAck(facId, "MSH|^~\\&|LIS|FAC|HIS|FAC|20261120||ORU^R01|M1|P|2.5.1");
        assertEquals("AA", hl7Ack.get("ackCode"));

        List<DicomStudyEntity> found = service.searchDicomStudies(patientId, "CT");
        assertEquals(1, found.size());
        assertEquals("CT", found.get(0).getModality());
    }
}
