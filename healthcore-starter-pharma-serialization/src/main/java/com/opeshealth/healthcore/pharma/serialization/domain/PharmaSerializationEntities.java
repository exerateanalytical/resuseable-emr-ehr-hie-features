/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Serialization & Distribution Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.serialization.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PharmaSerializationEntities {

    private PharmaSerializationEntities() {}

    @Entity
    @Table(name = "hc_pser_serialized_units")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class SerializedUnitEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "gtin14", nullable = false, length = 14)
        private String gtin14;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "serial_number", nullable = false, length = 64)
        private String serialNumber;

        @Column(name = "expiry_date", nullable = false)
        private LocalDate expiryDate;

        @Column(name = "packaging_level", nullable = false, length = 24)
        private String packagingLevel = "UNIT"; // UNIT, CASE, PALLET

        @Column(name = "parent_sscc", length = 32)
        private String parentSscc;

        @Column(name = "gs1_ai_string", nullable = false, length = 160)
        private String gs1AiString;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "COMMISSIONED"; // COMMISSIONED, AGGREGATED, SHIPPED, RECALLED, DECOMMISSIONED

        @Column(name = "commissioned_by", nullable = false, length = 120)
        private String commissionedBy;

        @Column(name = "commissioned_at", nullable = false)
        private Instant commissionedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getGtin14() { return gtin14; }
        public void setGtin14(String gtin14) { this.gtin14 = gtin14; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public String getSerialNumber() { return serialNumber; }
        public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public String getPackagingLevel() { return packagingLevel; }
        public void setPackagingLevel(String packagingLevel) { this.packagingLevel = packagingLevel; }
        public String getParentSscc() { return parentSscc; }
        public void setParentSscc(String parentSscc) { this.parentSscc = parentSscc; }
        public String getGs1AiString() { return gs1AiString; }
        public void setGs1AiString(String gs1AiString) { this.gs1AiString = gs1AiString; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCommissionedBy() { return commissionedBy; }
        public void setCommissionedBy(String commissionedBy) { this.commissionedBy = commissionedBy; }
        public Instant getCommissionedAt() { return commissionedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pser_warehouse_lots")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WarehouseLotEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "gtin14", nullable = false, length = 14)
        private String gtin14;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "expiry_date", nullable = false)
        private LocalDate expiryDate;

        @Column(name = "warehouse_zone", nullable = false, length = 64)
        private String warehouseZone;

        @Column(name = "quantity_units", nullable = false)
        private int quantityUnits;

        @Column(name = "min_temp_c", nullable = false, precision = 6, scale = 2)
        private BigDecimal minTempC = BigDecimal.valueOf(2);

        @Column(name = "max_temp_c", nullable = false, precision = 6, scale = 2)
        private BigDecimal maxTempC = BigDecimal.valueOf(25);

        @Column(name = "last_recorded_temp_c", precision = 6, scale = 2)
        private BigDecimal lastRecordedTempC;

        @Column(name = "cold_chain_excursion", nullable = false)
        private boolean coldChainExcursion = false;

        @Column(name = "lot_status", nullable = false, length = 24)
        private String lotStatus = "QUARANTINE"; // QUARANTINE, RELEASED, RECALLED, EXCURSION_HOLD

        @Column(name = "qa_release_sig_sha256", length = 64)
        private String qaReleaseSigSha256;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getGtin14() { return gtin14; }
        public void setGtin14(String gtin14) { this.gtin14 = gtin14; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public String getWarehouseZone() { return warehouseZone; }
        public void setWarehouseZone(String warehouseZone) { this.warehouseZone = warehouseZone; }
        public int getQuantityUnits() { return quantityUnits; }
        public void setQuantityUnits(int quantityUnits) { this.quantityUnits = quantityUnits; }
        public BigDecimal getMinTempC() { return minTempC; }
        public void setMinTempC(BigDecimal minTempC) { this.minTempC = minTempC; }
        public BigDecimal getMaxTempC() { return maxTempC; }
        public void setMaxTempC(BigDecimal maxTempC) { this.maxTempC = maxTempC; }
        public BigDecimal getLastRecordedTempC() { return lastRecordedTempC; }
        public void setLastRecordedTempC(BigDecimal lastRecordedTempC) { this.lastRecordedTempC = lastRecordedTempC; }
        public boolean isColdChainExcursion() { return coldChainExcursion; }
        public void setColdChainExcursion(boolean coldChainExcursion) { this.coldChainExcursion = coldChainExcursion; }
        public String getLotStatus() { return lotStatus; }
        public void setLotStatus(String lotStatus) { this.lotStatus = lotStatus; }
        public String getQaReleaseSigSha256() { return qaReleaseSigSha256; }
        public void setQaReleaseSigSha256(String qaReleaseSigSha256) { this.qaReleaseSigSha256 = qaReleaseSigSha256; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pser_shipments")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DistributionShipmentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "shipment_number", nullable = false, length = 64)
        private String shipmentNumber;

        @Column(name = "sscc_pallet_code", nullable = false, length = 32)
        private String ssccPalletCode;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "destination_partner", nullable = false, length = 160)
        private String destinationPartner;

        @Column(name = "unit_count", nullable = false)
        private int unitCount;

        @Column(name = "cold_chain_verified", nullable = false)
        private boolean coldChainVerified = true;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DISPATCHED"; // DISPATCHED, DELIVERED, RECALL_INTERCEPTED

        @Column(name = "dispatched_by", nullable = false, length = 120)
        private String dispatchedBy;

        @Column(name = "dispatched_at", nullable = false)
        private Instant dispatchedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getShipmentNumber() { return shipmentNumber; }
        public void setShipmentNumber(String shipmentNumber) { this.shipmentNumber = shipmentNumber; }
        public String getSsccPalletCode() { return ssccPalletCode; }
        public void setSsccPalletCode(String ssccPalletCode) { this.ssccPalletCode = ssccPalletCode; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public String getDestinationPartner() { return destinationPartner; }
        public void setDestinationPartner(String destinationPartner) { this.destinationPartner = destinationPartner; }
        public int getUnitCount() { return unitCount; }
        public void setUnitCount(int unitCount) { this.unitCount = unitCount; }
        public boolean isColdChainVerified() { return coldChainVerified; }
        public void setColdChainVerified(boolean coldChainVerified) { this.coldChainVerified = coldChainVerified; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDispatchedBy() { return dispatchedBy; }
        public void setDispatchedBy(String dispatchedBy) { this.dispatchedBy = dispatchedBy; }
        public Instant getDispatchedAt() { return dispatchedAt; }
        public long getVersion() { return version; }
    }
}
