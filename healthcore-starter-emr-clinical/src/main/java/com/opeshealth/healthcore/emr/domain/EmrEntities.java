/*
 * =============================================================================
 * Opes HealthCore SDK — 10-Stage EMR Clinical Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emr.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Full longitudinal EMR entities covering Appointments, Encounters, ESI Triage,
 * LOINC Vitals + NEWS2, Immutable Signed Clinical Notes, CPOE Orders, Ward Beds,
 * Admissions/Discharges, eMAR 5-Rights Administration, CarePlans, and Specialty Records.
 */
public final class EmrEntities {

    private EmrEntities() {}

    @Entity @Table(name = "hc_appointments")
    public static class AppointmentEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "practitioner_id", nullable = false, length = 120) private String practitionerId;
        @Column(name = "department_code", nullable = false, length = 64) private String departmentCode;
        @Column(name = "scheduled_start", nullable = false) private Instant scheduledStart;
        @Column(name = "scheduled_end", nullable = false) private Instant scheduledEnd;
        @Column(name = "status", nullable = false, length = 32) private String status = "BOOKED";
        @Column(name = "visit_reason", length = 300) private String visitReason;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getPractitionerId() { return practitionerId; }
        public void setPractitionerId(String practitionerId) { this.practitionerId = practitionerId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public Instant getScheduledStart() { return scheduledStart; }
        public void setScheduledStart(Instant scheduledStart) { this.scheduledStart = scheduledStart; }
        public Instant getScheduledEnd() { return scheduledEnd; }
        public void setScheduledEnd(Instant scheduledEnd) { this.scheduledEnd = scheduledEnd; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getVisitReason() { return visitReason; }
        public void setVisitReason(String visitReason) { this.visitReason = visitReason; }
    }

    @Entity @Table(name = "hc_encounters")
    public static class EncounterEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_class", nullable = false, length = 24) private String encounterClass; // AMB, IMP, EMER, HH
        @Column(name = "status", nullable = false, length = 32) private String status = "ARRIVED";
        @Column(name = "attending_id", length = 120) private String attendingId;
        @Column(name = "department_code", length = 64) private String departmentCode;
        @Column(name = "primary_icd10", length = 32) private String primaryIcd10;
        @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
        @Column(name = "ended_at") private Instant endedAt;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getEncounterClass() { return encounterClass; }
        public void setEncounterClass(String encounterClass) { this.encounterClass = encounterClass; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAttendingId() { return attendingId; }
        public void setAttendingId(String attendingId) { this.attendingId = attendingId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getPrimaryIcd10() { return primaryIcd10; }
        public void setPrimaryIcd10(String primaryIcd10) { this.primaryIcd10 = primaryIcd10; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getEndedAt() { return endedAt; }
        public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    }

    @Entity @Table(name = "hc_triage_assessments")
    public static class TriageAssessmentEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "esi_level", nullable = false) private int esiLevel;
        @Column(name = "chief_complaint", nullable = false, length = 400) private String chiefComplaint;
        @Column(name = "red_flags", length = 400) private String redFlags;
        @Column(name = "triaged_by", nullable = false, length = 120) private String triagedBy;
        @Column(name = "triaged_at", nullable = false) private Instant triagedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public int getEsiLevel() { return esiLevel; }
        public void setEsiLevel(int esiLevel) { this.esiLevel = esiLevel; }
        public String getChiefComplaint() { return chiefComplaint; }
        public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }
        public String getRedFlags() { return redFlags; }
        public void setRedFlags(String redFlags) { this.redFlags = redFlags; }
        public String getTriagedBy() { return triagedBy; }
        public void setTriagedBy(String triagedBy) { this.triagedBy = triagedBy; }
        public Instant getTriagedAt() { return triagedAt; }
    }

    @Entity @Table(name = "hc_vital_signs")
    public static class VitalSignsEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "temp_celsius", precision = 5, scale = 2) private BigDecimal tempCelsius;
        @Column(name = "heart_rate_bpm") private Integer heartRateBpm;
        @Column(name = "resp_rate_per_min") private Integer respRatePerMin;
        @Column(name = "systolic_bp") private Integer systolicBp;
        @Column(name = "diastolic_bp") private Integer diastolicBp;
        @Column(name = "spo2_percent") private Integer spo2Percent;
        @Column(name = "weight_kg", precision = 6, scale = 2) private BigDecimal weightKg;
        @Column(name = "height_cm", precision = 6, scale = 2) private BigDecimal heightCm;
        @Column(name = "news2_score") private Integer news2Score;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public BigDecimal getTempCelsius() { return tempCelsius; }
        public void setTempCelsius(BigDecimal tempCelsius) { this.tempCelsius = tempCelsius; }
        public Integer getHeartRateBpm() { return heartRateBpm; }
        public void setHeartRateBpm(Integer heartRateBpm) { this.heartRateBpm = heartRateBpm; }
        public Integer getRespRatePerMin() { return respRatePerMin; }
        public void setRespRatePerMin(Integer respRatePerMin) { this.respRatePerMin = respRatePerMin; }
        public Integer getSystolicBp() { return systolicBp; }
        public void setSystolicBp(Integer systolicBp) { this.systolicBp = systolicBp; }
        public Integer getDiastolicBp() { return diastolicBp; }
        public void setDiastolicBp(Integer diastolicBp) { this.diastolicBp = diastolicBp; }
        public Integer getSpo2Percent() { return spo2Percent; }
        public void setSpo2Percent(Integer spo2Percent) { this.spo2Percent = spo2Percent; }
        public BigDecimal getWeightKg() { return weightKg; }
        public void setWeightKg(BigDecimal weightKg) { this.weightKg = weightKg; }
        public BigDecimal getHeightCm() { return heightCm; }
        public void setHeightCm(BigDecimal heightCm) { this.heightCm = heightCm; }
        public Integer getNews2Score() { return news2Score; }
        public void setNews2Score(Integer news2Score) { this.news2Score = news2Score; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    /** Immutable once SIGNED; corrections create linked ADDENDUM revisions. */
    @Entity @Table(name = "hc_clinical_notes")
    public static class ClinicalNoteRevisionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "note_type", nullable = false, length = 32) private String noteType; // SOAP, ADDENDUM, SBAR, DISCHARGE
        @Column(name = "revision_number", nullable = false) private int revisionNumber = 1;
        @Column(name = "parent_note_id") private UUID parentNoteId;
        @Lob @Column(name = "subjective") private String subjective;
        @Lob @Column(name = "objective") private String objective;
        @Lob @Column(name = "assessment") private String assessment;
        @Lob @Column(name = "plan_text") private String planText;
        @Column(name = "sensitivity_tag", nullable = false, length = 32) private String sensitivityTag = "GENERAL";
        @Column(name = "status", nullable = false, length = 24) private String status = "DRAFT"; // DRAFT, PENDING_COSIGN, SIGNED
        @Column(name = "author_id", nullable = false, length = 120) private String authorId;
        @Column(name = "cosigned_by", length = 120) private String cosignedBy;
        @Column(name = "signed_at") private Instant signedAt;

        public UUID getId() { return id; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getNoteType() { return noteType; }
        public void setNoteType(String noteType) { this.noteType = noteType; }
        public int getRevisionNumber() { return revisionNumber; }
        public void setRevisionNumber(int revisionNumber) { this.revisionNumber = revisionNumber; }
        public UUID getParentNoteId() { return parentNoteId; }
        public void setParentNoteId(UUID parentNoteId) { this.parentNoteId = parentNoteId; }
        public String getSubjective() { return subjective; }
        public void setSubjective(String subjective) { this.subjective = subjective; }
        public String getObjective() { return objective; }
        public void setObjective(String objective) { this.objective = objective; }
        public String getAssessment() { return assessment; }
        public void setAssessment(String assessment) { this.assessment = assessment; }
        public String getPlanText() { return planText; }
        public void setPlanText(String planText) { this.planText = planText; }
        public String getSensitivityTag() { return sensitivityTag; }
        public void setSensitivityTag(String sensitivityTag) { this.sensitivityTag = sensitivityTag; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAuthorId() { return authorId; }
        public void setAuthorId(String authorId) { this.authorId = authorId; }
        public String getCosignedBy() { return cosignedBy; }
        public void setCosignedBy(String cosignedBy) { this.cosignedBy = cosignedBy; }
        public Instant getSignedAt() { return signedAt; }
        public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }
    }

    @Entity @Table(name = "hc_clinical_orders")
    public static class ClinicalOrderEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "order_category", nullable = false, length = 24) private String orderCategory; // MEDICATION, LAB, IMAGING
        @Column(name = "code_system", nullable = false, length = 120) private String codeSystem;
        @Column(name = "order_code", nullable = false, length = 64) private String orderCode;
        @Column(name = "order_display", nullable = false, length = 300) private String orderDisplay;
        @Column(name = "dosage_or_instructions", length = 400) private String dosageOrInstructions;
        @Column(name = "priority", nullable = false, length = 24) private String priority = "ROUTINE";
        @Column(name = "status", nullable = false, length = 32) private String status = "ACTIVE";
        @Column(name = "cdss_override_reason", length = 300) private String cdssOverrideReason;
        @Column(name = "ordered_by", nullable = false, length = 120) private String orderedBy;
        @Column(name = "ordered_at", nullable = false) private Instant orderedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getOrderCategory() { return orderCategory; }
        public void setOrderCategory(String orderCategory) { this.orderCategory = orderCategory; }
        public String getCodeSystem() { return codeSystem; }
        public void setCodeSystem(String codeSystem) { this.codeSystem = codeSystem; }
        public String getOrderCode() { return orderCode; }
        public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
        public String getOrderDisplay() { return orderDisplay; }
        public void setOrderDisplay(String orderDisplay) { this.orderDisplay = orderDisplay; }
        public String getDosageOrInstructions() { return dosageOrInstructions; }
        public void setDosageOrInstructions(String dosageOrInstructions) { this.dosageOrInstructions = dosageOrInstructions; }
        public String getPriority() { return priority; }
        public void setPriority(String priority) { this.priority = priority; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getCdssOverrideReason() { return cdssOverrideReason; }
        public void setCdssOverrideReason(String cdssOverrideReason) { this.cdssOverrideReason = cdssOverrideReason; }
        public String getOrderedBy() { return orderedBy; }
        public void setOrderedBy(String orderedBy) { this.orderedBy = orderedBy; }
        public Instant getOrderedAt() { return orderedAt; }
    }

    @Entity @Table(name = "hc_ward_beds")
    public static class WardBedEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "ward_code", nullable = false, length = 64) private String wardCode;
        @Column(name = "bed_number", nullable = false, length = 32) private String bedNumber;
        @Column(name = "occupied", nullable = false) private boolean occupied = false;
        @Column(name = "active_admission_id") private UUID activeAdmissionId;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getWardCode() { return wardCode; }
        public void setWardCode(String wardCode) { this.wardCode = wardCode; }
        public String getBedNumber() { return bedNumber; }
        public void setBedNumber(String bedNumber) { this.bedNumber = bedNumber; }
        public boolean isOccupied() { return occupied; }
        public void setOccupied(boolean occupied) { this.occupied = occupied; }
        public UUID getActiveAdmissionId() { return activeAdmissionId; }
        public void setActiveAdmissionId(UUID activeAdmissionId) { this.activeAdmissionId = activeAdmissionId; }
    }

    @Entity @Table(name = "hc_admissions")
    public static class AdmissionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "ward_bed_id", nullable = false) private UUID wardBedId;
        @Column(name = "admitted_at", nullable = false) private Instant admittedAt = Instant.now();
        @Column(name = "discharged_at") private Instant dischargedAt;
        @Column(name = "discharge_disposition", length = 48) private String dischargeDisposition;
        @Lob @Column(name = "discharge_summary") private String dischargeSummary;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getWardBedId() { return wardBedId; }
        public void setWardBedId(UUID wardBedId) { this.wardBedId = wardBedId; }
        public Instant getAdmittedAt() { return admittedAt; }
        public Instant getDischargedAt() { return dischargedAt; }
        public void setDischargedAt(Instant dischargedAt) { this.dischargedAt = dischargedAt; }
        public String getDischargeDisposition() { return dischargeDisposition; }
        public void setDischargeDisposition(String dischargeDisposition) { this.dischargeDisposition = dischargeDisposition; }
        public String getDischargeSummary() { return dischargeSummary; }
        public void setDischargeSummary(String dischargeSummary) { this.dischargeSummary = dischargeSummary; }
    }

    @Entity @Table(name = "hc_emar_administrations")
    public static class EmarAdministrationEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "admission_id", nullable = false) private UUID admissionId;
        @Column(name = "order_id", nullable = false) private UUID orderId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "scanned_patient_wristband", nullable = false, length = 64) private String scannedPatientWristband;
        @Column(name = "scanned_drug_barcode", nullable = false, length = 64) private String scannedDrugBarcode;
        @Column(name = "dose_administered", nullable = false, length = 100) private String doseAdministered;
        @Column(name = "status", nullable = false, length = 24) private String status = "GIVEN";
        @Column(name = "administered_by", nullable = false, length = 120) private String administeredBy;
        @Column(name = "administered_at", nullable = false) private Instant administeredAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getAdmissionId() { return admissionId; }
        public void setAdmissionId(UUID admissionId) { this.admissionId = admissionId; }
        public UUID getOrderId() { return orderId; }
        public void setOrderId(UUID orderId) { this.orderId = orderId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getScannedPatientWristband() { return scannedPatientWristband; }
        public void setScannedPatientWristband(String scannedPatientWristband) { this.scannedPatientWristband = scannedPatientWristband; }
        public String getScannedDrugBarcode() { return scannedDrugBarcode; }
        public void setScannedDrugBarcode(String scannedDrugBarcode) { this.scannedDrugBarcode = scannedDrugBarcode; }
        public String getDoseAdministered() { return doseAdministered; }
        public void setDoseAdministered(String doseAdministered) { this.doseAdministered = doseAdministered; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getAdministeredBy() { return administeredBy; }
        public void setAdministeredBy(String administeredBy) { this.administeredBy = administeredBy; }
        public Instant getAdministeredAt() { return administeredAt; }
    }

    @Entity @Table(name = "hc_care_plans")
    public static class CarePlanEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "care_category", nullable = false, length = 48) private String careCategory; // CHRONIC_NCD, ANC_MCH, POST_DISCHARGE, HOME_CARE
        @Column(name = "goal_summary", nullable = false, length = 500) private String goalSummary;
        @Column(name = "next_followup_date") private LocalDate nextFollowupDate;
        @Column(name = "referral_destination", length = 160) private String referralDestination;
        @Column(name = "status", nullable = false, length = 24) private String status = "ACTIVE";

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getCareCategory() { return careCategory; }
        public void setCareCategory(String careCategory) { this.careCategory = careCategory; }
        public String getGoalSummary() { return goalSummary; }
        public void setGoalSummary(String goalSummary) { this.goalSummary = goalSummary; }
        public LocalDate getNextFollowupDate() { return nextFollowupDate; }
        public void setNextFollowupDate(LocalDate nextFollowupDate) { this.nextFollowupDate = nextFollowupDate; }
        public String getReferralDestination() { return referralDestination; }
        public void setReferralDestination(String referralDestination) { this.referralDestination = referralDestination; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    @Entity @Table(name = "hc_specialty_records")
    public static class SpecialtyRecordEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "specialty_code", nullable = false, length = 48) private String specialtyCode;
        @Lob @Column(name = "structured_payload_json", nullable = false) private String structuredPayloadJson;
        @Column(name = "recorded_by", nullable = false, length = 120) private String recordedBy;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getSpecialtyCode() { return specialtyCode; }
        public void setSpecialtyCode(String specialtyCode) { this.specialtyCode = specialtyCode; }
        public String getStructuredPayloadJson() { return structuredPayloadJson; }
        public void setStructuredPayloadJson(String structuredPayloadJson) { this.structuredPayloadJson = structuredPayloadJson; }
        public String getRecordedBy() { return recordedBy; }
        public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
        public Instant getRecordedAt() { return recordedAt; }
    }
}
