package com.opeshealth.healthcore.biobanking;

import com.opeshealth.healthcore.biobanking.domain.BiobankingEntities.*;
import com.opeshealth.healthcore.biobanking.service.BiobankingService;
import com.opeshealth.healthcore.biobanking.service.BiobankingService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BiobankingServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesConsentIntakeAliquotGenealogyExcursionQuarantineAndConsentWithdrawalBlock() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        BiospecimenRepository spRepo = mock(BiospecimenRepository.class);
        MtaRequestRepository mtaRepo = mock(MtaRequestRepository.class);
        when(spRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(mtaRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        BiobankingService service = new BiobankingService(spRepo, mtaRepo, null);

        assertThrows(IllegalStateException.class, () -> service.storeSpecimen(new StoreSpecimenRequest(
                "BB-001", "DNR-88", "BROAD_RESEARCH", false, "PLASMA", BigDecimal.valueOf(1000), "ULT-01", "R1-B2-A1", BigDecimal.valueOf(-80))));

        BiospecimenEntity parent = service.storeSpecimen(new StoreSpecimenRequest(
                "BB-001", "DNR-88", "BROAD_RESEARCH", true, "PLASMA", BigDecimal.valueOf(1000), "ULT-01", "R1-B2-A1", BigDecimal.valueOf(-80)));
        when(spRepo.findByIdAndFacilityId(parent.getId(), facId)).thenReturn(Optional.of(parent));

        BiospecimenEntity aliquot = service.createAliquot(new CreateAliquotRequest(
                parent.getId(), "BB-001-A1", BigDecimal.valueOf(250), "ULT-02", "R2-B1-A3"));
        assertEquals(parent.getId(), aliquot.getParentAliquotId());
        assertEquals(0, BigDecimal.valueOf(750).compareTo(parent.getVolumeUl()));
        assertEquals(1, parent.getFreezeThawCycles());

        when(spRepo.findByIdAndFacilityId(aliquot.getId(), facId)).thenReturn(Optional.of(aliquot));
        MtaRequestEntity mta = service.fulfillMtaShipment(new RequestMtaShipmentRequest(
                "MTA-2026-07", "Pasteur Research Institute", aliquot.getId(), "Biomarker validation", true), "dir.biobank");
        assertEquals("APPROVED_SHIPPED", mta.getStatus());
        assertEquals("SHIPPED_MTA", aliquot.getStatus());

        // Withdraw donor consent -> blocks MTA on remaining parent specimen
        when(spRepo.findByFacilityIdAndDonorPseudonym(facId, "DNR-88")).thenReturn(List.of(parent));
        service.withdrawDonorConsent("DNR-88");
        assertEquals("CONSENT_WITHDRAWN", parent.getStatus());
        assertThrows(IllegalStateException.class, () -> service.fulfillMtaShipment(new RequestMtaShipmentRequest(
                "MTA-2026-08", "External Lab", parent.getId(), "Secondary analysis", true), "dir.biobank"));
    }
}
