package com.healthcore.mpi;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_identifiers")
public class PatientIdentifierEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "facility_id")
    private UUID facilityId;

    @Column(name = "id_type", nullable = false, length = 40)
    private String idType;

    @Column(name = "id_value", nullable = false, length = 120)
    private String idValue;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "system_uri", length = 255)
    private String systemUri;

    @Column(name = "use_code", length = 24)
    private String useCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public String getIdType() { return idType; }
    public void setIdType(String idType) { this.idType = idType; }
    public String getIdValue() { return idValue; }
    public void setIdValue(String idValue) { this.idValue = idValue; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
    public String getSystemUri() { return systemUri; }
    public void setSystemUri(String systemUri) { this.systemUri = systemUri; }
    public String getUseCode() { return useCode; }
    public void setUseCode(String useCode) { this.useCode = useCode; }
    public Instant getCreatedAt() { return createdAt; }
}
