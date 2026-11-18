/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Ops Entities (Blood, OT, Telemed, IoT, CRVS, Workforce)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.ops.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class HospitalOpsEntities {

    private HospitalOpsEntities() {}

    @Entity @Table(name = "hc_blood_units")
    public static class BloodComponentUnitEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "isbt_unit_code", nullable = false, length = 64) private String isbtUnitCode;
        @Column(name = "component_type", nullable = false, length = 32) private String componentType; // PRBC, FFP, PLATELETS, WHOLE_BLOOD
        @Column(name = "abo_rhd", nullable = false, length = 8) private String aboRhd; // O+, O-, A+, etc.
        @Column(name = "expiry_date", nullable = false) private LocalDate expiryDate;
        @Column(name = "crossmatched_patient_id") private UUID crossmatchedPatientId;
        @Column(name = "status", nullable = false, length = 24) private String status = "AVAILABLE";
        @Column(name = "transfusion_verifier_1", length = 120) private String transfusionVerifier1;
        @Column(name = "transfusion_verifier_2", length = 120) private String transfusionVerifier2;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getIsbtUnitCode() { return isbtUnitCode; }
        public void setIsbtUnitCode(String isbtUnitCode) { this.isbtUnitCode = isbtUnitCode; }
        public String getComponentType() { return componentType; }
        public void setComponentType(String componentType) { this.componentType = componentType; }
        public String getAboRhd() { return aboRhd; }
        public void setAboRhd(String aboRhd) { this.aboRhd = aboRhd; }
        public LocalDate getExpiryDate() { return expiryDate; }
        public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
        public UUID getCrossmatchedPatientId() { return crossmatchedPatientId; }
        public void setCrossmatchedPatientId(UUID crossmatchedPatientId) { this.crossmatchedPatientId = crossmatchedPatientId; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getTransfusionVerifier1() { return transfusionVerifier1; }
        public void setTransfusionVerifier1(String transfusionVerifier1) { this.transfusionVerifier1 = transfusionVerifier1; }
        public String getTransfusionVerifier2() { return transfusionVerifier2; }
        public void setTransfusionVerifier2(String transfusionVerifier2) { this.transfusionVerifier2 = transfusionVerifier2; }
    }

    @Entity @Table(name = "hc_surgical_schedules")
    public static class SurgicalScheduleEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "theater_room", nullable = false, length = 48) private String theaterRoom;
        @Column(name = "procedure_name", nullable = false, length = 240) private String procedureName;
        @Column(name = "lead_surgeon_id", nullable = false, length = 120) private String leadSurgeonId;
        @Column(name = "who_checklist_completed", nullable = false) private boolean whoChecklistCompleted = false;
        @Column(name = "implant_udi", length = 128) private String implantUdi;
        @Column(name = "status", nullable = false, length = 24) private String status = "SCHEDULED";
        @Column(name = "scheduled_at", nullable = false) private Instant scheduledAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getTheaterRoom() { return theaterRoom; }
        public void setTheaterRoom(String theaterRoom) { this.theaterRoom = theaterRoom; }
        public String getProcedureName() { return procedureName; }
        public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
        public String getLeadSurgeonId() { return leadSurgeonId; }
        public void setLeadSurgeonId(String leadSurgeonId) { this.leadSurgeonId = leadSurgeonId; }
        public boolean isWhoChecklistCompleted() { return whoChecklistCompleted; }
        public void setWhoChecklistCompleted(boolean whoChecklistCompleted) { this.whoChecklistCompleted = whoChecklistCompleted; }
        public String getImplantUdi() { return implantUdi; }
        public void setImplantUdi(String implantUdi) { this.implantUdi = implantUdi; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getScheduledAt() { return scheduledAt; }
        public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    }

    @Entity @Table(name = "hc_telemedicine_sessions")
    public static class TelemedicineSessionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "clinician_id", nullable = false, length = 120) private String clinicianId;
        @Column(name = "signaling_room_token", nullable = false, length = 160) private String signalingRoomToken;
        @Column(name = "low_bandwidth_audio_mode", nullable = false) private boolean lowBandwidthAudioMode = false;
        @Column(name = "status", nullable = false, length = 24) private String status = "READY";
        @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getClinicianId() { return clinicianId; }
        public void setClinicianId(String clinicianId) { this.clinicianId = clinicianId; }
        public String getSignalingRoomToken() { return signalingRoomToken; }
        public void setSignalingRoomToken(String signalingRoomToken) { this.signalingRoomToken = signalingRoomToken; }
        public boolean isLowBandwidthAudioMode() { return lowBandwidthAudioMode; }
        public void setLowBandwidthAudioMode(boolean lowBandwidthAudioMode) { this.lowBandwidthAudioMode = lowBandwidthAudioMode; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getStartedAt() { return startedAt; }
    }

    @Entity @Table(name = "hc_cold_chain_telemetry")
    public static class ColdChainTelemetryEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "equipment_tag", nullable = false, length = 64) private String equipmentTag;
        @Column(name = "temp_celsius", nullable = false, precision = 5, scale = 2) private BigDecimal tempCelsius;
        @Column(name = "excursion_alert", nullable = false) private boolean excursionAlert = false;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getEquipmentTag() { return equipmentTag; }
        public void setEquipmentTag(String equipmentTag) { this.equipmentTag = equipmentTag; }
        public BigDecimal getTempCelsius() { return tempCelsius; }
        public void setTempCelsius(BigDecimal tempCelsius) { this.tempCelsius = tempCelsius; }
        public boolean isExcursionAlert() { return excursionAlert; }
        public void setExcursionAlert(boolean excursionAlert) { this.excursionAlert = excursionAlert; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity @Table(name = "hc_crvs_events")
    public static class CrvsBirthDeathEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "event_type", nullable = false, length = 24) private String eventType; // BIRTH or DEATH
        @Column(name = "certificate_number", nullable = false, length = 64) private String certificateNumber;
        @Column(name = "who_icd10_cause_of_death", length = 32) private String whoIcd10CauseOfDeath;
        @Column(name = "mortuary_compartment", length = 48) private String mortuaryCompartment;
        @Column(name = "mccd_part1_immediate", length = 240) private String mccdPart1Immediate;
        @Column(name = "mccd_part1_underlying", length = 240) private String mccdPart1Underlying;
        @Column(name = "mortuary_custody_status", length = 32) private String mortuaryCustodyStatus;
        @Column(name = "released_to_next_of_kin", length = 160) private String releasedToNextOfKin;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getCertificateNumber() { return certificateNumber; }
        public void setCertificateNumber(String certificateNumber) { this.certificateNumber = certificateNumber; }
        public String getWhoIcd10CauseOfDeath() { return whoIcd10CauseOfDeath; }
        public void setWhoIcd10CauseOfDeath(String whoIcd10CauseOfDeath) { this.whoIcd10CauseOfDeath = whoIcd10CauseOfDeath; }
        public String getMortuaryCompartment() { return mortuaryCompartment; }
        public void setMortuaryCompartment(String mortuaryCompartment) { this.mortuaryCompartment = mortuaryCompartment; }
        public String getMccdPart1Immediate() { return mccdPart1Immediate; }
        public void setMccdPart1Immediate(String mccdPart1Immediate) { this.mccdPart1Immediate = mccdPart1Immediate; }
        public String getMccdPart1Underlying() { return mccdPart1Underlying; }
        public void setMccdPart1Underlying(String mccdPart1Underlying) { this.mccdPart1Underlying = mccdPart1Underlying; }
        public String getMortuaryCustodyStatus() { return mortuaryCustodyStatus; }
        public void setMortuaryCustodyStatus(String mortuaryCustodyStatus) { this.mortuaryCustodyStatus = mortuaryCustodyStatus; }
        public String getReleasedToNextOfKin() { return releasedToNextOfKin; }
        public void setReleasedToNextOfKin(String releasedToNextOfKin) { this.releasedToNextOfKin = releasedToNextOfKin; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity @Table(name = "hc_practitioner_credentials")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PractitionerCredentialEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "practitioner_id", nullable = false, length = 120) private String practitionerId;
        @Column(name = "license_number", nullable = false, length = 80) private String licenseNumber;
        @Column(name = "regulatory_council", nullable = false, length = 120) private String regulatoryCouncil;
        @Column(name = "expires_on", nullable = false) private LocalDate expiresOn;
        @Column(name = "suspended", nullable = false) private boolean suspended = false;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getPractitionerId() { return practitionerId; }
        public void setPractitionerId(String practitionerId) { this.practitionerId = practitionerId; }
        public String getLicenseNumber() { return licenseNumber; }
        public void setLicenseNumber(String licenseNumber) { this.licenseNumber = licenseNumber; }
        public String getRegulatoryCouncil() { return regulatoryCouncil; }
        public void setRegulatoryCouncil(String regulatoryCouncil) { this.regulatoryCouncil = regulatoryCouncil; }
        public LocalDate getExpiresOn() { return expiresOn; }
        public void setExpiresOn(LocalDate expiresOn) { this.expiresOn = expiresOn; }
        public boolean isSuspended() { return suspended; }
        public void setSuspended(boolean suspended) { this.suspended = suspended; }
    }

    @Entity @Table(name = "hc_blood_donors")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class BloodDonorEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "donor_code", nullable = false, unique = true, length = 64) private String donorCode;
        @Column(name = "full_name", nullable = false, length = 160) private String fullName;
        @Column(name = "abo_rhd", nullable = false, length = 8) private String aboRhd;
        @Column(name = "hemoglobin_g_dl", nullable = false, precision = 4, scale = 1) private BigDecimal hemoglobinGDl;
        @Column(name = "eligible", nullable = false) private boolean eligible = true;
        @Column(name = "deferral_reason", length = 240) private String deferralReason;
        @Column(name = "deferral_until") private LocalDate deferralUntil;
        @Column(name = "registered_at", nullable = false) private Instant registeredAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDonorCode() { return donorCode; }
        public void setDonorCode(String donorCode) { this.donorCode = donorCode; }
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getAboRhd() { return aboRhd; }
        public void setAboRhd(String aboRhd) { this.aboRhd = aboRhd; }
        public BigDecimal getHemoglobinGDl() { return hemoglobinGDl; }
        public void setHemoglobinGDl(BigDecimal hemoglobinGDl) { this.hemoglobinGDl = hemoglobinGDl; }
        public boolean isEligible() { return eligible; }
        public void setEligible(boolean eligible) { this.eligible = eligible; }
        public String getDeferralReason() { return deferralReason; }
        public void setDeferralReason(String deferralReason) { this.deferralReason = deferralReason; }
        public LocalDate getDeferralUntil() { return deferralUntil; }
        public void setDeferralUntil(LocalDate deferralUntil) { this.deferralUntil = deferralUntil; }
        public Instant getRegisteredAt() { return registeredAt; }
    }

    @Entity @Table(name = "hc_transfusion_reactions")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class TransfusionReactionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "blood_unit_id", nullable = false) private UUID bloodUnitId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "reaction_type", nullable = false, length = 32) private String reactionType; // FEBRILE, ALLERGIC, HEMOLYTIC, TRALI
        @Column(name = "severity", nullable = false, length = 24) private String severity;
        @Column(name = "clinical_notes", length = 500) private String clinicalNotes;
        @Column(name = "reported_at", nullable = false) private Instant reportedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getBloodUnitId() { return bloodUnitId; }
        public void setBloodUnitId(UUID bloodUnitId) { this.bloodUnitId = bloodUnitId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getReactionType() { return reactionType; }
        public void setReactionType(String reactionType) { this.reactionType = reactionType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getClinicalNotes() { return clinicalNotes; }
        public void setClinicalNotes(String clinicalNotes) { this.clinicalNotes = clinicalNotes; }
        public Instant getReportedAt() { return reportedAt; }
    }

    @Entity @Table(name = "hc_anesthesia_pacu_logs")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class AnesthesiaPacuLogEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "surgery_id", nullable = false) private UUID surgeryId;
        @Column(name = "asa_class", nullable = false, length = 16) private String asaClass; // ASA_I..ASA_V
        @Column(name = "induction_agent", nullable = false, length = 160) private String inductionAgent;
        @Column(name = "airway_management", length = 80) private String airwayManagement;
        @Column(name = "estimated_blood_loss_ml") private Integer estimatedBloodLossMl;
        @Column(name = "pacu_aldrete_score") private Integer pacuAldreteScore; // 0-10 discharge readiness
        @Column(name = "anesthetist_id", nullable = false, length = 120) private String anesthetistId;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSurgeryId() { return surgeryId; }
        public void setSurgeryId(UUID surgeryId) { this.surgeryId = surgeryId; }
        public String getAsaClass() { return asaClass; }
        public void setAsaClass(String asaClass) { this.asaClass = asaClass; }
        public String getInductionAgent() { return inductionAgent; }
        public void setInductionAgent(String inductionAgent) { this.inductionAgent = inductionAgent; }
        public String getAirwayManagement() { return airwayManagement; }
        public void setAirwayManagement(String airwayManagement) { this.airwayManagement = airwayManagement; }
        public Integer getEstimatedBloodLossMl() { return estimatedBloodLossMl; }
        public void setEstimatedBloodLossMl(Integer estimatedBloodLossMl) { this.estimatedBloodLossMl = estimatedBloodLossMl; }
        public Integer getPacuAldreteScore() { return pacuAldreteScore; }
        public void setPacuAldreteScore(Integer pacuAldreteScore) { this.pacuAldreteScore = pacuAldreteScore; }
        public String getAnesthetistId() { return anesthetistId; }
        public void setAnesthetistId(String anesthetistId) { this.anesthetistId = anesthetistId; }
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity @Table(name = "hc_biomed_maintenance")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class BiomedMaintenanceEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "equipment_tag", nullable = false, length = 64) private String equipmentTag;
        @Column(name = "equipment_name", nullable = false, length = 160) private String equipmentName;
        @Column(name = "last_pm_on") private LocalDate lastPmOn;
        @Column(name = "calibration_due_on", nullable = false) private LocalDate calibrationDueOn;
        @Column(name = "status", nullable = false, length = 24) private String status = "OPERATIONAL";
        @Column(name = "notes", length = 300) private String notes;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getEquipmentTag() { return equipmentTag; }
        public void setEquipmentTag(String equipmentTag) { this.equipmentTag = equipmentTag; }
        public String getEquipmentName() { return equipmentName; }
        public void setEquipmentName(String equipmentName) { this.equipmentName = equipmentName; }
        public LocalDate getLastPmOn() { return lastPmOn; }
        public void setLastPmOn(LocalDate lastPmOn) { this.lastPmOn = lastPmOn; }
        public LocalDate getCalibrationDueOn() { return calibrationDueOn; }
        public void setCalibrationDueOn(LocalDate calibrationDueOn) { this.calibrationDueOn = calibrationDueOn; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    @Entity @Table(name = "hc_workforce_rosters")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class WorkforceRosterEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "practitioner_id", nullable = false, length = 120) private String practitionerId;
        @Column(name = "department_code", nullable = false, length = 64) private String departmentCode;
        @Column(name = "shift_type", nullable = false, length = 24) private String shiftType; // DAY, NIGHT, ON_CALL
        @Column(name = "shift_date", nullable = false) private LocalDate shiftDate;
        @Column(name = "on_call_phone", length = 64) private String onCallPhone;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getPractitionerId() { return practitionerId; }
        public void setPractitionerId(String practitionerId) { this.practitionerId = practitionerId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getShiftType() { return shiftType; }
        public void setShiftType(String shiftType) { this.shiftType = shiftType; }
        public LocalDate getShiftDate() { return shiftDate; }
        public void setShiftDate(LocalDate shiftDate) { this.shiftDate = shiftDate; }
        public String getOnCallPhone() { return onCallPhone; }
        public void setOnCallPhone(String onCallPhone) { this.onCallPhone = onCallPhone; }
    }
}
