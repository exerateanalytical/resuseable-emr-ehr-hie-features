/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 Observation Resource DTO
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opeshealth.healthcore.dto.FhirCommonTypes.*;
import com.opeshealth.healthcore.emr.domain.EmrEntities.VitalSignsEntity;
import org.hl7.fhir.r4.model.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Standard HL7 FHIR R4 {@code Observation} DTO complementing {@code PatientAggregateEntity}
 * for LOINC vital signs, laboratory results, NEWS2 panels, and clinical measurements.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationDto(
        String resourceType,
        String id,
        FhirMetaDto meta,
        List<FhirIdentifierDto> identifier,
        String status,
        List<FhirCodeableConceptDto> category,
        FhirCodeableConceptDto code,
        FhirReferenceDto subject,
        FhirReferenceDto encounter,
        Instant effectiveDateTime,
        Instant issued,
        List<FhirReferenceDto> performer,
        FhirQuantityDto valueQuantity,
        String valueString,
        Boolean valueBoolean,
        FhirCodeableConceptDto valueCodeableConcept,
        List<FhirCodeableConceptDto> interpretation,
        List<FhirReferenceRangeDto> referenceRange,
        List<String> note,
        List<ComponentDto> component
) {
    public ObservationDto {
        if (resourceType == null || resourceType.isBlank()) {
            resourceType = "Observation";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComponentDto(
            FhirCodeableConceptDto code,
            FhirQuantityDto valueQuantity,
            String valueString,
            FhirCodeableConceptDto valueCodeableConcept,
            List<FhirCodeableConceptDto> interpretation,
            List<FhirReferenceRangeDto> referenceRange
    ) {}

    public Observation toFhirR4() {
        Observation obs = new Observation();
        if (id != null && !id.isBlank()) {
            obs.setId(id);
        }
        if (meta != null) {
            if (meta.versionId() != null) obs.getMeta().setVersionId(meta.versionId());
            if (meta.lastUpdated() != null) obs.getMeta().setLastUpdated(Date.from(meta.lastUpdated()));
        }
        if (identifier != null) {
            for (FhirIdentifierDto ident : identifier) {
                Identifier fi = obs.addIdentifier().setSystem(ident.system()).setValue(ident.value());
                if (ident.use() != null && !ident.use().isBlank()) {
                    fi.setUse(parseFhirEnum(ident.use(), Identifier.IdentifierUse::fromCode));
                }
            }
        }
        if (status != null && !status.isBlank()) {
            obs.setStatus(parseFhirEnum(status, Observation.ObservationStatus::fromCode));
        }
        if (category != null) {
            category.forEach(c -> obs.addCategory(toCodeableConcept(c)));
        }
        if (code != null) {
            obs.setCode(toCodeableConcept(code));
        }
        if (subject != null) {
            obs.setSubject(new Reference(subject.reference()).setDisplay(subject.display()));
        }
        if (encounter != null) {
            obs.setEncounter(new Reference(encounter.reference()).setDisplay(encounter.display()));
        }
        if (effectiveDateTime != null) {
            obs.setEffective(new DateTimeType(Date.from(effectiveDateTime)));
        }
        if (issued != null) {
            obs.setIssued(Date.from(issued));
        }
        if (performer != null) {
            performer.forEach(p -> obs.addPerformer(new Reference(p.reference()).setDisplay(p.display())));
        }
        if (valueQuantity != null) {
            obs.setValue(toQuantity(valueQuantity));
        } else if (valueString != null) {
            obs.setValue(new StringType(valueString));
        } else if (valueBoolean != null) {
            obs.setValue(new BooleanType(valueBoolean));
        } else if (valueCodeableConcept != null) {
            obs.setValue(toCodeableConcept(valueCodeableConcept));
        }
        if (interpretation != null) {
            interpretation.forEach(i -> obs.addInterpretation(toCodeableConcept(i)));
        }
        if (referenceRange != null) {
            for (FhirReferenceRangeDto rr : referenceRange) {
                Observation.ObservationReferenceRangeComponent comp = obs.addReferenceRange();
                if (rr.low() != null) comp.setLow(toQuantity(rr.low()));
                if (rr.high() != null) comp.setHigh(toQuantity(rr.high()));
                if (rr.text() != null) comp.setText(rr.text());
            }
        }
        if (note != null) {
            note.forEach(n -> obs.addNote().setText(n));
        }
        if (component != null) {
            for (ComponentDto c : component) {
                Observation.ObservationComponentComponent comp = obs.addComponent();
                if (c.code() != null) comp.setCode(toCodeableConcept(c.code()));
                if (c.valueQuantity() != null) comp.setValue(toQuantity(c.valueQuantity()));
                else if (c.valueString() != null) comp.setValue(new StringType(c.valueString()));
                else if (c.valueCodeableConcept() != null) comp.setValue(toCodeableConcept(c.valueCodeableConcept()));
                if (c.interpretation() != null) c.interpretation().forEach(i -> comp.addInterpretation(toCodeableConcept(i)));
                if (c.referenceRange() != null) {
                    for (FhirReferenceRangeDto rr : c.referenceRange()) {
                        Observation.ObservationReferenceRangeComponent range = comp.addReferenceRange();
                        if (rr.low() != null) range.setLow(toQuantity(rr.low()));
                        if (rr.high() != null) range.setHigh(toQuantity(rr.high()));
                        if (rr.text() != null) range.setText(rr.text());
                    }
                }
            }
        }
        return obs;
    }

    public static ObservationDto fromFhirR4(Observation o) {
        FhirQuantityDto valQty = o.hasValueQuantity() ? fromQuantity(o.getValueQuantity()) : null;
        String valStr = o.hasValueStringType() ? o.getValueStringType().getValue() : null;
        Boolean valBool = o.hasValueBooleanType() ? o.getValueBooleanType().booleanValue() : null;
        FhirCodeableConceptDto valCc = o.hasValueCodeableConcept() ? fromCodeableConcept(o.getValueCodeableConcept()) : null;

        Instant effective = o.hasEffectiveDateTimeType() && o.getEffectiveDateTimeType().hasValue()
                ? o.getEffectiveDateTimeType().getValue().toInstant()
                : null;

        List<ComponentDto> comps = new ArrayList<>();
        for (Observation.ObservationComponentComponent c : o.getComponent()) {
            comps.add(new ComponentDto(
                    c.hasCode() ? fromCodeableConcept(c.getCode()) : null,
                    c.hasValueQuantity() ? fromQuantity(c.getValueQuantity()) : null,
                    c.hasValueStringType() ? c.getValueStringType().getValue() : null,
                    c.hasValueCodeableConcept() ? fromCodeableConcept(c.getValueCodeableConcept()) : null,
                    c.getInterpretation().stream().map(ObservationDto::fromCodeableConcept).toList(),
                    c.getReferenceRange().stream()
                            .map(rr -> new FhirReferenceRangeDto(
                                    rr.hasLow() ? fromQuantity(rr.getLow()) : null,
                                    rr.hasHigh() ? fromQuantity(rr.getHigh()) : null,
                                    rr.getText()))
                            .toList()
            ));
        }

        FhirMetaDto metaDto = o.hasMeta()
                ? new FhirMetaDto(o.getMeta().getVersionId(),
                        o.getMeta().hasLastUpdated() ? o.getMeta().getLastUpdated().toInstant() : null, null)
                : null;

        return new ObservationDto(
                "Observation",
                o.hasIdElement() ? o.getIdElement().getIdPart() : null,
                metaDto,
                o.getIdentifier().stream()
                        .map(i -> new FhirIdentifierDto(i.hasUse() ? i.getUse().toCode() : null, null, i.getSystem(), i.getValue(), null))
                        .toList(),
                o.hasStatus() ? o.getStatus().toCode() : null,
                o.getCategory().stream().map(ObservationDto::fromCodeableConcept).toList(),
                o.hasCode() ? fromCodeableConcept(o.getCode()) : null,
                o.hasSubject() ? new FhirReferenceDto(o.getSubject().getReference(), "Patient", null, o.getSubject().getDisplay()) : null,
                o.hasEncounter() ? new FhirReferenceDto(o.getEncounter().getReference(), "Encounter", null, o.getEncounter().getDisplay()) : null,
                effective,
                o.hasIssued() ? o.getIssued().toInstant() : null,
                o.getPerformer().stream()
                        .map(p -> new FhirReferenceDto(p.getReference(), null, null, p.getDisplay()))
                        .toList(),
                valQty, valStr, valBool, valCc,
                o.getInterpretation().stream().map(ObservationDto::fromCodeableConcept).toList(),
                o.getReferenceRange().stream()
                        .map(rr -> new FhirReferenceRangeDto(
                                rr.hasLow() ? fromQuantity(rr.getLow()) : null,
                                rr.hasHigh() ? fromQuantity(rr.getHigh()) : null,
                                rr.getText()))
                        .toList(),
                o.getNote().stream().map(org.hl7.fhir.r4.model.Annotation::getText).toList(),
                comps
        );
    }

    /** Maps {@link VitalSignsEntity} to standard LOINC-coded HL7 FHIR R4 {@link ObservationDto} entries. */
    public static List<ObservationDto> vitalSignsToObservations(VitalSignsEntity vitals) {
        List<ObservationDto> list = new ArrayList<>();
        FhirCodeableConceptDto vitalCat = FhirCodeableConceptDto.of(
                "http://terminology.hl7.org/CodeSystem/observation-category", "vital-signs", "Vital Signs");
        FhirReferenceDto patientRef = FhirReferenceDto.toResource("Patient", vitals.getPatientId().toString(), null);
        FhirReferenceDto encRef = FhirReferenceDto.toResource("Encounter", vitals.getEncounterId().toString(), null);
        Instant ts = vitals.getRecordedAt();

        if (vitals.getTempCelsius() != null) {
            list.add(singleVital(vitals.getId() + "-temp", vitalCat,
                    "8310-5", "Body temperature", patientRef, encRef, ts,
                    FhirQuantityDto.ucum(vitals.getTempCelsius(), "Cel", "Cel")));
        }
        if (vitals.getHeartRateBpm() != null) {
            list.add(singleVital(vitals.getId() + "-hr", vitalCat,
                    "8867-4", "Heart rate", patientRef, encRef, ts,
                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getHeartRateBpm()), "beats/minute", "/min")));
        }
        if (vitals.getRespRatePerMin() != null) {
            list.add(singleVital(vitals.getId() + "-rr", vitalCat,
                    "9279-1", "Respiratory rate", patientRef, encRef, ts,
                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getRespRatePerMin()), "breaths/minute", "/min")));
        }
        if (vitals.getSpo2Percent() != null) {
            list.add(singleVital(vitals.getId() + "-spo2", vitalCat,
                    "2708-6", "Oxygen saturation in Arterial blood", patientRef, encRef, ts,
                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getSpo2Percent()), "%", "%")));
        }
        if (vitals.getSystolicBp() != null && vitals.getDiastolicBp() != null) {
            list.add(new ObservationDto(
                    "Observation", vitals.getId() + "-bp", null, List.of(), "final", List.of(vitalCat),
                    FhirCodeableConceptDto.of("http://loinc.org", "85354-9", "Blood pressure panel"),
                    patientRef, encRef, ts, ts, List.of(),
                    null, null, null, null, List.of(), List.of(), List.of(),
                    List.of(
                            new ComponentDto(
                                    FhirCodeableConceptDto.of("http://loinc.org", "8480-6", "Systolic blood pressure"),
                                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getSystolicBp()), "mmHg", "mm[Hg]"),
                                    null, null, List.of(), List.of()),
                            new ComponentDto(
                                    FhirCodeableConceptDto.of("http://loinc.org", "8462-4", "Diastolic blood pressure"),
                                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getDiastolicBp()), "mmHg", "mm[Hg]"),
                                    null, null, List.of(), List.of())
                    )));
        }
        if (vitals.getNews2Score() != null) {
            list.add(new ObservationDto(
                    "Observation", vitals.getId() + "-news2", null, List.of(), "final", List.of(vitalCat),
                    FhirCodeableConceptDto.of("http://snomed.info/sct", "1104051000000101", "Royal College of Physicians NEWS2 total score"),
                    patientRef, encRef, ts, ts, List.of(),
                    FhirQuantityDto.ucum(BigDecimal.valueOf(vitals.getNews2Score()), "{score}", "{score}"),
                    null, null, null, List.of(), List.of(), List.of(), List.of()));
        }
        return list;
    }

    private static ObservationDto singleVital(
            String id, FhirCodeableConceptDto category, String loincCode, String display,
            FhirReferenceDto patientRef, FhirReferenceDto encRef, Instant ts, FhirQuantityDto qty) {
        return new ObservationDto(
                "Observation", id, null, List.of(), "final", List.of(category),
                FhirCodeableConceptDto.of("http://loinc.org", loincCode, display),
                patientRef, encRef, ts, ts, List.of(),
                qty, null, null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static CodeableConcept toCodeableConcept(FhirCodeableConceptDto dto) {
        CodeableConcept cc = new CodeableConcept();
        if (dto.text() != null) cc.setText(dto.text());
        if (dto.coding() != null) {
            dto.coding().forEach(c -> cc.addCoding().setSystem(c.system()).setCode(c.code()).setDisplay(c.display()));
        }
        return cc;
    }

    private static FhirCodeableConceptDto fromCodeableConcept(CodeableConcept cc) {
        return new FhirCodeableConceptDto(
                cc.getCoding().stream().map(c -> new FhirCodingDto(c.getSystem(), c.getCode(), c.getDisplay())).toList(),
                cc.getText());
    }

    private static Quantity toQuantity(FhirQuantityDto q) {
        Quantity out = new Quantity();
        if (q.value() != null) out.setValue(q.value());
        if (q.unit() != null) out.setUnit(q.unit());
        if (q.system() != null) out.setSystem(q.system());
        if (q.code() != null) out.setCode(q.code());
        return out;
    }

    private static FhirQuantityDto fromQuantity(Quantity q) {
        return new FhirQuantityDto(q.getValue(), q.getUnit(), q.getSystem(), q.getCode());
    }

    @FunctionalInterface
    private interface FhirEnumParser<T> {
        T parse(String code) throws Exception;
    }

    private static <T> T parseFhirEnum(String code, FhirEnumParser<T> parser) {
        try {
            return parser.parse(code.toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR Observation enum code: " + code, ex);
        }
    }
}
