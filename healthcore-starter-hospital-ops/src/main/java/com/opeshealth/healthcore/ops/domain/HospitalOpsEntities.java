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
        public Instant getRecordedAt() { return recordedAt; }
    }

    @Entity @Table(name = "hc_practitioner_credentials")
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
}
