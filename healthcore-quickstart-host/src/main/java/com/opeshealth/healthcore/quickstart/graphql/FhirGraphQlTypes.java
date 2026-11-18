/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 GraphQL Type Projections & Converters
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.graphql;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.dto.EncounterDto;
import com.opeshealth.healthcore.dto.FhirCommonTypes.*;
import com.opeshealth.healthcore.dto.ObservationDto;
import com.opeshealth.healthcore.dto.PractitionerDto;
import com.opeshealth.healthcore.quickstart.service.EncounterService;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Immutable Java 25 GraphQL view records, filter/mutation input records, and
 * bidirectional HAPI FHIR R4 converters used by {@link FhirGraphQlController}.
 */
public final class FhirGraphQlTypes {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    private FhirGraphQlTypes() {}

    // -------------------------------------------------------------------------
    // GraphQL Shared Datatype Records (aligned 1:1 with fhir.graphqls)
    // -------------------------------------------------------------------------

    public record GqlMeta(String versionId, String lastUpdated, List<String> profile) {}

    public record GqlCoding(String system, String code, String display) {}

    public record GqlCodeableConcept(List<GqlCoding> coding, String text) {}

    public record GqlIdentifier(
            String use,
            GqlCodeableConcept type,
            String system,
            String value,
            String assignerDisplay) {}

    public record GqlHumanName(
            String use,
            String text,
            String family,
            List<String> given,
            List<String> prefix,
            List<String> suffix) {}

    public record GqlContactPoint(String system, String value, String use, Integer rank) {}

    public record GqlAddress(
            String use,
            List<String> line,
            String city,
            String district,
            String state,
            String postalCode,
            String country) {}

    public record GqlReference(String reference, String type, String display) {}

    public record GqlPeriod(String start, String end) {}

    public record GqlQuantity(Double value, String unit, String system, String code) {}

    public record GqlReferenceRange(GqlQuantity low, GqlQuantity high, String text) {}

    // -------------------------------------------------------------------------
    // GraphQL Resource Types
    // -------------------------------------------------------------------------

    public record GqlPatient(
            String id,
            String resourceType,
            GqlMeta meta,
            List<GqlIdentifier> identifier,
            Boolean active,
            List<GqlHumanName> name,
            List<GqlContactPoint> telecom,
            List<GqlAddress> address,
            String gender,
            String birthDate,
            Boolean deceasedBoolean,
            String deceasedDateTime,
            String facilityId) {}

    public record GqlPractitionerQualification(
            List<GqlIdentifier> identifier,
            GqlCodeableConcept code,
            GqlPeriod period,
            String issuerDisplay) {}

    public record GqlPractitioner(
            String id,
            String resourceType,
            GqlMeta meta,
            List<GqlIdentifier> identifier,
            Boolean active,
            List<GqlHumanName> name,
            List<GqlContactPoint> telecom,
            List<GqlAddress> address,
            String gender,
            String birthDate,
            List<GqlPractitionerQualification> qualification,
            List<GqlCodeableConcept> communication,
            String facilityId) {}

    public record GqlEncounterParticipant(List<GqlCodeableConcept> type, GqlReference individual) {}

    public record GqlEncounterDiagnosis(GqlReference condition, GqlCodeableConcept use, Integer rank) {}

    public record GqlEncounterLocation(GqlReference location, String status) {}

    public record GqlEncounter(
            String id,
            String resourceType,
            GqlMeta meta,
            List<GqlIdentifier> identifier,
            String status,
            GqlCoding encounterClass,
            List<GqlCodeableConcept> type,
            GqlCodeableConcept priority,
            GqlReference subject,
            List<GqlEncounterParticipant> participant,
            GqlPeriod period,
            List<GqlCodeableConcept> reasonCode,
            List<GqlEncounterDiagnosis> diagnosis,
            List<GqlEncounterLocation> location,
            GqlReference serviceProvider) {}

    public record GqlObservationComponent(
            GqlCodeableConcept code,
            GqlQuantity valueQuantity,
            String valueString,
            GqlCodeableConcept valueCodeableConcept,
            List<GqlCodeableConcept> interpretation,
            List<GqlReferenceRange> referenceRange) {}

    public record GqlObservation(
            String id,
            String resourceType,
            GqlMeta meta,
            List<GqlIdentifier> identifier,
            String status,
            List<GqlCodeableConcept> category,
            GqlCodeableConcept code,
            GqlReference subject,
            GqlReference encounterReference,
            String effectiveDateTime,
            String issued,
            List<GqlReference> performer,
            GqlQuantity valueQuantity,
            String valueString,
            Boolean valueBoolean,
            GqlCodeableConcept valueCodeableConcept,
            List<GqlCodeableConcept> interpretation,
            List<GqlReferenceRange> referenceRange,
            List<String> note,
            List<GqlObservationComponent> component) {}

    // -------------------------------------------------------------------------
    // Connections & Mutation Result Types
    // -------------------------------------------------------------------------

    public record GqlPatientConnection(int total, int offset, int count, List<GqlPatient> items) {}

    public record GqlPractitionerConnection(int total, int offset, int count, List<GqlPractitioner> items) {}

    public record GqlEncounterConnection(int total, int offset, int count, List<GqlEncounter> items) {}

    public record GqlObservationConnection(int total, int offset, int count, List<GqlObservation> items) {}

    public record GqlDeactivationResult(String id, String resourceType, String status, String deactivatedAt) {}

    // -------------------------------------------------------------------------
    // Search Filter & Mutation Input Records
    // -------------------------------------------------------------------------

    public record PatientFilterInput(
            UUID id,
            UUID facilityId,
            String identifier,
            String name,
            String nameContains,
            String family,
            String given,
            LocalDate birthdateFrom,
            LocalDate birthdateTo,
            String gender,
            String phone,
            String email,
            Boolean active) {}

    public record PractitionerFilterInput(
            UUID id,
            UUID facilityId,
            String identifier,
            String name,
            String family,
            String given,
            String gender,
            String qualification,
            Boolean active) {}

    public record EncounterFilterInput(
            UUID id,
            UUID facilityId,
            String patient,
            String status,
            String encounterClass,
            String practitioner,
            String location,
            String reasonCode,
            LocalDate dateFrom,
            LocalDate dateTo) {}

    public record ObservationFilterInput(
            UUID id,
            UUID facilityId,
            String identifier,
            String patient,
            String encounter,
            String performer,
            String category,
            String code,
            String codeText,
            String status,
            LocalDate dateFrom,
            LocalDate dateTo) {}

    public record PatientCreateInput(
            UUID facilityId,
            String mrn,
            String familyName,
            String givenName,
            String middleName,
            String gender,
            String birthDate,
            String phone,
            String email,
            String addressLine,
            String city,
            String country) {}

    public record PatientUpdateInput(
            UUID facilityId,
            String familyName,
            String givenName,
            String middleName,
            String gender,
            String birthDate,
            String phone,
            String email,
            Boolean active) {}

    public record PractitionerCreateInput(
            UUID facilityId,
            String identifierSystem,
            String identifierValue,
            String namePrefix,
            String familyName,
            String givenName,
            String gender,
            String birthDate,
            String phone,
            String email,
            String qualificationCode,
            String qualificationDisplay,
            String qualificationIssuer,
            String communicationCode) {}

    public record EncounterCreateInput(
            UUID facilityId,
            String status,
            String encounterClass,
            UUID patientId,
            UUID attendingPractitionerId,
            String locationCode,
            String reasonIcd10Code,
            String reasonDisplay,
            String startedAt,
            String endedAt) {}

    public record EncounterUpdateInput(
            String status,
            String encounterClass,
            UUID patientId,
            UUID attendingPractitionerId,
            String locationCode,
            String reasonIcd10Code,
            String startedAt,
            String endedAt) {}

    public record ObservationComponentInput(
            String codeSystem,
            String code,
            String codeDisplay,
            Double valueQuantity,
            String valueUnit,
            String valueUnitCode,
            String valueString) {}

    public record ObservationCreateInput(
            UUID facilityId,
            String identifierSystem,
            String identifierValue,
            String status,
            String categoryCode,
            String categoryDisplay,
            String codeSystem,
            String code,
            String codeDisplay,
            String codeText,
            UUID patientId,
            UUID encounterId,
            UUID performerId,
            String effectiveDateTime,
            Double valueQuantity,
            String valueUnit,
            String valueUnitCode,
            String valueString,
            Boolean valueBoolean,
            String noteText,
            List<ObservationComponentInput> components) {}

    // -------------------------------------------------------------------------
    // HAPI FHIR R4 -> GraphQL Mappers
    // -------------------------------------------------------------------------

    public static GqlPatient fromFhirPatient(Patient p, UUID facilityId) {
        GqlMeta meta = p.hasMeta()
                ? new GqlMeta(
                        p.getMeta().getVersionId(),
                        p.getMeta().hasLastUpdated() ? p.getMeta().getLastUpdated().toInstant().toString() : null,
                        p.getMeta().getProfile().stream().map(CanonicalType::getValue).toList())
                : null;
        List<GqlIdentifier> idents = p.getIdentifier().stream()
                .map(i -> new GqlIdentifier(
                        i.hasUse() ? i.getUse().toCode() : null,
                        null,
                        i.getSystem(),
                        i.getValue(),
                        i.hasAssigner() ? i.getAssigner().getDisplay() : null))
                .toList();
        List<GqlHumanName> names = p.getName().stream()
                .map(n -> new GqlHumanName(
                        n.hasUse() ? n.getUse().toCode() : null,
                        n.getText(),
                        n.getFamily(),
                        n.getGiven().stream().map(StringType::getValue).toList(),
                        n.getPrefix().stream().map(StringType::getValue).toList(),
                        n.getSuffix().stream().map(StringType::getValue).toList()))
                .toList();
        List<GqlContactPoint> telecoms = p.getTelecom().stream()
                .map(t -> new GqlContactPoint(
                        t.hasSystem() ? t.getSystem().toCode() : null,
                        t.getValue(),
                        t.hasUse() ? t.getUse().toCode() : null,
                        t.hasRank() ? t.getRank() : null))
                .toList();
        List<GqlAddress> addresses = p.getAddress().stream()
                .map(a -> new GqlAddress(
                        a.hasUse() ? a.getUse().toCode() : null,
                        a.getLine().stream().map(StringType::getValue).toList(),
                        a.getCity(),
                        a.getDistrict(),
                        a.getState(),
                        a.getPostalCode(),
                        a.getCountry()))
                .toList();
        String dob = p.hasBirthDate()
                ? p.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString()
                : null;
        Boolean deceasedBool = p.hasDeceasedBooleanType() ? p.getDeceasedBooleanType().booleanValue() : null;
        String deceasedDt = p.hasDeceasedDateTimeType() && p.getDeceasedDateTimeType().hasValue()
                ? p.getDeceasedDateTimeType().getValue().toInstant().toString()
                : null;
        return new GqlPatient(
                p.hasIdElement() ? p.getIdElement().getIdPart() : null,
                "Patient",
                meta,
                idents,
                p.hasActive() ? p.getActive() : Boolean.TRUE,
                names,
                telecoms,
                addresses,
                p.hasGender() ? p.getGender().toCode() : null,
                dob,
                deceasedBool,
                deceasedDt,
                facilityId != null ? facilityId.toString() : null
        );
    }

    public static GqlPractitioner fromFhirPractitioner(Practitioner p, UUID facilityId) {
        PractitionerDto dto = PractitionerDto.fromFhirR4(p);
        return new GqlPractitioner(
                dto.id(),
                dto.resourceType(),
                toGqlMeta(dto.meta()),
                toGqlIdentifiers(dto.identifier()),
                dto.active(),
                toGqlHumanNames(dto.name()),
                toGqlContactPoints(dto.telecom()),
                toGqlAddresses(dto.address()),
                dto.gender(),
                dto.birthDate() != null ? dto.birthDate().toString() : null,
                dto.qualification() == null ? List.of() : dto.qualification().stream()
                        .map(q -> new GqlPractitionerQualification(
                                toGqlIdentifiers(q.identifier()),
                                toGqlCodeableConcept(q.code()),
                                toGqlPeriod(q.period()),
                                q.issuerDisplay()))
                        .toList(),
                dto.communication() == null ? List.of() : dto.communication().stream()
                        .map(FhirGraphQlTypes::toGqlCodeableConcept)
                        .toList(),
                facilityId != null ? facilityId.toString() : null
        );
    }

    public static GqlEncounter fromFhirEncounter(Encounter e) {
        EncounterDto dto = EncounterDto.fromFhirR4(e);
        return new GqlEncounter(
                dto.id(),
                dto.resourceType(),
                toGqlMeta(dto.meta()),
                toGqlIdentifiers(dto.identifier()),
                dto.status(),
                dto.fhirClass() != null
                        ? new GqlCoding(dto.fhirClass().system(), dto.fhirClass().code(), dto.fhirClass().display())
                        : null,
                dto.type() == null ? List.of() : dto.type().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                toGqlCodeableConcept(dto.priority()),
                toGqlReference(dto.subject()),
                dto.participant() == null ? List.of() : dto.participant().stream()
                        .map(pt -> new GqlEncounterParticipant(
                                pt.type() == null ? List.of() : pt.type().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                                toGqlReference(pt.individual())))
                        .toList(),
                toGqlPeriod(dto.period()),
                dto.reasonCode() == null ? List.of() : dto.reasonCode().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                dto.diagnosis() == null ? List.of() : dto.diagnosis().stream()
                        .map(d -> new GqlEncounterDiagnosis(toGqlReference(d.condition()), toGqlCodeableConcept(d.use()), d.rank()))
                        .toList(),
                dto.location() == null ? List.of() : dto.location().stream()
                        .map(l -> new GqlEncounterLocation(toGqlReference(l.location()), l.status()))
                        .toList(),
                toGqlReference(dto.serviceProvider())
        );
    }

    public static GqlEncounter fromEncounterEntity(com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity entity) {
        return fromFhirEncounter(EncounterService.toFhirEncounter(entity));
    }

    public static GqlObservation fromFhirObservation(Observation o) {
        ObservationDto dto = ObservationDto.fromFhirR4(o);
        return new GqlObservation(
                dto.id(),
                dto.resourceType(),
                toGqlMeta(dto.meta()),
                toGqlIdentifiers(dto.identifier()),
                dto.status(),
                dto.category() == null ? List.of() : dto.category().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                toGqlCodeableConcept(dto.code()),
                toGqlReference(dto.subject()),
                toGqlReference(dto.encounter()),
                dto.effectiveDateTime() != null ? dto.effectiveDateTime().toString() : null,
                dto.issued() != null ? dto.issued().toString() : null,
                dto.performer() == null ? List.of() : dto.performer().stream().map(FhirGraphQlTypes::toGqlReference).toList(),
                toGqlQuantity(dto.valueQuantity()),
                dto.valueString(),
                dto.valueBoolean(),
                toGqlCodeableConcept(dto.valueCodeableConcept()),
                dto.interpretation() == null ? List.of() : dto.interpretation().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                dto.referenceRange() == null ? List.of() : dto.referenceRange().stream().map(FhirGraphQlTypes::toGqlReferenceRange).toList(),
                dto.note() == null ? List.of() : dto.note(),
                dto.component() == null ? List.of() : dto.component().stream()
                        .map(c -> new GqlObservationComponent(
                                toGqlCodeableConcept(c.code()),
                                toGqlQuantity(c.valueQuantity()),
                                c.valueString(),
                                toGqlCodeableConcept(c.valueCodeableConcept()),
                                c.interpretation() == null ? List.of() : c.interpretation().stream().map(FhirGraphQlTypes::toGqlCodeableConcept).toList(),
                                c.referenceRange() == null ? List.of() : c.referenceRange().stream().map(FhirGraphQlTypes::toGqlReferenceRange).toList()))
                        .toList()
        );
    }

    // -------------------------------------------------------------------------
    // GraphQL Mutation Input -> Validated HL7 FHIR R4 JSON Builders
    // -------------------------------------------------------------------------

    public static String toFhirPatientJson(PatientCreateInput input) {
        Patient p = new Patient();
        p.setActive(true);
        if (!isBlank(input.mrn())) {
            p.addIdentifier().setSystem("urn:opes:mrn").setValue(input.mrn().trim());
        }
        HumanName hn = p.addName().setUse(HumanName.NameUse.OFFICIAL);
        if (!isBlank(input.familyName())) hn.setFamily(input.familyName().trim());
        if (!isBlank(input.givenName())) hn.addGiven(input.givenName().trim());
        if (!isBlank(input.middleName())) hn.addGiven(input.middleName().trim());
        if (!isBlank(input.gender())) {
            p.setGender(parseAdministrativeGender(input.gender()));
        }
        if (!isBlank(input.birthDate())) {
            LocalDate dob = LocalDate.parse(input.birthDate().trim());
            p.setBirthDate(Date.from(dob.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (!isBlank(input.phone())) {
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue(input.phone().trim());
        }
        if (!isBlank(input.email())) {
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue(input.email().trim());
        }
        if (!isBlank(input.addressLine()) || !isBlank(input.city()) || !isBlank(input.country())) {
            Address addr = p.addAddress();
            if (!isBlank(input.addressLine())) addr.addLine(input.addressLine().trim());
            if (!isBlank(input.city())) addr.setCity(input.city().trim());
            if (!isBlank(input.country())) addr.setCountry(input.country().trim());
        }
        return FHIR_R4.newJsonParser().encodeResourceToString(p);
    }

    public static String toFhirPatientUpdateJson(Patient existing, PatientUpdateInput input) {
        Patient p = existing.copy();
        if (input.active() != null) {
            p.setActive(input.active());
        }
        if (!isBlank(input.familyName()) || !isBlank(input.givenName()) || !isBlank(input.middleName())) {
            p.getName().clear();
            HumanName hn = p.addName().setUse(HumanName.NameUse.OFFICIAL);
            String fam = !isBlank(input.familyName()) ? input.familyName().trim() : existing.getNameFirstRep().getFamily();
            if (!isBlank(fam)) hn.setFamily(fam);
            String giv = !isBlank(input.givenName())
                    ? input.givenName().trim()
                    : (!existing.getNameFirstRep().getGiven().isEmpty() ? existing.getNameFirstRep().getGiven().get(0).getValue() : null);
            if (!isBlank(giv)) hn.addGiven(giv);
            if (!isBlank(input.middleName())) hn.addGiven(input.middleName().trim());
        }
        if (!isBlank(input.gender())) {
            p.setGender(parseAdministrativeGender(input.gender()));
        }
        if (!isBlank(input.birthDate())) {
            LocalDate dob = LocalDate.parse(input.birthDate().trim());
            p.setBirthDate(Date.from(dob.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (!isBlank(input.phone())) {
            p.getTelecom().removeIf(t -> t.getSystem() == ContactPoint.ContactPointSystem.PHONE);
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue(input.phone().trim());
        }
        if (!isBlank(input.email())) {
            p.getTelecom().removeIf(t -> t.getSystem() == ContactPoint.ContactPointSystem.EMAIL);
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue(input.email().trim());
        }
        return FHIR_R4.newJsonParser().encodeResourceToString(p);
    }

    public static String toFhirPractitionerJson(PractitionerCreateInput input) {
        Practitioner p = new Practitioner();
        p.setActive(true);
        if (!isBlank(input.identifierValue())) {
            p.addIdentifier()
                    .setSystem(!isBlank(input.identifierSystem()) ? input.identifierSystem().trim() : "urn:opes:practitioner-license")
                    .setValue(input.identifierValue().trim());
        }
        HumanName hn = p.addName().setUse(HumanName.NameUse.OFFICIAL);
        if (!isBlank(input.namePrefix())) hn.addPrefix(input.namePrefix().trim());
        if (!isBlank(input.familyName())) hn.setFamily(input.familyName().trim());
        if (!isBlank(input.givenName())) hn.addGiven(input.givenName().trim());
        if (!isBlank(input.gender())) {
            p.setGender(parseAdministrativeGender(input.gender()));
        }
        if (!isBlank(input.birthDate())) {
            LocalDate dob = LocalDate.parse(input.birthDate().trim());
            p.setBirthDate(Date.from(dob.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (!isBlank(input.phone())) {
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue(input.phone().trim());
        }
        if (!isBlank(input.email())) {
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue(input.email().trim());
        }
        if (!isBlank(input.qualificationCode()) || !isBlank(input.qualificationDisplay())) {
            Practitioner.PractitionerQualificationComponent qual = p.addQualification();
            if (!isBlank(input.qualificationDisplay())) qual.getCode().setText(input.qualificationDisplay().trim());
            if (!isBlank(input.qualificationCode())) {
                qual.getCode().addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v2-0360")
                        .setCode(input.qualificationCode().trim())
                        .setDisplay(input.qualificationDisplay()));
            }
            if (!isBlank(input.qualificationIssuer())) {
                qual.setIssuer(new Reference().setDisplay(input.qualificationIssuer().trim()));
            }
        }
        if (!isBlank(input.communicationCode())) {
            p.addCommunication().addCoding(new Coding()
                    .setSystem("urn:ietf:bcp:47")
                    .setCode(input.communicationCode().trim()));
        }
        return FHIR_R4.newJsonParser().encodeResourceToString(p);
    }

    public static String toFhirEncounterJson(EncounterCreateInput input) {
        return buildEncounterJson(
                input.status(),
                input.encounterClass(),
                input.patientId(),
                input.attendingPractitionerId(),
                input.locationCode(),
                input.reasonIcd10Code(),
                input.reasonDisplay(),
                input.startedAt(),
                input.endedAt());
    }

    public static String toFhirEncounterJson(EncounterUpdateInput input) {
        return buildEncounterJson(
                input.status(),
                input.encounterClass(),
                input.patientId(),
                input.attendingPractitionerId(),
                input.locationCode(),
                input.reasonIcd10Code(),
                null,
                input.startedAt(),
                input.endedAt());
    }

    private static String buildEncounterJson(
            String status,
            String encounterClass,
            UUID patientId,
            UUID attendingPractitionerId,
            String locationCode,
            String reasonIcd10Code,
            String reasonDisplay,
            String startedAt,
            String endedAt) {
        Encounter enc = new Encounter();
        try {
            enc.setStatus(Encounter.EncounterStatus.fromCode(status.trim().toLowerCase(Locale.ROOT)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR Encounter status: " + status, ex);
        }
        enc.setClass_(new Coding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                .setCode(encounterClass.trim().toUpperCase(Locale.ROOT))
                .setDisplay(encounterClass.trim().toUpperCase(Locale.ROOT)));
        if (patientId != null) {
            enc.setSubject(new Reference("Patient/" + patientId));
        }
        if (attendingPractitionerId != null) {
            enc.addParticipant().setIndividual(new Reference("Practitioner/" + attendingPractitionerId));
        }
        if (!isBlank(locationCode)) {
            String ref = locationCode.contains("/") ? locationCode.trim() : "Location/" + locationCode.trim();
            enc.addLocation().setLocation(new Reference(ref));
        }
        if (!isBlank(reasonIcd10Code)) {
            enc.addReasonCode(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://hl7.org/fhir/sid/icd-10")
                    .setCode(reasonIcd10Code.trim())
                    .setDisplay(!isBlank(reasonDisplay) ? reasonDisplay.trim() : reasonIcd10Code.trim())));
        }
        if (!isBlank(startedAt)) {
            enc.getPeriod().setStart(Date.from(Instant.parse(startedAt.trim())));
        }
        if (!isBlank(endedAt)) {
            enc.getPeriod().setEnd(Date.from(Instant.parse(endedAt.trim())));
        }
        return FHIR_R4.newJsonParser().encodeResourceToString(enc);
    }

    public static String toFhirObservationJson(ObservationCreateInput input) {
        Observation obs = new Observation();
        if (!isBlank(input.identifierValue())) {
            obs.addIdentifier()
                    .setSystem(!isBlank(input.identifierSystem()) ? input.identifierSystem().trim() : "urn:opes:observation")
                    .setValue(input.identifierValue().trim());
        }
        try {
            obs.setStatus(Observation.ObservationStatus.fromCode(input.status().trim().toLowerCase(Locale.ROOT)));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR Observation status: " + input.status(), ex);
        }
        if (!isBlank(input.categoryCode())) {
            obs.addCategory(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/observation-category")
                    .setCode(input.categoryCode().trim())
                    .setDisplay(input.categoryDisplay())));
        }
        CodeableConcept codeCc = new CodeableConcept();
        if (!isBlank(input.codeText())) codeCc.setText(input.codeText().trim());
        codeCc.addCoding(new Coding()
                .setSystem(!isBlank(input.codeSystem()) ? input.codeSystem().trim() : "http://loinc.org")
                .setCode(input.code().trim())
                .setDisplay(input.codeDisplay()));
        obs.setCode(codeCc);
        if (input.patientId() != null) {
            obs.setSubject(new Reference("Patient/" + input.patientId()));
        }
        if (input.encounterId() != null) {
            obs.setEncounter(new Reference("Encounter/" + input.encounterId()));
        }
        if (input.performerId() != null) {
            obs.addPerformer(new Reference("Practitioner/" + input.performerId()));
        }
        if (!isBlank(input.effectiveDateTime())) {
            obs.setEffective(new DateTimeType(Date.from(Instant.parse(input.effectiveDateTime().trim()))));
        }
        if (input.valueQuantity() != null) {
            Quantity q = new Quantity().setValue(BigDecimal.valueOf(input.valueQuantity()));
            if (!isBlank(input.valueUnit())) q.setUnit(input.valueUnit().trim());
            if (!isBlank(input.valueUnitCode())) {
                q.setSystem("http://unitsofmeasure.org").setCode(input.valueUnitCode().trim());
            }
            obs.setValue(q);
        } else if (input.valueString() != null) {
            obs.setValue(new StringType(input.valueString()));
        } else if (input.valueBoolean() != null) {
            obs.setValue(new BooleanType(input.valueBoolean()));
        }
        if (!isBlank(input.noteText())) {
            obs.addNote(new Annotation().setText(input.noteText().trim()));
        }
        if (input.components() != null) {
            for (ObservationComponentInput comp : input.components()) {
                Observation.ObservationComponentComponent c = obs.addComponent();
                c.setCode(new CodeableConcept().addCoding(new Coding()
                        .setSystem(!isBlank(comp.codeSystem()) ? comp.codeSystem().trim() : "http://loinc.org")
                        .setCode(comp.code().trim())
                        .setDisplay(comp.codeDisplay())));
                if (comp.valueQuantity() != null) {
                    Quantity q = new Quantity().setValue(BigDecimal.valueOf(comp.valueQuantity()));
                    if (!isBlank(comp.valueUnit())) q.setUnit(comp.valueUnit().trim());
                    if (!isBlank(comp.valueUnitCode())) {
                        q.setSystem("http://unitsofmeasure.org").setCode(comp.valueUnitCode().trim());
                    }
                    c.setValue(q);
                } else if (comp.valueString() != null) {
                    c.setValue(new StringType(comp.valueString()));
                }
            }
        }
        return FHIR_R4.newJsonParser().encodeResourceToString(obs);
    }

    // -------------------------------------------------------------------------
    // Internal Conversion Helpers
    // -------------------------------------------------------------------------

    private static AdministrativeGender parseAdministrativeGender(String gender) {
        try {
            return AdministrativeGender.fromCode(gender.trim().toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unsupported FHIR gender: " + gender, ex);
        }
    }

    private static GqlMeta toGqlMeta(FhirMetaDto meta) {
        if (meta == null) return null;
        return new GqlMeta(
                meta.versionId(),
                meta.lastUpdated() != null ? meta.lastUpdated().toString() : null,
                meta.profile() != null ? meta.profile() : List.of()
        );
    }

    private static List<GqlIdentifier> toGqlIdentifiers(List<FhirIdentifierDto> list) {
        if (list == null) return List.of();
        return list.stream()
                .map(i -> new GqlIdentifier(i.use(), toGqlCodeableConcept(i.type()), i.system(), i.value(), i.assignerDisplay()))
                .toList();
    }

    private static List<GqlHumanName> toGqlHumanNames(List<FhirHumanNameDto> list) {
        if (list == null) return List.of();
        return list.stream()
                .map(n -> new GqlHumanName(
                        n.use(),
                        n.text(),
                        n.family(),
                        n.given() != null ? n.given() : List.of(),
                        n.prefix() != null ? n.prefix() : List.of(),
                        n.suffix() != null ? n.suffix() : List.of()))
                .toList();
    }

    private static List<GqlContactPoint> toGqlContactPoints(List<FhirContactPointDto> list) {
        if (list == null) return List.of();
        return list.stream()
                .map(c -> new GqlContactPoint(c.system(), c.value(), c.use(), c.rank()))
                .toList();
    }

    private static List<GqlAddress> toGqlAddresses(List<FhirAddressDto> list) {
        if (list == null) return List.of();
        return list.stream()
                .map(a -> new GqlAddress(
                        a.use(),
                        a.line() != null ? a.line() : List.of(),
                        a.city(),
                        a.district(),
                        a.state(),
                        a.postalCode(),
                        a.country()))
                .toList();
    }

    private static GqlCodeableConcept toGqlCodeableConcept(FhirCodeableConceptDto cc) {
        if (cc == null) return null;
        List<GqlCoding> codings = cc.coding() == null
                ? List.of()
                : cc.coding().stream().map(c -> new GqlCoding(c.system(), c.code(), c.display())).toList();
        return new GqlCodeableConcept(codings, cc.text());
    }

    private static GqlReference toGqlReference(FhirReferenceDto ref) {
        if (ref == null) return null;
        return new GqlReference(ref.reference(), ref.type(), ref.display());
    }

    private static GqlPeriod toGqlPeriod(FhirPeriodDto period) {
        if (period == null) return null;
        return new GqlPeriod(
                period.start() != null ? period.start().toString() : null,
                period.end() != null ? period.end().toString() : null
        );
    }

    private static GqlQuantity toGqlQuantity(FhirQuantityDto q) {
        if (q == null) return null;
        return new GqlQuantity(
                q.value() != null ? q.value().doubleValue() : null,
                q.unit(),
                q.system(),
                q.code()
        );
    }

    private static GqlReferenceRange toGqlReferenceRange(FhirReferenceRangeDto rr) {
        if (rr == null) return null;
        return new GqlReferenceRange(toGqlQuantity(rr.low()), toGqlQuantity(rr.high()), rr.text());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
