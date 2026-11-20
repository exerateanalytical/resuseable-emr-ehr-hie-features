package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.ContactTracingEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.DiseaseSurveillanceCaseEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.ContactTracingRepository;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.SurveillanceCaseRepository;
import com.opeshealth.healthcore.starters.DiseaseSurveillanceStarter.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiseaseSurveillanceStarterTest {

    @Test
    void generatesIdsrDistrictBulletinAndContactTracingSecondaryAttackRate() {
        SurveillanceCaseRepository caseRepo = mock(SurveillanceCaseRepository.class);
        ContactTracingRepository contactRepo = mock(ContactTracingRepository.class);

        DiseaseSurveillanceCaseEntity c1 = new DiseaseSurveillanceCaseEntity();
        c1.setDistrictCode("DLA-BONANJO");
        c1.setDiseaseIcd10("A00.9");
        c1.setCaseClassification("CONFIRMED");
        when(caseRepo.findByDistrictCode("DLA-BONANJO")).thenReturn(List.of(c1));

        ContactTracingEntity ct = new ContactTracingEntity();
        ct.setIndexCaseId(c1.getId());
        ct.setRiskLevel("HIGH");
        ct.setConvertedToCase(true);
        when(contactRepo.findAll()).thenReturn(List.of(ct));

        IdsrEpidemicIntelligenceService service = new IdsrEpidemicIntelligenceService(
                mock(PublicHealthService.class), caseRepo, contactRepo);

        DistrictEpidemicBulletin bulletin = service.generateDistrictBulletin("DLA-BONANJO");
        assertEquals(1, bulletin.totalNotifiableCases());
        assertEquals(1, bulletin.labConfirmedCases());

        ContactTracingNetworkSummary ring = service.analyzeContactTracingRing(c1.getId());
        assertEquals(1, ring.totalContactsTraced());
        assertEquals(100.0, ring.secondaryAttackRatePercent(), 0.01);
    }
}
