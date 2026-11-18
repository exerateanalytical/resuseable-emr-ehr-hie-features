/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 Practitioner Resource DTO
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.opeshealth.healthcore.dto.FhirCommonTypes.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Standard HL7 FHIR R4 {@code Practitioner} DTO complementing {@code PatientAggregateEntity}
 * with bidirectional conversion to HAPI FHIR R4 {@link Practitioner}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PractitionerDto(
        String resourceType,
        String id,
        FhirMetaDto meta,
        List<FhirIdentifierDto> identifier,
        Boolean active,
        List<FhirHumanNameDto> name,
        List<FhirContactPointDto> telecom,
        List<FhirAddressDto> address,
        String gender,
        LocalDate birthDate,
        List<QualificationDto> qualification,
        List<FhirCodeableConceptDto> communication
) {
    public PractitionerDto {
        if (resourceType == null || resourceType.isBlank()) {
            resourceType = "Practitioner";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QualificationDto(
            List<FhirIdentifierDto> identifier,
            FhirCodeableConceptDto code,
            FhirPeriodDto period,
            String issuerDisplay
    ) {}

    public Practitioner toFhirR4() {
        Practitioner p = new Practitioner();
        if (id != null && !id.isBlank()) {
            p.setId(id);
        }
        if (meta != null) {
            if (meta.versionId() != null) p.getMeta().setVersionId(meta.versionId());
            if (meta.lastUpdated() != null) p.getMeta().setLastUpdated(Date.from(meta.lastUpdated()));
            if (meta.profile() != null) meta.profile().forEach(p.getMeta()::addProfile);
        }
        if (identifier != null) {
            for (FhirIdentifierDto ident : identifier) {
                Identifier fi = p.addIdentifier().setSystem(ident.system()).setValue(ident.value());
                if (ident.use() != null && !ident.use().isBlank()) {
                    fi.setUse(parseFhirEnum(ident.use(), Identifier.IdentifierUse::fromCode));
                }
                if (ident.assignerDisplay() != null) fi.setAssigner(new Reference().setDisplay(ident.assignerDisplay()));
                if (ident.type() != null && ident.type().coding() != null) {
                    ident.type().coding().forEach(c -> fi.getType().addCoding()
                            .setSystem(c.system()).setCode(c.code()).setDisplay(c.display()));
                }
            }
        }
        if (active != null) {
            p.setActive(active);
        }
        if (name != null) {
            for (FhirHumanNameDto hn : name) {
                HumanName target = p.addName();
                if (hn.use() != null && !hn.use().isBlank()) {
                    target.setUse(parseFhirEnum(hn.use(), HumanName.NameUse::fromCode));
                }
                if (hn.text() != null) target.setText(hn.text());
                if (hn.family() != null) target.setFamily(hn.family());
                if (hn.given() != null) hn.given().forEach(target::addGiven);
                if (hn.prefix() != null) hn.prefix().forEach(target::addPrefix);
                if (hn.suffix() != null) hn.suffix().forEach(target::addSuffix);
            }
        }
        if (telecom != null) {
            for (FhirContactPointDto cp : telecom) {
                ContactPoint target = p.addTelecom().setValue(cp.value());
                if (cp.system() != null && !cp.system().isBlank()) {
                    target.setSystem(parseFhirEnum(cp.system(), ContactPoint.ContactPointSystem::fromCode));
                }
                if (cp.use() != null && !cp.use().isBlank()) {
                    target.setUse(parseFhirEnum(cp.use(), ContactPoint.ContactPointUse::fromCode));
                }
                if (cp.rank() != null) target.setRank(cp.rank());
            }
        }
        if (address != null) {
            for (FhirAddressDto a : address) {
                Address target = p.addAddress();
                if (a.use() != null && !a.use().isBlank()) {
                    target.setUse(parseFhirEnum(a.use(), Address.AddressUse::fromCode));
                }
                if (a.line() != null) a.line().forEach(target::addLine);
                if (a.city() != null) target.setCity(a.city());
                if (a.district() != null) target.setDistrict(a.district());
                if (a.state() != null) target.setState(a.state());
                if (a.postalCode() != null) target.setPostalCode(a.postalCode());
                if (a.country() != null) target.setCountry(a.country());
            }
        }
        if (gender != null && !gender.isBlank()) {
            p.setGender(parseFhirEnum(gender, AdministrativeGender::fromCode));
        }
        if (birthDate != null) {
            p.setBirthDate(Date.from(birthDate.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (qualification != null) {
            for (QualificationDto q : qualification) {
                Practitioner.PractitionerQualificationComponent comp = p.addQualification();
                if (q.code() != null && q.code().coding() != null) {
                    q.code().coding().forEach(c -> comp.getCode().addCoding()
                            .setSystem(c.system()).setCode(c.code()).setDisplay(c.display()));
                    if (q.code().text() != null) comp.getCode().setText(q.code().text());
                }
                if (q.period() != null) {
                    if (q.period().start() != null) comp.getPeriod().setStart(Date.from(q.period().start()));
                    if (q.period().end() != null) comp.getPeriod().setEnd(Date.from(q.period().end()));
                }
                if (q.issuerDisplay() != null) {
                    comp.setIssuer(new Reference().setDisplay(q.issuerDisplay()));
                }
                if (q.identifier() != null) {
                    q.identifier().forEach(ident -> comp.addIdentifier()
                            .setSystem(ident.system()).setValue(ident.value()));
                }
            }
        }
        if (communication != null) {
            for (FhirCodeableConceptDto lang : communication) {
                CodeableConcept cc = p.addCommunication();
                if (lang.text() != null) cc.setText(lang.text());
                if (lang.coding() != null) {
                    lang.coding().forEach(c -> cc.addCoding()
                            .setSystem(c.system()).setCode(c.code()).setDisplay(c.display()));
                }
            }
        }
        return p;
    }

    public static PractitionerDto fromFhirR4(Practitioner p) {
        List<FhirIdentifierDto> idents = p.getIdentifier().stream()
                .map(i -> new FhirIdentifierDto(
                        i.hasUse() ? i.getUse().toCode() : null,
                        null,
                        i.getSystem(),
                        i.getValue(),
                        i.hasAssigner() ? i.getAssigner().getDisplay() : null))
                .toList();

        List<FhirHumanNameDto> names = p.getName().stream()
                .map(n -> new FhirHumanNameDto(
                        n.hasUse() ? n.getUse().toCode() : null,
                        n.getText(),
                        n.getFamily(),
                        n.getGiven().stream().map(StringType::getValue).toList(),
                        n.getPrefix().stream().map(StringType::getValue).toList(),
                        n.getSuffix().stream().map(StringType::getValue).toList()))
                .toList();

        List<FhirContactPointDto> telecoms = p.getTelecom().stream()
                .map(t -> new FhirContactPointDto(
                        t.hasSystem() ? t.getSystem().toCode() : null,
                        t.getValue(),
                        t.hasUse() ? t.getUse().toCode() : null,
                        t.hasRank() ? t.getRank() : null))
                .toList();

        List<FhirAddressDto> addresses = p.getAddress().stream()
                .map(a -> new FhirAddressDto(
                        a.hasUse() ? a.getUse().toCode() : null,
                        a.getLine().stream().map(StringType::getValue).toList(),
                        a.getCity(), a.getDistrict(), a.getState(), a.getPostalCode(), a.getCountry()))
                .toList();

        List<QualificationDto> quals = new ArrayList<>();
        for (Practitioner.PractitionerQualificationComponent q : p.getQualification()) {
            List<FhirIdentifierDto> qIdents = q.getIdentifier().stream()
                    .map(i -> new FhirIdentifierDto(
                            i.hasUse() ? i.getUse().toCode() : null, null, i.getSystem(), i.getValue(), null))
                    .toList();
            FhirCodeableConceptDto codeDto = q.hasCode()
                    ? new FhirCodeableConceptDto(
                            q.getCode().getCoding().stream()
                                    .map(c -> new FhirCodingDto(c.getSystem(), c.getCode(), c.getDisplay()))
                                    .toList(),
                            q.getCode().getText())
                    : null;
            FhirPeriodDto period = q.hasPeriod()
                    ? new FhirPeriodDto(
                            q.getPeriod().hasStart() ? q.getPeriod().getStart().toInstant() : null,
                            q.getPeriod().hasEnd() ? q.getPeriod().getEnd().toInstant() : null)
                    : null;
            quals.add(new QualificationDto(qIdents, codeDto, period, q.hasIssuer() ? q.getIssuer().getDisplay() : null));
        }

        List<FhirCodeableConceptDto> langs = p.getCommunication().stream()
                .map(c -> new FhirCodeableConceptDto(
                        c.getCoding().stream()
                                .map(cd -> new FhirCodingDto(cd.getSystem(), cd.getCode(), cd.getDisplay()))
                                .toList(),
                        c.getText()))
                .toList();

        LocalDate dob = p.hasBirthDate()
                ? p.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                : null;

        FhirMetaDto metaDto = p.hasMeta()
                ? new FhirMetaDto(
                        p.getMeta().getVersionId(),
                        p.getMeta().hasLastUpdated() ? p.getMeta().getLastUpdated().toInstant() : null,
                        p.getMeta().getProfile().isEmpty()
                                ? null
                                : p.getMeta().getProfile().stream().map(CanonicalType::getValue).toList())
                : null;

        return new PractitionerDto(
                "Practitioner",
                p.hasIdElement() ? p.getIdElement().getIdPart() : null,
                metaDto,
                idents,
                p.hasActive() ? p.getActive() : null,
                names,
                telecoms,
                addresses,
                p.hasGender() ? p.getGender().toCode() : null,
                dob,
                quals,
                langs
        );
    }

    @FunctionalInterface
    private interface FhirEnumParser<T> {
        T parse(String code) throws Exception;
    }

    private static <T> T parseFhirEnum(String code, FhirEnumParser<T> parser) {
        try {
            return parser.parse(code.toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR Practitioner enum code: " + code, ex);
        }
    }
}
