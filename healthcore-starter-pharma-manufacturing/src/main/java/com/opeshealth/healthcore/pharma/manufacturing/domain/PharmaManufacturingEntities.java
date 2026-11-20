/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Manufacturing & eBMR/eBPR Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.manufacturing.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Facility-scoped pharmaceutical manufacturing entities (strictly independent of clinical
 * MPI/EMR/HIS modules): Product Master (API/Excipients/Finished Goods), Master Manufacturing
 * Formulas, Electronic Batch Manufacturing/Packaging Records (eBMR/eBPR), Material Weighing
 * Genealogy, and In-Process Controls (IPC).
 */
public final class PharmaManufacturingEntities {

    private PharmaManufacturingEntities() {}

    @Entity
    @Table(name = "hc_pmfg_product_master")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PharmaProductEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "product_code", nullable = false, length = 64)
        private String productCode;

        @Column(name = "product_name", nullable = false, length = 200)
        private String productName;

        @Column(name = "material_class", nullable = false, length = 32)
        private String materialClass; // API, EXCIPIENT, PACKAGING, INTERMEDIATE, FINISHED_GOOD

        @Column(name = "dosage_form", length = 64)
        private String dosageForm;

        @Column(name = "strength_spec", length = 80)
        private String strengthSpec;

        @Column(name = "storage_condition", nullable = false, length = 80)
        private String storageCondition;

        @Column(name = "shelf_life_months", nullable = false)
        private int shelfLifeMonths = 24;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public String getMaterialClass() { return materialClass; }
        public void setMaterialClass(String materialClass) { this.materialClass = materialClass; }
        public String getDosageForm() { return dosageForm; }
        public void setDosageForm(String dosageForm) { this.dosageForm = dosageForm; }
        public String getStrengthSpec() { return strengthSpec; }
        public void setStrengthSpec(String strengthSpec) { this.strengthSpec = strengthSpec; }
        public String getStorageCondition() { return storageCondition; }
        public void setStorageCondition(String storageCondition) { this.storageCondition = storageCondition; }
        public int getShelfLifeMonths() { return shelfLifeMonths; }
        public void setShelfLifeMonths(int shelfLifeMonths) { this.shelfLifeMonths = shelfLifeMonths; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pmfg_master_formulas")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class MasterFormulaEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "product_id", nullable = false)
        private UUID productId;

        @Column(name = "formula_code", nullable = false, length = 64)
        private String formulaCode;

        @Column(name = "formula_version", nullable = false, length = 24)
        private String formulaVersion;

        @Column(name = "standard_batch_size", nullable = false, precision = 14, scale = 4)
        private BigDecimal standardBatchSize;

        @Column(name = "batch_unit", nullable = false, length = 24)
        private String batchUnit;

        @Lob
        @Column(name = "bom_spec_json", nullable = false)
        private String bomSpecJson;

        @Lob
        @Column(name = "instructions_json", nullable = false)
        private String instructionsJson;

        @Column(name = "approval_status", nullable = false, length = 24)
        private String approvalStatus = "DRAFT"; // DRAFT, APPROVED, SUPERSEDED

        @Column(name = "authored_by", nullable = false, length = 120)
        private String authoredBy;

        @Column(name = "approved_by", length = 120)
        private String approvedBy;

        @Column(name = "approval_sig_sha256", length = 64)
        private String approvalSigSha256;

        @Column(name = "approved_at")
        private Instant approvedAt;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getProductId() { return productId; }
        public void setProductId(UUID productId) { this.productId = productId; }
        public String getFormulaCode() { return formulaCode; }
        public void setFormulaCode(String formulaCode) { this.formulaCode = formulaCode; }
        public String getFormulaVersion() { return formulaVersion; }
        public void setFormulaVersion(String formulaVersion) { this.formulaVersion = formulaVersion; }
        public BigDecimal getStandardBatchSize() { return standardBatchSize; }
        public void setStandardBatchSize(BigDecimal standardBatchSize) { this.standardBatchSize = standardBatchSize; }
        public String getBatchUnit() { return batchUnit; }
        public void setBatchUnit(String batchUnit) { this.batchUnit = batchUnit; }
        public String getBomSpecJson() { return bomSpecJson; }
        public void setBomSpecJson(String bomSpecJson) { this.bomSpecJson = bomSpecJson; }
        public String getInstructionsJson() { return instructionsJson; }
        public void setInstructionsJson(String instructionsJson) { this.instructionsJson = instructionsJson; }
        public String getApprovalStatus() { return approvalStatus; }
        public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
        public String getAuthoredBy() { return authoredBy; }
        public void setAuthoredBy(String authoredBy) { this.authoredBy = authoredBy; }
        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
        public String getApprovalSigSha256() { return approvalSigSha256; }
        public void setApprovalSigSha256(String approvalSigSha256) { this.approvalSigSha256 = approvalSigSha256; }
        public Instant getApprovedAt() { return approvedAt; }
        public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pmfg_batch_records")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ElectronicBatchRecordEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "formula_id", nullable = false)
        private UUID formulaId;

        @Column(name = "production_order_no", nullable = false, length = 64)
        private String productionOrderNo;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "record_kind", nullable = false, length = 16)
        private String recordKind = "BMR"; // BMR or BPR

        @Column(name = "equipment_line_code", nullable = false, length = 64)
        private String equipmentLineCode;

        @Column(name = "line_clearance_verified", nullable = false)
        private boolean lineClearanceVerified = false;

        @Column(name = "line_cleared_by", length = 120)
        private String lineClearedBy;

        @Column(name = "theoretical_yield", nullable = false, precision = 14, scale = 4)
        private BigDecimal theoreticalYield;

        @Column(name = "actual_yield", precision = 14, scale = 4)
        private BigDecimal actualYield;

        @Column(name = "yield_percent", precision = 6, scale = 2)
        private BigDecimal yieldPercent;

        @Column(name = "batch_status", nullable = false, length = 32)
        private String batchStatus = "PLANNED"; // PLANNED, LINE_CLEARED, IN_PRODUCTION, AWAITING_QA_DISPOSITION, RELEASED, REJECTED, QUARANTINED

        @Column(name = "operator_id", nullable = false, length = 120)
        private String operatorId;

        @Column(name = "qa_reviewer_id", length = 120)
        private String qaReviewerId;

        @Column(name = "qa_signature_sha256", length = 64)
        private String qaSignatureSha256;

        @Column(name = "manufactured_date", nullable = false)
        private LocalDate manufacturedDate;

        @Column(name = "expiry_date", nullable = false)
        private LocalDate expiryDate;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getFormulaId() { return formulaId; }
        public void setFormulaId(UUID formulaId) { this.formulaId = formulaId; }
        public String getProductionOrderNo() { return productionOrderNo; }
        public void setProductionOrderNo(String productionOrderNo) { this.productionOrderNo = productionOrderNo; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public String getRecordKind() { return recordKind; }
        public void setRecordKind(String recordKind) { this.recordKind = recordKind; }
        public String getEquipmentLineCode() { return equipmentLineCode; }
        public void setEquipmentLineCode(String equipmentLineCode) { this.equipmentLineCode = equipmentLineCode; }
        public boolean isLineClearanceVerified() { return lineClearanceVerified; }
        public void setLineClearanceVerified(boolean lineClearanceVerified) { this.lineClearanceVerified = lineClearanceVerified; }
        public String getLineClearedBy() { return lineClearedBy; }
        public void setLineClearedBy(String lineClearedBy) { this.lineClearedBy = lineClearedBy; }
        public BigDecimal getTheoreticalYield() { return theoreticalYield; }
        public void setTheoreticalYield(BigDecimal theoreticalYield) { this.theoreticalYield = theoreticalYield; }
        public BigDecimal getActualYield() { return actualYield; }
        public void setActualYield(BigDecimal actualYield) { this.actualYield = actualYield; }
        public BigDecimal getYieldPercent() { return yieldPercent; }
        public void setYieldPercent(BigDecimal yieldPercent) { this.yieldPercent = yieldPercent; }
        public String getBatchStatus() { return batchStatus; }
        public void setBatchStatus(String batchStatus) { this.batchStatus = batchStatus; }
        public String getOperatorId() { return operatorId; }
        public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
        public String getQaReviewerId() { return qaReviewerId; }
        public void setQaReviewerId(String qaReviewerId) { this.qaReviewerId = qaReviewerId; }
        public String getQaSignatureSha256() { return qaSignatureSha256; }
        public void setQaSignatureSha256(String qaSignatureSha256) { this.qaSignatureSha256 = qaSignatureSha256; }
        public LocalDate getManufacturedDate() { return manufacturedDate; }
        public void setManufacturedDate(LocalDate manufacturedDate) { this.manufacturedDate = manufacturedDate; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pmfg_material_dispensing")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class MaterialDispensingEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "batch_record_id", nullable = false)
        private UUID batchRecordId;

        @Column(name = "material_product_code", nullable = false, length = 64)
        private String materialProductCode;

        @Column(name = "source_material_lot", nullable = false, length = 64)
        private String sourceMaterialLot;

        @Column(name = "target_quantity", nullable = false, precision = 14, scale = 4)
        private BigDecimal targetQuantity;

        @Column(name = "actual_weighed_quantity", nullable = false, precision = 14, scale = 4)
        private BigDecimal actualWeighedQuantity;

        @Column(name = "unit_of_measure", nullable = false, length = 24)
        private String unitOfMeasure;

        @Column(name = "scale_equipment_id", nullable = false, length = 64)
        private String scaleEquipmentId;

        @Column(name = "weighed_by", nullable = false, length = 120)
        private String weighedBy;

        @Column(name = "verified_by", nullable = false, length = 120)
        private String verifiedBy;

        @Column(name = "dispensed_at", nullable = false)
        private Instant dispensedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getBatchRecordId() { return batchRecordId; }
        public void setBatchRecordId(UUID batchRecordId) { this.batchRecordId = batchRecordId; }
        public String getMaterialProductCode() { return materialProductCode; }
        public void setMaterialProductCode(String materialProductCode) { this.materialProductCode = materialProductCode; }
        public String getSourceMaterialLot() { return sourceMaterialLot; }
        public void setSourceMaterialLot(String sourceMaterialLot) { this.sourceMaterialLot = sourceMaterialLot; }
        public BigDecimal getTargetQuantity() { return targetQuantity; }
        public void setTargetQuantity(BigDecimal targetQuantity) { this.targetQuantity = targetQuantity; }
        public BigDecimal getActualWeighedQuantity() { return actualWeighedQuantity; }
        public void setActualWeighedQuantity(BigDecimal actualWeighedQuantity) { this.actualWeighedQuantity = actualWeighedQuantity; }
        public String getUnitOfMeasure() { return unitOfMeasure; }
        public void setUnitOfMeasure(String unitOfMeasure) { this.unitOfMeasure = unitOfMeasure; }
        public String getScaleEquipmentId() { return scaleEquipmentId; }
        public void setScaleEquipmentId(String scaleEquipmentId) { this.scaleEquipmentId = scaleEquipmentId; }
        public String getWeighedBy() { return weighedBy; }
        public void setWeighedBy(String weighedBy) { this.weighedBy = weighedBy; }
        public String getVerifiedBy() { return verifiedBy; }
        public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
        public Instant getDispensedAt() { return dispensedAt; }
    }

    @Entity
    @Table(name = "hc_pmfg_in_process_controls")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class InProcessControlEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "batch_record_id", nullable = false)
        private UUID batchRecordId;

        @Column(name = "stage_code", nullable = false, length = 48)
        private String stageCode;

        @Column(name = "parameter_name", nullable = false, length = 120)
        private String parameterName;

        @Column(name = "measured_value", nullable = false, precision = 14, scale = 4)
        private BigDecimal measuredValue;

        @Column(name = "min_limit", precision = 14, scale = 4)
        private BigDecimal minLimit;

        @Column(name = "max_limit", precision = 14, scale = 4)
        private BigDecimal maxLimit;

        @Column(name = "unit_of_measure", nullable = false, length = 24)
        private String unitOfMeasure;

        @Column(name = "within_spec", nullable = false)
        private boolean withinSpec;

        @Column(name = "recorded_by", nullable = false, length = 120)
        private String recordedBy;

        @Column(name = "recorded_at", nullable = false)
        private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getBatchRecordId() { return batchRecordId; }
        public void setBatchRecordId(UUID batchRecordId) { this.batchRecordId = batchRecordId; }
        public String getStageCode() { return stageCode; }
        public void setStageCode(String stageCode) { this.stageCode = stageCode; }
        public String getParameterName() { return parameterName; }
        public void setParameterName(String parameterName) { this.parameterName = parameterName; }
        public BigDecimal getMeasuredValue() { return measuredValue; }
        public void setMeasuredValue(BigDecimal measuredValue) { this.measuredValue = measuredValue; }
        public BigDecimal getMinLimit() { return minLimit; }
        public void setMinLimit(BigDecimal minLimit) { this.minLimit = minLimit; }
        public BigDecimal getMaxLimit( ) { return maxLimit; }
        public void setMaxLimit(BigDecimal maxLimit) { this.maxLimit = maxLimit; }
        public String getUnitOfMeasure() { return unitOfMeasure; }
        public void setUnitOfMeasure(String unitOfMeasure) { this.unitOfMeasure = unitOfMeasure; }
        public boolean isWithinSpec() { return withinSpec; }
        public void setWithinSpec(boolean withinSpec) { this.withinSpec = withinSpec; }
        public String getRecordedBy() { return recordedBy; }
        public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
        public Instant getRecordedAt() { return recordedAt; }
    }
}
