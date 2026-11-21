/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmacy (PIS) & Lab/Diagnostic (LIS) Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacylis.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PharmacyLisEntities {

    private PharmacyLisEntities() {}

    /** Lot-level medication inventory supporting FEFO picking, expiry quarantine, and controlled schedules. */
    @Entity(name = "PharmacyMedicationBatch") @Table(name = "hc_medication_batches")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class MedicationBatchEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "drug_code", nullable = false, length = 64) private String drugCode;
        @Column(name = "drug_name", nullable = false, length = 200) private String drugName;
        @Column(name = "lot_number", nullable = false, length = 64) private String lotNumber;
        @Column(name = "expiry_date", nullable = false) private LocalDate expiryDate;
        @Column(name = "quantity_on_hand", nullable = false) private int quantityOnHand;
        @Column(name = "reorder_threshold", nullable = false) private int reorderThreshold = 20;
        @Column(name = "controlled_schedule", length = 16) private String controlledSchedule;
        @Column(name = "quarantined", nullable = false) private boolean quarantined = false;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDrugCode() { return drugCode; }
        public void setDrugCode(String drugCode) { this.drugCode = drugCode; }
        public String getDrugName() { return drugName; }
        public void setDrugName(String drugName) { this.drugName = drugName; }
        public String getLotNumber() { return lotNumber; }
        public void setLotNumber(String lotNumber) { this.lotNumber = lotNumber; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public int getQuantityOnHand() { return quantityOnHand; }
        public void setQuantityOnHand(int quantityOnHand) { this.quantityOnHand = quantityOnHand; }
        public int getReorderThreshold() { return reorderThreshold; }
        public void setReorderThreshold(int reorderThreshold) { this.reorderThreshold = reorderThreshold; }
        public String getControlledSchedule() { return controlledSchedule; }
        public void setControlledSchedule(String controlledSchedule) { this.controlledSchedule = controlledSchedule; }
        public boolean isQuarantined() { return quarantined; }
        public void setQuarantined(boolean quarantined) { this.quarantined = quarantined; }
        public long getVersion() { return version; }
    }

    @Entity(name = "PharmacyDispenseRecord") @Table(name = "hc_pharmacy_dispenses")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PharmacyDispenseEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "prescription_order_id") private UUID prescriptionOrderId;
        @Column(name = "batch_id", nullable = false) private UUID batchId;
        @Column(name = "prescribed_drug_code", nullable = false, length = 64) private String prescribedDrugCode;
        @Column(name = "dispensed_drug_code", nullable = false, length = 64) private String dispensedDrugCode;
        @Column(name = "quantity_dispensed", nullable = false) private int quantityDispensed;
        @Column(name = "partial_fill", nullable = false) private boolean partialFill = false;
        @Column(name = "pharmacist_id", nullable = false, length = 120) private String pharmacistId;
        @Column(name = "controlled_witness_id", length = 120) private String controlledWitnessId;
        @Column(name = "dispensed_at", nullable = false) private Instant dispensedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getPrescriptionOrderId() { return prescriptionOrderId; }
        public void setPrescriptionOrderId(UUID prescriptionOrderId) { this.prescriptionOrderId = prescriptionOrderId; }
        public UUID getBatchId() { return batchId; }
        public void setBatchId(UUID batchId) { this.batchId = batchId; }
        public String getPrescribedDrugCode() { return prescribedDrugCode; }
        public void setPrescribedDrugCode(String prescribedDrugCode) { this.prescribedDrugCode = prescribedDrugCode; }
        public String getDispensedDrugCode() { return dispensedDrugCode; }
        public void setDispensedDrugCode(String dispensedDrugCode) { this.dispensedDrugCode = dispensedDrugCode; }
        public int getQuantityDispensed() { return quantityDispensed; }
        public void setQuantityDispensed(int quantityDispensed) { this.quantityDispensed = quantityDispensed; }
        public boolean isPartialFill() { return partialFill; }
        public void setPartialFill(boolean partialFill) { this.partialFill = partialFill; }
        public String getPharmacistId() { return pharmacistId; }
        public void setPharmacistId(String pharmacistId) { this.pharmacistId = pharmacistId; }
        public String getControlledWitnessId() { return controlledWitnessId; }
        public void setControlledWitnessId(String controlledWitnessId) { this.controlledWitnessId = controlledWitnessId; }
        public Instant getDispensedAt() { return dispensedAt; }
    }

    @Entity(name = "LisSpecimenRecord") @Table(name = "hc_lab_specimens")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class LabSpecimenEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "lab_order_id") private UUID labOrderId;
        @Column(name = "accession_barcode", nullable = false, length = 64) private String accessionBarcode;
        @Column(name = "specimen_type", nullable = false, length = 48) private String specimenType; // BLOOD_EDTA, URINE, SPUTUM, SWAB
        @Column(name = "status", nullable = false, length = 24) private String status = "COLLECTED"; // COLLECTED, RECEIVED, REJECTED, ANALYZED
        @Column(name = "rejection_reason", length = 200) private String rejectionReason;
        @Column(name = "collected_at", nullable = false) private Instant collectedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getLabOrderId() { return labOrderId; }
        public void setLabOrderId(UUID labOrderId) { this.labOrderId = labOrderId; }
        public String getAccessionBarcode() { return accessionBarcode; }
        public void setAccessionBarcode(String accessionBarcode) { this.accessionBarcode = accessionBarcode; }
        public String getSpecimenType() { return specimenType; }
        public void setSpecimenType(String specimenType) { this.specimenType = specimenType; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getRejectionReason() { return rejectionReason; }
        public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
        public Instant getCollectedAt() { return collectedAt; }
    }

    @Entity(name = "LisDiagnosticReport") @Table(name = "hc_diagnostic_reports")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DiagnosticReportEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "specimen_id", nullable = false) private UUID specimenId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "loinc_code", nullable = false, length = 64) private String loincCode;
        @Column(name = "test_name", nullable = false, length = 200) private String testName;
        @Column(name = "numeric_value", precision = 12, scale = 4) private BigDecimal numericValue;
        @Column(name = "text_value", length = 400) private String textValue;
        @Column(name = "ucum_unit", length = 32) private String ucumUnit;
        @Column(name = "reference_range", length = 80) private String referenceRange;
        @Column(name = "abnormal_flag", length = 16) private String abnormalFlag; // N, H, L, HH (PANIC), LL (PANIC)
        @Column(name = "westgard_qc_passed", nullable = false) private boolean westgardQcPassed = true;
        @Column(name = "panic_acknowledged_by", length = 120) private String panicAcknowledgedBy;
        @Column(name = "verified_by", length = 120) private String verifiedBy;
        @Column(name = "verified_at") private Instant verifiedAt;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSpecimenId() { return specimenId; }
        public void setSpecimenId(UUID specimenId) { this.specimenId = specimenId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getLoincCode() { return loincCode; }
        public void setLoincCode(String loincCode) { this.loincCode = loincCode; }
        public String getTestName() { return testName; }
        public void setTestName(String testName) { this.testName = testName; }
        public BigDecimal getNumericValue() { return numericValue; }
        public void setNumericValue(BigDecimal numericValue) { this.numericValue = numericValue; }
        public String getTextValue() { return textValue; }
        public void setTextValue(String textValue) { this.textValue = textValue; }
        public String getUcumUnit() { return ucumUnit; }
        public void setUcumUnit(String ucumUnit) { this.ucumUnit = ucumUnit; }
        public String getReferenceRange() { return referenceRange; }
        public void setReferenceRange(String referenceRange) { this.referenceRange = referenceRange; }
        public String getAbnormalFlag() { return abnormalFlag; }
        public void setAbnormalFlag(String abnormalFlag) { this.abnormalFlag = abnormalFlag; }
        public boolean isWestgardQcPassed() { return westgardQcPassed; }
        public void setWestgardQcPassed(boolean westgardQcPassed) { this.westgardQcPassed = westgardQcPassed; }
        public String getPanicAcknowledgedBy() { return panicAcknowledgedBy; }
        public void setPanicAcknowledgedBy(String panicAcknowledgedBy) { this.panicAcknowledgedBy = panicAcknowledgedBy; }
        public String getVerifiedBy() { return verifiedBy; }
        public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
        public Instant getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
    }
}
