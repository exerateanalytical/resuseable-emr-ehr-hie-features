package com.healthcore.rx;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_allergies")
public class PatientAllergyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "substance_code", length = 64)
    private String substanceCode;

    @Column(name = "substance_name", nullable = false, length = 160)
    private String substanceName;

    @Column(name = "reaction", length = 200)
    private String reaction;

    @Column(name = "severity", nullable = false, length = 24)
    private String severity;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public String getSubstanceCode() { return substanceCode; }
    public void setSubstanceCode(String substanceCode) { this.substanceCode = substanceCode; }
    public String getSubstanceName() { return substanceName; }
    public void setSubstanceName(String substanceName) { this.substanceName = substanceName; }
    public String getReaction() { return reaction; }
    public void setReaction(String reaction) { this.reaction = reaction; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getRecordedAt() { return recordedAt; }
}
