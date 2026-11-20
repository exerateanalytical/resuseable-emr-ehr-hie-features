/*
 * =============================================================================
 * Opes HealthCore SDK — Standalone Pharmacy Commerce Service & REST API
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacy.commerce.service;

import com.opeshealth.healthcore.persistence.sdk.TraceableLotPrimitive;
import com.opeshealth.healthcore.pharmacy.commerce.domain.PharmacyCommerceEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class PharmacyCommerceService {

    /**
     * Pluggable dispensing policy SPI for country-specific substitution, generic rules,
     * insurer copay verification, or controlled-substance checks.
     */
    public interface PharmacyDispensingPolicySpi {
        void validateDispense(PharmacyProductEntity product, int quantity, String pharmacistId, String witnessId);
    }

    /**
     * Pluggable inventory valuation SPI (FIFO, FEFO, Weighted Average Cost, country tax/VAT).
     */
    public interface PharmacyInventoryValuationSpi {
        BigDecimal calculateDispenseTotal(PharmacyProductEntity product, int quantityDispensed);
    }

    public interface PharmacyProductRepository extends JpaRepository<PharmacyProductEntity, UUID> {
        Optional<PharmacyProductEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PharmacyProductEntity> findByFacilityIdOrderByGenericNameAsc(UUID facilityId);
    }

    public interface SupplierRepository extends JpaRepository<SupplierEntity, UUID> {
        Optional<SupplierEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<SupplierEntity> findByFacilityIdOrderBySupplierNameAsc(UUID facilityId);
    }

    public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrderEntity, UUID> {
        Optional<PurchaseOrderEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<PurchaseOrderEntity> findByFacilityIdAndPoNumber(UUID facilityId, String poNumber);
    }

    public interface PharmacyLotRepository extends JpaRepository<PharmacyLotEntity, UUID> {
        Optional<PharmacyLotEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PharmacyLotEntity> findByFacilityIdAndProductIdOrderByExpiryDateAsc(UUID facilityId, UUID productId);
        List<PharmacyLotEntity> findByFacilityIdAndBatchNumber(UUID facilityId, String batchNumber);
        List<PharmacyLotEntity> findByFacilityIdOrderByExpiryDateAsc(UUID facilityId);
    }

    public interface PharmacyDispenseRepository extends JpaRepository<PharmacyDispenseEntity, UUID> {
        Optional<PharmacyDispenseEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PharmacyDispenseEntity> findByFacilityIdOrderByDispensedAtDesc(UUID facilityId);
    }

    public interface StockTransferRepository extends JpaRepository<StockTransferEntity, UUID> {
        Optional<StockTransferEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface StockAdjustmentRepository extends JpaRepository<StockAdjustmentEntity, UUID> {
        List<StockAdjustmentEntity> findByFacilityIdOrderByRecordedAtDesc(UUID facilityId);
    }

    public record CreateProductRequest(
            String skuCode,
            String genericName,
            String brandName,
            String strength,
            String dosageForm,
            String atcOrRxnormCode,
            boolean controlledSubstance,
            int reorderThreshold,
            BigDecimal unitPrice
    ) {}

    public record RegisterSupplierRequest(
            String supplierCode,
            String supplierName,
            String licenseNumber,
            boolean qualified
    ) {}

    public record CreatePurchaseOrderRequest(
            String poNumber,
            UUID supplierId,
            UUID productId,
            int orderedQuantity
    ) {}

    public record GoodsReceiptRequest(
            UUID productId,
            String supplierName,
            String purchaseOrderRef,
            String gtin14,
            String batchNumber,
            LocalDate expiryDate,
            int quantityReceived
    ) {}

    public record DispensePrescriptionRequest(
            String prescriptionRef,
            String customerRef,
            UUID productId,
            int quantityRequested,
            int refillsRemaining,
            String pharmacistId,
            String witnessId,
            Integer quantityPrescribed,
            String counselingNotes
    ) {
        public DispensePrescriptionRequest(
                String prescriptionRef, String customerRef, UUID productId,
                int quantityRequested, int refillsRemaining, String pharmacistId, String witnessId) {
            this(prescriptionRef, customerRef, productId, quantityRequested, refillsRemaining, pharmacistId, witnessId, quantityRequested, null);
        }
    }

    public record InitiateTransferRequest(
            String transferRef,
            UUID destinationBranchId,
            UUID lotId,
            int quantity,
            String requestedBy
    ) {}

    public record StockAdjustmentRequest(
            UUID lotId,
            String adjustmentType,
            int quantityDelta,
            String reason,
            String performedBy
    ) {}

    private final PharmacyProductRepository productRepo;
    private final SupplierRepository supplierRepo;
    private final PurchaseOrderRepository poRepo;
    private final PharmacyLotRepository lotRepo;
    private final PharmacyDispenseRepository dispenseRepo;
    private final StockTransferRepository transferRepo;
    private final StockAdjustmentRepository adjustmentRepo;
    private final PharmacyDispensingPolicySpi policySpi;
    private final PharmacyInventoryValuationSpi valuationSpi;

    public PharmacyCommerceService(
            PharmacyProductRepository productRepo,
            SupplierRepository supplierRepo,
            PurchaseOrderRepository poRepo,
            PharmacyLotRepository lotRepo,
            PharmacyDispenseRepository dispenseRepo,
            StockTransferRepository transferRepo,
            StockAdjustmentRepository adjustmentRepo,
            ObjectProvider<PharmacyDispensingPolicySpi> policySpiProvider,
            ObjectProvider<PharmacyInventoryValuationSpi> valuationSpiProvider) {
        this.productRepo = productRepo;
        this.supplierRepo = supplierRepo;
        this.poRepo = poRepo;
        this.lotRepo = lotRepo;
        this.dispenseRepo = dispenseRepo;
        this.transferRepo = transferRepo;
        this.adjustmentRepo = adjustmentRepo;
        this.policySpi = policySpiProvider != null
                ? policySpiProvider.getIfAvailable(() -> PharmacyCommerceService::defaultPolicy)
                : PharmacyCommerceService::defaultPolicy;
        this.valuationSpi = valuationSpiProvider != null
                ? valuationSpiProvider.getIfAvailable(() -> (p, q) -> p.getUnitPrice().multiply(BigDecimal.valueOf(q)))
                : (p, q) -> p.getUnitPrice().multiply(BigDecimal.valueOf(q));
    }

    private static void defaultPolicy(PharmacyProductEntity product, int qty, String pharmacistId, String witnessId) {
        if (qty <= 0) {
            throw new IllegalArgumentException("Dispense quantity must be positive.");
        }
        if (product.isControlledSubstance()) {
            if (witnessId == null || witnessId.isBlank() || witnessId.trim().equalsIgnoreCase(pharmacistId.trim())) {
                throw new SecurityException("Controlled-substance dispensing requires an independent second witness.");
            }
        }
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_PRODUCT_CREATE", resourceType = "PharmacyProduct")
    public PharmacyProductEntity createProduct(CreateProductRequest req) {
        UUID facId = requireFacility();
        PharmacyProductEntity p = new PharmacyProductEntity();
        p.setFacilityId(facId);
        p.setSkuCode(req.skuCode().trim().toUpperCase(Locale.ROOT));
        p.setGenericName(req.genericName().trim());
        p.setBrandName(req.brandName().trim());
        p.setStrength(req.strength().trim());
        p.setDosageForm(req.dosageForm().trim());
        p.setAtcOrRxnormCode(req.atcOrRxnormCode());
        p.setControlledSubstance(req.controlledSubstance());
        p.setReorderThreshold(Math.max(1, req.reorderThreshold()));
        p.setUnitPrice(req.unitPrice());
        return productRepo.save(p);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_SUPPLIER_REGISTER", resourceType = "Supplier")
    public SupplierEntity registerSupplier(RegisterSupplierRequest req) {
        UUID facId = requireFacility();
        SupplierEntity s = new SupplierEntity();
        s.setFacilityId(facId);
        s.setSupplierCode(req.supplierCode().trim().toUpperCase(Locale.ROOT));
        s.setSupplierName(req.supplierName().trim());
        s.setLicenseNumber(req.licenseNumber());
        s.setQualified(req.qualified());
        return supplierRepo.save(s);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_PO_CREATE", resourceType = "PurchaseOrder")
    public PurchaseOrderEntity createPurchaseOrder(CreatePurchaseOrderRequest req) {
        UUID facId = requireFacility();
        SupplierEntity supplier = supplierRepo.findByIdAndFacilityId(req.supplierId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Supplier not found: " + req.supplierId()));
        if (!supplier.isQualified()) {
            throw new IllegalStateException("Procurement blocked: supplier '" + supplier.getSupplierName() + "' is not qualified.");
        }
        productRepo.findByIdAndFacilityId(req.productId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Pharmacy product not found: " + req.productId()));
        if (req.orderedQuantity() <= 0) {
            throw new IllegalArgumentException("orderedQuantity must be positive.");
        }
        PurchaseOrderEntity po = new PurchaseOrderEntity();
        po.setFacilityId(facId);
        po.setPoNumber(req.poNumber().trim().toUpperCase(Locale.ROOT));
        po.setSupplierId(req.supplierId());
        po.setProductId(req.productId());
        po.setOrderedQuantity(req.orderedQuantity());
        po.setStatus("OPEN");
        return poRepo.save(po);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_GOODS_RECEIPT", resourceType = "PharmacyLot")
    public PharmacyLotEntity receiveGoods(GoodsReceiptRequest req) {
        UUID facId = requireFacility();
        productRepo.findByIdAndFacilityId(req.productId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Pharmacy product not found: " + req.productId()));
        TraceableLotPrimitive lotCheck = new TraceableLotPrimitive(
                req.gtin14(), req.batchNumber(), req.expiryDate(), null, false);
        if (lotCheck.isExpiredOn(LocalDate.now())) {
            throw new IllegalStateException("Goods receipt rejected: incoming batch '" + req.batchNumber() + "' is already expired.");
        }
        String poRef = req.purchaseOrderRef().trim().toUpperCase(Locale.ROOT);
        poRepo.findByFacilityIdAndPoNumber(facId, poRef).ifPresent(po -> {
            po.setReceivedQuantity(po.getReceivedQuantity() + req.quantityReceived());
            po.setStatus(po.getReceivedQuantity() >= po.getOrderedQuantity() ? "RECEIVED" : "PARTIALLY_RECEIVED");
            poRepo.save(po);
        });

        PharmacyLotEntity lot = new PharmacyLotEntity();
        lot.setFacilityId(facId);
        lot.setProductId(req.productId());
        lot.setSupplierName(req.supplierName().trim());
        lot.setPurchaseOrderRef(poRef);
        lot.setGtin14(lotCheck.gtin14());
        lot.setBatchNumber(lotCheck.lotOrBatchNumber());
        lot.setExpiryDate(lotCheck.expiryDate());
        lot.setQuantityOnHand(req.quantityReceived());
        lot.setStatus("AVAILABLE");
        return lotRepo.save(lot);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_RX_DISPENSE_FEFO", resourceType = "PharmacyDispense")
    public PharmacyDispenseEntity dispenseFefo(DispensePrescriptionRequest req) {
        UUID facId = requireFacility();
        PharmacyProductEntity product = productRepo.findByIdAndFacilityId(req.productId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Pharmacy product not found: " + req.productId()));
        policySpi.validateDispense(product, req.quantityRequested(), req.pharmacistId(), req.witnessId());

        List<PharmacyLotEntity> lots = lotRepo.findByFacilityIdAndProductIdOrderByExpiryDateAsc(facId, product.getId());
        LocalDate today = LocalDate.now();
        PharmacyLotEntity selectedLot = lots.stream()
                .filter(l -> "AVAILABLE".equals(l.getStatus()) && l.getQuantityOnHand() >= req.quantityRequested())
                .filter(l -> new TraceableLotPrimitive(l.getGtin14(), l.getBatchNumber(), l.getExpiryDate(), null, false).isEligibleForUse(today))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No eligible non-expired, non-recalled FEFO lot available for SKU " + product.getSkuCode()));

        selectedLot.setQuantityOnHand(selectedLot.getQuantityOnHand() - req.quantityRequested());
        lotRepo.save(selectedLot);

        int prescribed = req.quantityPrescribed() != null ? req.quantityPrescribed() : req.quantityRequested();
        PharmacyDispenseEntity dispense = new PharmacyDispenseEntity();
        dispense.setFacilityId(facId);
        dispense.setPrescriptionRef(req.prescriptionRef().trim().toUpperCase(Locale.ROOT));
        dispense.setCustomerRef(req.customerRef().trim());
        dispense.setProductId(product.getId());
        dispense.setLotId(selectedLot.getId());
        dispense.setQuantityPrescribed(prescribed);
        dispense.setQuantityDispensed(req.quantityRequested());
        dispense.setRefillsRemaining(Math.max(0, req.refillsRemaining()));
        dispense.setPharmacistId(req.pharmacistId().trim());
        dispense.setWitnessId(req.witnessId());
        dispense.setCounselingNotes(req.counselingNotes());
        dispense.setTotalAmount(valuationSpi.calculateDispenseTotal(product, req.quantityRequested()));
        dispense.setStatus(req.quantityRequested() < prescribed ? "PARTIAL_FILL" : "DISPENSED");
        return dispenseRepo.save(dispense);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_RX_REFILL", resourceType = "PharmacyDispense")
    public PharmacyDispenseEntity dispenseRefill(UUID originalDispenseId, String pharmacistId, String witnessId) {
        UUID facId = requireFacility();
        PharmacyDispenseEntity orig = dispenseRepo.findByIdAndFacilityId(originalDispenseId, facId)
                .orElseThrow(() -> new NoSuchElementException("Original dispense not found: " + originalDispenseId));
        if (orig.getRefillsRemaining() <= 0) {
            throw new IllegalStateException("No refills remaining for prescription " + orig.getPrescriptionRef());
        }
        orig.setRefillsRemaining(orig.getRefillsRemaining() - 1);
        dispenseRepo.save(orig);
        return dispenseFefo(new DispensePrescriptionRequest(
                orig.getPrescriptionRef() + "-RF", orig.getCustomerRef(), orig.getProductId(),
                orig.getQuantityDispensed(), orig.getRefillsRemaining(), pharmacistId, witnessId,
                orig.getQuantityPrescribed(), "Refill counseling verified"
        ));
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_TRANSFER_OUT", resourceType = "StockTransfer")
    public StockTransferEntity initiateInterBranchTransfer(InitiateTransferRequest req) {
        UUID facId = requireFacility();
        PharmacyLotEntity lot = lotRepo.findByIdAndFacilityId(req.lotId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Lot not found: " + req.lotId()));
        if (!"AVAILABLE".equals(lot.getStatus()) || req.quantity() <= 0 || req.quantity() > lot.getQuantityOnHand()) {
            throw new IllegalStateException("Cannot transfer lot " + lot.getBatchNumber() + " (status=" + lot.getStatus() + ", onHand=" + lot.getQuantityOnHand() + ").");
        }
        lot.setQuantityOnHand(lot.getQuantityOnHand() - req.quantity());
        lotRepo.save(lot);

        StockTransferEntity transfer = new StockTransferEntity();
        transfer.setFacilityId(facId);
        transfer.setTransferRef(req.transferRef().trim().toUpperCase(Locale.ROOT));
        transfer.setDestinationBranchId(req.destinationBranchId());
        transfer.setProductId(lot.getProductId());
        transfer.setLotId(lot.getId());
        transfer.setQuantity(req.quantity());
        transfer.setRequestedBy(req.requestedBy().trim());
        transfer.setStatus("IN_TRANSIT");
        return transferRepo.save(transfer);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_TRANSFER_CONFIRM", resourceType = "StockTransfer")
    public StockTransferEntity confirmTransferReceipt(UUID transferId, String receivedBy) {
        UUID facId = requireFacility();
        StockTransferEntity transfer = transferRepo.findByIdAndFacilityId(transferId, facId)
                .orElseThrow(() -> new NoSuchElementException("Stock transfer not found: " + transferId));
        if (!"IN_TRANSIT".equals(transfer.getStatus())) {
            throw new IllegalStateException("Only IN_TRANSIT transfers may be confirmed.");
        }
        transfer.setReceivedBy(receivedBy.trim());
        transfer.setStatus("RECEIVED_CONFIRMED");
        transfer.setUpdatedAt(Instant.now());
        return transferRepo.save(transfer);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_STOCK_ADJUST", resourceType = "StockAdjustment")
    public StockAdjustmentEntity recordStockAdjustment(StockAdjustmentRequest req) {
        UUID facId = requireFacility();
        PharmacyLotEntity lot = lotRepo.findByIdAndFacilityId(req.lotId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Lot not found: " + req.lotId()));
        int updated = lot.getQuantityOnHand() + req.quantityDelta();
        if (updated < 0) {
            throw new IllegalArgumentException("Stock adjustment would result in negative inventory.");
        }
        lot.setQuantityOnHand(updated);
        if ("EXPIRY_WITHDRAWAL".equalsIgnoreCase(req.adjustmentType()) && updated == 0) {
            lot.setStatus("EXPIRED_WITHDRAWN");
        }
        lotRepo.save(lot);

        StockAdjustmentEntity adj = new StockAdjustmentEntity();
        adj.setFacilityId(facId);
        adj.setLotId(lot.getId());
        adj.setAdjustmentType(req.adjustmentType().trim().toUpperCase(Locale.ROOT));
        adj.setQuantityDelta(req.quantityDelta());
        adj.setReason(req.reason().trim());
        adj.setPerformedBy(req.performedBy().trim());
        return adjustmentRepo.save(adj);
    }

    @Transactional
    @ClinicalAudit(action = "PHCOM_RECALL_QUARANTINE", resourceType = "PharmacyLot")
    public List<PharmacyLotEntity> quarantineRecalledBatch(String batchNumber) {
        UUID facId = requireFacility();
        List<PharmacyLotEntity> lots = lotRepo.findByFacilityIdAndBatchNumber(facId, batchNumber.trim());
        for (PharmacyLotEntity lot : lots) {
            lot.setStatus("QUARANTINED_RECALL");
            lotRepo.save(lot);
        }
        return lots;
    }

    @Transactional(readOnly = true)
    public List<PharmacyLotEntity> findNearExpiryLots(int warningDays) {
        UUID facId = requireFacility();
        LocalDate today = LocalDate.now();
        return lotRepo.findByFacilityIdOrderByExpiryDateAsc(facId).stream()
                .filter(l -> "AVAILABLE".equals(l.getStatus()) && l.getQuantityOnHand() > 0)
                .filter(l -> new TraceableLotPrimitive(l.getGtin14(), l.getBatchNumber(), l.getExpiryDate(), null, false)
                        .isNearExpiry(today, warningDays))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PharmacyProductEntity> findLowStockProducts() {
        UUID facId = requireFacility();
        List<PharmacyProductEntity> products = productRepo.findByFacilityIdOrderByGenericNameAsc(facId);
        List<PharmacyLotEntity> lots = lotRepo.findByFacilityIdOrderByExpiryDateAsc(facId);
        Map<UUID, Integer> totals = new HashMap<>();
        for (PharmacyLotEntity l : lots) {
            if ("AVAILABLE".equals(l.getStatus())) {
                totals.merge(l.getProductId(), l.getQuantityOnHand(), Integer::sum);
            }
        }
        return products.stream()
                .filter(p -> totals.getOrDefault(p.getId(), 0) <= p.getReorderThreshold())
                .toList();
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Standalone Pharmacy Commerce operations."));
    }

    @RestController
    @RequestMapping("/api/v1/pharmacy-commerce")
    public static class PharmacyCommerceController {
        private final PharmacyCommerceService service;

        public PharmacyCommerceController(PharmacyCommerceService service) {
            this.service = service;
        }

        @PostMapping("/products")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_pharmacy:dispense')")
        public PharmacyProductEntity createProduct(@RequestBody CreateProductRequest req) {
            return service.createProduct(req);
        }

        @PostMapping("/suppliers")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public SupplierEntity registerSupplier(@RequestBody RegisterSupplierRequest req) {
            return service.registerSupplier(req);
        }

        @PostMapping("/purchase-orders")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public PurchaseOrderEntity createPo(@RequestBody CreatePurchaseOrderRequest req) {
            return service.createPurchaseOrder(req);
        }

        @PostMapping("/goods-receipt")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public PharmacyLotEntity receiveGoods(@RequestBody GoodsReceiptRequest req) {
            return service.receiveGoods(req);
        }

        @PostMapping("/dispense")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_pharmacy:dispense')")
        public PharmacyDispenseEntity dispense(@RequestBody DispensePrescriptionRequest req) {
            return service.dispenseFefo(req);
        }

        @PostMapping("/dispense/{id}/refill")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_pharmacy:dispense')")
        public PharmacyDispenseEntity refill(@PathVariable UUID id, @RequestParam String pharmacistId, @RequestParam(required = false) String witnessId) {
            return service.dispenseRefill(id, pharmacistId, witnessId);
        }

        @PostMapping("/transfers")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public StockTransferEntity transfer(@RequestBody InitiateTransferRequest req) {
            return service.initiateInterBranchTransfer(req);
        }

        @PostMapping("/transfers/{id}/confirm")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public StockTransferEntity confirmTransfer(@PathVariable UUID id, @RequestParam String receivedBy) {
            return service.confirmTransferReceipt(id, receivedBy);
        }

        @PostMapping("/adjustments")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public StockAdjustmentEntity adjust(@RequestBody StockAdjustmentRequest req) {
            return service.recordStockAdjustment(req);
        }

        @PostMapping("/recall/{batchNumber}")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public List<PharmacyLotEntity> recall(@PathVariable String batchNumber) {
            return service.quarantineRecalledBatch(batchNumber);
        }

        @GetMapping("/alerts/near-expiry")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:read')")
        public List<PharmacyLotEntity> nearExpiry(@RequestParam(defaultValue = "90") int days) {
            return service.findNearExpiryLots(days);
        }

        @GetMapping("/alerts/low-stock")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:read')")
        public List<PharmacyProductEntity> lowStock() {
            return service.findLowStockProducts();
        }
    }
}
