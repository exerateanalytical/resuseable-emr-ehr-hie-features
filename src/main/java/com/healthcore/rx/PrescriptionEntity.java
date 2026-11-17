package com.healthcore.rx;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "prescriptions")
public class PrescriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "encounter_id", nullable = false)
    private UUID encounterId;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "prescriber_id", nullable = false, length = 120)
    private String prescriberId;

    @Column(name = "medication_code", length = 64)
    private String medicationCode;

    @Column(name = "medication_name", nullable = false, length = 200)
    private String medicationName;

    @Column(name = "dosage", nullable = false, length = 120)
    private String dosage;

    @Column(name = "frequency", nullable = false, length = 120)
    private String frequency;

    @Column(name = "duration_days", nullable = false)
    private int durationDays;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "status", nullable = false, length = 24)
    private String status = "ACTIVE";

    @Column(name = "allergy_override_reason")
    private String allergyOverrideReason;

    @Column(name = "prescribed_at", nullable = false)
    private Instant prescribedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public String getPrescriberId() { return prescriberId; }
    public void setPrescriberId(String prescriberId) { this.prescriberId = prescriberId; }
    public String getMedicationCode() { return medicationCode; }
    public void setMedicationCode(String medicationCode) { this.medicationCode = medicationCode; }
    public String getMedicationName() { return medicationName; }
    public void setMedicationName(String medicationName) { this.medicationName = medicationName; }
    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }
    public String getFrequency() { return frequency; }
    public void setFrequency(String frequency) { this.frequency = frequency; }
    public int getDurationDays() { return durationDays; }
    public void setDurationDays(int durationDays) { this.durationDays = durationDays; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAllergyOverrideReason() { return allergyOverrideReason; }
    public void setAllergyOverrideReason(String allergyOverrideReason) { this.allergyOverrideReason = allergyOverrideReason; }
    public Instant getPrescribedAt() { return prescribedAt; }
}
