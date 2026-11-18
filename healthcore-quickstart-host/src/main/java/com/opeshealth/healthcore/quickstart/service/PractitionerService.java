/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Practitioner CRUD & Validation Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.opeshealth.healthcore.dto.PractitionerDto;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.quickstart.domain.PractitionerEntity;
import com.opeshealth.healthcore.quickstart.domain.PractitionerRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService.StringMatchModifier;
import com.opeshealth.healthcore.service.PatientService.StringSearchFilter;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Full CRUD and HL7 FHIR R4 validation service for {@link PractitionerEntity}
 * backed by {@link PractitionerRepository} with standard FHIR R4 schema checks,
 * identifier uniqueness enforcement, {@link PractitionerDto} interoperability,
 * search modifiers ({@code :contains}, {@code :exact}, default prefix), and ETag
 * optimistic concurrency control.
 */
@Service
@Transactional
public class PractitionerService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String DEFAULT_IDENT_SYSTEM = "urn:opes:practitioner-license";
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1900, 1, 1);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> VALID_FHIR_GENDERS = Set.of("male", "female", "other", "unknown");

    public record PractitionerSearchCriteria(
            UUID id,
            UUID facilityId,
            String identifier,
            List<StringSearchFilter> nameFilters,
            List<StringSearchFilter> familyFilters,
            List<StringSearchFilter> givenFilters,
            String gender,
            String qualification,
            Boolean active,
            int offset,
            int count
    ) {
        public PractitionerSearchCriteria {
            nameFilters = nameFilters != null ? List.copyOf(nameFilters) : List.of();
            familyFilters = familyFilters != null ? List.copyOf(familyFilters) : List.of();
            givenFilters = givenFilters != null ? List.copyOf(givenFilters) : List.of();
            if (offset < 0) offset = 0;
            if (count <= 0) count = 20;
            if (count > 100) count = 100;
        }
    }

    public record PractitionerSearchResult(
            List<PractitionerEntity> practitioners,
            int total,
            int offset,
            int count
    ) {}

    private final PractitionerRepository practitionerRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final FhirValidator schemaValidator;

    public PractitionerService(PractitionerRepository practitionerRepo) {
        this(practitionerRepo, (OutboxRelayService.OutboxRepository) null);
    }

    @Autowired
    public PractitionerService(
            PractitionerRepository practitionerRepo,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider) {
        this(practitionerRepo, outboxRepoProvider != null ? outboxRepoProvider.getIfAvailable() : null);
    }

    public PractitionerService(
            PractitionerRepository practitionerRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.practitionerRepo = Objects.requireNonNull(practitionerRepo, "PractitionerRepository is required");
        this.outboxRepo = outboxRepo;
        this.schemaValidator = FHIR_R4.newValidator();
        this.schemaValidator.setValidateAgainstStandardSchema(true);
        this.schemaValidator.setValidateAgainstStandardSchematron(false);
    }

    public ValidationResult validatePractitionerSchema(Practitioner practitioner) {
        if (practitioner == null) {
            throw new IllegalArgumentException("FHIR R4 Practitioner resource must not be null");
        }
        return schemaValidator.validateWithResult(practitioner);
    }

    public void validateEntity(PractitionerEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Practitioner entity must not be null");
        }
        if (entity.getFacilityId() == null) {
            throw new IllegalArgumentException("Practitioner facilityId is required");
        }
        if (isBlank(entity.getFamilyName())) {
            throw new IllegalArgumentException("Practitioner familyName is required");
        }
        if (isBlank(entity.getGivenName())) {
            throw new IllegalArgumentException("Practitioner givenName is required");
        }
        if (isBlank(entity.getGender())
                || !VALID_FHIR_GENDERS.contains(entity.getGender().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Practitioner gender must be one of male, female, other, unknown");
        }
        if (entity.getBirthDate() != null) {
            if (entity.getBirthDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("Practitioner birthDate cannot be in the future");
            }
            if (entity.getBirthDate().isBefore(MIN_BIRTH_DATE)) {
                throw new IllegalArgumentException("Practitioner birthDate precedes minimum supported date 1900-01-01");
            }
        }
        if (!isBlank(entity.getEmail()) && !EMAIL_PATTERN.matcher(entity.getEmail().trim()).matches()) {
            throw new IllegalArgumentException("Invalid email format for Practitioner telecom");
        }
    }

    public void validateFhirPractitioner(
            Practitioner fhirPractitioner,
            UUID facilityId,
            boolean isCreate,
            UUID existingPractitionerId) {
        ValidationResult schemaResult = validatePractitionerSchema(fhirPractitioner);
        if (!schemaResult.isSuccessful()) {
            List<String> diagnostics = schemaResult.getMessages().stream()
                    .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
                    .map(SingleValidationMessage::toString)
                    .toList();
            throw new IllegalArgumentException("HL7 FHIR R4 Practitioner schema validation failed: " + String.join("; ", diagnostics));
        }
        if (fhirPractitioner.getName().isEmpty()
                || fhirPractitioner.getName().stream().noneMatch(PractitionerService::hasUsableName)) {
            throw new IllegalArgumentException("FHIR Practitioner.name must include at least one HumanName with family, given, or text");
        }
        if (fhirPractitioner.hasBirthDate()) {
            LocalDate dob = fhirPractitioner.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (dob.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("FHIR Practitioner.birthDate cannot be in the future: " + dob);
            }
            if (dob.isBefore(MIN_BIRTH_DATE)) {
                throw new IllegalArgumentException("FHIR Practitioner.birthDate precedes 1900-01-01: " + dob);
            }
        }
        if (fhirPractitioner.hasGender()) {
            AdministrativeGender g = fhirPractitioner.getGender();
            if (g == null || !VALID_FHIR_GENDERS.contains(g.toCode().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported FHIR Practitioner.gender; expected male, female, other, or unknown");
            }
        }
        for (ContactPoint cp : fhirPractitioner.getTelecom()) {
            if (!cp.hasValue() || isBlank(cp.getValue())) {
                throw new IllegalArgumentException("FHIR Practitioner.telecom entry must have a non-blank value");
            }
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL
                    && !EMAIL_PATTERN.matcher(cp.getValue().trim()).matches()) {
                throw new IllegalArgumentException("Invalid FHIR Practitioner.telecom email address: " + cp.getValue());
            }
            if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE
                    && cp.getValue().replaceAll("[^0-9]", "").length() < 5) {
                throw new IllegalArgumentException("Invalid FHIR Practitioner.telecom phone number: " + cp.getValue());
            }
        }
        UUID scopedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        for (Identifier ident : fhirPractitioner.getIdentifier()) {
            if (!ident.hasValue() || isBlank(ident.getValue())) {
                throw new IllegalArgumentException("FHIR Practitioner.identifier entries must specify a non-blank value");
            }
            String sys = ident.hasSystem() && !isBlank(ident.getSystem()) ? ident.getSystem().trim() : DEFAULT_IDENT_SYSTEM;
            String val = ident.getValue().trim();
            practitionerRepo.findByFacilityIdAndIdentifierSystemAndIdentifierValue(scopedFacility, sys, val)
                    .ifPresent(found -> {
                        if (isCreate || !Objects.equals(found.getId(), existingPractitionerId)) {
                            throw new IllegalStateException("Duplicate Practitioner identifier in facility: " + sys + "|" + val);
                        }
                    });
        }
    }

    @ClinicalAudit(action = "PRACTITIONER_CREATE", resourceType = "Practitioner")
    public PractitionerEntity createPractitioner(PractitionerEntity entity) {
        if (entity.getFacilityId() == null) {
            entity.setFacilityId(DEFAULT_FACILITY_ID);
        }
        if (isBlank(entity.getGender())) {
            entity.setGender("UNKNOWN");
        }
        validateEntity(entity);
        if (!isBlank(entity.getIdentifierValue())) {
            String sys = !isBlank(entity.getIdentifierSystem()) ? entity.getIdentifierSystem().trim() : DEFAULT_IDENT_SYSTEM;
            entity.setIdentifierSystem(sys);
            practitionerRepo.findByFacilityIdAndIdentifierSystemAndIdentifierValue(
                            entity.getFacilityId(), sys, entity.getIdentifierValue().trim())
                    .ifPresent(existing -> {
                        throw new IllegalStateException("Duplicate Practitioner identifier in facility: " + sys + "|" + existing.getIdentifierValue());
                    });
        }
        entity.setGender(entity.getGender().trim().toUpperCase(Locale.ROOT));
        entity.setUpdatedAt(Instant.now());
        validatePractitionerSchema(toFhirPractitioner(entity));
        PractitionerEntity saved = practitionerRepo.save(entity);
        emitOutboxEvent("Practitioner", saved.getId(), "PractitionerCreatedEvent",
                "{\"practitionerId\":\"" + saved.getId() + "\",\"familyName\":\"" + saved.getFamilyName() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "PRACTITIONER_CREATE_FHIR", resourceType = "Practitioner")
    public Practitioner createFromFhirJson(UUID facilityId, String fhirPractitionerJson) {
        Practitioner incoming = parseFhirPractitioner(fhirPractitionerJson);
        UUID resolvedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        validateFhirPractitioner(incoming, resolvedFacility, true, null);

        PractitionerEntity entity = new PractitionerEntity();
        entity.setFacilityId(resolvedFacility);
        applyFhirFieldsToEntity(entity, incoming);
        validateEntity(entity);

        PractitionerEntity saved = practitionerRepo.save(entity);
        emitOutboxEvent("Practitioner", saved.getId(), "PractitionerCreatedEvent",
                "{\"practitionerId\":\"" + saved.getId() + "\",\"familyName\":\"" + saved.getFamilyName() + "\"}");
        return toFhirPractitioner(saved);
    }

    public PractitionerDto createFromDto(UUID facilityId, PractitionerDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("PractitionerDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return PractitionerDto.fromFhirR4(createFromFhirJson(facilityId, json));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PRACTITIONER_READ", resourceType = "Practitioner")
    public PractitionerEntity getPractitionerById(UUID id) {
        return practitionerRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Practitioner not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<PractitionerEntity> findPractitionerById(UUID id) {
        return practitionerRepo.findById(id);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PRACTITIONER_READ_FHIR", resourceType = "Practitioner")
    public Practitioner getFhirPractitionerById(UUID id) {
        return toFhirPractitioner(getPractitionerById(id));
    }

    @Transactional(readOnly = true)
    public PractitionerDto getPractitionerDtoById(UUID id) {
        return PractitionerDto.fromFhirR4(getFhirPractitionerById(id));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PRACTITIONER_SEARCH", resourceType = "Practitioner")
    public PractitionerSearchResult searchPractitioners(PractitionerSearchCriteria criteria) {
        if (criteria.id() != null) {
            Optional<PractitionerEntity> one = findPractitionerById(criteria.id())
                    .filter(p -> matchesCriteria(p, criteria));
            List<PractitionerEntity> list = one.map(List::of).orElseGet(List::of);
            return new PractitionerSearchResult(list, list.size(), criteria.offset(), criteria.count());
        }

        List<PractitionerEntity> candidates = resolveCandidatePool(criteria);
        List<PractitionerEntity> matched = candidates.stream()
                .filter(p -> matchesCriteria(p, criteria))
                .sorted(Comparator.comparing(PractitionerEntity::getFamilyName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(PractitionerEntity::getGivenName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        int total = matched.size();
        int from = Math.min(criteria.offset(), total);
        int to = Math.min(from + criteria.count(), total);
        return new PractitionerSearchResult(matched.subList(from, to), total, criteria.offset(), criteria.count());
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PRACTITIONER_SEARCH_FHIR", resourceType = "Practitioner")
    public Bundle searchFhirBundle(PractitionerSearchCriteria criteria) {
        PractitionerSearchResult result = searchPractitioners(criteria);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.total());
        for (PractitionerEntity entity : result.practitioners()) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl("Practitioner/" + entity.getId());
            entry.setResource(toFhirPractitioner(entity));
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    @ClinicalAudit(action = "PRACTITIONER_UPDATE", resourceType = "Practitioner")
    public PractitionerEntity updatePractitioner(UUID id, PractitionerEntity updates, Long expectedVersion) {
        PractitionerEntity existing = getPractitionerById(id);
        if (expectedVersion != null && existing.getVersion() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Stale Practitioner version; expected W/\"" + existing.getVersion() + "\"");
        }
        if (!isBlank(updates.getNamePrefix())) existing.setNamePrefix(updates.getNamePrefix());
        if (!isBlank(updates.getFamilyName())) existing.setFamilyName(updates.getFamilyName());
        if (!isBlank(updates.getGivenName())) existing.setGivenName(updates.getGivenName());
        if (updates.getNameSuffix() != null) existing.setNameSuffix(updates.getNameSuffix());
        if (updates.getBirthDate() != null) existing.setBirthDate(updates.getBirthDate());
        if (!isBlank(updates.getGender())) existing.setGender(updates.getGender().trim().toUpperCase(Locale.ROOT));
        if (updates.getPhone() != null) existing.setPhone(updates.getPhone());
        if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
        if (updates.getQualificationCode() != null) existing.setQualificationCode(updates.getQualificationCode());
        if (updates.getQualificationDisplay() != null) existing.setQualificationDisplay(updates.getQualificationDisplay());
        if (updates.getQualificationIssuer() != null) existing.setQualificationIssuer(updates.getQualificationIssuer());
        if (!isBlank(updates.getCommunicationCode())) existing.setCommunicationCode(updates.getCommunicationCode());
        existing.setActive(updates.isActive());
        existing.setUpdatedAt(Instant.now());

        validateEntity(existing);
        validatePractitionerSchema(toFhirPractitioner(existing));
        PractitionerEntity saved = practitionerRepo.save(existing);
        emitOutboxEvent("Practitioner", saved.getId(), "PractitionerUpdatedEvent",
                "{\"practitionerId\":\"" + saved.getId() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "PRACTITIONER_UPDATE_FHIR", resourceType = "Practitioner")
    public Practitioner updateFromFhirJson(UUID id, String fhirPractitionerJson, String ifMatchHeader) {
        PractitionerEntity existing = getPractitionerById(id);
        enforceIfMatch(existing, ifMatchHeader);

        Practitioner incoming = parseFhirPractitioner(fhirPractitionerJson);
        validateFhirPractitioner(incoming, existing.getFacilityId(), false, existing.getId());
        applyFhirFieldsToEntity(existing, incoming);
        existing.setUpdatedAt(Instant.now());
        validateEntity(existing);

        PractitionerEntity saved = practitionerRepo.save(existing);
        emitOutboxEvent("Practitioner", saved.getId(), "PractitionerUpdatedEvent",
                "{\"practitionerId\":\"" + saved.getId() + "\"}");
        return toFhirPractitioner(saved);
    }

    public PractitionerDto updateFromDto(UUID id, PractitionerDto dto, String ifMatchHeader) {
        if (dto == null) {
            throw new IllegalArgumentException("PractitionerDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return PractitionerDto.fromFhirR4(updateFromFhirJson(id, json, ifMatchHeader));
    }

    @ClinicalAudit(action = "PRACTITIONER_DEACTIVATE", resourceType = "Practitioner")
    public PractitionerEntity deactivatePractitioner(UUID id) {
        PractitionerEntity existing = getPractitionerById(id);
        existing.setActive(false);
        existing.setUpdatedAt(Instant.now());
        PractitionerEntity saved = practitionerRepo.save(existing);
        emitOutboxEvent("Practitioner", saved.getId(), "PractitionerDeactivatedEvent",
                "{\"practitionerId\":\"" + saved.getId() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "PRACTITIONER_DELETE", resourceType = "Practitioner")
    public void deletePractitioner(UUID id) {
        PractitionerEntity existing = getPractitionerById(id);
        practitionerRepo.deleteById(existing.getId());
        emitOutboxEvent("Practitioner", existing.getId(), "PractitionerDeletedEvent",
                "{\"practitionerId\":\"" + existing.getId() + "\"}");
    }

    public String encodeFhirResource(org.hl7.fhir.instance.model.api.IBaseResource resource) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    public Practitioner parseFhirPractitioner(String fhirPractitionerJson) {
        if (isBlank(fhirPractitionerJson)) {
            throw new IllegalArgumentException("FHIR Practitioner JSON payload must not be empty");
        }
        try {
            IParser strictParser = FHIR_R4.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
            Practitioner parsed = strictParser.parseResource(Practitioner.class, fhirPractitionerJson);
            if (parsed == null || !"Practitioner".equals(parsed.fhirType())) {
                throw new IllegalArgumentException("FHIR payload resourceType must be 'Practitioner'");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid HL7 FHIR R4 Practitioner JSON: " + ex.getMessage(), ex);
        }
    }

    public static Practitioner toFhirPractitioner(PractitionerEntity e) {
        Practitioner p = new Practitioner();
        p.setId(e.getId().toString());
        p.getMeta().setVersionId(Long.toString(e.getVersion()));
        if (e.getUpdatedAt() != null) {
            p.getMeta().setLastUpdated(Date.from(e.getUpdatedAt()));
        }

        if (!isBlank(e.getIdentifierValue())) {
            Identifier ident = p.addIdentifier()
                    .setSystem(!isBlank(e.getIdentifierSystem()) ? e.getIdentifierSystem() : DEFAULT_IDENT_SYSTEM)
                    .setValue(e.getIdentifierValue());
            if (!isBlank(e.getIdentifierUse())) {
                try {
                    ident.setUse(Identifier.IdentifierUse.fromCode(e.getIdentifierUse().toLowerCase(Locale.ROOT)));
                } catch (Exception ignored) {
                    ident.setUse(Identifier.IdentifierUse.OFFICIAL);
                }
            }
            if (!isBlank(e.getAssignerDisplay())) {
                ident.setAssigner(new Reference().setDisplay(e.getAssignerDisplay()));
            }
        }

        p.setActive(e.isActive());

        HumanName hn = p.addName().setUse(HumanName.NameUse.OFFICIAL);
        if (!isBlank(e.getNamePrefix())) hn.addPrefix(e.getNamePrefix());
        if (!isBlank(e.getFamilyName())) hn.setFamily(e.getFamilyName());
        if (!isBlank(e.getGivenName())) hn.addGiven(e.getGivenName());
        if (!isBlank(e.getNameSuffix())) hn.addSuffix(e.getNameSuffix());

        if (!isBlank(e.getPhone())) {
            p.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.WORK)
                    .setRank(1)
                    .setValue(e.getPhone());
        }
        if (!isBlank(e.getEmail())) {
            p.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                    .setUse(ContactPoint.ContactPointUse.WORK)
                    .setValue(e.getEmail());
        }

        if (!isBlank(e.getAddressLine()) || !isBlank(e.getCity()) || !isBlank(e.getCountry())) {
            Address addr = p.addAddress();
            if (!isBlank(e.getAddressUse())) {
                try {
                    addr.setUse(Address.AddressUse.fromCode(e.getAddressUse().toLowerCase(Locale.ROOT)));
                } catch (Exception ignored) {
                    addr.setUse(Address.AddressUse.WORK);
                }
            }
            if (!isBlank(e.getAddressLine())) addr.addLine(e.getAddressLine());
            if (!isBlank(e.getCity())) addr.setCity(e.getCity());
            if (!isBlank(e.getDistrict())) addr.setDistrict(e.getDistrict());
            if (!isBlank(e.getState())) addr.setState(e.getState());
            if (!isBlank(e.getPostalCode())) addr.setPostalCode(e.getPostalCode());
            if (!isBlank(e.getCountry())) addr.setCountry(e.getCountry());
        }

        if (!isBlank(e.getGender())) {
            try {
                p.setGender(AdministrativeGender.fromCode(e.getGender().toLowerCase(Locale.ROOT)));
            } catch (Exception ignored) {
                p.setGender(AdministrativeGender.UNKNOWN);
            }
        }
        if (e.getBirthDate() != null) {
            p.setBirthDate(Date.from(e.getBirthDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }

        if (!isBlank(e.getQualificationCode()) || !isBlank(e.getQualificationDisplay())) {
            Practitioner.PractitionerQualificationComponent q = p.addQualification();
            CodeableConcept cc = q.getCode();
            if (!isBlank(e.getQualificationDisplay())) cc.setText(e.getQualificationDisplay());
            if (!isBlank(e.getQualificationCode())) {
                cc.addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v2-0360")
                        .setCode(e.getQualificationCode())
                        .setDisplay(e.getQualificationDisplay()));
            }
            if (!isBlank(e.getQualificationIssuer())) {
                q.setIssuer(new Reference().setDisplay(e.getQualificationIssuer()));
            }
        }

        if (!isBlank(e.getCommunicationCode())) {
            p.addCommunication().addCoding(new Coding()
                    .setSystem("urn:ietf:bcp:47")
                    .setCode(e.getCommunicationCode()));
        }
        return p;
    }

    private List<PractitionerEntity> resolveCandidatePool(PractitionerSearchCriteria c) {
        if (c.facilityId() != null && !isBlank(c.identifier())) {
            String raw = c.identifier().trim();
            int pipe = raw.indexOf('|');
            if (pipe >= 0) {
                String sys = raw.substring(0, pipe);
                String val = raw.substring(pipe + 1);
                Optional<PractitionerEntity> exact = practitionerRepo
                        .findByFacilityIdAndIdentifierSystemAndIdentifierValue(c.facilityId(), sys, val);
                if (exact.isPresent()) {
                    return List.of(exact.get());
                }
            }
        }
        if (c.facilityId() != null) {
            return Boolean.TRUE.equals(c.active())
                    ? practitionerRepo.findByFacilityIdAndActiveTrueOrderByFamilyNameAsc(c.facilityId())
                    : practitionerRepo.findByFacilityIdOrderByFamilyNameAsc(c.facilityId());
        }
        return practitionerRepo.findAll();
    }

    private static boolean matchesCriteria(PractitionerEntity p, PractitionerSearchCriteria c) {
        if (c.facilityId() != null && !Objects.equals(c.facilityId(), p.getFacilityId())) {
            return false;
        }
        if (c.active() != null && p.isActive() != c.active()) {
            return false;
        }
        if (!isBlank(c.gender()) && !c.gender().trim().equalsIgnoreCase(p.getGender())) {
            return false;
        }
        for (StringSearchFilter filter : c.familyFilters()) {
            if (!matchesStringFilter(p.getFamilyName(), filter)) {
                return false;
            }
        }
        for (StringSearchFilter filter : c.givenFilters()) {
            if (!matchesStringFilter(p.getGivenName(), filter)) {
                return false;
            }
        }
        for (StringSearchFilter filter : c.nameFilters()) {
            String fullGivenFamily = joinNonBlank(p.getNamePrefix(), p.getGivenName(), p.getFamilyName(), p.getNameSuffix());
            String fullFamilyGiven = joinNonBlank(p.getFamilyName(), p.getGivenName());
            boolean hit = matchesStringFilter(p.getFamilyName(), filter)
                    || matchesStringFilter(p.getGivenName(), filter)
                    || matchesStringFilter(fullGivenFamily, filter)
                    || matchesStringFilter(fullFamilyGiven, filter)
                    || matchesStringFilter(p.getIdentifierValue(), filter);
            if (!hit) {
                return false;
            }
        }
        if (!isBlank(c.identifier())) {
            String raw = c.identifier().trim();
            int pipe = raw.indexOf('|');
            String sys = pipe >= 0 ? raw.substring(0, pipe) : null;
            String val = pipe >= 0 ? raw.substring(pipe + 1) : raw;
            if (p.getIdentifierValue() == null || !p.getIdentifierValue().equalsIgnoreCase(val)) {
                return false;
            }
            if (sys != null && (p.getIdentifierSystem() == null || !sys.equals(p.getIdentifierSystem()))) {
                return false;
            }
        }
        if (!isBlank(c.qualification())) {
            String needle = c.qualification().trim().toLowerCase(Locale.ROOT);
            boolean codeHit = p.getQualificationCode() != null && p.getQualificationCode().toLowerCase(Locale.ROOT).contains(needle);
            boolean dispHit = p.getQualificationDisplay() != null && p.getQualificationDisplay().toLowerCase(Locale.ROOT).contains(needle);
            if (!codeHit && !dispHit) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesStringFilter(String candidate, StringSearchFilter filter) {
        if (filter == null || isBlank(filter.value())) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        String needle = filter.modifier() == StringMatchModifier.EXACT
                ? filter.value()
                : filter.value().trim();
        return switch (filter.modifier()) {
            case PREFIX -> candidate.toLowerCase(Locale.ROOT).startsWith(needle.toLowerCase(Locale.ROOT));
            case CONTAINS -> candidate.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
            case EXACT -> candidate.equals(needle);
        };
    }

    private static void enforceIfMatch(PractitionerEntity existing, String ifMatch) {
        if (isBlank(ifMatch)) {
            return;
        }
        String currentEtag = "W/\"" + existing.getVersion() + "\"";
        String rawVersion = Long.toString(existing.getVersion());
        String trimmed = ifMatch.trim();
        String stripped = trimmed.replace("\"", "").replace("W/", "").trim();
        if (!Objects.equals(trimmed, currentEtag) && !Objects.equals(stripped, rawVersion)) {
            throw new OptimisticLockingFailureException(
                    "Stale If-Match ETag '" + ifMatch + "'; current version is " + currentEtag);
        }
    }

    private static void applyFhirFieldsToEntity(PractitionerEntity entity, Practitioner incoming) {
        if (!incoming.getIdentifier().isEmpty()) {
            Identifier ident = incoming.getIdentifierFirstRep();
            if (ident.hasSystem()) entity.setIdentifierSystem(ident.getSystem().trim());
            else if (isBlank(entity.getIdentifierSystem())) entity.setIdentifierSystem(DEFAULT_IDENT_SYSTEM);
            if (ident.hasValue()) entity.setIdentifierValue(ident.getValue().trim());
            if (ident.hasUse()) entity.setIdentifierUse(ident.getUse().toCode().toUpperCase(Locale.ROOT));
            if (ident.hasAssigner() && ident.getAssigner().hasDisplay()) {
                entity.setAssignerDisplay(ident.getAssigner().getDisplay());
            }
        }
        if (!incoming.getName().isEmpty()) {
            HumanName hn = incoming.getNameFirstRep();
            if (!hn.getPrefix().isEmpty()) entity.setNamePrefix(hn.getPrefixAsSingleText());
            if (hn.hasFamily()) entity.setFamilyName(hn.getFamily());
            if (!hn.getGiven().isEmpty()) {
                entity.setGivenName(String.join(" ", hn.getGiven().stream().map(StringType::getValue).toList()));
            } else if (hn.hasText() && isBlank(entity.getGivenName())) {
                entity.setGivenName(hn.getText());
            }
            if (!hn.getSuffix().isEmpty()) entity.setNameSuffix(hn.getSuffixAsSingleText());
        }
        if (incoming.hasActive()) entity.setActive(incoming.getActive());
        if (incoming.hasGender()) entity.setGender(incoming.getGender().toCode().toUpperCase(Locale.ROOT));
        if (incoming.hasBirthDate()) {
            entity.setBirthDate(incoming.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
        }
        for (ContactPoint cp : incoming.getTelecom()) {
            if (!cp.hasValue()) continue;
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL) {
                entity.setEmail(cp.getValue().trim());
            } else if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE || cp.getSystem() == null) {
                entity.setPhone(cp.getValue().trim());
            }
        }
        if (!incoming.getAddress().isEmpty()) {
            Address addr = incoming.getAddressFirstRep();
            if (addr.hasUse()) entity.setAddressUse(addr.getUse().toCode().toUpperCase(Locale.ROOT));
            if (!addr.getLine().isEmpty()) {
                entity.setAddressLine(String.join(", ", addr.getLine().stream().map(StringType::getValue).toList()));
            }
            if (addr.hasCity()) entity.setCity(addr.getCity());
            if (addr.hasDistrict()) entity.setDistrict(addr.getDistrict());
            if (addr.hasState()) entity.setState(addr.getState());
            if (addr.hasPostalCode()) entity.setPostalCode(addr.getPostalCode());
            if (addr.hasCountry()) entity.setCountry(addr.getCountry());
        }
        if (!incoming.getQualification().isEmpty()) {
            Practitioner.PractitionerQualificationComponent q = incoming.getQualificationFirstRep();
            if (q.hasCode()) {
                if (!q.getCode().getCoding().isEmpty()) {
                    Coding coding = q.getCode().getCodingFirstRep();
                    if (coding.hasCode()) entity.setQualificationCode(coding.getCode());
                    if (coding.hasDisplay()) entity.setQualificationDisplay(coding.getDisplay());
                }
                if (q.getCode().hasText()) {
                    entity.setQualificationDisplay(q.getCode().getText());
                }
            }
            if (q.hasIssuer() && q.getIssuer().hasDisplay()) {
                entity.setQualificationIssuer(q.getIssuer().getDisplay());
            }
        }
        if (!incoming.getCommunication().isEmpty() && !incoming.getCommunicationFirstRep().getCoding().isEmpty()) {
            String code = incoming.getCommunicationFirstRep().getCodingFirstRep().getCode();
            if (!isBlank(code)) {
                entity.setCommunicationCode(code);
            }
        }
    }

    private void emitOutboxEvent(String aggType, UUID aggId, String eventType, String json) {
        if (outboxRepo == null) return;
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(aggType + ":" + eventType + ":" + aggId + ":" + evt.getId());
        evt.setAggregateType(aggType);
        evt.setAggregateId(aggId);
        evt.setEventType(eventType);
        evt.setPayloadJson(json);
        outboxRepo.save(evt);
    }

    private static boolean hasUsableName(HumanName hn) {
        return !isBlank(hn.getFamily()) || !hn.getGiven().isEmpty() || !isBlank(hn.getText());
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!isBlank(part)) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
