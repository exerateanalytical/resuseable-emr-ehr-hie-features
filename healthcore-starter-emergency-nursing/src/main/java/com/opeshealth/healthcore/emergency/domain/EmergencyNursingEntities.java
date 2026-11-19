/*
 * =============================================================================
 * Opes HealthCore SDK — Emergency Triage & Inpatient Nursing Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emergency.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class EmergencyNursingEntities {
    private EmergencyNursingEntities() {}

    @Entity
    @Table(name = "hc_ed_visits")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class EmergencyVisitEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id") private UUID encounterId;
        @Column(name = "chief_complaint", nullable = false, length = 300) private String chiefComplaint;
        @Column(name = "esi_level", nullable = false) private int esiLevel;
        @Column(name = "arrival_mode", nullable = false, length = 32) private String arrivalMode;
        @Column(name = "assigned_bay", length = 48) private String assignedBay;
        @Column(name = "triage_nurse_id", nullable = false, length = 120) private String triageNurseId;
        @Column(name = "attending_clinician_id", length = 120) private String attendingClinicianId;
        @Column(name = "visit_status", nullable = false, length = 32) private String visitStatus = "TRIAGED";
        @Column(name = "disposition", length = 32) private String disposition;
        @Column(name = "arrived_at", nullable = false) private Instant arrivedAt = Instant.now();
        @Column(name = "disposition_at") private Instant dispositionAt;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public String getChiefComplaint() { return chiefComplaint; }
        public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }
        public int getEsiLevel() { return esiLevel; }
        public void setEsiLevel(int esiLevel) { this.esiLevel = esiLevel; }
        public String getArrivalMode() { return arrivalMode; }
        public void setArrivalMode(String arrivalMode) { this.arrivalMode = arrivalMode; }
        public String getAssignedBay() { return assignedBay; }
        public void setAssignedBay(String assignedBay) { this.assignedBay = assignedBay; }
        public String getTriageNurseId() { return triageNurseId; }
        public void setTriageNurseId(String triageNurseId) { this.triageNurseId = triageNurseId; }
        public String getAttendingClinicianId() { return attendingClinicianId; }
        public void setAttendingClinicianId(String attendingClinicianId) { this.attendingClinicianId = attendingClinicianId; }
        public String getVisitStatus() { return visitStatus; }
        public void setVisitStatus(String visitStatus) { this.visitStatus = visitStatus; }
        public String getDisposition() { return disposition; }
        public void setDisposition(String disposition) { this.disposition = disposition; }
        public Instant getArrivedAt() { return arrivedAt; }
        public Instant getDispositionAt() { return dispositionAt; }
        public void setDispositionAt(Instant dispositionAt) { this.dispositionAt = dispositionAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_nursing_assignments")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class NursingAssignmentEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "ward_code", nullable = false, length = 48) private String wardCode;
        @Column(name = "bed_code", nullable = false, length = 48) private String bedCode;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "nurse_id", nullable = false, length = 120) private String nurseId;
        @Column(name = "shift_code", nullable = false, length = 24) private String shiftCode;
        @Column(name = "active_flag", nullable = false) private boolean activeFlag = true;
        @Column(name = "assigned_at", nullable = false) private Instant assignedAt = Instant.now();
        @Column(name = "ended_at") private Instant endedAt;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getWardCode() { return wardCode; }
        public void setWardCode(String wardCode) { this.wardCode = wardCode; }
        public String getBedCode() { return bedCode; }
        public void setBedCode(String bedCode) { this.bedCode = bedCode; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getNurseId() { return nurseId; }
        public void setNurseId(String nurseId) { this.nurseId = nurseId; }
        public String getShiftCode() { return shiftCode; }
        public void setShiftCode(String shiftCode) { this.shiftCode = shiftCode; }
        public boolean isActiveFlag() { return activeFlag; }
        public void setActiveFlag(boolean activeFlag) { this.activeFlag = activeFlag; }
        public Instant getAssignedAt() { return assignedAt; }
        public Instant getEndedAt() { return endedAt; }
        public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    }

    @Entity
    @Table(name = "hc_nursing_news2_scores")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class News2ObservationEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id") private UUID encounterId;
        @Column(name = "respiratory_rate", nullable = false) private int respiratoryRate;
        @Column(name = "spo2_percent", nullable = false) private int spo2Percent;
        @Column(name = "supplemental_o2", nullable = false) private boolean supplementalO2;
        @Column(name = "systolic_bp", nullable = false) private int systolicBp;
        @Column(name = "heart_rate", nullable = false) private int heartRate;
        @Column(name = "temperature_c", nullable = false) private double temperatureC;
        @Column(name = "consciousness_acvpu", nullable = false, length = 16) private String consciousnessAcvpu;
        @Column(name = "total_score", nullable = false) private int totalScore;
        @Column(name = "risk_band", nullable = false, length = 24) private String riskBand;
        @Column(name = "escalation_required", nullable = false) private boolean escalationRequired;
        @Column(name = "recorded_by", nullable = false, length = 120) private String recordedBy;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public int getRespiratoryRate() { return respiratoryRate; }
        public void setRespiratoryRate(int respiratoryRate) { this.respiratoryRate = respiratoryRate; }
        public int getSpo2Percent() { return spo2Percent; }
        public void setSpo2Percent(int spo2Percent) { this.spo2Percent = spo2Percent; }
        public boolean isSupplementalO2() { return supplementalO2; }
        public void setSupplementalO2(boolean supplementalO2) { this.supplementalO2 = supplementalO2; }
        public int getSystolicBp() { return systolicBp; }
        public void setSystolicBp(int systolicBp) { this.systolicBp = systolicBp; }
        public int getHeartRate() { return heartRate; }
        public void setHeartRate(int heartRate) { this.heartRate = heartRate; }
        public double getTemperatureC() { return temperatureC; }
        public void setTemperatureC(double temperatureC) { this.temperatureC = temperatureC; }
        public String getConsciousnessAcvpu() { return consciousnessAcvpu; }
        public void setConsciousnessAcvpu(String consciousnessAcvpu) { this.consciousnessAcvpu = consciousnessAcvpu; }
        public int getTotalScore() { return totalScore; }
        public void setTotalScore(int totalScore) { this.totalScore = totalScore; }
        public String getRiskBand() { return riskBand; }
        public void setRiskBand(String riskBand) { this.riskBand = riskBand; }
        public boolean isEscalationRequired() { return escalationRequired; }
        public void setEscalationRequired(boolean escalationRequired) { this.escalationRequired = escalationRequired; }
        public String getRecordedBy() { return recordedBy; }
        public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity
    @Table(name = "hc_discharge_readiness")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class DischargeReadinessEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "med_reconciliation_complete", nullable = false) private boolean medReconciliationComplete;
        @Column(name = "discharge_summary_signed", nullable = false) private boolean dischargeSummarySigned;
        @Column(name = "patient_education_documented", nullable = false) private boolean patientEducationDocumented;
        @Column(name = "follow_up_scheduled", nullable = false) private boolean followUpScheduled;
        @Column(name = "billing_cleared", nullable = false) private boolean billingCleared;
        @Column(name = "ready_for_discharge", nullable = false) private boolean readyForDischarge;
        @Column(name = "updated_by", nullable = false, length = 120) private String updatedBy;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public boolean isMedReconciliationComplete() { return medReconciliationComplete; }
        public void setMedReconciliationComplete(boolean medReconciliationComplete) { this.medReconciliationComplete = medReconciliationComplete; }
        public boolean isDischargeSummarySigned() { return dischargeSummarySigned; }
        public void setDischargeSummarySigned(boolean dischargeSummarySigned) { this.dischargeSummarySigned = dischargeSummarySigned; }
        public boolean isPatientEducationDocumented() { return patientEducationDocumented; }
        public void setPatientEducationDocumented(boolean patientEducationDocumented) { this.patientEducationDocumented = patientEducationDocumented; }
        public boolean isFollowUpScheduled() { return followUpScheduled; }
        public void setFollowUpScheduled(boolean followUpScheduled) { this.followUpScheduled = followUpScheduled; }
        public boolean isBillingCleared() { return billingCleared; }
        public void setBillingCleared(boolean billingCleared) { this.billingCleared = billingCleared; }
        public boolean isReadyForDischarge() { return readyForDischarge; }
        public void setReadyForDischarge(boolean readyForDischarge) { this.readyForDischarge = readyForDischarge; }
        public String getUpdatedBy() { return updatedBy; }
        public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }
}
