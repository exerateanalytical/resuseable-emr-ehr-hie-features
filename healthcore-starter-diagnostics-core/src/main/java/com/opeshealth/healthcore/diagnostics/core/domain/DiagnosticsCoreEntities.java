/*
 * =============================================================================
 * Opes HealthCore SDK — Diagnostics Center Core Entities (ISO 15189-Aligned)
 * =============================================================================
 */
package com.opeshealth.healthcore.diagnostics.core.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class DiagnosticsCoreEntities {

    private DiagnosticsCoreEntities() {}

    @Entity
    @Table(name = "hc_diag_referring_partners")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ReferringPartnerEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "partner_code", nullable = false, length = 64)
        private String partnerCode;

        @Column(name = "partner_name", nullable = false, length = 160)
        private String partnerName;

        @Column(name = "partner_type", nullable = false, length = 32)
        private String partnerType;

        @Column(name = "report_delivery_channel", nullable = false, length = 32)
        private String reportDeliveryChannel = "PORTAL";

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
        public String getPartnerCode() { return partnerCode; }
        public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
        public String getPartnerName() { return partnerName; }
        public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
        public String getPartnerType() { return partnerType; }
        public void setPartnerType(String partnerType) { this.partnerType = partnerType; }
        public String getReportDeliveryChannel() { return reportDeliveryChannel; }
        public void setReportDeliveryChannel(String reportDeliveryChannel) { this.reportDeliveryChannel = reportDeliveryChannel; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_diag_catalog_items")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DiagnosticCatalogItemEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "catalog_code", nullable = false, length = 64)
        private String catalogCode;

        @Column(name = "display_name", nullable = false, length = 160)
        private String displayName;

        @Column(name = "modality_domain", nullable = false, length = 24)
        private String modalityDomain;

        @Column(name = "package_panel", nullable = false)
        private boolean packagePanel = false;

        @Column(name = "unit_ucum", length = 32)
        private String unitUcum;

        @Column(name = "ref_low", precision = 12, scale = 4)
        private BigDecimal refLow;

        @Column(name = "ref_high", precision = 12, scale = 4)
        private BigDecimal refHigh;

        @Column(name = "critical_low", precision = 12, scale = 4)
        private BigDecimal criticalLow;

        @Column(name = "critical_high", precision = 12, scale = 4)
        private BigDecimal criticalHigh;

        @Column(name = "tat_sla_minutes", nullable = false)
        private int tatSlaMinutes = 180;

        @Column(name = "send_out_partner", length = 120)
        private String sendOutPartner;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getCatalogCode() { return catalogCode; }
        public void setCatalogCode(String catalogCode) { this.catalogCode = catalogCode; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getModalityDomain() { return modalityDomain; }
        public void setModalityDomain(String modalityDomain) { this.modalityDomain = modalityDomain; }
        public boolean isPackagePanel() { return packagePanel; }
        public void setPackagePanel(boolean packagePanel) { this.packagePanel = packagePanel; }
        public String getUnitUcum() { return unitUcum; }
        public void setUnitUcum(String unitUcum) { this.unitUcum = unitUcum; }
        public BigDecimal getRefLow() { return refLow; }
        public void setRefLow(BigDecimal refLow) { this.refLow = refLow; }
        public BigDecimal getRefHigh() { return refHigh; }
        public void setRefHigh(BigDecimal refHigh) { this.refHigh = refHigh; }
        public BigDecimal getCriticalLow() { return criticalLow; }
        public void setCriticalLow(BigDecimal criticalLow) { this.criticalLow = criticalLow; }
        public BigDecimal getCriticalHigh() { return criticalHigh; }
        public void setCriticalHigh(BigDecimal criticalHigh) { this.criticalHigh = criticalHigh; }
        public int getTatSlaMinutes() { return tatSlaMinutes; }
        public void setTatSlaMinutes(int tatSlaMinutes) { this.tatSlaMinutes = tatSlaMinutes; }
        public String getSendOutPartner() { return sendOutPartner; }
        public void setSendOutPartner(String sendOutPartner) { this.sendOutPartner = sendOutPartner; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_diag_service_orders")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DiagnosticServiceOrderEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "accession_number", nullable = false, length = 64)
        private String accessionNumber;

        @Column(name = "modality_domain", nullable = false, length = 24)
        private String modalityDomain;

        @Column(name = "catalog_code", nullable = false, length = 64)
        private String catalogCode;

        @Column(name = "subject_ref", nullable = false, length = 120)
        private String subjectRef;

        @Column(name = "referring_partner_code", length = 64)
        private String referringPartnerCode;

        @Column(name = "tat_sla_minutes", nullable = false)
        private int tatSlaMinutes = 180;

        @Column(name = "rejection_reason", length = 240)
        private String rejectionReason;

        @Column(name = "recollection_requested", nullable = false)
        private boolean recollectionRequested = false;

        @Column(name = "status", nullable = false, length = 32)
        private String status = "REGISTERED"; // REGISTERED, REJECTED_RECOLLECT, IN_PROGRESS, VERIFIED_RELEASED, AMENDED

        @Column(name = "ordered_at", nullable = false)
        private Instant orderedAt = Instant.now();

        @Column(name = "verified_at")
        private Instant verifiedAt;

        @Column(name = "tat_breached", nullable = false)
        private boolean tatBreached = false;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public String getModalityDomain() { return modalityDomain; }
        public void setModalityDomain(String modalityDomain) { this.modalityDomain = modalityDomain; }
        public String getCatalogCode() { return catalogCode; }
        public void setCatalogCode(String catalogCode) { this.catalogCode = catalogCode; }
        public String getSubjectRef() { return subjectRef; }
        public void setSubjectRef(String subjectRef) { this.subjectRef = subjectRef; }
        public String getReferringPartnerCode() { return referringPartnerCode; }
        public void setReferringPartnerCode(String referringPartnerCode) { this.referringPartnerCode = referringPartnerCode; }
        public int getTatSlaMinutes() { return tatSlaMinutes; }
        public void setTatSlaMinutes(int tatSlaMinutes) { this.tatSlaMinutes = tatSlaMinutes; }
        public String getRejectionReason() { return rejectionReason; }
        public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
        public boolean isRecollectionRequested() { return recollectionRequested; }
        public void setRecollectionRequested(boolean recollectionRequested) { this.recollectionRequested = recollectionRequested; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getOrderedAt() { return orderedAt; }
        public void setOrderedAt(Instant orderedAt) { this.orderedAt = orderedAt; }
        public Instant getVerifiedAt() { return verifiedAt; }
        public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
        public boolean isTatBreached() { return tatBreached; }
        public void setTatBreached(boolean tatBreached) { this.tatBreached = tatBreached; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_diag_critical_notifications")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class CriticalValueNotificationEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "accession_number", nullable = false, length = 64)
        private String accessionNumber;

        @Column(name = "critical_value_summary", nullable = false, length = 240)
        private String criticalValueSummary;

        @Column(name = "notified_clinician", nullable = false, length = 120)
        private String notifiedClinician;

        @Column(name = "read_back_verified", nullable = false)
        private boolean readBackVerified = false;

        @Column(name = "acknowledged_by", length = 120)
        private String acknowledgedBy;

        @Column(name = "acknowledged_at")
        private Instant acknowledgedAt;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "ESCALATED"; // ESCALATED, ACKNOWLEDGED_READBACK

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public String getCriticalValueSummary() { return criticalValueSummary; }
        public void setCriticalValueSummary(String criticalValueSummary) { this.criticalValueSummary = criticalValueSummary; }
        public String getNotifiedClinician() { return notifiedClinician; }
        public void setNotifiedClinician(String notifiedClinician) { this.notifiedClinician = notifiedClinician; }
        public boolean isReadBackVerified() { return readBackVerified; }
        public void setReadBackVerified(boolean readBackVerified) { this.readBackVerified = readBackVerified; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
        public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_diag_result_amendments")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ResultAmendmentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "order_id", nullable = false)
        private UUID orderId;

        @Column(name = "prior_result_summary", nullable = false, length = org.hibernate.Length.LONG32)
        private String priorResultSummary;

        @Column(name = "amended_result_summary", nullable = false, length = org.hibernate.Length.LONG32)
        private String amendedResultSummary;

        @Column(name = "amendment_reason", nullable = false, length = 240)
        private String amendmentReason;

        @Column(name = "amended_by", nullable = false, length = 120)
        private String amendedBy;

        @Column(name = "amended_at", nullable = false)
        private Instant amendedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public String getPriorResultSummary() { return priorResultSummary; }
        public void setPriorResultSummary(String priorResultSummary) { this.priorResultSummary = priorResultSummary; }
        public String getAmendedResultSummary() { return amendedResultSummary; }
        public void setAmendedResultSummary(String amendedResultSummary) { this.amendedResultSummary = amendedResultSummary; }
        public String getAmendmentReason() { return amendmentReason; }
        public void setAmendmentReason(String amendmentReason) { this.amendmentReason = amendmentReason; }
        public String getAmendedBy() { return amendedBy; }
        public void setAmendedBy(String amendedBy) { this.amendedBy = amendedBy; }
        public Instant getAmendedAt() { return amendedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_diag_quality_incidents")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class QualityIncidentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "incident_code", nullable = false, length = 64)
        private String incidentCode;

        @Column(name = "phase", nullable = false, length = 24)
        private String phase;

        @Column(name = "accession_number", length = 64)
        private String accessionNumber;

        @Column(name = "description", nullable = false, length = org.hibernate.Length.LONG32)
        private String description;

        @Column(name = "corrective_action", length = org.hibernate.Length.LONG32)
        private String correctiveAction;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "OPEN";

        @Column(name = "reported_by", nullable = false, length = 120)
        private String reportedBy;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getIncidentCode() { return incidentCode; }
        public void setIncidentCode(String incidentCode) { this.incidentCode = incidentCode; }
        public String getPhase() { return phase; }
        public void setPhase(String phase) { this.phase = phase; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getCorrectiveAction() { return correctiveAction; }
        public void setCorrectiveAction(String correctiveAction) { this.correctiveAction = correctiveAction; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReportedBy() { return reportedBy; }
        public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
