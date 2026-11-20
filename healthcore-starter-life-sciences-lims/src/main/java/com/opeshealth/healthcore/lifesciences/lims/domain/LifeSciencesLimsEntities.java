/*
 * =============================================================================
 * Opes HealthCore SDK — Life Sciences Research LIMS & ELN Domain Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.lifesciences.lims.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

public final class LifeSciencesLimsEntities {

    private LifeSciencesLimsEntities() {}

    @Entity
    @Table(name = "hc_lslims_studies")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ResearchStudyEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "study_code", nullable = false, length = 64)
        private String studyCode;

        @Column(name = "title", nullable = false, length = 240)
        private String title;

        @Column(name = "principal_investigator", nullable = false, length = 120)
        private String principalInvestigator;

        @Column(name = "protocol_version", nullable = false, length = 24)
        private String protocolVersion = "v1.0";

        @Column(name = "ethics_approval_ref", length = 120)
        private String ethicsApprovalRef;

        @Column(name = "ethics_approved", nullable = false)
        private boolean ethicsApproved = false;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DRAFT"; // DRAFT, ACTIVE, COMPLETED, ARCHIVED

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
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getPrincipalInvestigator() { return principalInvestigator; }
        public void setPrincipalInvestigator(String principalInvestigator) { this.principalInvestigator = principalInvestigator; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public String getEthicsApprovalRef() { return ethicsApprovalRef; }
        public void setEthicsApprovalRef(String ethicsApprovalRef) { this.ethicsApprovalRef = ethicsApprovalRef; }
        public boolean isEthicsApproved() { return ethicsApproved; }
        public void setEthicsApproved(boolean ethicsApproved) { this.ethicsApproved = ethicsApproved; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    /**
     * Domain-neutral scientific research sample entity. Deliberately decoupled from clinical
     * patients: supports donor pseudonyms, organisms, cell lines, tissues, and environmental matrices.
     */
    @Entity
    @Table(name = "hc_lslims_scientific_samples")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ScientificSampleEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "study_id", nullable = false)
        private UUID studyId;

        @Column(name = "sample_barcode", nullable = false, length = 64)
        private String sampleBarcode;

        @Column(name = "source_classification", nullable = false, length = 32)
        private String sourceClassification; // DONOR_PSEUDONYM, ORGANISM, CELL_LINE, TISSUE_CULTURE, ENVIRONMENTAL

        @Column(name = "source_identifier", nullable = false, length = 120)
        private String sourceIdentifier;

        @Column(name = "material_type", nullable = false, length = 64)
        private String materialType;

        @Column(name = "parent_sample_id")
        private UUID parentSampleId;

        @Column(name = "custodian", nullable = false, length = 120)
        private String custodian;

        @Column(name = "storage_location", length = 120)
        private String storageLocation;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "REGISTERED"; // REGISTERED, IN_ASSAY, CONSUMED, ARCHIVED

        @Column(name = "registered_at", nullable = false)
        private Instant registeredAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getStudyId() { return studyId; }
        public void setStudyId(UUID studyId) { this.studyId = studyId; }
        public String getSampleBarcode() { return sampleBarcode; }
        public void setSampleBarcode(String sampleBarcode) { this.sampleBarcode = sampleBarcode; }
        public String getSourceClassification() { return sourceClassification; }
        public void setSourceClassification(String sourceClassification) { this.sourceClassification = sourceClassification; }
        public String getSourceIdentifier() { return sourceIdentifier; }
        public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }
        public String getMaterialType() { return materialType; }
        public void setMaterialType(String materialType) { this.materialType = materialType; }
        public UUID getParentSampleId() { return parentSampleId; }
        public void setParentSampleId(UUID parentSampleId) { this.parentSampleId = parentSampleId; }
        public String getCustodian() { return custodian; }
        public void setCustodian(String custodian) { this.custodian = custodian; }
        public String getStorageLocation() { return storageLocation; }
        public void setStorageLocation(String storageLocation) { this.storageLocation = storageLocation; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getRegisteredAt() { return registeredAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_lslims_eln_experiments")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ElnExperimentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "study_id", nullable = false)
        private UUID studyId;

        @Column(name = "experiment_code", nullable = false, length = 64)
        private String experimentCode;

        @Column(name = "revision_number", nullable = false)
        private int revisionNumber = 1;

        @Column(name = "protocol_version", nullable = false, length = 24)
        private String protocolVersion;

        @Column(name = "instrument_id", length = 64)
        private String instrumentId;

        @Lob
        @Column(name = "notebook_markdown", nullable = false)
        private String notebookMarkdown;

        @Lob
        @Column(name = "observations_json")
        private String observationsJson;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "IN_PROGRESS"; // IN_PROGRESS, SUBMITTED_FOR_REVIEW, SIGNED_LOCKED

        @Column(name = "author_id", nullable = false, length = 120)
        private String authorId;

        @Column(name = "reviewer_id", length = 120)
        private String reviewerId;

        @Column(name = "review_sig_sha256", length = 64)
        private String reviewSigSha256;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getStudyId() { return studyId; }
        public void setStudyId(UUID studyId) { this.studyId = studyId; }
        public String getExperimentCode() { return experimentCode; }
        public void setExperimentCode(String experimentCode) { this.experimentCode = experimentCode; }
        public int getRevisionNumber() { return revisionNumber; }
        public void setRevisionNumber(int revisionNumber) { this.revisionNumber = revisionNumber; }
        public String getProtocolVersion() { return protocolVersion; }
        public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
        public String getInstrumentId() { return instrumentId; }
        public void setInstrumentId(String instrumentId) { this.instrumentId = instrumentId; }
        public String getNotebookMarkdown() { return notebookMarkdown; }
        public void setNotebookMarkdown(String notebookMarkdown) { this.notebookMarkdown = notebookMarkdown; }
        public String getObservationsJson() { return observationsJson; }
        public void setObservationsJson(String observationsJson) { this.observationsJson = observationsJson; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAuthorId() { return authorId; }
        public void setAuthorId(String authorId) { this.authorId = authorId; }
        public String getReviewerId() { return reviewerId; }
        public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
        public String getReviewSigSha256() { return reviewSigSha256; }
        public void setReviewSigSha256(String reviewSigSha256) { this.reviewSigSha256 = reviewSigSha256; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }
}
