/*
 * =============================================================================
 * Opes HealthCore SDK — Public Health, CHW, Home Care, EPI & IDSR Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class PublicHealthEntities {

    private PublicHealthEntities() {}

    @Entity @Table(name = "hc_chw_households")
    public static class ChwHouseholdEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "household_code", nullable = false, length = 64) private String householdCode;
        @Column(name = "village_or_quarter", nullable = false, length = 120) private String villageOrQuarter;
        @Column(name = "district_code", nullable = false, length = 64) private String districtCode;
        @Column(name = "head_of_household", nullable = false, length = 160) private String headOfHousehold;
        @Column(name = "assigned_chw_id", nullable = false, length = 120) private String assignedChwId;
        @Column(name = "gps_lat", precision = 9, scale = 6) private BigDecimal gpsLat;
        @Column(name = "gps_lon", precision = 9, scale = 6) private BigDecimal gpsLon;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getHouseholdCode() { return householdCode; }
        public void setHouseholdCode(String householdCode) { this.householdCode = householdCode; }
        public String getVillageOrQuarter() { return villageOrQuarter; }
        public void setVillageOrQuarter(String villageOrQuarter) { this.villageOrQuarter = villageOrQuarter; }
        public String getDistrictCode() { return districtCode; }
        public void setDistrictCode(String districtCode) { this.districtCode = districtCode; }
        public String getHeadOfHousehold() { return headOfHousehold; }
        public void setHeadOfHousehold(String headOfHousehold) { this.headOfHousehold = headOfHousehold; }
        public String getAssignedChwId() { return assignedChwId; }
        public void setAssignedChwId(String assignedChwId) { this.assignedChwId = assignedChwId; }
        public BigDecimal getGpsLat() { return gpsLat; }
        public void setGpsLat(BigDecimal gpsLat) { this.gpsLat = gpsLat; }
        public BigDecimal getGpsLon() { return gpsLon; }
        public void setGpsLon(BigDecimal gpsLon) { this.gpsLon = gpsLon; }
    }

    @Entity @Table(name = "hc_chw_visits")
    public static class ChwVisitEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "household_id", nullable = false) private UUID householdId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "chw_id", nullable = false, length = 120) private String chwId;
        @Column(name = "program_type", nullable = false, length = 48) private String programType; // ANC, ICCM_MALARIA, MUAC, TB_DOT
        @Column(name = "muac_mm") private Integer muacMm;
        @Column(name = "danger_signs_present", nullable = false) private boolean dangerSignsPresent = false;
        @Column(name = "referral_qr_token", length = 128) private String referralQrToken;
        @Column(name = "notes", length = 500) private String notes;
        @Column(name = "visited_at", nullable = false) private Instant visitedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getHouseholdId() { return householdId; }
        public void setHouseholdId(UUID householdId) { this.householdId = householdId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getChwId() { return chwId; }
        public void setChwId(String chwId) { this.chwId = chwId; }
        public String getProgramType() { return programType; }
        public void setProgramType(String programType) { this.programType = programType; }
        public Integer getMuacMm() { return muacMm; }
        public void setMuacMm(Integer muacMm) { this.muacMm = muacMm; }
        public boolean isDangerSignsPresent() { return dangerSignsPresent; }
        public void setDangerSignsPresent(boolean dangerSignsPresent) { this.dangerSignsPresent = dangerSignsPresent; }
        public String getReferralQrToken() { return referralQrToken; }
        public void setReferralQrToken(String referralQrToken) { this.referralQrToken = referralQrToken; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public Instant getVisitedAt() { return visitedAt; }
    }

    @Entity @Table(name = "hc_home_care_plans")
    public static class HomeCarePlanEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "care_track", nullable = false, length = 48) private String careTrack; // PALLIATIVE, POST_OP, CHRONIC_RPM
        @Column(name = "assigned_nurse_or_chw", nullable = false, length = 120) private String assignedNurseOrChw;
        @Column(name = "visit_frequency_days", nullable = false) private int visitFrequencyDays = 7;
        @Column(name = "status", nullable = false, length = 24) private String status = "ACTIVE";

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getCareTrack() { return careTrack; }
        public void setCareTrack(String careTrack) { this.careTrack = careTrack; }
        public String getAssignedNurseOrChw() { return assignedNurseOrChw; }
        public void setAssignedNurseOrChw(String assignedNurseOrChw) { this.assignedNurseOrChw = assignedNurseOrChw; }
        public int getVisitFrequencyDays() { return visitFrequencyDays; }
        public void setVisitFrequencyDays(int visitFrequencyDays) { this.visitFrequencyDays = visitFrequencyDays; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    @Entity @Table(name = "hc_immunization_records")
    public static class ImmunizationRecordEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "cvx_code", nullable = false, length = 32) private String cvxCode;
        @Column(name = "vaccine_name", nullable = false, length = 160) private String vaccineName;
        @Column(name = "dose_number", nullable = false) private int doseNumber;
        @Column(name = "batch_lot", nullable = false, length = 64) private String batchLot;
        @Column(name = "administered_at", nullable = false) private Instant administeredAt = Instant.now();
        @Column(name = "next_due_date") private LocalDate nextDueDate;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getCvxCode() { return cvxCode; }
        public void setCvxCode(String cvxCode) { this.cvxCode = cvxCode; }
        public String getVaccineName() { return vaccineName; }
        public void setVaccineName(String vaccineName) { this.vaccineName = vaccineName; }
        public int getDoseNumber() { return doseNumber; }
        public void setDoseNumber(int doseNumber) { this.doseNumber = doseNumber; }
        public String getBatchLot() { return batchLot; }
        public void setBatchLot(String batchLot) { this.batchLot = batchLot; }
        public Instant getAdministeredAt() { return administeredAt; }
        public LocalDate getNextDueDate() { return nextDueDate; }
        public void setNextDueDate(LocalDate nextDueDate) { this.nextDueDate = nextDueDate; }
    }

    @Entity @Table(name = "hc_surveillance_cases")
    public static class SurveillanceCaseEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "district_code", nullable = false, length = 64) private String districtCode;
        @Column(name = "disease_icd10", nullable = false, length = 32) private String diseaseIcd10; // B50.9 Malaria, B20 HIV, B18.1 HepB, A00 Cholera, A15 TB, A98 VHF
        @Column(name = "disease_name", nullable = false, length = 160) private String diseaseName;
        @Column(name = "case_classification", nullable = false, length = 32) private String caseClassification = "CONFIRMED";
        @Column(name = "reported_at", nullable = false) private Instant reportedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getDistrictCode() { return districtCode; }
        public void setDistrictCode(String districtCode) { this.districtCode = districtCode; }
        public String getDiseaseIcd10() { return diseaseIcd10; }
        public void setDiseaseIcd10(String diseaseIcd10) { this.diseaseIcd10 = diseaseIcd10; }
        public String getDiseaseName() { return diseaseName; }
        public void setDiseaseName(String diseaseName) { this.diseaseName = diseaseName; }
        public String getCaseClassification() { return caseClassification; }
        public void setCaseClassification(String caseClassification) { this.caseClassification = caseClassification; }
        public Instant getReportedAt() { return reportedAt; }
    }

    @Entity @Table(name = "hc_contact_tracing_links")
    public static class ContactTracingLinkEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "index_case_id", nullable = false) private UUID indexCaseId;
        @Column(name = "contact_name", nullable = false, length = 160) private String contactName;
        @Column(name = "contact_phone", length = 64) private String contactPhone;
        @Column(name = "exposure_type", nullable = false, length = 48) private String exposureType;
        @Column(name = "follow_up_status", nullable = false, length = 32) private String followUpStatus = "MONITORING";

        public UUID getId() { return id; }
        public UUID getIndexCaseId() { return indexCaseId; }
        public void setIndexCaseId(UUID indexCaseId) { this.indexCaseId = indexCaseId; }
        public String getContactName() { return contactName; }
        public void setContactName(String contactName) { this.contactName = contactName; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public String getExposureType() { return exposureType; }
        public void setExposureType(String exposureType) { this.exposureType = exposureType; }
        public String getFollowUpStatus() { return followUpStatus; }
        public void setFollowUpStatus(String followUpStatus) { this.followUpStatus = followUpStatus; }
    }

    /** WHO IDSR Case Investigation Form (CIF) capturing epidemiological onset, travel & lab specimen tracking. */
    @Entity @Table(name = "hc_idsr_cif_forms")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class CaseInvestigationFormEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "surveillance_case_id", nullable = false) private UUID surveillanceCaseId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "disease_icd10", nullable = false, length = 32) private String diseaseIcd10;
        @Column(name = "symptom_onset_date", nullable = false) private LocalDate symptomOnsetDate;
        @Column(name = "travel_history", length = 300) private String travelHistory;
        @Column(name = "lab_specimen_taken", nullable = false) private boolean labSpecimenTaken = false;
        @Column(name = "lab_result", length = 120) private String labResult;
        @Column(name = "investigator_id", nullable = false, length = 120) private String investigatorId;
        @Column(name = "completed_at", nullable = false) private Instant completedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSurveillanceCaseId() { return surveillanceCaseId; }
        public void setSurveillanceCaseId(UUID surveillanceCaseId) { this.surveillanceCaseId = surveillanceCaseId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getDiseaseIcd10() { return diseaseIcd10; }
        public void setDiseaseIcd10(String diseaseIcd10) { this.diseaseIcd10 = diseaseIcd10; }
        public LocalDate getSymptomOnsetDate() { return symptomOnsetDate; }
        public void setSymptomOnsetDate(LocalDate symptomOnsetDate) { this.symptomOnsetDate = symptomOnsetDate; }
        public String getTravelHistory() { return travelHistory; }
        public void setTravelHistory(String travelHistory) { this.travelHistory = travelHistory; }
        public boolean isLabSpecimenTaken() { return labSpecimenTaken; }
        public void setLabSpecimenTaken(boolean labSpecimenTaken) { this.labSpecimenTaken = labSpecimenTaken; }
        public String getLabResult() { return labResult; }
        public void setLabResult(String labResult) { this.labResult = labResult; }
        public String getInvestigatorId() { return investigatorId; }
        public void setInvestigatorId(String investigatorId) { this.investigatorId = investigatorId; }
        public Instant getCompletedAt() { return completedAt; }
    }

    /** Longitudinal HIV ART (Viral Load), TB DOT, and Hepatitis B chronic disease care cascade. */
    @Entity @Table(name = "hc_chronic_cascades")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ChronicCascadeEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "program_code", nullable = false, length = 32) private String programCode; // HIV_ART, TB_DOT, HEPATITIS_B
        @Column(name = "regimen", nullable = false, length = 160) private String regimen;
        @Column(name = "enrolled_on", nullable = false) private LocalDate enrolledOn;
        @Column(name = "viral_load_copies_ml") private Integer viralLoadCopiesMl;
        @Column(name = "adherence_percent", nullable = false) private int adherencePercent = 100;
        @Column(name = "outcome_status", nullable = false, length = 32) private String outcomeStatus = "ACTIVE"; // ACTIVE, SUPPRESSED, LTFU, CURED
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getProgramCode() { return programCode; }
        public void setProgramCode(String programCode) { this.programCode = programCode; }
        public String getRegimen() { return regimen; }
        public void setRegimen(String regimen) { this.regimen = regimen; }
        public LocalDate getEnrolledOn() { return enrolledOn; }
        public void setEnrolledOn(LocalDate enrolledOn) { this.enrolledOn = enrolledOn; }
        public Integer getViralLoadCopiesMl() { return viralLoadCopiesMl; }
        public void setViralLoadCopiesMl(Integer viralLoadCopiesMl) { this.viralLoadCopiesMl = viralLoadCopiesMl; }
        public int getAdherencePercent() { return adherencePercent; }
        public void setAdherencePercent(int adherencePercent) { this.adherencePercent = adherencePercent; }
        public String getOutcomeStatus() { return outcomeStatus; }
        public void setOutcomeStatus(String outcomeStatus) { this.outcomeStatus = outcomeStatus; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    /** Maps local facility/district and clinical ICD-10/EPI/CHW codes to DHIS2 UIDs. */
    @Entity @Table(name = "hc_dhis2_mappings")
    @org.hibernate.annotations.BatchSize(size = 32)
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class Dhis2MappingEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "district_code", nullable = false, length = 64) private String districtCode;
        @Column(name = "source_domain", nullable = false, length = 32) private String sourceDomain; // IDSR, EPI, CHW, CHRONIC
        @Column(name = "source_code", nullable = false, length = 64) private String sourceCode;
        @Column(name = "dhis2_org_unit_uid", nullable = false, length = 32) private String dhis2OrgUnitUid;
        @Column(name = "dhis2_data_element_uid", nullable = false, length = 32) private String dhis2DataElementUid;
        @Column(name = "dhis2_category_option_uid", length = 32) private String dhis2CategoryOptionUid;
        @Column(name = "dhis2_program_uid", length = 32) private String dhis2ProgramUid;
        @Column(name = "active", nullable = false) private boolean active = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDistrictCode() { return districtCode; }
        public void setDistrictCode(String districtCode) { this.districtCode = districtCode; }
        public String getSourceDomain() { return sourceDomain; }
        public void setSourceDomain(String sourceDomain) { this.sourceDomain = sourceDomain; }
        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
        public String getDhis2OrgUnitUid() { return dhis2OrgUnitUid; }
        public void setDhis2OrgUnitUid(String dhis2OrgUnitUid) { this.dhis2OrgUnitUid = dhis2OrgUnitUid; }
        public String getDhis2DataElementUid() { return dhis2DataElementUid; }
        public void setDhis2DataElementUid(String dhis2DataElementUid) { this.dhis2DataElementUid = dhis2DataElementUid; }
        public String getDhis2CategoryOptionUid() { return dhis2CategoryOptionUid; }
        public void setDhis2CategoryOptionUid(String dhis2CategoryOptionUid) { this.dhis2CategoryOptionUid = dhis2CategoryOptionUid; }
        public String getDhis2ProgramUid() { return dhis2ProgramUid; }
        public void setDhis2ProgramUid(String dhis2ProgramUid) { this.dhis2ProgramUid = dhis2ProgramUid; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
    }

    /** Audit record of outbound DHIS2 dataValueSets and Tracker API transmissions. */
    @Entity @Table(name = "hc_dhis2_sync_logs")
    public static class Dhis2SyncLogEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "sync_mode", nullable = false, length = 32) private String syncMode; // DATA_VALUE_SET, TRACKER_EVENT
        @Column(name = "district_code", nullable = false, length = 64) private String districtCode;
        @Column(name = "period_code", nullable = false, length = 32) private String periodCode;
        @Lob @Column(name = "payload_json", nullable = false) private String payloadJson;
        @Column(name = "http_status") private Integer httpStatus;
        @Column(name = "status", nullable = false, length = 24) private String status = "DRY_RUN"; // DRY_RUN, SENT, FAILED
        @Column(name = "dhis2_import_summary", length = 600) private String dhis2ImportSummary;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public String getSyncMode() { return syncMode; }
        public void setSyncMode(String syncMode) { this.syncMode = syncMode; }
        public String getDistrictCode() { return districtCode; }
        public void setDistrictCode(String districtCode) { this.districtCode = districtCode; }
        public String getPeriodCode() { return periodCode; }
        public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }
        public String getPayloadJson() { return payloadJson; }
        public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
        public Integer getHttpStatus() { return httpStatus; }
        public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getDhis2ImportSummary() { return dhis2ImportSummary; }
        public void setDhis2ImportSummary(String dhis2ImportSummary) { this.dhis2ImportSummary = dhis2ImportSummary; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
