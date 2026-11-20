package com.opeshealth.healthcore.pharmacy.commerce;

import com.opeshealth.healthcore.pharmacy.commerce.domain.PharmacyCommerceEntities.*;
import com.opeshealth.healthcore.pharmacy.commerce.service.PharmacyCommerceService;
import com.opeshealth.healthcore.pharmacy.commerce.service.PharmacyCommerceService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PharmacyCommerceServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesFefoSelectionControlledDrugWitnessAndRecallBlockWithoutEmrOrLis() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PharmacyProductRepository prodRepo = mock(PharmacyProductRepository.class);
        PharmacyLotRepository lotRepo = mock(PharmacyLotRepository.class);
        PharmacyDispenseRepository dispRepo = mock(PharmacyDispenseRepository.class);
        when(prodRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lotRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dispRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmacyCommerceService service = new PharmacyCommerceService(prodRepo, lotRepo, dispRepo, null);

        PharmacyProductEntity morphine = service.createProduct(new CreateProductRequest(
                "SKU-MOR-10", "Morphine Sulfate", "MST Continus", "10mg", "TABLET", "N02AA01", true, 10, BigDecimal.valueOf(1500)));
        when(prodRepo.findByIdAndFacilityId(morphine.getId(), facId)).thenReturn(Optional.of(morphine));

        // Expired goods receipt rejected
        assertThrows(IllegalStateException.class, () -> service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "MedDist Ltd", "PO-001", "03400930000012", "LOT-OLD", LocalDate.now().minusDays(2), 50)));

        PharmacyLotEntity soonLot = service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "MedDist Ltd", "PO-002", "03400930000012", "LOT-SOON", LocalDate.now().plusDays(45), 100));
        PharmacyLotEntity laterLot = service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "MedDist Ltd", "PO-003", "03400930000012", "LOT-LATER", LocalDate.now().plusDays(300), 100));
        when(lotRepo.findByFacilityIdAndProductIdOrderByExpiryDateAsc(facId, morphine.getId())).thenReturn(List.of(soonLot, laterLot));

        // Controlled drug without second witness blocked
        assertThrows(SecurityException.class, () -> service.dispenseFefo(new DispensePrescriptionRequest(
                "RX-100", "WALKIN-01", morphine.getId(), 10, 0, "pharm.alice", "pharm.alice")));

        // FEFO picks soonLot first
        PharmacyDispenseEntity disp = service.dispenseFefo(new DispensePrescriptionRequest(
                "RX-100", "WALKIN-01", morphine.getId(), 10, 0, "pharm.alice", "pharm.bob"));
        assertEquals(soonLot.getId(), disp.getLotId());
        assertEquals(90, soonLot.getQuantityOnHand());

        // Recall soonLot -> FEFO automatically skips recalled lot and picks laterLot
        when(lotRepo.findByFacilityIdAndBatchNumber(facId, "LOT-SOON")).thenReturn(List.of(soonLot));
        service.quarantineRecalledBatch("LOT-SOON");
        assertEquals("QUARANTINED_RECALL", soonLot.getStatus());

        PharmacyDispenseEntity disp2 = service.dispenseFefo(new DispensePrescriptionRequest(
                "RX-101", "WALKIN-02", morphine.getId(), 10, 0, "pharm.alice", "pharm.bob"));
        assertEquals(laterLot.getId(), disp2.getLotId());
    }
}
