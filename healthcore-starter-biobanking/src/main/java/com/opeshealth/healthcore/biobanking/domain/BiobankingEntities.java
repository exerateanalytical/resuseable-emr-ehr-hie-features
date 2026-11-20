/*
 * =============================================================================
 * Opes HealthCore SDK — Biobanking & Biospecimen Domain Entities (ISO 20387)
 * =============================================================================
 */
package com.opeshealth.healthcore.biobanking.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class BiobankingEntities {

    private BiobankingEntities() {}

    @Entity
    @Table(name = "hc_biobank_specimens")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class BiospecimenEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "specimen_barcode", nullable = false, length = 64)
        private String specimenBarcode;

        @Column(name = "donor_pseudonym", nullable = false, length = 64)
        private String donorPseudonym;

        @Column(name = "consent_scope", nullable = false, length = 64)
        private String consentScope; // BROAD_RESEARCH, DISEASE_SPECIFIC, COMMERCIAL_EXCLUDED

        @Column(name = "consent_active", nullable = false)
        private boolean consentActive = true;

        @Column(name = "specimen_type", nullable = false, length = 64)
        private String specimenType; // PBMC, PLASMA, DNA, RNA, FFPE_TISSUE, SERUM

        @Column(name = "volume_ul", nullable = false, precision = 12, scale = 2)
        private BigDecimal volumeUl;

        @Column(name = "parent_aliquot_id")
        private UUID parentAliquotId;

        @Column(name = "freezer_id", nullable = false, length = 48)
        private String freezerId;

        @Column(name = "rack_box_slot", nullable = false, length = 64)
        private String rackBoxSlot; // e.g., R02-B05-C08

        @Column(name = "target_temp_c", nullable = false, precision = 6, scale = 2)
        private BigDecimal targetTempC = BigDecimal.valueOf(-80);

        @Column(name = "freeze_thaw_cycles", nullable = false)
        private int freezeThawCycles = 0;

        @Column(name = "temp_excursion_flagged", nullable = false)
        private boolean tempExcursionFlagged = false;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "STORED"; // STORED, QUARANTINED_EXCURSION, SHIPPED_MTA, CONSENT_WITHDRAWN, DISPOSED

        @Column(name = "collected_at", nullable = false)
        private Instant collectedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getSpecimenBarcode() { return specimenBarcode; }
        public void setSpecimenBarcode(String specimenBarcode) { this.specimenBarcode = specimenBarcode; }
        public String getDonorPseudonym() { return donorPseudonym; }
        public void setDonorPseudonym(String donorPseudonym) { this.donorPseudonym = donorPseudonym; }
        public String getConsentScope() { return consentScope; }
        public void setConsentScope(String consentScope) { this.consentScope = consentScope; }
        public boolean isConsentActive() { return consentActive; }
        public void setConsentActive(boolean consentActive) { this.consentActive = consentActive; }
        public String getSpecimenType() { return specimenType; }
        public void setSpecimenType(String specimenType) { this.specimenType = specimenType; }
        public BigDecimal getVolumeUl() { return volumeUl; }
        public void setVolumeUl(BigDecimal volumeUl) { this.volumeUl = volumeUl; }
        public UUID getParentAliquotId() { return parentAliquotId; }
        public void setParentAliquotId(UUID parentAliquotId) { this.parentAliquotId = parentAliquotId; }
        public String getFreezerId() { return freezerId; }
        public void setFreezerId(String freezerId) { this.freezerId = freezerId; }
        public String getRackBoxSlot() { return rackBoxSlot; }
        public void setRackBoxSlot(String rackBoxSlot) { this.rackBoxSlot = rackBoxSlot; }
        public BigDecimal getTargetTempC() { return targetTempC; }
        public void setTargetTempC(BigDecimal targetTempC) { this.targetTempC = targetTempC; }
        public int getFreezeThawCycles() { return freezeThawCycles; }
        public void setFreezeThawCycles(int freezeThawCycles) { this.freezeThawCycles = freezeThawCycles; }
        public boolean isTempExcursionFlagged() { return tempExcursionFlagged; }
        public void setTempExcursionFlagged(boolean tempExcursionFlagged) { this.tempExcursionFlagged = tempExcursionFlagged; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCollectedAt() { return collectedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_biobank_mta_requests")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class MtaRequestEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "mta_reference", nullable = false, length = 64)
        private String mtaReference;

        @Column(name = "recipient_institution", nullable = false, length = 160)
        private String recipientInstitution;

        @Column(name = "specimen_id", nullable = false)
        private UUID specimenId;

        @Column(name = "requested_purpose", nullable = false, length = 160)
        private String requestedPurpose;

        @Column(name = "ethics_verified", nullable = false)
        private boolean ethicsVerified = false;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "REQUESTED"; // REQUESTED, APPROVED_SHIPPED, REJECTED

        @Column(name = "approved_by", length = 120)
        private String approvedBy;

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
        public String getMtaReference() { return mtaReference; }
        public void setMtaReference(String mtaReference) { this.mtaReference = mtaReference; }
        public String getRecipientInstitution() { return recipientInstitution; }
        public void setRecipientInstitution(String recipientInstitution) { this.recipientInstitution = recipientInstitution; }
        public UUID getSpecimenId() { return specimenId; }
        public void setSpecimenId(UUID specimenId) { this.specimenId = specimenId; }
        public String getRequestedPurpose() { return requestedPurpose; }
        public void setRequestedPurpose(String requestedPurpose) { this.requestedPurpose = requestedPurpose; }
        public boolean isEthicsVerified() { return ethicsVerified; }
        public void setEthicsVerified(boolean ethicsVerified) { this.ethicsVerified = ethicsVerified; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
        public String getApprovalSigSha256() { return approvalSigSha256; }
        public void setApprovalSigSha256(String approvalSigSha256) { this.approvalSigSha256 = approvalSigSha256; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
