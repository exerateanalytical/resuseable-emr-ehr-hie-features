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
        private String partnerType; // PHYSICIAN, CLINIC, HOSPITAL, CORPORATE_WELLNESS

        @Column(name = "report_delivery_channel", nullable = false, length = 32)
        private String reportDeliveryChannel = "PORTAL"; // PORTAL, FHIR_API, HL7_ORU, SECURE_EMAIL

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
        private String modalityDomain; // LABORATORY, RADIOLOGY, CARDIOLOGY, PATHOLOGY

        @Column(name = "catalog_code", nullable = false, length = 64)
        private String catalogCode; // LOINC, CPT, or local package code

        @Column(name = "subject_ref", nullable = false, length = 120)
        private String subjectRef; // Walk-in client ID or external MPI reference

        @Column(name = "referring_partner_code", length = 64)
        private String referringPartnerCode;

        @Column(name = "tat_sla_minutes", nullable = false)
        private int tatSlaMinutes = 180;

        @Column(name = "rejection_reason", length = 240)
        private String rejectionReason;

        @Column(name = "recollection_requested", nullable = false)
        private boolean recollectionRequested = false;

        @Column(name = "status", nullable = false, length = 32)
        private String status = "REGISTERED"; // REGISTERED, REJECTED_RECOLLECT, IN_PROGRESS, VERIFIED_RELEASED

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
        private String phase; // PRE_ANALYTICAL, ANALYTICAL, POST_ANALYTICAL

        @Column(name = "accession_number", length = 64)
        private String accessionNumber;

        @Lob
        @Column(name = "description", nullable = false)
        private String description;

        @Lob
        @Column(name = "corrective_action")
        private String correctiveAction;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "OPEN"; // OPEN, RESOLVED

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
