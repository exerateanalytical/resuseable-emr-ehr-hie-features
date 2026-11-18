/*
 * =============================================================================
 * Opes HealthCore SDK — Master Patient Index (MPI) Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Core Master Patient Index (MPI) aggregate, encrypted identifier vault,
 * normalized demographics, cross-DB phonetic blocking index, and reversible merge history.
 */
public final class MpiEntities {

    private MpiEntities() {}

    @Entity
    @Table(name = "hc_patients")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PatientAggregateEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "mrn", nullable = false, length = 64)
        private String mrn;

        @Column(name = "family_name", nullable = false, length = 120)
        private String familyName;

        @Column(name = "given_name", nullable = false, length = 120)
        private String givenName;

        @Column(name = "birth_date", nullable = false)
        private LocalDate birthDate;

        @Column(name = "gender", nullable = false, length = 16)
        private String gender;

        @Column(name = "blood_group", length = 8)
        private String bloodGroup;

        @Column(name = "primary_phone", length = 64)
        private String primaryPhone;

        @Column(name = "preferred_language", length = 16)
        private String preferredLanguage = "en";

        @Column(name = "golden_record", nullable = false)
        private boolean goldenRecord = true;

        @Column(name = "surviving_patient_id")
        private UUID survivingPatientId;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Column(name = "deceased", nullable = false)
        private boolean deceased = false;

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
        public String getMrn() { return mrn; }
        public void setMrn(String mrn) { this.mrn = mrn; }
        public String getFamilyName() { return familyName; }
        public void setFamilyName(String familyName) { this.familyName = familyName; }
        public String getGivenName() { return givenName; }
        public void setGivenName(String givenName) { this.givenName = givenName; }
        public LocalDate getBirthDate() { return birthDate; }
        public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
        public String getGender() { return gender; }
        public void setGender(String gender) { this.gender = gender; }
        public String getBloodGroup() { return bloodGroup; }
        public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
        public String getPrimaryPhone() { return primaryPhone; }
        public void setPrimaryPhone(String primaryPhone) { this.primaryPhone = primaryPhone; }
        public String getPreferredLanguage() { return preferredLanguage; }
        public void setPreferredLanguage(String preferredLanguage) { this.preferredLanguage = preferredLanguage; }
        public boolean isGoldenRecord() { return goldenRecord; }
        public void setGoldenRecord(boolean goldenRecord) { this.goldenRecord = goldenRecord; }
        public UUID getSurvivingPatientId() { return survivingPatientId; }
        public void setSurvivingPatientId(UUID survivingPatientId) { this.survivingPatientId = survivingPatientId; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public boolean isDeceased() { return deceased; }
        public void setDeceased(boolean deceased) { this.deceased = deceased; }
        public long getVersion() { return version; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    /** Encrypted National ID / Passport / Insurance / Biometric identifier with HMAC blind index. */
    @Entity
    @Table(name = "hc_patient_identifiers")
    public static class PatientIdentifierEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "identifier_system", nullable = false, length = 120)
        private String identifierSystem;

        @Convert(converter = PiiVaultCrypto.EncryptedStringConverter.class)
        @Column(name = "encrypted_value", nullable = false, length = 512)
        private String encryptedValue;

        @Column(name = "blind_index", nullable = false, length = 88)
        private String blindIndex;

        @Column(name = "biometric_template_hash", length = 128)
        private String biometricTemplateHash;

        public UUID getId() { return id; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getIdentifierSystem() { return identifierSystem; }
        public void setIdentifierSystem(String identifierSystem) { this.identifierSystem = identifierSystem; }
        public String getEncryptedValue() { return encryptedValue; }
        public void setEncryptedValue(String encryptedValue) {
            this.encryptedValue = encryptedValue;
            this.blindIndex = PiiVaultCrypto.blindIndex(encryptedValue);
        }
        public String getBlindIndex() { return blindIndex; }
        public String getBiometricTemplateHash() { return biometricTemplateHash; }
        public void setBiometricTemplateHash(String biometricTemplateHash) { this.biometricTemplateHash = biometricTemplateHash; }
    }

    @Entity
    @Table(name = "hc_patient_demographics")
    public static class PatientDemographicsEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "address_line", length = 250)
        private String addressLine;

        @Column(name = "city_or_village", length = 120)
        private String cityOrVillage;

        @Column(name = "region_or_state", length = 120)
        private String regionOrState;

        @Column(name = "country", length = 64)
        private String country;

        @Column(name = "emergency_contact_name", length = 160)
        private String emergencyContactName;

        @Column(name = "emergency_contact_phone", length = 64)
        private String emergencyContactPhone;

        @Column(name = "emergency_relationship", length = 40)
        private String emergencyRelationship;

        public UUID getId() { return id; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getAddressLine() { return addressLine; }
        public void setAddressLine(String addressLine) { this.addressLine = addressLine; }
        public String getCityOrVillage() { return cityOrVillage; }
        public void setCityOrVillage(String cityOrVillage) { this.cityOrVillage = cityOrVillage; }
        public String getRegionOrState() { return regionOrState; }
        public void setRegionOrState(String regionOrState) { this.regionOrState = regionOrState; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getEmergencyContactName() { return emergencyContactName; }
        public void setEmergencyContactName(String emergencyContactName) { this.emergencyContactName = emergencyContactName; }
        public String getEmergencyContactPhone() { return emergencyContactPhone; }
        public void setEmergencyContactPhone(String emergencyContactPhone) { this.emergencyContactPhone = emergencyContactPhone; }
        public String getEmergencyRelationship() { return emergencyRelationship; }
        public void setEmergencyRelationship(String emergencyRelationship) { this.emergencyRelationship = emergencyRelationship; }
    }

    /** Dialect-independent phonetic blocking key computed in pure Java at write time. */
    @Entity
    @Table(name = "hc_patient_phonetics")
    public static class PatientPhoneticIndexEntity {
        @Id
        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "family_metaphone", nullable = false, length = 16)
        private String familyMetaphone;

        @Column(name = "given_metaphone", nullable = false, length = 16)
        private String givenMetaphone;

        @Column(name = "birth_year", nullable = false)
        private int birthYear;

        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getFamilyMetaphone() { return familyMetaphone; }
        public void setFamilyMetaphone(String familyMetaphone) { this.familyMetaphone = familyMetaphone; }
        public String getGivenMetaphone() { return givenMetaphone; }
        public void setGivenMetaphone(String givenMetaphone) { this.givenMetaphone = givenMetaphone; }
        public int getBirthYear() { return birthYear; }
        public void setBirthYear(int birthYear) { this.birthYear = birthYear; }
    }

    @Entity
    @Table(name = "hc_patient_merges")
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PatientMergeHistoryEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "source_patient_id", nullable = false)
        private UUID sourcePatientId;

        @Column(name = "surviving_patient_id", nullable = false)
        private UUID survivingPatientId;

        @Column(name = "merged_by", nullable = false, length = 120)
        private String mergedBy;

        @Column(name = "reason", length = 300)
        private String reason;

        @Column(name = "reversed", nullable = false)
        private boolean reversed = false;

        @Column(name = "merged_at", nullable = false)
        private Instant mergedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSourcePatientId() { return sourcePatientId; }
        public void setSourcePatientId(UUID sourcePatientId) { this.sourcePatientId = sourcePatientId; }
        public UUID getSurvivingPatientId() { return survivingPatientId; }
        public void setSurvivingPatientId(UUID survivingPatientId) { this.survivingPatientId = survivingPatientId; }
        public String getMergedBy() { return mergedBy; }
        public void setMergedBy(String mergedBy) { this.mergedBy = mergedBy; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public boolean isReversed() { return reversed; }
        public void setReversed(boolean reversed) { this.reversed = reversed; }
        public Instant getMergedAt() { return mergedAt; }
    }
}
