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
    void enforcesEligibilityEconsentQueryLockSaeIpAccountabilityAndExports() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        TrialSubjectRepository subRepo = mock(TrialSubjectRepository.class);
        EcrfEntryRepository ecrfRepo = mock(EcrfEntryRepository.class);
        SafetyEventRepository safetyRepo = mock(SafetyEventRepository.class);
        InvestigationalProductRepository ipRepo = mock(InvestigationalProductRepository.class);
        when(subRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ecrfRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(safetyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ipRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        ClinicalResearchService service = new ClinicalResearchService(subRepo, ecrfRepo, safetyRepo, ipRepo, null);

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

        // Investigational Product accountability + maker-checker witness signature on destruction
        InvestigationalProductLedgerEntity kit = service.dispenseInvestigationalProduct(
                new DispenseIpRequest(sub.getId(), "KIT-0042", "LOT-2026A", 30, "pharm.trial.01"));
        when(ipRepo.findByIdAndFacilityId(kit.getId(), facId)).thenReturn(Optional.of(kit));
        assertThrows(SecurityException.class, () -> service.reconcileInvestigationalProduct(kit.getId(),
                new ReconcileIpRequest(4, 2, "pharm.trial.01", "pharm.trial.01")));
        InvestigationalProductLedgerEntity destroyed = service.reconcileInvestigationalProduct(kit.getId(),
                new ReconcileIpRequest(4, 2, "pharm.trial.01", "qa.witness.02"));
        assertEquals("DESTROYED", destroyed.getStatus());
        assertNotNull(destroyed.getWitnessSigSha256());

        // De-identified CDISC SDTM, CDASH/ADaM, and OMOP CDM exports
        Map<String, Object> sdtm = service.exportDeidentifiedCdiscSdtm("TRIAL-01");
        assertEquals(true, sdtm.get("deidentified"));
        assertEquals(1, sdtm.get("subjectCount"));

        Map<String, Object> adam = service.exportDeidentifiedCdiscCdashAndAdam("TRIAL-01");
        assertEquals(true, adam.get("deidentified"));
        assertEquals(1, adam.get("adslRecordCount"));

        Map<String, Object> omop = service.exportDeidentifiedOmopCdm("TRIAL-01");
        assertEquals(true, omop.get("deidentified"));
        assertEquals(1, omop.get("personCount"));
    }
}
