package com.healthcore.mpi;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_telecoms")
public class PatientTelecomEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private PatientEntity patient;

    @Column(name = "system", nullable = false, length = 24)
    private String system;

    @Column(name = "value", nullable = false, length = 160)
    private String value;

    @Column(name = "use_code", length = 24)
    private String useCode;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public PatientEntity getPatient() { return patient; }
    public void setPatient(PatientEntity patient) { this.patient = patient; }
    public String getSystem() { return system; }
    public void setSystem(String system) { this.system = system; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getUseCode() { return useCode; }
    public void setUseCode(String useCode) { this.useCode = useCode; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Instant getCreatedAt() { return createdAt; }
}
