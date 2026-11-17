package com.healthcore.mpi;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_communications")
public class PatientCommunicationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private PatientEntity patient;

    @Column(name = "language_code", nullable = false, length = 24)
    private String languageCode;

    @Column(name = "language_display", length = 80)
    private String languageDisplay;

    @Column(name = "preferred", nullable = false)
    private boolean preferred;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public PatientEntity getPatient() { return patient; }
    public void setPatient(PatientEntity patient) { this.patient = patient; }
    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }
    public String getLanguageDisplay() { return languageDisplay; }
    public void setLanguageDisplay(String languageDisplay) { this.languageDisplay = languageDisplay; }
    public boolean isPreferred() { return preferred; }
    public void setPreferred(boolean preferred) { this.preferred = preferred; }
    public Instant getCreatedAt() { return createdAt; }
}
