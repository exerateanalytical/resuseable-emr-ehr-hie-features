package com.healthcore.emr;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "encounters")
public class EncounterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "clinician_id", nullable = false, length = 120)
    private String clinicianId;

    @Column(name = "encounter_type", nullable = false, length = 40)
    private String encounterType;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "chief_complaint")
    private String chiefComplaint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "soap_notes", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> soapNotes = new HashMap<>();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "diagnosis_codes", nullable = false, columnDefinition = "text[]")
    private List<String> diagnosisCodes = new ArrayList<>();

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public String getClinicianId() { return clinicianId; }
    public void setClinicianId(String clinicianId) { this.clinicianId = clinicianId; }
    public String getEncounterType() { return encounterType; }
    public void setEncounterType(String encounterType) { this.encounterType = encounterType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String chiefComplaint) { this.chiefComplaint = chiefComplaint; }
    public Map<String, Object> getSoapNotes() { return soapNotes; }
    public void setSoapNotes(Map<String, Object> soapNotes) { this.soapNotes = soapNotes; }
    public List<String> getDiagnosisCodes() { return diagnosisCodes; }
    public void setDiagnosisCodes(List<String> diagnosisCodes) { this.diagnosisCodes = diagnosisCodes; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
