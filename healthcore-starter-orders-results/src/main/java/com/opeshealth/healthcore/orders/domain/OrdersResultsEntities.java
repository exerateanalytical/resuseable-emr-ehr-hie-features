/*
 * =============================================================================
 * Opes HealthCore SDK — Unified Orders & Closed-Loop Results Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.orders.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Facility-scoped unified orders and closed-loop clinical results entities.
 */
public final class OrdersResultsEntities {

    private OrdersResultsEntities() {}

    public enum OrderDomain {
        LABORATORY,
        IMAGING,
        MEDICATION,
        PROCEDURE,
        REFERRAL
    }

    public enum OrderPriority {
        ROUTINE,
        URGENT,
        STAT
    }

    public enum OrderStatus {
        DRAFT,
        ORDERED,
        ACKNOWLEDGED,
        SPECIMEN_COLLECTED,
        IN_PROGRESS,
        RESULTED,
        VERIFIED,
        CANCELLED
    }

    public enum ResultStatus {
        PRELIMINARY,
        FINAL,
        AMENDED,
        CANCELLED
    }

    public enum AbnormalFlag {
        NORMAL,
        LOW,
        HIGH,
        CRITICAL_LOW,
        CRITICAL_HIGH
    }

    @Entity
    @Table(name = "hc_order_sets")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalOrderSetEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "set_code", nullable = false, length = 64)
        private String setCode;

        @Column(name = "name", nullable = false, length = 160)
        private String name;

        @Column(name = "clinical_indication", length = 300)
        private String clinicalIndication;

        /** Semicolon-delimited order items in format DOMAIN|CODE|DISPLAY|PRIORITY. */
        @Column(name = "items_spec", nullable = false, length = org.hibernate.Length.LONG32)
        private String itemsSpec;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getSetCode() { return setCode; }
        public void setSetCode(String setCode) { this.setCode = setCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getClinicalIndication() { return clinicalIndication; }
        public void setClinicalIndication(String clinicalIndication) { this.clinicalIndication = clinicalIndication; }
        public String getItemsSpec() { return itemsSpec; }
        public void setItemsSpec(String itemsSpec) { this.itemsSpec = itemsSpec; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Entity
    @Table(name = "hc_unified_orders")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class UnifiedClinicalOrderEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "encounter_id")
        private UUID encounterId;

        @Enumerated(EnumType.STRING)
        @Column(name = "order_domain", nullable = false, length = 24)
        private OrderDomain orderDomain;

        @Column(name = "code_system", nullable = false, length = 120)
        private String codeSystem = "http://loinc.org";

        @Column(name = "order_code", nullable = false, length = 64)
        private String orderCode;

        @Column(name = "order_display", nullable = false, length = 300)
        private String orderDisplay;

        @Enumerated(EnumType.STRING)
        @Column(name = "priority", nullable = false, length = 16)
        private OrderPriority priority = OrderPriority.ROUTINE;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 24)
        private OrderStatus status = OrderStatus.ORDERED;

        @Column(name = "clinical_notes", length = 500)
        private String clinicalNotes;

        @Column(name = "accession_number", length = 64)
        private String accessionNumber;

        @Column(name = "reflex_source_order_id")
        private UUID reflexSourceOrderId;

        @Column(name = "ordered_by", nullable = false, length = 120)
        private String orderedBy;

        @Column(name = "acknowledged_by", length = 120)
        private String acknowledgedBy;

        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;

        @Column(name = "cancellation_reason", length = 300)
        private String cancellationReason;

        @Column(name = "ordered_at", nullable = false)
        private Instant orderedAt = Instant.now();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public OrderDomain getOrderDomain() { return orderDomain; }
        public void setOrderDomain(OrderDomain orderDomain) { this.orderDomain = orderDomain; }
        public String getCodeSystem() { return codeSystem; }
        public void setCodeSystem(String codeSystem) { this.codeSystem = codeSystem; }
        public String getOrderCode() { return orderCode; }
        public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
        public String getOrderDisplay() { return orderDisplay; }
        public void setOrderDisplay(String orderDisplay) { this.orderDisplay = orderDisplay; }
        public OrderPriority getPriority() { return priority; }
        public void setPriority(OrderPriority priority) { this.priority = priority; }
        public OrderStatus getStatus() { return status; }
        public void setStatus(OrderStatus status) { this.status = status; }
        public String getClinicalNotes() { return clinicalNotes; }
        public void setClinicalNotes(String clinicalNotes) { this.clinicalNotes = clinicalNotes; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public UUID getReflexSourceOrderId() { return reflexSourceOrderId; }
        public void setReflexSourceOrderId(UUID reflexSourceOrderId) { this.reflexSourceOrderId = reflexSourceOrderId; }
        public String getOrderedBy() { return orderedBy; }
        public void setOrderedBy(String orderedBy) { this.orderedBy = orderedBy; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
        public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
        public String getCancellationReason() { return cancellationReason; }
        public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
        public Instant getOrderedAt() { return orderedAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_order_specimens")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class OrderSpecimenEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "accession_barcode", nullable = false, length = 64)
        private String accessionBarcode;

        @Column(name = "specimen_type", nullable = false, length = 64)
        private String specimenType;

        @Column(name = "collected_by", nullable = false, length = 120)
        private String collectedBy;

        @Column(name = "collected_at", nullable = false)
        private Instant collectedAt = Instant.now();

        @Column(name = "condition_notes", length = 300)
        private String conditionNotes;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public String getAccessionBarcode() { return accessionBarcode; }
        public void setAccessionBarcode(String accessionBarcode) { this.accessionBarcode = accessionBarcode; }
        public String getSpecimenType() { return specimenType; }
        public void setSpecimenType(String specimenType) { this.specimenType = specimenType; }
        public String getCollectedBy() { return collectedBy; }
        public void setCollectedBy(String collectedBy) { this.collectedBy = collectedBy; }
        public Instant getCollectedAt() { return collectedAt; }
        public String getConditionNotes() { return conditionNotes; }
        public void setConditionNotes(String conditionNotes) { this.conditionNotes = conditionNotes; }
    }

    @Entity
    @Table(name = "hc_clinical_results")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalResultEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "result_code", nullable = false, length = 64)
        private String resultCode;

        @Column(name = "result_display", nullable = false, length = 240)
        private String resultDisplay;

        @Column(name = "numeric_value", precision = 14, scale = 4)
        private BigDecimal numericValue;

        @Column(name = "text_value", length = 1000)
        private String textValue;

        @Column(name = "unit", length = 40)
        private String unit;

        @Column(name = "reference_range", length = 120)
        private String referenceRange;

        @Enumerated(EnumType.STRING)
        @Column(name = "abnormal_flag", nullable = false, length = 24)
        private AbnormalFlag abnormalFlag = AbnormalFlag.NORMAL;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 24)
        private ResultStatus status = ResultStatus.PRELIMINARY;

        @Column(name = "revision_number", nullable = false)
        private int revisionNumber = 1;

        @Column(name = "amended_from_result_id")
        private UUID amendedFromResultId;

        @Column(name = "amendment_reason", length = 400)
        private String amendmentReason;

        @Column(name = "entered_by", nullable = false, length = 120)
        private String enteredBy;

        @Column(name = "verified_by", length = 120)
        private String verifiedBy;

        @Column(name = "verified_at")
        private Instant verifiedAt;

        @Column(name = "critical_ack_required", nullable = false)
        private boolean criticalAckRequired = false;

        @Column(name = "critical_ack_by", length = 120)
        private String criticalAckBy;

        @Column(name = "critical_ack_at")
        private Instant criticalAckAt;

        @Column(name = "critical_ack_notes", length = 400)
        private String criticalAckNotes;

        @Column(name = "recorded_at", nullable = false)
        private Instant recordedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getResultCode() { return resultCode; }
        public void setResultCode(String resultCode) { this.resultCode = resultCode; }
        public String getResultDisplay() { return resultDisplay; }
        public void setResultDisplay(String resultDisplay) { this.resultDisplay = resultDisplay; }
        public BigDecimal getNumericValue() { return numericValue; }
        public void setNumericValue(BigDecimal numericValue) { this.numericValue = numericValue; }
        public String getTextValue() { return textValue; }
        public void setTextValue(String textValue) { this.textValue = textValue; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getReferenceRange() { return referenceRange; }
        public void setReferenceRange(String referenceRange) { this.referenceRange = referenceRange; }
        public AbnormalFlag getAbnormalFlag() { return abnormalFlag; }
        public void setAbnormalFlag(AbnormalFlag abnormalFlag) { this.abnormalFlag = abnormalFlag; }
        public ResultStatus getStatus() { return status; }
        public void setStatus(ResultStatus status) { this.status = status; }
        public int getRevisionNumber() { return revisionNumber; }
        public void setRevisionNumber(int revisionNumber) { this.revisionNumber = revisionNumber; }
        public UUID getAmendedFromResultId() { return amendedFromResultId; }
        public void setAmendedFromResultId(UUID amendedFromResultId) { this.amendedFromResultId = amendedFromResultId; }
        public String getAmendmentReason() { return amendmentReason; }
        public void setAmendmentReason(String amendmentReason) { this.amendmentReason = amendmentReason; }
        public String getEnteredBy() { return enteredBy; }
        public void setEnteredBy(String enteredBy) { this.enteredBy = enteredBy; }
        public String getVerifiedBy() { return verifiedBy; }
        public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
        public Instant getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
        public boolean isCriticalAckRequired() { return criticalAckRequired; }
        public void setCriticalAckRequired(boolean criticalAckRequired) { this.criticalAckRequired = criticalAckRequired; }
        public String getCriticalAckBy() { return criticalAckBy; }
        public void setCriticalAckBy(String criticalAckBy) { this.criticalAckBy = criticalAckBy; }
        public Instant getCriticalAckAt() { return criticalAckAt; }
        public void setCriticalAckAt(Instant criticalAckAt) { this.criticalAckAt = criticalAckAt; }
        public String getCriticalAckNotes() { return criticalAckNotes; }
        public void setCriticalAckNotes(String criticalAckNotes) { this.criticalAckNotes = criticalAckNotes; }
        public Instant getRecordedAt() { return recordedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_reflex_order_rules")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ReflexOrderRuleEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "trigger_result_code", nullable = false, length = 64)
        private String triggerResultCode;

        @Enumerated(EnumType.STRING)
        @Column(name = "trigger_flag", nullable = false, length = 24)
        private AbnormalFlag triggerFlag;

        @Enumerated(EnumType.STRING)
        @Column(name = "reflex_domain", nullable = false, length = 24)
        private OrderDomain reflexDomain = OrderDomain.LABORATORY;

        @Column(name = "reflex_order_code", nullable = false, length = 64)
        private String reflexOrderCode;

        @Column(name = "reflex_order_display", nullable = false, length = 240)
        private String reflexOrderDisplay;

        @Enumerated(EnumType.STRING)
        @Column(name = "reflex_priority", nullable = false, length = 16)
        private OrderPriority reflexPriority = OrderPriority.URGENT;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getTriggerResultCode() { return triggerResultCode; }
        public void setTriggerResultCode(String triggerResultCode) { this.triggerResultCode = triggerResultCode; }
        public AbnormalFlag getTriggerFlag() { return triggerFlag; }
        public void setTriggerFlag(AbnormalFlag triggerFlag) { this.triggerFlag = triggerFlag; }
        public OrderDomain getReflexDomain() { return reflexDomain; }
        public void setReflexDomain(OrderDomain reflexDomain) { this.reflexDomain = reflexDomain; }
        public String getReflexOrderCode() { return reflexOrderCode; }
        public void setReflexOrderCode(String reflexOrderCode) { this.reflexOrderCode = reflexOrderCode; }
        public String getReflexOrderDisplay() { return reflexOrderDisplay; }
        public void setReflexOrderDisplay(String reflexOrderDisplay) { this.reflexOrderDisplay = reflexOrderDisplay; }
        public OrderPriority getReflexPriority() { return reflexPriority; }
        public void setReflexPriority(OrderPriority reflexPriority) { this.reflexPriority = reflexPriority; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }
}
