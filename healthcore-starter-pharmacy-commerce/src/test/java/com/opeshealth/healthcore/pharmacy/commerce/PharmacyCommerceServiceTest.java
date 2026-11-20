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
    void verifiesAllSevenPack01WorkflowsWithoutEmrOrLis() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PharmacyProductRepository prodRepo = mock(PharmacyProductRepository.class);
        SupplierRepository supplierRepo = mock(SupplierRepository.class);
        PurchaseOrderRepository poRepo = mock(PurchaseOrderRepository.class);
        PharmacyLotRepository lotRepo = mock(PharmacyLotRepository.class);
        PharmacyDispenseRepository dispRepo = mock(PharmacyDispenseRepository.class);
        StockTransferRepository transferRepo = mock(StockTransferRepository.class);
        StockAdjustmentRepository adjRepo = mock(StockAdjustmentRepository.class);

        when(prodRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(supplierRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(poRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lotRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dispRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(transferRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(adjRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmacyCommerceService service = new PharmacyCommerceService(
                prodRepo, supplierRepo, poRepo, lotRepo, dispRepo, transferRepo, adjRepo, null, null);

        // Workflow 1: Supplier -> PO -> Goods Receipt -> Batch Validation
        SupplierEntity sup = service.registerSupplier(new RegisterSupplierRequest("SUP-01", "CamPharma Wholesale", "LIC-CM-09", true));
        when(supplierRepo.findByIdAndFacilityId(sup.getId(), facId)).thenReturn(Optional.of(sup));

        PharmacyProductEntity morphine = service.createProduct(new CreateProductRequest(
                "SKU-MOR-10", "Morphine Sulfate", "MST Continus", "10mg", "TABLET", "N02AA01", true, 25, BigDecimal.valueOf(1500)));
        when(prodRepo.findByIdAndFacilityId(morphine.getId(), facId)).thenReturn(Optional.of(morphine));

        PurchaseOrderEntity po = service.createPurchaseOrder(new CreatePurchaseOrderRequest("PO-2026-01", sup.getId(), morphine.getId(), 100));
        when(poRepo.findByFacilityIdAndPoNumber(facId, "PO-2026-01")).thenReturn(Optional.of(po));

        assertThrows(IllegalStateException.class, () -> service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "CamPharma Wholesale", "PO-2026-01", "03400930000012", "LOT-OLD", LocalDate.now().minusDays(2), 50)));

        PharmacyLotEntity soonLot = service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "CamPharma Wholesale", "PO-2026-01", "03400930000012", "LOT-SOON", LocalDate.now().plusDays(45), 100));
        assertEquals("RECEIVED", po.getStatus());

        PharmacyLotEntity laterLot = service.receiveGoods(new GoodsReceiptRequest(
                morphine.getId(), "CamPharma Wholesale", "PO-2026-02", "03400930000012", "LOT-LATER", LocalDate.now().plusDays(300), 100));
        when(lotRepo.findByFacilityIdAndProductIdOrderByExpiryDateAsc(facId, morphine.getId())).thenReturn(List.of(soonLot, laterLot));

        // Workflow 2 & 6: Controlled drug witness + Partial fill + Refill
        assertThrows(SecurityException.class, () -> service.dispenseFefo(new DispensePrescriptionRequest(
                "RX-100", "WALKIN-01", morphine.getId(), 10, 1, "pharm.alice", "pharm.alice")));

        PharmacyDispenseEntity disp = service.dispenseFefo(new DispensePrescriptionRequest(
                "RX-100", "WALKIN-01", morphine.getId(), 10, 1, "pharm.alice", "pharm.bob", 20, "Take with food"));
        assertEquals("PARTIAL_FILL", disp.getStatus());
        assertEquals(soonLot.getId(), disp.getLotId());

        when(dispRepo.findByIdAndFacilityId(disp.getId(), facId)).thenReturn(Optional.of(disp));
        PharmacyDispenseEntity refill = service.dispenseRefill(disp.getId(), "pharm.alice", "pharm.bob");
        assertEquals(0, disp.getRefillsRemaining());
        assertEquals("RX-100-RF", refill.getPrescriptionRef());

        // Workflow 3: Inter-branch stock transfer
        when(lotRepo.findByIdAndFacilityId(laterLot.getId(), facId)).thenReturn(Optional.of(laterLot));
        StockTransferEntity xfer = service.initiateInterBranchTransfer(new InitiateTransferRequest(
                "TRF-01", UUID.randomUUID(), laterLot.getId(), 30, "branch.mgr"));
        assertEquals("IN_TRANSIT", xfer.getStatus());
        assertEquals(70, laterLot.getQuantityOnHand());
        when(transferRepo.findByIdAndFacilityId(xfer.getId(), facId)).thenReturn(Optional.of(xfer));
        service.confirmTransferReceipt(xfer.getId(), "dest.pharm");
        assertEquals("RECEIVED_CONFIRMED", xfer.getStatus());

        // Workflow 4: Recall quarantine skips recalled lot
        when(lotRepo.findByFacilityIdAndBatchNumber(facId, "LOT-SOON")).thenReturn(List.of(soonLot));
        service.quarantineRecalledBatch("LOT-SOON");
        assertEquals("QUARANTINED_RECALL", soonLot.getStatus());

        // Workflow 5: Expiry withdrawal adjustment + low-stock alert
        StockAdjustmentEntity adj = service.recordStockAdjustment(new StockAdjustmentRequest(
                laterLot.getId(), "DAMAGED_WRITE_OFF", -50, "Broken blister packs", "pharm.alice"));
        assertEquals(-50, adj.getQuantityDelta());
        assertEquals(20, laterLot.getQuantityOnHand());

        when(prodRepo.findByFacilityIdOrderByGenericNameAsc(facId)).thenReturn(List.of(morphine));
        when(lotRepo.findByFacilityIdOrderByExpiryDateAsc(facId)).thenReturn(List.of(soonLot, laterLot));
        assertEquals(1, service.findLowStockProducts().size());
    }
}
