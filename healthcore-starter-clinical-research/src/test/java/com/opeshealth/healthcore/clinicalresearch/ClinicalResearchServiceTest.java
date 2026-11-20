package com.opeshealth.healthcore.clinicalresearch;

import com.opeshealth.healthcore.clinicalresearch.domain.ClinicalResearchEntities.*;
import com.opeshealth.healthcore.clinicalresearch.service.ClinicalResearchService;
import com.opeshealth.healthcore.clinicalresearch.service.ClinicalResearchService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClinicalResearchServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesEligibilityEconsentQueryLockSaeExpeditedFlagAndSdtmExport() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        TrialSubjectRepository subRepo = mock(TrialSubjectRepository.class);
        EcrfEntryRepository ecrfRepo = mock(EcrfEntryRepository.class);
        SafetyEventRepository safetyRepo = mock(SafetyEventRepository.class);
        when(subRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ecrfRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(safetyRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        ClinicalResearchService service = new ClinicalResearchService(subRepo, ecrfRepo, safetyRepo, null);

        assertThrows(IllegalStateException.class, () -> service.enrollSubject(
                new EnrollSubjectRequest("TRIAL-01", "SITE01", "SUBJ-01", "ARM-A", true, "")));

        TrialSubjectEntity sub = service.enrollSubject(
                new EnrollSubjectRequest("TRIAL-01", "SITE01", "SUBJ-01", "ARM-A", true, "ICF-v2.1"));
        when(subRepo.findByIdAndFacilityId(sub.getId(), facId)).thenReturn(Optional.of(sub));
        when(subRepo.findByFacilityIdAndProtocolCode(facId, "TRIAL-01")).thenReturn(List.of(sub));

        EcrfEntryEntity ecrf = service.captureEcrfVisit(new CaptureEcrfRequest(sub.getId(), "WEEK4", "VS", "{\"sysbp\":122}"));
        when(ecrfRepo.findByIdAndFacilityId(ecrf.getId(), facId)).thenReturn(Optional.of(ecrf));

        service.raiseDataQuery(ecrf.getId());
        assertThrows(IllegalStateException.class, () -> service.signAndLockEcrf(ecrf.getId(), "pi.dr.ngono"));

        service.resolveDataQuery(ecrf.getId(), "{\"sysbp\":120}");
        service.signAndLockEcrf(ecrf.getId(), "pi.dr.ngono");
        assertEquals("LOCKED", ecrf.getStatus());
        assertNotNull(ecrf.getPiSigSha256());

        SafetyEventEntity sae = service.reportSafetyEvent(new ReportSafetyEventRequest(
                sub.getId(), "SAE", "Anaphylactic reaction", true, "PROBABLE", "crc.01"));
        assertTrue(sae.isExpeditedReportRequired());

        Map<String, Object> sdtm = service.exportDeidentifiedCdiscSdtm("TRIAL-01");
        assertEquals(true, sdtm.get("deidentified"));
        assertEquals(1, sdtm.get("subjectCount"));
    }
}
