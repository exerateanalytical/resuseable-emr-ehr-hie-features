package com.healthcore.mpi;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Entity
@Table(name = "patients")
public class PatientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "enterprise_id", nullable = false, unique = true, length = 64)
    private String enterpriseId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "name_prefix", length = 32)
    private String namePrefix;

    @Column(name = "given_name", nullable = false, length = 120)
    private String givenName;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "middle_names", nullable = false, columnDefinition = "text[]")
    private List<String> middleNames = new ArrayList<>();

    @Column(name = "family_name", nullable = false, length = 120)
    private String familyName;

    @Column(name = "name_suffix", length = 32)
    private String nameSuffix;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "sex", nullable = false, length = 16)
    private String sex;

    @Column(name = "marital_status_code", length = 16)
    private String maritalStatusCode;

    @Column(name = "deceased_boolean", nullable = false)
    private boolean deceasedBoolean = false;

    @Column(name = "deceased_date_time")
    private Instant deceasedDateTime;

    @Column(name = "multiple_birth_boolean")
    private Boolean multipleBirthBoolean;

    @Column(name = "multiple_birth_integer")
    private Integer multipleBirthInteger;

    @Column(name = "preferred_language", length = 16)
    private String preferredLanguage;

    @Column(name = "primary_phone", length = 32)
    private String primaryPhone;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "address_line", length = 255)
    private String addressLine;

    @Column(name = "blood_group", length = 8)
    private String bloodGroup;

    @Column(name = "general_practitioner_ref", length = 120)
    private String generalPractitionerRef;

    @Column(name = "managing_organization_id")
    private UUID managingOrganizationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fhir_extensions", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> fhirExtensions = new HashMap<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SUBSELECT)
    @BatchSize(size = 50)
    private List<PatientTelecomEntity> telecoms = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SUBSELECT)
    @BatchSize(size = 50)
    private List<PatientAddressEntity> addresses = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SUBSELECT)
    @BatchSize(size = 50)
    private List<PatientContactEntity> contacts = new ArrayList<>();

    @OneToMany(mappedBy = "patient", cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SUBSELECT)
    @BatchSize(size = 50)
    private List<PatientCommunicationEntity> communications = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public void replaceTelecoms(List<PatientTelecomEntity> items) {
        this.telecoms.clear();
        for (PatientTelecomEntity item : items) {
            item.setPatient(this);
            this.telecoms.add(item);
        }
    }

    public void replaceAddresses(List<PatientAddressEntity> items) {
        this.addresses.clear();
        for (PatientAddressEntity item : items) {
            item.setPatient(this);
            this.addresses.add(item);
        }
    }

    public void replaceContacts(List<PatientContactEntity> items) {
        this.contacts.clear();
        for (PatientContactEntity item : items) {
            item.setPatient(this);
            this.contacts.add(item);
        }
    }

    public void replaceCommunications(List<PatientCommunicationEntity> items) {
        this.communications.clear();
        for (PatientCommunicationEntity item : items) {
            item.setPatient(this);
            this.communications.add(item);
        }
    }

    public UUID getId() { return id; }
    public String getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(String enterpriseId) { this.enterpriseId = enterpriseId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getNamePrefix() { return namePrefix; }
    public void setNamePrefix(String namePrefix) { this.namePrefix = namePrefix; }
    public String getGivenName() { return givenName; }
    public void setGivenName(String givenName) { this.givenName = givenName; }
    public List<String> getMiddleNames() { return middleNames; }
    public void setMiddleNames(List<String> middleNames) { this.middleNames = middleNames; }
    public String getFamilyName() { return familyName; }
    public void setFamilyName(String familyName) { this.familyName = familyName; }
    public String getNameSuffix() { return nameSuffix; }
    public void setNameSuffix(String nameSuffix) { this.nameSuffix = nameSuffix; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getSex() { return sex; }
    public void setSex(String sex) { this.sex = sex; }
    public String getMaritalStatusCode() { return maritalStatusCode; }
    public void setMaritalStatusCode(String maritalStatusCode) { this.maritalStatusCode = maritalStatusCode; }
    public boolean isDeceasedBoolean() { return deceasedBoolean; }
    public void setDeceasedBoolean(boolean deceasedBoolean) { this.deceasedBoolean = deceasedBoolean; }
    public Instant getDeceasedDateTime() { return deceasedDateTime; }
    public void setDeceasedDateTime(Instant deceasedDateTime) { this.deceasedDateTime = deceasedDateTime; }
    public Boolean getMultipleBirthBoolean() { return multipleBirthBoolean; }
    public void setMultipleBirthBoolean(Boolean multipleBirthBoolean) { this.multipleBirthBoolean = multipleBirthBoolean; }
    public Integer getMultipleBirthInteger() { return multipleBirthInteger; }
    public void setMultipleBirthInteger(Integer multipleBirthInteger) { this.multipleBirthInteger = multipleBirthInteger; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
    public String getPrimaryPhone() { return primaryPhone; }
    public void setPrimaryPhone(String primaryPhone) { this.primaryPhone = primaryPhone; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getAddressLine() { return addressLine; }
    public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
    public String getGeneralPractitionerRef() { return generalPractitionerRef; }
    public void setGeneralPractitionerRef(String generalPractitionerRef) { this.generalPractitionerRef = generalPractitionerRef; }
    public UUID getManagingOrganizationId() { return managingOrganizationId; }
    public void setManagingOrganizationId(UUID managingOrganizationId) { this.managingOrganizationId = managingOrganizationId; }
    public Map<String, Object> getFhirExtensions() { return fhirExtensions; }
    public void setFhirExtensions(Map<String, Object> fhirExtensions) { this.fhirExtensions = fhirExtensions; }
    public List<PatientTelecomEntity> getTelecoms() { return telecoms; }
    public List<PatientAddressEntity> getAddresses() { return addresses; }
    public List<PatientContactEntity> getContacts() { return contacts; }
    public List<PatientCommunicationEntity> getCommunications() { return communications; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
