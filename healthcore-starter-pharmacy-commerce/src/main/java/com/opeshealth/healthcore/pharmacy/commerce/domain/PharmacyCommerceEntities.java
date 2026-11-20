/*
 * =============================================================================
 * Opes HealthCore SDK — Standalone Pharmacy Commerce Domain Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacy.commerce.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PharmacyCommerceEntities {

    private PharmacyCommerceEntities() {}

    @Entity
    @Table(name = "hc_phcom_products")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PharmacyProductEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "sku_code", nullable = false, length = 64)
        private String skuCode;

        @Column(name = "generic_name", nullable = false, length = 160)
        private String genericName;

        @Column(name = "brand_name", nullable = false, length = 160)
        private String brandName;

        @Column(name = "strength", nullable = false, length = 64)
        private String strength;

        @Column(name = "dosage_form", nullable = false, length = 64)
        private String dosageForm;

        @Column(name = "atc_or_rxnorm_code", length = 64)
        private String atcOrRxnormCode;

        @Column(name = "controlled_substance", nullable = false)
        private boolean controlledSubstance = false;

        @Column(name = "reorder_threshold", nullable = false)
        private int reorderThreshold = 20;

        @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
        private BigDecimal unitPrice;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getSkuCode() { return skuCode; }
        public void setSkuCode(String skuCode) { this.skuCode = skuCode; }
        public String getGenericName() { return genericName; }
        public void setGenericName(String genericName) { this.genericName = genericName; }
        public String getBrandName() { return brandName; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public String getStrength() { return strength; }
        public void setStrength(String strength) { this.strength = strength; }
        public String getDosageForm() { return dosageForm; }
        public void setDosageForm(String dosageForm) { this.dosageForm = dosageForm; }
        public String getAtcOrRxnormCode() { return atcOrRxnormCode; }
        public void setAtcOrRxnormCode(String atcOrRxnormCode) { this.atcOrRxnormCode = atcOrRxnormCode; }
        public boolean isControlledSubstance() { return controlledSubstance; }
        public void setControlledSubstance(boolean controlledSubstance) { this.controlledSubstance = controlledSubstance; }
        public int getReorderThreshold() { return reorderThreshold; }
        public void setReorderThreshold(int reorderThreshold) { this.reorderThreshold = reorderThreshold; }
        public BigDecimal getUnitPrice() { return unitPrice; }
        public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_phcom_inventory_lots")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PharmacyLotEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "product_id", nullable = false)
        private UUID productId;

        @Column(name = "supplier_name", nullable = false, length = 160)
        private String supplierName;

        @Column(name = "purchase_order_ref", nullable = false, length = 64)
        private String purchaseOrderRef;

        @Column(name = "gtin14", length = 14)
        private String gtin14;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "expiry_date", nullable = false)
        private LocalDate expiryDate;

        @Column(name = "quantity_on_hand", nullable = false)
        private int quantityOnHand;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "AVAILABLE"; // AVAILABLE, QUARANTINED_RECALL, EXPIRED

        @Column(name = "received_at", nullable = false)
        private Instant receivedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getProductId() { return productId; }
        public void setProductId(UUID productId) { this.productId = productId; }
        public String getSupplierName() { return supplierName; }
        public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
        public String getPurchaseOrderRef() { return purchaseOrderRef; }
        public void setPurchaseOrderRef(String purchaseOrderRef) { this.purchaseOrderRef = purchaseOrderRef; }
        public String getGtin14() { return gtin14; }
        public void setGtin14(String gtin14) { this.gtin14 = gtin14; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public int getQuantityOnHand() { return quantityOnHand; }
        public void setQuantityOnHand(int quantityOnHand) { this.quantityOnHand = quantityOnHand; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getReceivedAt() { return receivedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_phcom_dispenses")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PharmacyDispenseEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "prescription_ref", nullable = false, length = 64)
        private String prescriptionRef;

        @Column(name = "customer_ref", nullable = false, length = 120)
        private String customerRef;

        @Column(name = "product_id", nullable = false)
        private UUID productId;

        @Column(name = "lot_id", nullable = false)
        private UUID lotId;

        @Column(name = "quantity_dispensed", nullable = false)
        private int quantityDispensed;

        @Column(name = "refills_remaining", nullable = false)
        private int refillsRemaining = 0;

        @Column(name = "pharmacist_id", nullable = false, length = 120)
        private String pharmacistId;

        @Column(name = "witness_id", length = 120)
        private String witnessId;

        @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
        private BigDecimal totalAmount;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DISPENSED"; // DISPENSED, PARTIAL_FILL, RETURNED

        @Column(name = "dispensed_at", nullable = false)
        private Instant dispensedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getPrescriptionRef() { return prescriptionRef; }
        public void setPrescriptionRef(String prescriptionRef) { this.prescriptionRef = prescriptionRef; }
        public String getCustomerRef() { return customerRef; }
        public void setCustomerRef(String customerRef) { this.customerRef = customerRef; }
        public UUID getProductId() { return productId; }
        public void setProductId(UUID productId) { this.productId = productId; }
        public UUID getLotId() { return lotId; }
        public void setLotId(UUID lotId) { this.lotId = lotId; }
        public int getQuantityDispensed() { return quantityDispensed; }
        public void setQuantityDispensed(int quantityDispensed) { this.quantityDispensed = quantityDispensed; }
        public int getRefillsRemaining() { return refillsRemaining; }
        public void setRefillsRemaining(int refillsRemaining) { this.refillsRemaining = refillsRemaining; }
        public String getPharmacistId() { return pharmacistId; }
        public void setPharmacistId(String pharmacistId) { this.pharmacistId = pharmacistId; }
        public String getWitnessId() { return witnessId; }
        public void setWitnessId(String witnessId) { this.witnessId = witnessId; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getDispensedAt() { return dispensedAt; }
        public long getVersion() { return version; }
    }
}
