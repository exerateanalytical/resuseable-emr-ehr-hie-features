/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical Research / EDC Entities (ICH E6(R3) & CDISC)
 * =============================================================================
 */
package com.opeshealth.healthcore.clinicalresearch.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

public final class ClinicalResearchEntities {

    private ClinicalResearchEntities() {}

    @Entity
    @Table(name = "hc_cr_trial_subjects")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class TrialSubjectEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "protocol_code", nullable = false, length = 64)
        private String protocolCode;

        @Column(name = "site_code", nullable = false, length = 32)
        private String siteCode;

        @Column(name = "subject_pseudonym", nullable = false, length = 64)
        private String subjectPseudonym; // e.g., SITE01-SUBJ-0042 (never raw clinical PII)

        @Column(name = "arm_code", length = 32)
        private String armCode;

        @Column(name = "eligibility_passed", nullable = false)
        private boolean eligibilityPassed = false;

        @Column(name = "econsent_version", length = 24)
        private String econsentVersion;

        @Column(name = "econsent_signed_at")
        private Instant econsentSignedAt;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "SCREENING"; // SCREENING, ENROLLED, COMPLETED, WITHDRAWN

        @Column(name = "enrolled_at", nullable = false)
        private Instant enrolledAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getProtocolCode() { return protocolCode; }
        public void setProtocolCode(String protocolCode) { this.protocolCode = protocolCode; }
        public String getSiteCode() { return siteCode; }
        public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
        public String getSubjectPseudonym() { return subjectPseudonym; }
        public void setSubjectPseudonym(String subjectPseudonym) { this.subjectPseudonym = subjectPseudonym; }
        public String getArmCode() { return armCode; }
        public void setArmCode(String armCode) { this.armCode = armCode; }
        public boolean isEligibilityPassed() { return eligibilityPassed; }
        public void setEligibilityPassed(boolean eligibilityPassed) { this.eligibilityPassed = eligibilityPassed; }
        public String getEconsentVersion() { return econsentVersion; }
        public void setEconsentVersion(String econsentVersion) { this.econsentVersion = econsentVersion; }
        public Instant getEconsentSignedAt() { return econsentSignedAt; }
        public void setEconsentSignedAt(Instant econsentSignedAt) { this.econsentSignedAt = econsentSignedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getEnrolledAt() { return enrolledAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_cr_ecrf_entries")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class EcrfEntryEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "subject_id", nullable = false)
        private UUID subjectId;

        @Column(name = "visit_code", nullable = false, length = 48)
        private String visitCode; // SCREENING, DAY1, WEEK4, EOS

        @Column(name = "sdtm_domain", nullable = false, length = 16)
        private String sdtmDomain; // DM, VS, LB, AE, EX, CM

        @Lob
        @Column(name = "form_payload_json", nullable = false)
        private String formPayloadJson;

        @Column(name = "open_query_count", nullable = false)
        private int openQueryCount = 0;

        @Column(name = "investigator_id", length = 120)
        private String investigatorId;

        @Column(name = "pi_sig_sha256", length = 64)
        private String piSigSha256;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DRAFT"; // DRAFT, QUERIED, SDV_VERIFIED, LOCKED

        @Column(name = "captured_at", nullable = false)
        private Instant capturedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSubjectId() { return subjectId; }
        public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
        public String getVisitCode() { return visitCode; }
        public void setVisitCode(String visitCode) { this.visitCode = visitCode; }
        public String getSdtmDomain() { return sdtmDomain; }
        public void setSdtmDomain(String sdtmDomain) { this.sdtmDomain = sdtmDomain; }
        public String getFormPayloadJson() { return formPayloadJson; }
        public void setFormPayloadJson(String formPayloadJson) { this.formPayloadJson = formPayloadJson; }
        public int getOpenQueryCount() { return openQueryCount; }
        public void setOpenQueryCount(int openQueryCount) { this.openQueryCount = openQueryCount; }
        public String getInvestigatorId() { return investigatorId; }
        public void setInvestigatorId(String investigatorId) { this.investigatorId = investigatorId; }
        public String getPiSigSha256() { return piSigSha256; }
        public void setPiSigSha256(String piSigSha256) { this.piSigSha256 = piSigSha256; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCapturedAt() { return capturedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_cr_safety_events")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class SafetyEventEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "subject_id", nullable = false)
        private UUID subjectId;

        @Column(name = "event_category", nullable = false, length = 32)
        private String eventCategory; // AE, SAE, PROTOCOL_DEVIATION

        @Column(name = "meddra_term", nullable = false, length = 160)
        private String meddraTerm;

        @Column(name = "serious", nullable = false)
        private boolean serious = false;

        @Column(name = "causality", nullable = false, length = 32)
        private String causality; // UNRELATED, POSSIBLE, PROBABLE, DEFINITE

        @Column(name = "expedited_report_required", nullable = false)
        private boolean expeditedReportRequired = false;

        @Column(name = "reported_by", nullable = false, length = 120)
        private String reportedBy;

        @Column(name = "reported_at", nullable = false)
        private Instant reportedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSubjectId() { return subjectId; }
        public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
        public String getEventCategory() { return eventCategory; }
        public void setEventCategory(String eventCategory) { this.eventCategory = eventCategory; }
        public String getMeddraTerm() { return meddraTerm; }
        public void setMeddraTerm(String meddraTerm) { this.meddraTerm = meddraTerm; }
        public boolean isSerious() { return serious; }
        public void setSerious(boolean serious) { this.serious = serious; }
        public String getCausality() { return causality; }
        public void setCausality(String causality) { this.causality = causality; }
        public boolean isExpeditedReportRequired() { return expeditedReportRequired; }
        public void setExpeditedReportRequired(boolean expeditedReportRequired) { this.expeditedReportRequired = expeditedReportRequired; }
        public String getReportedBy() { return reportedBy; }
        public void setReportedBy(String reportedBy) { this.reportedBy = reportedBy; }
        public Instant getReportedAt() { return reportedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_cr_ip_accountability")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class InvestigationalProductLedgerEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "protocol_code", nullable = false, length = 64)
        private String protocolCode;

        @Column(name = "site_code", nullable = false, length = 32)
        private String siteCode;

        @Column(name = "kit_number", nullable = false, length = 64)
        private String kitNumber;

        @Column(name = "lot_number", nullable = false, length = 64)
        private String lotNumber;

        @Column(name = "subject_id")
        private UUID subjectId;

        @Column(name = "dispensed_units", nullable = false)
        private int dispensedUnits = 0;

        @Column(name = "returned_units", nullable = false)
        private int returnedUnits = 0;

        @Column(name = "destroyed_units", nullable = false)
        private int destroyedUnits = 0;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "DISPENSED"; // DISPENSED, RETURNED, DESTROYED

        @Column(name = "dispensed_by", length = 120)
        private String dispensedBy;

        @Column(name = "reconciled_by", length = 120)
        private String reconciledBy;

        @Column(name = "destruction_witness_id", length = 120)
        private String destructionWitnessId;

        @Column(name = "witness_sig_sha256", length = 64)
        private String witnessSigSha256;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getProtocolCode() { return protocolCode; }
        public void setProtocolCode(String protocolCode) { this.protocolCode = protocolCode; }
        public String getSiteCode() { return siteCode; }
        public void setSiteCode(String siteCode) { this.siteCode = siteCode; }
        public String getKitNumber() { return kitNumber; }
        public void setKitNumber(String kitNumber) { this.kitNumber = kitNumber; }
        public String getLotNumber() { return lotNumber; }
        public void setLotNumber(String lotNumber) { this.lotNumber = lotNumber; }
        public UUID getSubjectId() { return subjectId; }
        public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
        public int getDispensedUnits() { return dispensedUnits; }
        public void setDispensedUnits(int dispensedUnits) { this.dispensedUnits = dispensedUnits; }
        public int getReturnedUnits() { return returnedUnits; }
        public void setReturnedUnits(int returnedUnits) { this.returnedUnits = returnedUnits; }
        public int getDestroyedUnits() { return destroyedUnits; }
        public void setDestroyedUnits(int destroyedUnits) { this.destroyedUnits = destroyedUnits; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDispensedBy() { return dispensedBy; }
        public void setDispensedBy(String dispensedBy) { this.dispensedBy = dispensedBy; }
        public String getReconciledBy() { return reconciledBy; }
        public void setReconciledBy(String reconciledBy) { this.reconciledBy = reconciledBy; }
        public String getDestructionWitnessId() { return destructionWitnessId; }
        public void setDestructionWitnessId(String destructionWitnessId) { this.destructionWitnessId = destructionWitnessId; }
        public String getWitnessSigSha256() { return witnessSigSha256; }
        public void setWitnessSigSha256(String witnessSigSha256) { this.witnessSigSha256 = witnessSigSha256; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }
}
