/*
 * =============================================================================
 * Opes HealthCore SDK — Radiology RIS Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.radiology.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class RadiologyRisEntities {
    private RadiologyRisEntities() {}

    @Entity
    @Table(name = "hc_ris_modality_worklist")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ModalityWorklistEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id") private UUID encounterId;
        @Column(name = "accession_number", nullable = false, length = 64) private String accessionNumber;
        @Column(name = "study_instance_uid", nullable = false, length = 64) private String studyInstanceUid;
        @Column(name = "modality", nullable = false, length = 16) private String modality;
        @Column(name = "procedure_code", nullable = false, length = 64) private String procedureCode;
        @Column(name = "procedure_description", nullable = false, length = 255) private String procedureDescription;
        @Column(name = "body_site", length = 120) private String bodySite;
        @Column(name = "clinical_indication", length = 500) private String clinicalIndication;
        @Column(name = "scheduled_station_ae", length = 64) private String scheduledStationAe;
        @Column(name = "ordering_clinician_id", nullable = false, length = 120) private String orderingClinicianId;
        @Column(name = "stat_urgent", nullable = false) private boolean statUrgent;
        @Column(name = "mpps_status", nullable = false, length = 24) private String mppsStatus = "SCHEDULED";
        @Column(name = "performing_technologist_id", length = 120) private String performingTechnologistId;
        @Column(name = "series_count", nullable = false) private int seriesCount;
        @Column(name = "instance_count", nullable = false) private int instanceCount;
        @Column(name = "scheduled_at", nullable = false) private Instant scheduledAt = Instant.now();
        @Column(name = "started_at") private Instant startedAt;
        @Column(name = "completed_at") private Instant completedAt;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public String getStudyInstanceUid() { return studyInstanceUid; }
        public void setStudyInstanceUid(String studyInstanceUid) { this.studyInstanceUid = studyInstanceUid; }
        public String getModality() { return modality; }
        public void setModality(String modality) { this.modality = modality; }
        public String getProcedureCode() { return procedureCode; }
        public void setProcedureCode(String procedureCode) { this.procedureCode = procedureCode; }
        public String getProcedureDescription() { return procedureDescription; }
        public void setProcedureDescription(String procedureDescription) { this.procedureDescription = procedureDescription; }
        public String getBodySite() { return bodySite; }
        public void setBodySite(String bodySite) { this.bodySite = bodySite; }
        public String getClinicalIndication() { return clinicalIndication; }
        public void setClinicalIndication(String clinicalIndication) { this.clinicalIndication = clinicalIndication; }
        public String getScheduledStationAe() { return scheduledStationAe; }
        public void setScheduledStationAe(String scheduledStationAe) { this.scheduledStationAe = scheduledStationAe; }
        public String getOrderingClinicianId() { return orderingClinicianId; }
        public void setOrderingClinicianId(String orderingClinicianId) { this.orderingClinicianId = orderingClinicianId; }
        public boolean isStatUrgent() { return statUrgent; }
        public void setStatUrgent(boolean statUrgent) { this.statUrgent = statUrgent; }
        public String getMppsStatus() { return mppsStatus; }
        public void setMppsStatus(String mppsStatus) { this.mppsStatus = mppsStatus; }
        public String getPerformingTechnologistId() { return performingTechnologistId; }
        public void setPerformingTechnologistId(String performingTechnologistId) { this.performingTechnologistId = performingTechnologistId; }
        public int getSeriesCount() { return seriesCount; }
        public void setSeriesCount(int seriesCount) { this.seriesCount = seriesCount; }
        public int getInstanceCount() { return instanceCount; }
        public void setInstanceCount(int instanceCount) { this.instanceCount = instanceCount; }
        public Instant getScheduledAt() { return scheduledAt; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        public Instant getCompletedAt() { return completedAt; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_ris_diagnostic_reports")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class RadiologyReportEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "worklist_item_id", nullable = false) private UUID worklistItemId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "accession_number", nullable = false, length = 64) private String accessionNumber;
        @Column(name = "study_instance_uid", nullable = false, length = 64) private String studyInstanceUid;
        @Column(name = "report_status", nullable = false, length = 24) private String reportStatus = "FINAL";
        @Column(name = "findings_text", nullable = false, length = org.hibernate.Length.LONG32) private String findingsText;
        @Column(name = "impression_text", nullable = false, length = org.hibernate.Length.LONG32) private String impressionText;
        @Column(name = "radiologist_id", nullable = false, length = 120) private String radiologistId;
        @Column(name = "critical_finding", nullable = false) private boolean criticalFinding;
        @Column(name = "critical_category", length = 64) private String criticalCategory;
        @Column(name = "critical_acknowledged", nullable = false) private boolean criticalAcknowledged;
        @Column(name = "acknowledged_by", length = 120) private String acknowledgedBy;
        @Column(name = "readback_notes", length = 500) private String readbackNotes;
        @Column(name = "acknowledged_at") private Instant acknowledgedAt;
        @Column(name = "supersedes_report_id") private UUID supersedesReportId;
        @Column(name = "signed_at", nullable = false) private Instant signedAt = Instant.now();
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getWorklistItemId() { return worklistItemId; }
        public void setWorklistItemId(UUID worklistItemId) { this.worklistItemId = worklistItemId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getAccessionNumber() { return accessionNumber; }
        public void setAccessionNumber(String accessionNumber) { this.accessionNumber = accessionNumber; }
        public String getStudyInstanceUid() { return studyInstanceUid; }
        public void setStudyInstanceUid(String studyInstanceUid) { this.studyInstanceUid = studyInstanceUid; }
        public String getReportStatus() { return reportStatus; }
        public void setReportStatus(String reportStatus) { this.reportStatus = reportStatus; }
        public String getFindingsText() { return findingsText; }
        public void setFindingsText(String findingsText) { this.findingsText = findingsText; }
        public String getImpressionText() { return impressionText; }
        public void setImpressionText(String impressionText) { this.impressionText = impressionText; }
        public String getRadiologistId() { return radiologistId; }
        public void setRadiologistId(String radiologistId) { this.radiologistId = radiologistId; }
        public boolean isCriticalFinding() { return criticalFinding; }
        public void setCriticalFinding(boolean criticalFinding) { this.criticalFinding = criticalFinding; }
        public String getCriticalCategory() { return criticalCategory; }
        public void setCriticalCategory(String criticalCategory) { this.criticalCategory = criticalCategory; }
        public boolean isCriticalAcknowledged() { return criticalAcknowledged; }
        public void setCriticalAcknowledged(boolean criticalAcknowledged) { this.criticalAcknowledged = criticalAcknowledged; }
        public String getAcknowledgedBy() { return acknowledgedBy; }
        public void setAcknowledgedBy(String acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
        public String getReadbackNotes() { return readbackNotes; }
        public void setReadbackNotes(String readbackNotes) { this.readbackNotes = readbackNotes; }
        public Instant getAcknowledgedAt() { return acknowledgedAt; }
        public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
        public UUID getSupersedesReportId() { return supersedesReportId; }
        public void setSupersedesReportId(UUID supersedesReportId) { this.supersedesReportId = supersedesReportId; }
        public Instant getSignedAt() { return signedAt; }
        public long getVersion() { return version; }
    }
}
