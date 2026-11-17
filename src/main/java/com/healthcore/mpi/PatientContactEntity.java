package com.healthcore.mpi;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patient_contacts")
public class PatientContactEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false)
    private PatientEntity patient;

    @Column(name = "relationship_code", nullable = false, length = 32)
    private String relationshipCode;

    @Column(name = "relationship_display", length = 100)
    private String relationshipDisplay;

    @Column(name = "given_name", length = 120)
    private String givenName;

    @Column(name = "family_name", length = 120)
    private String familyName;

    @Column(name = "gender", length = 16)
    private String gender;

    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "email", length = 160)
    private String email;

    @Column(name = "address_summary", length = 255)
    private String addressSummary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public PatientEntity getPatient() { return patient; }
    public void setPatient(PatientEntity patient) { this.patient = patient; }
    public String getRelationshipCode() { return relationshipCode; }
    public void setRelationshipCode(String relationshipCode) { this.relationshipCode = relationshipCode; }
    public String getRelationshipDisplay() { return relationshipDisplay; }
    public void setRelationshipDisplay(String relationshipDisplay) { this.relationshipDisplay = relationshipDisplay; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddressSummary() { return addressSummary; }
    public void setAddressSummary(String addressSummary) { this.addressSummary = addressSummary; }
    public Instant getCreatedAt() { return createdAt; }
}
