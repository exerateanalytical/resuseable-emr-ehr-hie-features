/*
 * =============================================================================
 * Opes HealthCore SDK — Observation JPA Entity (Server Module)
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA aggregate backing HL7 FHIR R4 {@code Observation} resources (vital signs,
 * laboratory panels, clinical assessments) with multi-facility tenant filtering
 * and optimistic locking ({@link Version}).
 */
@Entity
@Table(name = "hc_observations")
@BatchSize(size = 32)
@Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
public class ObservationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7Generator.nextUuid();

    @Column(name = "facility_id", nullable = false)
    private UUID facilityId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "encounter_id")
    private UUID encounterId;

    @Column(name = "practitioner_id")
    private UUID practitionerId;

    @Column(name = "identifier_system", length = 120)
    private String identifierSystem;

    @Column(name = "identifier_value", length = 120)
    private String identifierValue;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "final";

    @Column(name = "category_system", length = 160)
    private String categorySystem;

    @Column(name = "category_code", length = 64)
    private String categoryCode;

    @Column(name = "category_display", length = 160)
    private String categoryDisplay;

    @Column(name = "code_system", length = 160)
    private String codeSystem;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "code_display", length = 200)
    private String codeDisplay;

    @Column(name = "code_text", length = 250)
    private String codeText;

    @Column(name = "subject_reference", nullable = false, length = 160)
    private String subjectReference;

    @Column(name = "encounter_reference", length = 160)
    private String encounterReference;

    @Column(name = "performer_reference", length = 160)
    private String performerReference;

    @Column(name = "performer_display", length = 160)
    private String performerDisplay;

    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "value_type", length = 32)
    private String valueType;

    @Column(name = "value_quantity", precision = 18, scale = 4)
    private BigDecimal valueQuantity;

    @Column(name = "value_unit", length = 48)
    private String valueUnit;

    @Column(name = "value_unit_system", length = 120)
    private String valueUnitSystem;

    @Column(name = "value_unit_code", length = 48)
    private String valueUnitCode;

    @Lob
    @Column(name = "value_string")
    private String valueString;

    @Column(name = "value_boolean")
    private Boolean valueBoolean;

    @Column(name = "value_code_system", length = 160)
    private String valueCodeSystem;

    @Column(name = "value_code", length = 64)
    private String valueCode;

    @Column(name = "value_code_display", length = 200)
    private String valueCodeDisplay;

    @Column(name = "interpretation_code", length = 32)
    private String interpretationCode;

    @Column(name = "interpretation_display", length = 120)
    private String interpretationDisplay;

    @Column(name = "ref_range_low", precision = 18, scale = 4)
    private BigDecimal refRangeLow;

    @Column(name = "ref_range_high", precision = 18, scale = 4)
    private BigDecimal refRangeHigh;

    @Column(name = "ref_range_text", length = 160)
    private String refRangeText;

    @Lob
    @Column(name = "note_text")
    private String noteText;

    @Lob
    @Column(name = "components_json")
    private String componentsJson;

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
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
    public UUID getPractitionerId() { return practitionerId; }
    public void setPractitionerId(UUID practitionerId) { this.practitionerId = practitionerId; }
    public String getIdentifierSystem() { return identifierSystem; }
    public void setIdentifierSystem(String identifierSystem) { this.identifierSystem = identifierSystem; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String identifierValue) { this.identifierValue = identifierValue; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCategorySystem() { return categorySystem; }
    public void setCategorySystem(String categorySystem) { this.categorySystem = categorySystem; }
    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }
    public String getCategoryDisplay() { return categoryDisplay; }
    public void setCategoryDisplay(String categoryDisplay) { this.categoryDisplay = categoryDisplay; }
    public String getCodeSystem() { return codeSystem; }
    public void setCodeSystem(String codeSystem) { this.codeSystem = codeSystem; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCodeDisplay() { return codeDisplay; }
    public void setCodeDisplay(String codeDisplay) { this.codeDisplay = codeDisplay; }
    public String getCodeText() { return codeText; }
    public void setCodeText(String codeText) { this.codeText = codeText; }
    public String getSubjectReference() { return subjectReference; }
    public void setSubjectReference(String subjectReference) { this.subjectReference = subjectReference; }
    public String getEncounterReference() { return encounterReference; }
    public void setEncounterReference(String encounterReference) { this.encounterReference = encounterReference; }
    public String getPerformerReference() { return performerReference; }
    public void setPerformerReference(String performerReference) { this.performerReference = performerReference; }
    public String getPerformerDisplay() { return performerDisplay; }
    public void setPerformerDisplay(String performerDisplay) { this.performerDisplay = performerDisplay; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public BigDecimal getValueQuantity() { return valueQuantity; }
    public void setValueQuantity(BigDecimal valueQuantity) { this.valueQuantity = valueQuantity; }
    public String getValueUnit() { return valueUnit; }
    public void setValueUnit(String valueUnit) { this.valueUnit = valueUnit; }
    public String getValueUnitSystem() { return valueUnitSystem; }
    public void setValueUnitSystem(String valueUnitSystem) { this.valueUnitSystem = valueUnitSystem; }
    public String getValueUnitCode() { return valueUnitCode; }
    public void setValueUnitCode(String valueUnitCode) { this.valueUnitCode = valueUnitCode; }
    public String getValueString() { return valueString; }
    public void setValueString(String valueString) { this.valueString = valueString; }
    public Boolean getValueBoolean() { return valueBoolean; }
    public void setValueBoolean(Boolean valueBoolean) { this.valueBoolean = valueBoolean; }
    public String getValueCodeSystem() { return valueCodeSystem; }
    public void setValueCodeSystem(String valueCodeSystem) { this.valueCodeSystem = valueCodeSystem; }
    public String getValueCode() { return valueCode; }
    public void setValueCode(String valueCode) { this.valueCode = valueCode; }
    public String getValueCodeDisplay() { return valueCodeDisplay; }
    public void setValueCodeDisplay(String valueCodeDisplay) { this.valueCodeDisplay = valueCodeDisplay; }
    public String getInterpretationCode() { return interpretationCode; }
    public void setInterpretationCode(String interpretationCode) { this.interpretationCode = interpretationCode; }
    public String getInterpretationDisplay() { return interpretationDisplay; }
    public void setInterpretationDisplay(String interpretationDisplay) { this.interpretationDisplay = interpretationDisplay; }
    public BigDecimal getRefRangeLow() { return refRangeLow; }
    public void setRefRangeLow(BigDecimal refRangeLow) { this.refRangeLow = refRangeLow; }
    public BigDecimal getRefRangeHigh() { return refRangeHigh; }
    public void setRefRangeHigh(BigDecimal refRangeHigh) { this.refRangeHigh = refRangeHigh; }
    public String getRefRangeText() { return refRangeText; }
    public void setRefRangeText(String refRangeText) { this.refRangeText = refRangeText; }
    public String getNoteText() { return noteText; }
    public void setNoteText(String noteText) { this.noteText = noteText; }
    public String getComponentsJson() { return componentsJson; }
    public void setComponentsJson(String componentsJson) { this.componentsJson = componentsJson; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
