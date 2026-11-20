package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.MedicationBatchRepository;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.PharmacyDispenseRepository;
import com.opeshealth.healthcore.starters.PharmacyInventoryStarter.FacilityPharmacyStockHealth;
import com.opeshealth.healthcore.starters.PharmacyInventoryStarter.PharmacyFefoAndColdStockGovernanceService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PharmacyInventoryStarterTest {

    @Test
    void detectsNearExpiryAndExpiredBatchesForFacility() {
        UUID facId = UUID.randomUUID();
        MedicationBatchRepository batchRepo = mock(MedicationBatchRepository.class);
        PharmacyDispenseRepository dispenseRepo = mock(PharmacyDispenseRepository.class);

        MedicationBatchEntity soon = new MedicationBatchEntity();
        soon.setFacilityId(facId);
        soon.setDrugCode("AMOX500");
        soon.setBatchNumber("B-SOON");
        soon.setExpiryDate(LocalDate.now().plusDays(15));
        soon.setQuantityRemaining(40);

        MedicationBatchEntity expired = new MedicationBatchEntity();
        expired.setFacilityId(facId);
        expired.setDrugCode("INSULIN");
        expired.setBatchNumber("B-EXP");
        expired.setExpiryDate(LocalDate.now().minusDays(2));
        expired.setQuantityRemaining(10);

        when(batchRepo.findAll()).thenReturn(List.of(soon, expired));
        PharmacyFefoAndColdStockGovernanceService service = new PharmacyFefoAndColdStockGovernanceService(
                mock(PharmacyLisService.class), batchRepo, dispenseRepo);

        FacilityPharmacyStockHealth health = service.evaluateFacilityStockHealth(facId, 60);
        assertEquals(facId, health.facilityId());
        assertFalse(health.nearExpiryAlerts().isEmpty());
    }
}
