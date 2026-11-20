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

    public interface PharmacyProductRepository extends JpaRepository<PharmacyProductEntity, UUID> {
        Optional<PharmacyProductEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PharmacyProductEntity> findByFacilityIdOrderByGenericNameAsc(UUID facilityId);
    }

    public interface PharmacyLotRepository extends JpaRepository<PharmacyLotEntity, UUID> {
        List<PharmacyLotEntity> findByFacilityIdAndProductIdOrderByExpiryDateAsc(UUID facilityId, UUID productId);
        List<PharmacyLotEntity> findByFacilityIdAndBatchNumber(UUID facilityId, String batchNumber);
        List<PharmacyLotEntity> findByFacilityIdOrderByExpiryDateAsc(UUID facilityId);
    }

    public interface PharmacyDispenseRepository extends JpaRepository<PharmacyDispenseEntity, UUID> {
        List<PharmacyDispenseEntity> findByFacilityIdOrderByDispensedAtDesc(UUID facilityId);
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
            String witnessId
    ) {}

    private final PharmacyProductRepository productRepo;
    private final PharmacyLotRepository lotRepo;
    private final PharmacyDispenseRepository dispenseRepo;
    private final PharmacyDispensingPolicySpi policySpi;

    public PharmacyCommerceService(
            PharmacyProductRepository productRepo,
            PharmacyLotRepository lotRepo,
            PharmacyDispenseRepository dispenseRepo,
            ObjectProvider<PharmacyDispensingPolicySpi> policySpiProvider) {
        this.productRepo = productRepo;
        this.lotRepo = lotRepo;
        this.dispenseRepo = dispenseRepo;
        this.policySpi = policySpiProvider != null
                ? policySpiProvider.getIfAvailable(() -> PharmacyCommerceService::defaultPolicy)
                : PharmacyCommerceService::defaultPolicy;
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
        PharmacyLotEntity lot = new PharmacyLotEntity();
        lot.setFacilityId(facId);
        lot.setProductId(req.productId());
        lot.setSupplierName(req.supplierName().trim());
        lot.setPurchaseOrderRef(req.purchaseOrderRef().trim().toUpperCase(Locale.ROOT));
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

        PharmacyDispenseEntity dispense = new PharmacyDispenseEntity();
        dispense.setFacilityId(facId);
        dispense.setPrescriptionRef(req.prescriptionRef().trim().toUpperCase(Locale.ROOT));
        dispense.setCustomerRef(req.customerRef().trim());
        dispense.setProductId(product.getId());
        dispense.setLotId(selectedLot.getId());
        dispense.setQuantityDispensed(req.quantityRequested());
        dispense.setRefillsRemaining(Math.max(0, req.refillsRemaining()));
        dispense.setPharmacistId(req.pharmacistId().trim());
        dispense.setWitnessId(req.witnessId());
        dispense.setTotalAmount(product.getUnitPrice().multiply(BigDecimal.valueOf(req.quantityRequested())));
        dispense.setStatus("DISPENSED");
        return dispenseRepo.save(dispense);
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
    }
}
