/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 Encounter Resource DTO
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.opeshealth.healthcore.dto.FhirCommonTypes.*;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Reference;

import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Standard HL7 FHIR R4 {@code Encounter} DTO complementing {@code PatientAggregateEntity}
 * and bridging {@link EncounterEntity} to HAPI FHIR R4 {@link Encounter}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EncounterDto(
        String resourceType,
        String id,
        FhirMetaDto meta,
        List<FhirIdentifierDto> identifier,
        String status,
        @JsonProperty("class") FhirCodingDto fhirClass,
        List<FhirCodeableConceptDto> type,
        FhirCodeableConceptDto priority,
        FhirReferenceDto subject,
        List<ParticipantDto> participant,
        FhirPeriodDto period,
        List<FhirCodeableConceptDto> reasonCode,
        List<DiagnosisDto> diagnosis,
        HospitalizationDto hospitalization,
        List<LocationDto> location,
        FhirReferenceDto serviceProvider
) {
    public EncounterDto {
        if (resourceType == null || resourceType.isBlank()) {
            resourceType = "Encounter";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParticipantDto(
            List<FhirCodeableConceptDto> type,
            FhirReferenceDto individual
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DiagnosisDto(
            FhirReferenceDto condition,
            FhirCodeableConceptDto use,
            Integer rank
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HospitalizationDto(
            FhirCodeableConceptDto admitSource,
            FhirCodeableConceptDto dischargeDisposition
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocationDto(
            FhirReferenceDto location,
            String status
    ) {}

    public Encounter toFhirR4() {
        Encounter enc = new Encounter();
        if (id != null && !id.isBlank()) {
            enc.setId(id);
        }
        if (meta != null) {
            if (meta.versionId() != null) enc.getMeta().setVersionId(meta.versionId());
            if (meta.lastUpdated() != null) enc.getMeta().setLastUpdated(Date.from(meta.lastUpdated()));
        }
        if (identifier != null) {
            for (FhirIdentifierDto ident : identifier) {
                Identifier fi = enc.addIdentifier().setSystem(ident.system()).setValue(ident.value());
                if (ident.use() != null && !ident.use().isBlank()) {
                    fi.setUse(parseFhirEnum(ident.use(), Identifier.IdentifierUse::fromCode));
                }
            }
        }
        if (status != null && !status.isBlank()) {
            enc.setStatus(parseFhirEnum(status, Encounter.EncounterStatus::fromCode));
        }
        if (fhirClass != null) {
            enc.setClass_(new Coding()
                    .setSystem(fhirClass.system() != null ? fhirClass.system() : "http://terminology.hl7.org/CodeSystem/v3-ActCode")
                    .setCode(fhirClass.code())
                    .setDisplay(fhirClass.display()));
        }
        if (type != null) {
            type.forEach(t -> enc.addType(toCodeableConcept(t)));
        }
        if (priority != null) {
            enc.setPriority(toCodeableConcept(priority));
        }
        if (subject != null) {
            enc.setSubject(new Reference(subject.reference()).setDisplay(subject.display()));
        }
        if (participant != null) {
            for (ParticipantDto pt : participant) {
                Encounter.EncounterParticipantComponent comp = enc.addParticipant();
                if (pt.type() != null) pt.type().forEach(t -> comp.addType(toCodeableConcept(t)));
                if (pt.individual() != null) {
                    comp.setIndividual(new Reference(pt.individual().reference()).setDisplay(pt.individual().display()));
                }
            }
        }
        if (period != null) {
            if (period.start() != null) enc.getPeriod().setStart(Date.from(period.start()));
            if (period.end() != null) enc.getPeriod().setEnd(Date.from(period.end()));
        }
        if (reasonCode != null) {
            reasonCode.forEach(r -> enc.addReasonCode(toCodeableConcept(r)));
        }
        if (diagnosis != null) {
            for (DiagnosisDto d : diagnosis) {
                Encounter.DiagnosisComponent comp = enc.addDiagnosis();
                if (d.condition() != null) {
                    comp.setCondition(new Reference(d.condition().reference()).setDisplay(d.condition().display()));
                }
                if (d.use() != null) comp.setUse(toCodeableConcept(d.use()));
                if (d.rank() != null) comp.setRank(d.rank());
            }
        }
        if (hospitalization != null) {
            if (hospitalization.admitSource() != null) {
                enc.getHospitalization().setAdmitSource(toCodeableConcept(hospitalization.admitSource()));
            }
            if (hospitalization.dischargeDisposition() != null) {
                enc.getHospitalization().setDischargeDisposition(toCodeableConcept(hospitalization.dischargeDisposition()));
            }
        }
        if (location != null) {
            for (LocationDto loc : location) {
                Encounter.EncounterLocationComponent comp = enc.addLocation();
                if (loc.location() != null) {
                    comp.setLocation(new Reference(loc.location().reference()).setDisplay(loc.location().display()));
                }
                if (loc.status() != null && !loc.status().isBlank()) {
                    comp.setStatus(parseFhirEnum(loc.status(), Encounter.EncounterLocationStatus::fromCode));
                }
            }
        }
        if (serviceProvider != null) {
            enc.setServiceProvider(new Reference(serviceProvider.reference()).setDisplay(serviceProvider.display()));
        }
        return enc;
    }

    public static EncounterDto fromFhirR4(Encounter e) {
        FhirCodingDto cls = e.hasClass_()
                ? new FhirCodingDto(e.getClass_().getSystem(), e.getClass_().getCode(), e.getClass_().getDisplay())
                : null;
        FhirPeriodDto per = e.hasPeriod()
                ? new FhirPeriodDto(
                        e.getPeriod().hasStart() ? e.getPeriod().getStart().toInstant() : null,
                        e.getPeriod().hasEnd() ? e.getPeriod().getEnd().toInstant() : null)
                : null;
        List<ParticipantDto> participants = e.getParticipant().stream()
                .map(pt -> new ParticipantDto(
                        pt.getType().stream().map(EncounterDto::fromCodeableConcept).toList(),
                        pt.hasIndividual()
                                ? new FhirReferenceDto(pt.getIndividual().getReference(), "Practitioner", null, pt.getIndividual().getDisplay())
                                : null))
                .toList();
        FhirMetaDto metaDto = e.hasMeta()
                ? new FhirMetaDto(e.getMeta().getVersionId(),
                        e.getMeta().hasLastUpdated() ? e.getMeta().getLastUpdated().toInstant() : null, null)
                : null;
        return new EncounterDto(
                "Encounter",
                e.hasIdElement() ? e.getIdElement().getIdPart() : null,
                metaDto,
                e.getIdentifier().stream()
                        .map(i -> new FhirIdentifierDto(i.hasUse() ? i.getUse().toCode() : null, null, i.getSystem(), i.getValue(), null))
                        .toList(),
                e.hasStatus() ? e.getStatus().toCode() : null,
                cls,
                e.getType().stream().map(EncounterDto::fromCodeableConcept).toList(),
                e.hasPriority() ? fromCodeableConcept(e.getPriority()) : null,
                e.hasSubject() ? new FhirReferenceDto(e.getSubject().getReference(), "Patient", null, e.getSubject().getDisplay()) : null,
                participants,
                per,
                e.getReasonCode().stream().map(EncounterDto::fromCodeableConcept).toList(),
                e.getDiagnosis().stream()
                        .map(d -> new DiagnosisDto(
                                d.hasCondition() ? new FhirReferenceDto(d.getCondition().getReference(), "Condition", null, d.getCondition().getDisplay()) : null,
                                d.hasUse() ? fromCodeableConcept(d.getUse()) : null,
                                d.hasRank() ? d.getRank() : null))
                        .toList(),
                e.hasHospitalization()
                        ? new HospitalizationDto(
                                e.getHospitalization().hasAdmitSource() ? fromCodeableConcept(e.getHospitalization().getAdmitSource()) : null,
                                e.getHospitalization().hasDischargeDisposition() ? fromCodeableConcept(e.getHospitalization().getDischargeDisposition()) : null)
                        : null,
                e.getLocation().stream()
                        .map(l -> new LocationDto(
                                l.hasLocation() ? new FhirReferenceDto(l.getLocation().getReference(), "Location", null, l.getLocation().getDisplay()) : null,
                                l.hasStatus() ? l.getStatus().toCode() : null))
                        .toList(),
                e.hasServiceProvider() ? new FhirReferenceDto(e.getServiceProvider().getReference(), "Organization", null, e.getServiceProvider().getDisplay()) : null
        );
    }

    public static EncounterDto fromEntity(EncounterEntity entity) {
        String rawStatus = entity.getStatus() != null ? entity.getStatus().toUpperCase(Locale.ROOT) : "PLANNED";
        String fhirStatus = switch (rawStatus) {
            case "IN_PROGRESS" -> "in-progress";
            case "ARRIVED" -> "arrived";
            case "TRIAGED" -> "triaged";
            case "FINISHED", "COMPLETED", "DISCHARGED" -> "finished";
            case "CANCELLED" -> "cancelled";
            default -> "planned";
        };
        List<ParticipantDto> participants = entity.getAttendingId() != null
                ? List.of(new ParticipantDto(
                        List.of(FhirCodeableConceptDto.of("http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "ATND", "attender")),
                        FhirReferenceDto.toResource("Practitioner", entity.getAttendingId(), entity.getAttendingId())))
                : List.of();
        List<FhirCodeableConceptDto> reasons = entity.getPrimaryIcd10() != null
                ? List.of(FhirCodeableConceptDto.of("http://hl7.org/fhir/sid/icd-10", entity.getPrimaryIcd10(), entity.getPrimaryIcd10()))
                : List.of();
        List<LocationDto> locs = entity.getDepartmentCode() != null
                ? List.of(new LocationDto(FhirReferenceDto.toResource("Location", entity.getDepartmentCode(), entity.getDepartmentCode()), "active"))
                : List.of();

        return new EncounterDto(
                "Encounter",
                entity.getId() != null ? entity.getId().toString() : null,
                null,
                List.of(),
                fhirStatus,
                entity.getEncounterClass() != null
                        ? new FhirCodingDto("http://terminology.hl7.org/CodeSystem/v3-ActCode", entity.getEncounterClass(), entity.getEncounterClass())
                        : null,
                List.of(),
                null,
                entity.getPatientId() != null ? FhirReferenceDto.toResource("Patient", entity.getPatientId().toString(), null) : null,
                participants,
                new FhirPeriodDto(entity.getStartedAt(), entity.getEndedAt()),
                reasons,
                List.of(),
                null,
                locs,
                entity.getFacilityId() != null ? FhirReferenceDto.toResource("Organization", entity.getFacilityId().toString(), null) : null
        );
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

    @FunctionalInterface
    private interface FhirEnumParser<T> {
        T parse(String code) throws Exception;
    }

    private static <T> T parseFhirEnum(String code, FhirEnumParser<T> parser) {
        try {
            return parser.parse(code.toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR Encounter enum code: " + code, ex);
        }
    }
}
