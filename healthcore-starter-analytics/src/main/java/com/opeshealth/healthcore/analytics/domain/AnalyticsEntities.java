/*
 * =============================================================================
 * Opes HealthCore SDK — Analytics & Clinical Quality Measures Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.analytics.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class AnalyticsEntities {
    private AnalyticsEntities() {}

    @Entity
    @Table(name = "hc_analytics_quality_measures")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalQualityMeasureEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "measure_code", nullable = false, length = 64) private String measureCode;
        @Column(name = "measure_title", nullable = false, length = 240) private String measureTitle;
        @Column(name = "reporting_period", nullable = false, length = 32) private String reportingPeriod;
        @Column(name = "numerator_count", nullable = false) private long numeratorCount;
        @Column(name = "denominator_count", nullable = false) private long denominatorCount;
        @Column(name = "exclusion_count", nullable = false) private long exclusionCount;
        @Column(name = "compliance_rate_pct", nullable = false) private double complianceRatePct;
        @Column(name = "data_lineage_source", nullable = false, length = 240) private String dataLineageSource;
        @Column(name = "calculated_by", nullable = false, length = 120) private String calculatedBy;
        @Column(name = "calculated_at", nullable = false) private Instant calculatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getMeasureCode() { return measureCode; }
        public void setMeasureCode(String measureCode) { this.measureCode = measureCode; }
        public String getMeasureTitle() { return measureTitle; }
        public void setMeasureTitle(String measureTitle) { this.measureTitle = measureTitle; }
        public String getReportingPeriod() { return reportingPeriod; }
        public void setReportingPeriod(String reportingPeriod) { this.reportingPeriod = reportingPeriod; }
        public long getNumeratorCount() { return numeratorCount; }
        public void setNumeratorCount(long numeratorCount) { this.numeratorCount = numeratorCount; }
        public long getDenominatorCount() { return denominatorCount; }
        public void setDenominatorCount(long denominatorCount) { this.denominatorCount = denominatorCount; }
        public long getExclusionCount() { return exclusionCount; }
        public void setExclusionCount(long exclusionCount) { this.exclusionCount = exclusionCount; }
        public double getComplianceRatePct() { return complianceRatePct; }
        public void setComplianceRatePct(double complianceRatePct) { this.complianceRatePct = complianceRatePct; }
        public String getDataLineageSource() { return dataLineageSource; }
        public void setDataLineageSource(String dataLineageSource) { this.dataLineageSource = dataLineageSource; }
        public String getCalculatedBy() { return calculatedBy; }
        public void setCalculatedBy(String calculatedBy) { this.calculatedBy = calculatedBy; }
        public Instant getCalculatedAt() { return calculatedAt; }
    }

    @Entity
    @Table(name = "hc_analytics_cohort_exports")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class CohortExportRequestEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "cohort_name", nullable = false, length = 160) private String cohortName;
        @Lob @Column(name = "filter_criteria_json", nullable = false) private String filterCriteriaJson;
        @Column(name = "export_purpose", nullable = false, length = 120) private String exportPurpose;
        @Column(name = "deidentified_flag", nullable = false) private boolean deidentifiedFlag = true;
        @Column(name = "minimum_cell_suppression", nullable = false) private int minimumCellSuppression = 5;
        @Column(name = "matched_patient_count", nullable = false) private long matchedPatientCount;
        @Column(name = "approval_status", nullable = false, length = 32) private String approvalStatus = "PENDING_APPROVAL";
        @Column(name = "requested_by", nullable = false, length = 120) private String requestedBy;
        @Column(name = "approved_by", length = 120) private String approvedBy;
        @Column(name = "lineage_sha256", nullable = false, length = 64) private String lineageSha256;
        @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
        @Column(name = "approved_at") private Instant approvedAt;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getCohortName() { return cohortName; }
        public void setCohortName(String cohortName) { this.cohortName = cohortName; }
        public String getFilterCriteriaJson() { return filterCriteriaJson; }
        public void setFilterCriteriaJson(String filterCriteriaJson) { this.filterCriteriaJson = filterCriteriaJson; }
        public String getExportPurpose() { return exportPurpose; }
        public void setExportPurpose(String exportPurpose) { this.exportPurpose = exportPurpose; }
        public boolean isDeidentifiedFlag() { return deidentifiedFlag; }
        public void setDeidentifiedFlag(boolean deidentifiedFlag) { this.deidentifiedFlag = deidentifiedFlag; }
        public int getMinimumCellSuppression() { return minimumCellSuppression; }
        public void setMinimumCellSuppression(int minimumCellSuppression) { this.minimumCellSuppression = minimumCellSuppression; }
        public long getMatchedPatientCount() { return matchedPatientCount; }
        public void setMatchedPatientCount(long matchedPatientCount) { this.matchedPatientCount = matchedPatientCount; }
        public String getApprovalStatus() { return approvalStatus; }
        public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
        public String getRequestedBy() { return requestedBy; }
        public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
        public String getLineageSha256() { return lineageSha256; }
        public void setLineageSha256(String lineageSha256) { this.lineageSha256 = lineageSha256; }
        public Instant getRequestedAt() { return requestedAt; }
        public Instant getApprovedAt() { return approvedAt; }
        public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    }
}
