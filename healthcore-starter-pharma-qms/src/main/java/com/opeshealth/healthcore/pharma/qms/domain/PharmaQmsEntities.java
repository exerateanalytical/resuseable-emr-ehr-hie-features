/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical QMS, QC Lab, CoA & CAPA Domain Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.qms.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PharmaQmsEntities {

    private PharmaQmsEntities() {}

    @Entity
    @Table(name = "hc_pqms_controlled_docs")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ControlledDocumentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "document_code", nullable = false, length = 64)
        private String documentCode;

        @Column(name = "document_version", nullable = false, length = 24)
        private String documentVersion;

        @Column(name = "document_type", nullable = false, length = 32)
        private String documentType; // SOP, VALIDATION_PROTOCOL, SPEC, CHANGE_CONTROL

        @Column(name = "title", nullable = false, length = 240)
        private String title;

        @Column(name = "content_sha256", nullable = false, length = 64)
        private String contentSha256;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DRAFT"; // DRAFT, EFFECTIVE, SUPERSEDED

        @Column(name = "authored_by", nullable = false, length = 120)
        private String authoredBy;

        @Column(name = "approved_by", length = 120)
        private String approvedBy;

        @Column(name = "approval_sig_sha256", length = 64)
        private String approvalSigSha256;

        @Column(name = "effective_date")
        private LocalDate effectiveDate;

        @Column(name = "review_due_date")
        private LocalDate reviewDueDate;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDocumentCode() { return documentCode; }
        public void setDocumentCode(String documentCode) { this.documentCode = documentCode; }
        public String getDocumentVersion() { return documentVersion; }
        public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }
        public String getDocumentType() { return documentType; }
        public void setDocumentType(String documentType) { this.documentType = documentType; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContentSha256() { return contentSha256; }
        public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAuthoredBy() { return authoredBy; }
        public void setAuthoredBy(String authoredBy) { this.authoredBy = authoredBy; }
        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
        public String getApprovalSigSha256() { return approvalSigSha256; }
        public void setApprovalSigSha256(String approvalSigSha256) { this.approvalSigSha256 = approvalSigSha256; }
        public LocalDate getEffectiveDate() { return effectiveDate; }
        public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
        public LocalDate getReviewDueDate() { return reviewDueDate; }
        public void setReviewDueDate(LocalDate reviewDueDate) { this.reviewDueDate = reviewDueDate; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pqms_qc_certificates")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class QcCertificateEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "certificate_number", nullable = false, length = 64)
        private String certificateNumber;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "product_code", nullable = false, length = 64)
        private String productCode;

        @Column(name = "sample_stage", nullable = false, length = 32)
        private String sampleStage; // RAW_MATERIAL, IN_PROCESS, FINISHED_GOOD, STABILITY

        @Lob
        @Column(name = "test_results_json", nullable = false)
        private String testResultsJson;

        @Column(name = "oos_detected", nullable = false)
        private boolean oosDetected = false;

        @Column(name = "qc_disposition", nullable = false, length = 24)
        private String qcDisposition = "PENDING_REVIEW"; // PENDING_REVIEW, PASS_COA_ISSUED, OOS_HOLD, REJECTED

        @Column(name = "analyst_id", nullable = false, length = 120)
        private String analystId;

        @Column(name = "qa_approver_id", length = 120)
        private String qaApproverId;

        @Column(name = "coa_sig_sha256", length = 64)
        private String coaSigSha256;

        @Column(name = "issued_at", nullable = false)
        private Instant issuedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getCertificateNumber() { return certificateNumber; }
        public void setCertificateNumber(String certificateNumber) { this.certificateNumber = certificateNumber; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public String getSampleStage() { return sampleStage; }
        public void setSampleStage(String sampleStage) { this.sampleStage = sampleStage; }
        public String getTestResultsJson() { return testResultsJson; }
        public void setTestResultsJson(String testResultsJson) { this.testResultsJson = testResultsJson; }
        public boolean isOosDetected() { return oosDetected; }
        public void setOosDetected(boolean oosDetected) { this.oosDetected = oosDetected; }
        public String getQcDisposition() { return qcDisposition; }
        public void setQcDisposition(String qcDisposition) { this.qcDisposition = qcDisposition; }
        public String getAnalystId() { return analystId; }
        public void setAnalystId(String analystId) { this.analystId = analystId; }
        public String getQaApproverId() { return qaApproverId; }
        public void setQaApproverId(String qaApproverId) { this.qaApproverId = qaApproverId; }
        public String getCoaSigSha256() { return coaSigSha256; }
        public void setCoaSigSha256(String coaSigSha256) { this.coaSigSha256 = coaSigSha256; }
        public Instant getIssuedAt() { return issuedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pqms_deviations_capa")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DeviationCapaEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "record_number", nullable = false, length = 64)
        private String recordNumber;

        @Column(name = "record_category", nullable = false, length = 32)
        private String recordCategory; // DEVIATION, OOS_INVESTIGATION, CAPA, CHANGE_CONTROL, COMPLAINT, RECALL

        @Column(name = "severity", nullable = false, length = 24)
        private String severity; // MINOR, MAJOR, CRITICAL

        @Column(name = "affected_batch_number", length = 64)
        private String affectedBatchNumber;

        @Lob
        @Column(name = "description", nullable = false)
        private String description;

        @Lob
        @Column(name = "root_cause")
        private String rootCause;

        @Lob
        @Column(name = "capa_plan")
        private String capaPlan;

        @Column(name = "effectiveness_verified", nullable = false)
        private boolean effectivenessVerified = false;

        @Column(name = "status", nullable = false, length = 32)
        private String status = "OPEN"; // OPEN, UNDER_INVESTIGATION, CAPA_IN_PROGRESS, CLOSED

        @Column(name = "reported_by", nullable = false, length = 120)
        private String reportedBy;

        @Column(name = "closed_by", length = 120)
        private String closedBy;

        @Column(name = "closure_sig_sha256", length = 64)
        private String closureSigSha256;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "closed_at")
        private Instant closedAt;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getRecordNumber() { return recordNumber; }
        public void setRecordNumber(String recordNumber) { this.recordNumber = recordNumber; }
        public String getRecordCategory() { return recordCategory; }
        public void setRecordCategory(String recordCategory) { this.recordCategory = recordCategory; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getAffectedBatchNumber() { return affectedBatchNumber; }
        public void setAffectedBatchNumber(String affectedBatchNumber) { this.affectedBatchNumber = affectedBatchNumber; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getRootCause() { return rootCause; }
        public void setRootCause(String rootCause) { this.rootCause = rootCause; }
        public String getCapaPlan() { return capaPlan; }
        public void setCapaPlan(String capaPlan) { this.capaPlan = capaPlan; }
        public boolean isEffectivenessVerified() { return effectivenessVerified; }
        public void setEffectivenessVerified(boolean effectivenessVerified) { this.effectivenessVerified = effectivenessVerified; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getReportedBy() { return reportedBy; }
        public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
        public String getClosedBy() { return closedBy; }
        public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
        public String getClosureSigSha256() { return closureSigSha256; }
        public void setClosureSigSha256(String closureSigSha256) { this.closureSigSha256 = closureSigSha256; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getClosedAt() { return closedAt; }
        public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pqms_equipment_qual")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class EquipmentQualificationEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "asset_code", nullable = false, length = 64)
        private String assetCode;

        @Column(name = "equipment_name", nullable = false, length = 180)
        private String equipmentName;

        @Column(name = "qualification_stage", nullable = false, length = 24)
        private String qualificationStage; // IQ, OQ, PQ

        @Column(name = "calibration_status", nullable = false, length = 32)
        private String calibrationStatus = "QUALIFIED"; // QUALIFIED, CALIBRATION_EXPIRED, OUT_OF_SERVICE

        @Column(name = "last_calibrated_date", nullable = false)
        private LocalDate lastCalibratedDate;

        @Column(name = "calibration_due_date", nullable = false)
        private LocalDate calibrationDueDate;

        @Column(name = "calibrated_by", nullable = false, length = 120)
        private String calibratedBy;

        @Column(name = "qa_verified_by", length = 120)
        private String qaVerifiedBy;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getAssetCode() { return assetCode; }
        public void setAssetCode(String assetCode) { this.assetCode = assetCode; }
        public String getEquipmentName() { return equipmentName; }
        public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
        public String getQualificationStage() { return qualificationStage; }
        public void setQualificationStage(String qualificationStage) { this.qualificationStage = qualificationStage; }
        public String getCalibrationStatus() { return calibrationStatus; }
        public void setCalibrationStatus(String calibrationStatus) { this.calibrationStatus = calibrationStatus; }
        public LocalDate getLastCalibratedDate() { return lastCalibratedDate; }
        public void setLastCalibratedDate(LocalDate lastCalibratedDate) { this.lastCalibratedDate = lastCalibratedDate; }
        public LocalDate getCalibrationDueDate() { return calibrationDueDate; }
        public void setCalibrationDueDate(LocalDate calibrationDueDate) { this.calibrationDueDate = calibrationDueDate; }
        public String getCalibratedBy() { return calibratedBy; }
        public void setCalibratedBy(String calibratedBy) { this.calibratedBy = calibratedBy; }
        public String getQaVerifiedBy() { return qaVerifiedBy; }
        public void setQaVerifiedBy(String qaVerifiedBy) { this.qaVerifiedBy = qaVerifiedBy; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pqms_stability_samples")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class StabilitySampleEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "study_code", nullable = false, length = 64)
        private String studyCode;

        @Column(name = "product_code", nullable = false, length = 64)
        private String productCode;

        @Column(name = "batch_number", nullable = false, length = 64)
        private String batchNumber;

        @Column(name = "sample_kind", nullable = false, length = 32)
        private String sampleKind; // STABILITY_STUDY, RETAINED_SAMPLE

        @Column(name = "storage_condition", nullable = false, length = 64)
        private String storageCondition; // e.g., 25C/60%RH, 40C/75%RH, 30C/65%RH

        @Column(name = "timepoint_months", nullable = false)
        private int timepointMonths;

        @Column(name = "scheduled_pull_date", nullable = false)
        private LocalDate scheduledPullDate;

        @Column(name = "actual_pull_date")
        private LocalDate actualPullDate;

        @Lob
        @Column(name = "assay_summary_json")
        private String assaySummaryJson;

        @Column(name = "disposition", nullable = false, length = 24)
        private String disposition = "SCHEDULED"; // SCHEDULED, PASS, OOT_ALERT, OOS_FAIL

        @Column(name = "analyst_id", length = 120)
        private String analystId;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getStudyCode() { return studyCode; }
        public void setStudyCode(String studyCode) { this.studyCode = studyCode; }
        public String getProductCode() { return productCode; }
        public void setProductCode(String productCode) { this.productCode = productCode; }
        public String getBatchNumber() { return batchNumber; }
        public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
        public String getSampleKind() { return sampleKind; }
        public void setSampleKind(String sampleKind) { this.sampleKind = sampleKind; }
        public String getStorageCondition() { return storageCondition; }
        public void setStorageCondition(String storageCondition) { this.storageCondition = storageCondition; }
        public int getTimepointMonths() { return timepointMonths; }
        public void setTimepointMonths(int timepointMonths) { this.timepointMonths = timepointMonths; }
        public LocalDate getScheduledPullDate() { return scheduledPullDate; }
        public void setScheduledPullDate(LocalDate scheduledPullDate) { this.scheduledPullDate = scheduledPullDate; }
        public LocalDate getActualPullDate() { return actualPullDate; }
        public void setActualPullDate(LocalDate actualPullDate) { this.actualPullDate = actualPullDate; }
        public String getAssaySummaryJson() { return assaySummaryJson; }
        public void setAssaySummaryJson(String assaySummaryJson) { this.assaySummaryJson = assaySummaryJson; }
        public String getDisposition() { return disposition; }
        public void setDisposition(String disposition) { this.disposition = disposition; }
        public String getAnalystId() { return analystId; }
        public void setAnalystId(String analystId) { this.analystId = analystId; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_pqms_supplier_qual")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class SupplierQualificationEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "supplier_code", nullable = false, length = 64)
        private String supplierCode;

        @Column(name = "supplier_name", nullable = false, length = 180)
        private String supplierName;

        @Column(name = "material_scope", nullable = false, length = 180)
        private String materialScope;

        @Column(name = "qualification_status", nullable = false, length = 24)
        private String qualificationStatus = "PENDING"; // PENDING, APPROVED, SUSPENDED

        @Column(name = "audit_date", nullable = false)
        private LocalDate auditDate;

        @Column(name = "requalification_due_date", nullable = false)
        private LocalDate requalificationDueDate;

        @Column(name = "qa_approver_id", length = 120)
        private String qaApproverId;

        @Column(name = "approval_sig_sha256", length = 64)
        private String approvalSigSha256;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getSupplierCode() { return supplierCode; }
        public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
        public String getSupplierName() { return supplierName; }
        public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
        public String getMaterialScope() { return materialScope; }
        public void setMaterialScope(String materialScope) { this.materialScope = materialScope; }
        public String getQualificationStatus() { return qualificationStatus; }
        public void setQualificationStatus(String qualificationStatus) { this.qualificationStatus = qualificationStatus; }
        public LocalDate getAuditDate() { return auditDate; }
        public void setAuditDate(LocalDate auditDate) { this.auditDate = auditDate; }
        public LocalDate getRequalificationDueDate() { return requalificationDueDate; }
        public void setRequalificationDueDate(LocalDate requalificationDueDate) { this.requalificationDueDate = requalificationDueDate; }
        public String getQaApproverId() { return qaApproverId; }
        public void setQaApproverId(String qaApproverId) { this.qaApproverId = qaApproverId; }
        public String getApprovalSigSha256() { return approvalSigSha256; }
        public void setApprovalSigSha256(String approvalSigSha256) { this.approvalSigSha256 = approvalSigSha256; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
