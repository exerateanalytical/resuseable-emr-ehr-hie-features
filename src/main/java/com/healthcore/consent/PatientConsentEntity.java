package com.healthcore.consent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_consents")
public class PatientConsentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "granting_facility_id")
    private UUID grantingFacilityId;

    @Column(name = "target_facility_id")
    private UUID targetFacilityId;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "scope", nullable = false, length = 64)
    private String scope;

    @Column(name = "purpose_of_use", nullable = false, length = 64)
    private String purposeOfUse;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "recorded_by", nullable = false, length = 120)
    private String recordedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public UUID getGrantingFacilityId() { return grantingFacilityId; }
    public void setGrantingFacilityId(UUID grantingFacilityId) { this.grantingFacilityId = grantingFacilityId; }
    public UUID getTargetFacilityId() { return targetFacilityId; }
    public void setTargetFacilityId(UUID targetFacilityId) { this.targetFacilityId = targetFacilityId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getPurposeOfUse() { return purposeOfUse; }
    public void setPurposeOfUse(String purposeOfUse) { this.purposeOfUse = purposeOfUse; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String recordedBy) { this.recordedBy = recordedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
