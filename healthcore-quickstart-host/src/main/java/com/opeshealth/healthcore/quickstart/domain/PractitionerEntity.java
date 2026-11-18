/*
 * =============================================================================
 * Opes HealthCore SDK — Practitioner JPA Entity (Server Module)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * JPA aggregate backing HL7 FHIR R4 {@code Practitioner} resources with
 * multi-facility Hibernate filtering and optimistic locking ({@link Version}).
 */
@Entity
@Table(name = "hc_practitioners")
@BatchSize(size = 32)
@Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
public class PractitionerEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7Generator.nextUuid();

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "identifier_system", length = 120)
    private String identifierSystem;

    @Column(name = "identifier_value", length = 120)
    private String identifierValue;

    @Column(name = "identifier_use", length = 24)
    private String identifierUse = "OFFICIAL";

    @Column(name = "assigner_display", length = 160)
    private String assignerDisplay;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "name_prefix", length = 32)
    private String namePrefix;

    @Column(name = "family_name", nullable = false, length = 120)
    private String familyName;

    @Column(name = "given_name", nullable = false, length = 120)
    private String givenName;

    @Column(name = "name_suffix", length = 32)
    private String nameSuffix;

    @Column(name = "gender", nullable = false, length = 16)
    private String gender = "UNKNOWN";

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "phone", length = 64)
    private String phone;

    @Column(name = "email", length = 160)
    private String email;

    @Column(name = "address_use", length = 24)
    private String addressUse = "WORK";

    @Column(name = "address_line", length = 250)
    private String addressLine;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "district", length = 120)
    private String district;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "postal_code", length = 32)
    private String postalCode;

    @Column(name = "country", length = 64)
    private String country;

    @Column(name = "qualification_code", length = 64)
    private String qualificationCode;

    @Column(name = "qualification_display", length = 160)
    private String qualificationDisplay;

    @Column(name = "qualification_issuer", length = 160)
    private String qualificationIssuer;

    @Column(name = "communication_code", length = 24)
    private String communicationCode = "en";

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public String getIdentifierSystem() { return identifierSystem; }
    public void setIdentifierSystem(String identifierSystem) { this.identifierSystem = identifierSystem; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String identifierValue) { this.identifierValue = identifierValue; }
    public String getIdentifierUse() { return identifierUse; }
    public void setIdentifierUse(String identifierUse) { this.identifierUse = identifierUse; }
    public String getAssignerDisplay() { return assignerDisplay; }
    public void setAssignerDisplay(String assignerDisplay) { this.assignerDisplay = assignerDisplay; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getNamePrefix() { return namePrefix; }
    public void setNamePrefix(String namePrefix) { this.namePrefix = namePrefix; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public String getNameSuffix() { return nameSuffix; }
    public void setNameSuffix(String nameSuffix) { this.nameSuffix = nameSuffix; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddressUse() { return addressUse; }
    public void setAddressUse(String addressUse) { this.addressUse = addressUse; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getPostalCode() { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public String getQualificationCode() { return qualificationCode; }
    public void setQualificationCode(String qualificationCode) { this.qualificationCode = qualificationCode; }
    public String getQualificationDisplay() { return qualificationDisplay; }
    public void setQualificationDisplay(String qualificationDisplay) { this.qualificationDisplay = qualificationDisplay; }
    public String getQualificationIssuer() { return qualificationIssuer; }
    public void setQualificationIssuer(String qualificationIssuer) { this.qualificationIssuer = qualificationIssuer; }
    public String getCommunicationCode() { return communicationCode; }
    public void setCommunicationCode(String communicationCode) { this.communicationCode = communicationCode; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
