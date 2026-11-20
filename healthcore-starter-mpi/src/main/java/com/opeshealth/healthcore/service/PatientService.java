/*
 * =============================================================================
 * Opes HealthCore SDK — FHIR R4 Patient CRUD & Validation Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientDemographicsEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientIdentifierEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientPhoneticIndexEntity;
import com.opeshealth.healthcore.mpi.fhir.FhirPatientController;
import com.opeshealth.healthcore.mpi.fhir.FhirHumanNameFormatter;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientDemographicsRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientIdentifierRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientPhoneticRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Full CRUD service for {@link PatientAggregateEntity} backed by {@link PatientRepository}
 * with HL7 FHIR R4 validation rules, blind-indexed identifier uniqueness checks,
 * standard FHIR query parameter search, and optimistic concurrency enforcement.
 */
public class PatientService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String MRN_SYSTEM = "urn:opes:mrn";
    private static final String NATIONAL_ID_SYSTEM = "urn:opes:national-id";
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);
    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1880, 1, 1);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> VALID_FHIR_GENDERS = Set.of("male", "female", "other", "unknown");

    /** HL7 FHIR R4 string search modifiers. */
    public enum StringMatchModifier {
        PREFIX,
        CONTAINS,
        EXACT
    }

    public record StringSearchFilter(String value, StringMatchModifier modifier) {
        public StringSearchFilter {
            modifier = modifier != null ? modifier : StringMatchModifier.PREFIX;
        }

        public static StringSearchFilter prefix(String value) {
            return new StringSearchFilter(value, StringMatchModifier.PREFIX);
        }

        public static StringSearchFilter contains(String value) {
            return new StringSearchFilter(value, StringMatchModifier.CONTAINS);
        }

        public static StringSearchFilter exact(String value) {
            return new StringSearchFilter(value, StringMatchModifier.EXACT);
        }
    }

    public record PatientSearchCriteria(
            UUID id,
            UUID facilityId,
            String identifier,
            List<StringSearchFilter> nameFilters,
            List<StringSearchFilter> familyFilters,
            List<StringSearchFilter> givenFilters,
            List<String> birthdateExpressions,
            LocalDate birthdateFrom,
            LocalDate birthdateTo,
            String gender,
            String phone,
            String email,
            Boolean active,
            int offset,
            int count
    ) {
        public PatientSearchCriteria {
            nameFilters = nameFilters != null ? List.copyOf(nameFilters) : List.of();
            familyFilters = familyFilters != null ? List.copyOf(familyFilters) : List.of();
            givenFilters = givenFilters != null ? List.copyOf(givenFilters) : List.of();
            birthdateExpressions = normalizeBirthdateExpressions(birthdateExpressions);
            if (birthdateFrom != null && birthdateTo != null && birthdateFrom.isAfter(birthdateTo)) {
                throw new IllegalArgumentException("birthdateFrom (" + birthdateFrom + ") cannot be after birthdateTo (" + birthdateTo + ")");
            }
            if (offset < 0) offset = 0;
            if (count <= 0) count = 20;
            if (count > 100) count = 100;
        }

        /** Backward-compatible 13-argument constructor for simple query criteria. */
        public PatientSearchCriteria(
                UUID id,
                UUID facilityId,
                String identifier,
                String name,
                String family,
                String given,
                String birthdate,
                String gender,
                String phone,
                String email,
                Boolean active,
                int offset,
                int count) {
            this(
                    id,
                    facilityId,
                    identifier,
                    name != null && !name.isBlank() ? List.of(StringSearchFilter.prefix(name)) : List.of(),
                    family != null && !family.isBlank() ? List.of(StringSearchFilter.prefix(family)) : List.of(),
                    given != null && !given.isBlank() ? List.of(StringSearchFilter.prefix(given)) : List.of(),
                    birthdate != null && !birthdate.isBlank() ? List.of(birthdate) : List.of(),
                    null,
                    null,
                    gender,
                    phone,
                    email,
                    active,
                    offset,
                    count
            );
        }

        private static List<String> normalizeBirthdateExpressions(List<String> raw) {
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<String> expanded = new ArrayList<>();
            for (String item : raw) {
                if (item == null || item.isBlank()) continue;
                for (String token : item.split(",")) {
                    String trimmed = token.trim();
                    if (!trimmed.isEmpty()) {
                        expanded.add(trimmed);
                    }
                }
            }
            return List.copyOf(expanded);
        }
    }

    public record PatientSearchResult(
            List<PatientAggregateEntity> patients,
            int total,
            int offset,
            int count
    ) {}

    /**
     * HAPI FHIR R4 structural &amp; schema validator embedded within {@link PatientService}.
     * Enforces strict JSON parsing via {@link StrictErrorHandler} (rejecting unknown attributes,
     * malformed primitive types, and wrong {@code resourceType}) and validates resources against
     * the standard HL7 FHIR R4 XSD schema via {@link FhirValidator}.
     */
    public static final class FhirPatientValidatorService {

        private final FhirContext fhirContext;
        private final FhirValidator schemaValidator;

        public FhirPatientValidatorService() {
            this(FHIR_R4);
        }

        public FhirPatientValidatorService(FhirContext fhirContext) {
            this.fhirContext = Objects.requireNonNull(fhirContext, "FhirContext is required");
            this.schemaValidator = this.fhirContext.newValidator();
            this.schemaValidator.setValidateAgainstStandardSchema(true);
            this.schemaValidator.setValidateAgainstStandardSchematron(false);
        }

        public Patient parseStrict(String fhirPatientJson) {
            if (isBlank(fhirPatientJson)) {
                throw new IllegalArgumentException("FHIR Patient JSON payload must not be empty");
            }
            try {
                IParser strictParser = fhirContext.newJsonParser()
                        .setParserErrorHandler(new StrictErrorHandler());
                Patient parsed = strictParser.parseResource(Patient.class, fhirPatientJson);
                if (parsed == null || !"Patient".equals(parsed.fhirType())) {
                    throw new IllegalArgumentException("FHIR payload resourceType must be 'Patient'");
                }
                return parsed;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("HL7 FHIR R4 strict schema parse failure: " + ex.getMessage(), ex);
            }
        }

        public ValidationResult validateWithResult(Patient patient) {
            if (patient == null) {
                throw new IllegalArgumentException("FHIR R4 Patient resource must not be null");
            }
            return schemaValidator.validateWithResult(patient);
        }

        public OperationOutcome validateToOperationOutcome(Patient patient) {
            ValidationResult result = validateWithResult(patient);
            return result.toOperationOutcome() instanceof OperationOutcome oo ? oo : new OperationOutcome();
        }

        public void validateAgainstSchema(Patient patient) {
            ValidationResult result = validateWithResult(patient);
            if (!result.isSuccessful()) {
                List<String> diagnostics = result.getMessages().stream()
                        .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
                        .map(SingleValidationMessage::toString)
                        .toList();
                String summary = diagnostics.isEmpty()
                        ? result.getMessages().stream().map(SingleValidationMessage::toString).reduce((a, b) -> a + "; " + b).orElse("Unknown FHIR R4 schema violation")
                        : String.join("; ", diagnostics);
                throw new IllegalArgumentException("HL7 FHIR R4 Patient schema validation failed: " + summary);
            }
        }
    }

    private final PatientRepository patientRepo;
    private final PatientIdentifierRepository identifierRepo;
    private final PatientDemographicsRepository demographicsRepo;
    private final PatientPhoneticRepository phoneticRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final FhirPatientValidatorService fhirValidatorService;

    public PatientService(PatientRepository patientRepo) {
        this(patientRepo, null, null, null, null);
    }

    public PatientService(
            PatientRepository patientRepo,
            PatientIdentifierRepository identifierRepo,
            PatientDemographicsRepository demographicsRepo) {
        this(patientRepo, identifierRepo, demographicsRepo, null, null);
    }

    public PatientService(
            PatientRepository patientRepo,
            PatientIdentifierRepository identifierRepo,
            PatientDemographicsRepository demographicsRepo,
            PatientPhoneticRepository phoneticRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.patientRepo = Objects.requireNonNull(patientRepo, "PatientRepository is required");
        this.identifierRepo = identifierRepo;
        this.demographicsRepo = demographicsRepo;
        this.phoneticRepo = phoneticRepo;
        this.outboxRepo = outboxRepo;
        this.fhirValidatorService = new FhirPatientValidatorService(FHIR_R4);
    }

    public FhirPatientValidatorService getFhirValidatorService() {
        return fhirValidatorService;
    }

    public ValidationResult validatePatientSchema(Patient fhirPatient) {
        return fhirValidatorService.validateWithResult(fhirPatient);
    }

    /** Validates an entity before persistence, throwing {@link IllegalArgumentException} on rule violations. */
    public void validateEntity(PatientAggregateEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Patient entity must not be null");
        }
        if (entity.getFacilityId() == null) {
            throw new IllegalArgumentException("Patient facilityId is required");
        }
        if (isBlank(entity.getFamilyName())) {
            throw new IllegalArgumentException("Patient familyName is required");
        }
        if (isBlank(entity.getGivenName())) {
            throw new IllegalArgumentException("Patient givenName is required");
        }
        if (entity.getBirthDate() == null) {
            throw new IllegalArgumentException("Patient birthDate is required");
        }
        LocalDate today = LocalDate.now();
        if (entity.getBirthDate().isAfter(today)) {
            throw new IllegalArgumentException("Patient birthDate cannot be in the future: " + entity.getBirthDate());
        }
        if (entity.getBirthDate().isBefore(MIN_BIRTH_DATE)) {
            throw new IllegalArgumentException("Patient birthDate precedes minimum supported date 1880-01-01");
        }
        if (isBlank(entity.getGender()) || !VALID_FHIR_GENDERS.contains(entity.getGender().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Patient gender must be one of HL7 FHIR AdministrativeGender: male, female, other, unknown");
        }
        if (!isBlank(entity.getEmail()) && !EMAIL_PATTERN.matcher(entity.getEmail().trim()).matches()) {
            throw new IllegalArgumentException("Invalid email format for Patient telecom");
        }
        if (entity.getDeceasedAt() != null && entity.getDeceasedAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Patient deceasedDateTime cannot be in the future");
        }
    }

    /**
     * Enforces HL7 FHIR R4 {@code Patient} business validation rules before persistence:
     * required human name, valid AdministrativeGender, plausible birthDate/deceasedDateTime,
     * valid telecom contact values, and non-duplicate MRN / blind-indexed identifiers.
     */
    public void validateFhirPatient(Patient fhirPatient, UUID facilityId, boolean isCreate, UUID existingPatientId) {
        fhirValidatorService.validateAgainstSchema(fhirPatient);
        if (fhirPatient.getName().isEmpty() || fhirPatient.getName().stream().noneMatch(PatientService::hasUsableName)) {
            throw new IllegalArgumentException("FHIR Patient.name must include at least one HumanName with family, given, or text");
        }
        if (isCreate && !fhirPatient.hasBirthDate()) {
            throw new IllegalArgumentException("FHIR Patient.birthDate is required");
        }
        if (fhirPatient.hasBirthDate()) {
            LocalDate dob = fhirPatient.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate today = LocalDate.now();
            if (dob.isAfter(today)) {
                throw new IllegalArgumentException("FHIR Patient.birthDate cannot be in the future: " + dob);
            }
            if (dob.isBefore(MIN_BIRTH_DATE)) {
                throw new IllegalArgumentException("FHIR Patient.birthDate precedes 1880-01-01: " + dob);
            }
            if (fhirPatient.hasDeceasedDateTimeType() && fhirPatient.getDeceasedDateTimeType().hasValue()) {
                LocalDate deceasedDate = fhirPatient.getDeceasedDateTimeType().getValue().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDate();
                if (deceasedDate.isBefore(dob)) {
                    throw new IllegalArgumentException("FHIR Patient.deceasedDateTime cannot precede birthDate");
                }
            }
        }
        if (fhirPatient.hasDeceasedDateTimeType() && fhirPatient.getDeceasedDateTimeType().hasValue()
                && fhirPatient.getDeceasedDateTimeType().getValue().toInstant().isAfter(Instant.now())) {
            throw new IllegalArgumentException("FHIR Patient.deceasedDateTime cannot be in the future");
        }
        if (isCreate && !fhirPatient.hasGender()) {
            throw new IllegalArgumentException("FHIR Patient.gender is required (male | female | other | unknown)");
        }
        if (fhirPatient.hasGender()) {
            AdministrativeGender g = fhirPatient.getGender();
            if (g == null || !VALID_FHIR_GENDERS.contains(g.toCode().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Unsupported FHIR Patient.gender; expected male, female, other, or unknown");
            }
        }
        for (ContactPoint cp : fhirPatient.getTelecom()) {
            if (!cp.hasValue() || isBlank(cp.getValue())) {
                throw new IllegalArgumentException("FHIR Patient.telecom entry must have a non-blank value");
            }
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL
                    && !EMAIL_PATTERN.matcher(cp.getValue().trim()).matches()) {
                throw new IllegalArgumentException("Invalid FHIR Patient.telecom email address: " + cp.getValue());
            }
            if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE
                    && cp.getValue().replaceAll("[^0-9]", "").length() < 5) {
                throw new IllegalArgumentException("Invalid FHIR Patient.telecom phone number: " + cp.getValue());
            }
        }
        UUID scopedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        for (Identifier ident : fhirPatient.getIdentifier()) {
            if (!ident.hasValue() || isBlank(ident.getValue())) {
                throw new IllegalArgumentException("FHIR Patient.identifier entries must specify a non-blank value");
            }
            String sys = ident.hasSystem() ? ident.getSystem() : NATIONAL_ID_SYSTEM;
            if (MRN_SYSTEM.equals(sys)) {
                patientRepo.findByFacilityIdAndMrn(scopedFacility, ident.getValue().trim()).ifPresent(found -> {
                    if (isCreate || !Objects.equals(found.getId(), existingPatientId)) {
                        throw new IllegalStateException("Duplicate MRN in facility: " + ident.getValue());
                    }
                });
            } else if (identifierRepo != null) {
                String blind = PiiVaultCrypto.blindIndex(ident.getValue().trim());
                identifierRepo.findByIdentifierSystemAndBlindIndex(sys, blind).ifPresent(found -> {
                    if (isCreate || !Objects.equals(found.getPatientId(), existingPatientId)) {
                        throw new IllegalStateException("Duplicate identifier already registered for system " + sys);
                    }
                });
            }
        }
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_CREATE", resourceType = "Patient")
    public PatientAggregateEntity createPatient(PatientAggregateEntity entity) {
        if (entity.getFacilityId() == null) {
            entity.setFacilityId(DEFAULT_FACILITY_ID);
        }
        if (isBlank(entity.getMrn())) {
            entity.setMrn("OPES-" + System.currentTimeMillis());
        }
        validateEntity(entity);
        patientRepo.findByFacilityIdAndMrn(entity.getFacilityId(), entity.getMrn()).ifPresent(existing -> {
            throw new IllegalStateException("Duplicate MRN in facility: " + entity.getMrn());
        });
        entity.setGender(entity.getGender().trim().toUpperCase(Locale.ROOT));
        entity.setUpdatedAt(Instant.now());
        fhirValidatorService.validateAgainstSchema(FhirPatientController.toFhirPatient(entity, List.of(), null));
        PatientAggregateEntity saved = patientRepo.save(entity);
        syncPhonetics(saved);
        emitOutboxEvent("Patient", saved.getId(), "PatientCreatedEvent",
                "{\"patientId\":\"" + saved.getId() + "\",\"mrn\":\"" + saved.getMrn() + "\"}");
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_CREATE_FHIR", resourceType = "Patient")
    public Patient createFromFhirJson(UUID facilityId, String fhirPatientJson) {
        Patient incoming = parseFhirPatient(fhirPatientJson);
        UUID resolvedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        validateFhirPatient(incoming, resolvedFacility, true, null);

        PatientAggregateEntity entity = new PatientAggregateEntity();
        entity.setFacilityId(resolvedFacility);
        String mrn = extractMrn(incoming);
        entity.setMrn(!isBlank(mrn) ? mrn : "OPES-" + System.currentTimeMillis());
        applyFhirFieldsToEntity(entity, incoming);
        validateEntity(entity);

        PatientAggregateEntity saved = patientRepo.save(entity);
        syncPhonetics(saved);
        upsertIdentifiers(saved.getId(), incoming);
        PatientDemographicsEntity demo = upsertDemographics(saved.getId(), incoming);
        emitOutboxEvent("Patient", saved.getId(), "PatientCreatedEvent",
                "{\"patientId\":\"" + saved.getId() + "\",\"mrn\":\"" + saved.getMrn() + "\"}");

        return hydrateFhirPatient(saved, demo);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PATIENT_READ", resourceType = "Patient")
    public PatientAggregateEntity getPatientById(UUID id) {
        PatientAggregateEntity found = patientRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + id));
        return resolveSurviving(found);
    }

    @Transactional(readOnly = true)
    public Optional<PatientAggregateEntity> findPatientById(UUID id) {
        return patientRepo.findById(id).map(this::resolveSurviving);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PATIENT_READ_FHIR", resourceType = "Patient")
    public Patient getFhirPatientById(UUID id) {
        PatientAggregateEntity entity = getPatientById(id);
        return hydrateFhirPatient(entity, null);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PATIENT_SEARCH", resourceType = "Patient")
    public PatientSearchResult searchPatients(PatientSearchCriteria criteria) {
        // Validate birthdate expressions upfront even if candidate pool is empty
        for (String expr : criteria.birthdateExpressions()) {
            parseBirthDatePredicate(expr);
        }
        if (criteria.id() != null) {
            Optional<PatientAggregateEntity> one = findPatientById(criteria.id())
                    .filter(p -> matchesCriteria(p, criteria));
            List<PatientAggregateEntity> list = one.map(List::of).orElseGet(List::of);
            return new PatientSearchResult(list, list.size(), criteria.offset(), criteria.count());
        }

        List<PatientAggregateEntity> candidates = resolveCandidatePool(criteria);
        List<PatientAggregateEntity> matched = candidates.stream()
                .map(this::resolveSurviving)
                .distinct()
                .filter(p -> matchesCriteria(p, criteria))
                .sorted(Comparator.comparing(PatientAggregateEntity::getFamilyName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(PatientAggregateEntity::getGivenName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        int total = matched.size();
        int from = Math.min(criteria.offset(), total);
        int to = Math.min(from + criteria.count(), total);
        return new PatientSearchResult(matched.subList(from, to), total, criteria.offset(), criteria.count());
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "PATIENT_SEARCH_FHIR", resourceType = "Patient")
    public Bundle searchFhirBundle(PatientSearchCriteria criteria) {
        PatientSearchResult result = searchPatients(criteria);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.total());
        for (PatientAggregateEntity p : result.patients()) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl("Patient/" + p.getId());
            entry.setResource(hydrateFhirPatient(p, null));
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_UPDATE", resourceType = "Patient")
    public PatientAggregateEntity updatePatient(UUID id, PatientAggregateEntity updates, Long expectedVersion) {
        PatientAggregateEntity existing = getPatientById(id);
        if (expectedVersion != null && existing.getVersion() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Stale Patient version; expected W/\"" + existing.getVersion() + "\"");
        }
        if (!isBlank(updates.getNamePrefix())) existing.setNamePrefix(updates.getNamePrefix());
        if (!isBlank(updates.getFamilyName())) existing.setFamilyName(updates.getFamilyName());
        if (!isBlank(updates.getGivenName())) existing.setGivenName(updates.getGivenName());
        if (updates.getMiddleName() != null) existing.setMiddleName(updates.getMiddleName());
        if (updates.getBirthDate() != null) existing.setBirthDate(updates.getBirthDate());
        if (!isBlank(updates.getGender())) existing.setGender(updates.getGender().trim().toUpperCase(Locale.ROOT));
        if (updates.getPrimaryPhone() != null) existing.setPrimaryPhone(updates.getPrimaryPhone());
        if (updates.getSecondaryPhone() != null) existing.setSecondaryPhone(updates.getSecondaryPhone());
        if (updates.getEmail() != null) existing.setEmail(updates.getEmail());
        if (updates.getMaritalStatusCode() != null) existing.setMaritalStatusCode(updates.getMaritalStatusCode());
        if (!isBlank(updates.getPreferredLanguage())) existing.setPreferredLanguage(updates.getPreferredLanguage());
        existing.setActive(updates.isActive());
        existing.setDeceased(updates.isDeceased());
        existing.setDeceasedAt(updates.getDeceasedAt());
        existing.setUpdatedAt(Instant.now());

        validateEntity(existing);
        fhirValidatorService.validateAgainstSchema(FhirPatientController.toFhirPatient(existing, List.of(), null));
        PatientAggregateEntity saved = patientRepo.save(existing);
        syncPhonetics(saved);
        emitOutboxEvent("Patient", saved.getId(), "PatientUpdatedEvent",
                "{\"patientId\":\"" + saved.getId() + "\",\"mrn\":\"" + saved.getMrn() + "\"}");
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_UPDATE_FHIR", resourceType = "Patient")
    public Patient updateFromFhirJson(UUID id, String fhirPatientJson, String ifMatchHeader) {
        PatientAggregateEntity existing = getPatientById(id);
        enforceIfMatch(existing, ifMatchHeader);

        Patient incoming = parseFhirPatient(fhirPatientJson);
        validateFhirPatient(incoming, existing.getFacilityId(), false, existing.getId());
        applyFhirFieldsToEntity(existing, incoming);
        existing.setUpdatedAt(Instant.now());
        validateEntity(existing);

        PatientAggregateEntity saved = patientRepo.save(existing);
        syncPhonetics(saved);
        upsertIdentifiers(saved.getId(), incoming);
        PatientDemographicsEntity demo = upsertDemographics(saved.getId(), incoming);
        emitOutboxEvent("Patient", saved.getId(), "PatientUpdatedEvent",
                "{\"patientId\":\"" + saved.getId() + "\",\"mrn\":\"" + saved.getMrn() + "\"}");

        return hydrateFhirPatient(saved, demo);
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_DEACTIVATE", resourceType = "Patient")
    public PatientAggregateEntity deactivatePatient(UUID id) {
        PatientAggregateEntity existing = getPatientById(id);
        existing.setActive(false);
        existing.setUpdatedAt(Instant.now());
        PatientAggregateEntity saved = patientRepo.save(existing);
        emitOutboxEvent("Patient", saved.getId(), "PatientDeactivatedEvent",
                "{\"patientId\":\"" + saved.getId() + "\"}");
        return saved;
    }

    @Transactional
    @ClinicalAudit(action = "PATIENT_DELETE", resourceType = "Patient")
    public void deletePatient(UUID id) {
        PatientAggregateEntity existing = getPatientById(id);
        if (demographicsRepo != null) {
            demographicsRepo.deleteByPatientId(existing.getId());
        }
        patientRepo.deleteById(existing.getId());
        emitOutboxEvent("Patient", existing.getId(), "PatientDeletedEvent",
                "{\"patientId\":\"" + existing.getId() + "\"}");
    }

    public String encodeFhirResource(org.hl7.fhir.instance.model.api.IBaseResource resource) {
        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        return json.encodeResourceToString(resource);
    }

    public Patient parseFhirPatient(String fhirPatientJson) {
        return fhirValidatorService.parseStrict(fhirPatientJson);
    }

    private Patient hydrateFhirPatient(PatientAggregateEntity entity, PatientDemographicsEntity preloadedDemo) {
        List<PatientIdentifierEntity> idents = identifierRepo != null
                ? identifierRepo.findByPatientIdOrderByIdentifierSystemAsc(entity.getId())
                : List.of();
        PatientDemographicsEntity demo = preloadedDemo != null
                ? preloadedDemo
                : (demographicsRepo != null ? demographicsRepo.findByPatientId(entity.getId()).orElse(null) : null);
        return FhirPatientController.toFhirPatient(entity, idents, demo);
    }

    private List<PatientAggregateEntity> resolveCandidatePool(PatientSearchCriteria c) {
        if (!isBlank(c.identifier())) {
            String raw = c.identifier().trim();
            int pipe = raw.indexOf('|');
            String sys = pipe >= 0 ? raw.substring(0, pipe) : null;
            String val = pipe >= 0 ? raw.substring(pipe + 1) : raw;

            if (c.facilityId() != null && (sys == null || MRN_SYSTEM.equals(sys))) {
                Optional<PatientAggregateEntity> byMrn = patientRepo.findByFacilityIdAndMrn(c.facilityId(), val);
                if (byMrn.isPresent()) {
                    return List.of(byMrn.get());
                }
            }
            if (identifierRepo != null) {
                String lookupSystem = !isBlank(sys) ? sys : NATIONAL_ID_SYSTEM;
                Optional<PatientIdentifierEntity> byBlind = identifierRepo
                        .findByIdentifierSystemAndBlindIndex(lookupSystem, PiiVaultCrypto.blindIndex(val));
                if (byBlind.isPresent()) {
                    return patientRepo.findById(byBlind.get().getPatientId()).map(List::of).orElseGet(List::of);
                }
            }
        }
        if (c.facilityId() != null && !isBlank(c.email()) && !Boolean.FALSE.equals(c.active())) {
            Optional<PatientAggregateEntity> byEmail = patientRepo.findByFacilityIdAndEmailIgnoreCase(c.facilityId(), c.email().trim());
            if (byEmail.isPresent()) {
                return List.of(byEmail.get());
            }
        }
        if (c.facilityId() != null && !isBlank(c.phone()) && !Boolean.FALSE.equals(c.active())) {
            List<PatientAggregateEntity> byPhone = patientRepo.findByFacilityIdAndPrimaryPhone(c.facilityId(), c.phone().trim());
            if (!byPhone.isEmpty()) {
                return byPhone;
            }
        }
        if (c.facilityId() != null && Boolean.TRUE.equals(c.active())) {
            return patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(c.facilityId());
        }
        return patientRepo.findAll();
    }

    private boolean matchesCriteria(PatientAggregateEntity p, PatientSearchCriteria c) {
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
            boolean hit = matchesStringFilter(p.getGivenName(), filter)
                    || matchesStringFilter(p.getMiddleName(), filter);
            if (!hit) {
                return false;
            }
        }
        for (StringSearchFilter filter : c.nameFilters()) {
            String fullGivenFamily = joinNonBlank(p.getGivenName(), p.getMiddleName(), p.getFamilyName());
            String fullFamilyGiven = joinNonBlank(p.getFamilyName(), p.getGivenName(), p.getMiddleName());
            boolean hit = matchesStringFilter(p.getFamilyName(), filter)
                    || matchesStringFilter(p.getGivenName(), filter)
                    || matchesStringFilter(p.getMiddleName(), filter)
                    || matchesStringFilter(fullGivenFamily, filter)
                    || matchesStringFilter(fullFamilyGiven, filter)
                    || matchesStringFilter(p.getMrn(), filter);
            if (!hit) {
                return false;
            }
        }
        if (!isBlank(c.phone())) {
            String needle = c.phone().trim();
            boolean hit = needle.equals(p.getPrimaryPhone()) || needle.equals(p.getSecondaryPhone());
            if (!hit) return false;
        }
        if (!isBlank(c.email()) && (p.getEmail() == null || !c.email().trim().equalsIgnoreCase(p.getEmail()))) {
            return false;
        }
        if (c.birthdateFrom() != null && (p.getBirthDate() == null || p.getBirthDate().isBefore(c.birthdateFrom()))) {
            return false;
        }
        if (c.birthdateTo() != null && (p.getBirthDate() == null || p.getBirthDate().isAfter(c.birthdateTo()))) {
            return false;
        }
        for (String expr : c.birthdateExpressions()) {
            if (!matchesBirthDate(p.getBirthDate(), expr)) {
                return false;
            }
        }
        if (!isBlank(c.identifier()) && !matchesIdentifier(p, c.identifier().trim())) {
            return false;
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

    private boolean matchesIdentifier(PatientAggregateEntity p, String rawIdentifier) {
        int pipe = rawIdentifier.indexOf('|');
        String sys = pipe >= 0 ? rawIdentifier.substring(0, pipe) : null;
        String val = pipe >= 0 ? rawIdentifier.substring(pipe + 1) : rawIdentifier;

        if ((sys == null || MRN_SYSTEM.equals(sys)) && p.getMrn() != null && p.getMrn().equalsIgnoreCase(val)) {
            return true;
        }
        if (identifierRepo != null) {
            String blind = PiiVaultCrypto.blindIndex(val);
            return identifierRepo.findByPatientId(p.getId()).stream()
                    .anyMatch(ident -> (sys == null || sys.equals(ident.getIdentifierSystem()))
                            && blind.equals(ident.getBlindIndex()));
        }
        return false;
    }

    private record ParsedBirthDateQuery(String op, LocalDate start, LocalDate end) {}

    private static ParsedBirthDateQuery parseBirthDatePredicate(String expr) {
        String op = "eq";
        String datePart = expr;
        if (expr.length() > 2 && Character.isLetter(expr.charAt(0)) && Character.isLetter(expr.charAt(1))) {
            op = expr.substring(0, 2).toLowerCase(Locale.ROOT);
            datePart = expr.substring(2);
        }
        LocalDate start;
        LocalDate end;
        try {
            if (datePart.length() == 4) {
                int y = Year.parse(datePart).getValue();
                start = LocalDate.of(y, 1, 1);
                end = LocalDate.of(y, 12, 31);
            } else if (datePart.length() == 7) {
                YearMonth ym = YearMonth.parse(datePart);
                start = ym.atDay(1);
                end = ym.atEndOfMonth();
            } else {
                LocalDate exact = LocalDate.parse(datePart);
                start = exact;
                end = exact;
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR birthdate query parameter: " + expr, ex);
        }
        if (!Set.of("eq", "ne", "gt", "lt", "ge", "le", "sa", "eb", "ap").contains(op)) {
            throw new IllegalArgumentException("Unsupported FHIR date search prefix: " + op);
        }
        return new ParsedBirthDateQuery(op, start, end);
    }

    private static boolean matchesBirthDate(LocalDate actual, String expr) {
        if (actual == null) return false;
        ParsedBirthDateQuery q = parseBirthDatePredicate(expr);
        return switch (q.op()) {
            case "eq" -> !actual.isBefore(q.start()) && !actual.isAfter(q.end());
            case "ne" -> actual.isBefore(q.start()) || actual.isAfter(q.end());
            case "gt", "sa" -> actual.isAfter(q.end());
            case "lt", "eb" -> actual.isBefore(q.start());
            case "ge" -> !actual.isBefore(q.start());
            case "le" -> !actual.isAfter(q.end());
            case "ap" -> !actual.isBefore(q.start().minusYears(1)) && !actual.isAfter(q.end().plusYears(1));
            default -> throw new IllegalArgumentException("Unsupported FHIR date search prefix: " + q.op());
        };
    }

    private static void enforceIfMatch(PatientAggregateEntity existing, String ifMatch) {
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

    private PatientAggregateEntity resolveSurviving(PatientAggregateEntity entity) {
        UUID pointer = entity.getSurvivingPatientId();
        if (!entity.isGoldenRecord() && pointer != null) {
            return patientRepo.findById(pointer).orElse(entity);
        }
        return entity;
    }

    private void syncPhonetics(PatientAggregateEntity p) {
        if (phoneticRepo == null || p.getBirthDate() == null) return;
        PatientPhoneticIndexEntity phon = new PatientPhoneticIndexEntity();
        phon.setPatientId(p.getId());
        phon.setFamilyMetaphone(FellegiSunterMatcher.phoneticKey(p.getFamilyName()));
        phon.setGivenMetaphone(FellegiSunterMatcher.phoneticKey(p.getGivenName()));
        phon.setBirthYear(p.getBirthDate().getYear());
        phoneticRepo.save(phon);
    }

    private void upsertIdentifiers(UUID patientId, Patient incoming) {
        if (identifierRepo == null) return;
        for (Identifier ident : incoming.getIdentifier()) {
            if (!ident.hasValue() || MRN_SYSTEM.equals(ident.getSystem())) {
                continue;
            }
            String sys = ident.hasSystem() ? ident.getSystem() : NATIONAL_ID_SYSTEM;
            PatientIdentifierEntity entity = identifierRepo
                    .findFirstByPatientIdAndIdentifierSystem(patientId, sys)
                    .orElseGet(() -> {
                        PatientIdentifierEntity created = new PatientIdentifierEntity();
                        created.setPatientId(patientId);
                        created.setIdentifierSystem(sys);
                        return created;
                    });
            entity.setEncryptedValue(ident.getValue().trim());
            if (ident.hasUse()) entity.setIdentifierUse(ident.getUse().toCode().toUpperCase(Locale.ROOT));
            if (ident.hasType() && !ident.getType().getCoding().isEmpty()) {
                entity.setTypeCode(ident.getType().getCodingFirstRep().getCode());
            }
            if (ident.hasAssigner() && ident.getAssigner().hasDisplay()) {
                entity.setAssignerName(ident.getAssigner().getDisplay());
            }
            identifierRepo.save(entity);
        }
    }

    private PatientDemographicsEntity upsertDemographics(UUID patientId, Patient incoming) {
        if (demographicsRepo == null) return null;
        PatientDemographicsEntity demo = demographicsRepo.findByPatientId(patientId)
                .orElseGet(() -> {
                    PatientDemographicsEntity created = new PatientDemographicsEntity();
                    created.setPatientId(patientId);
                    return created;
                });
        if (!incoming.getAddress().isEmpty()) {
            Address addr = incoming.getAddressFirstRep();
            if (addr.hasUse()) demo.setAddressUse(addr.getUse().toCode().toUpperCase(Locale.ROOT));
            if (!addr.getLine().isEmpty()) {
                demo.setAddressLine(String.join(", ", addr.getLine().stream().map(StringType::getValue).toList()));
            }
            if (addr.hasCity()) demo.setCityOrVillage(addr.getCity());
            if (addr.hasDistrict()) demo.setDistrict(addr.getDistrict());
            if (addr.hasState()) demo.setRegionOrState(addr.getState());
            if (addr.hasPostalCode()) demo.setPostalCode(addr.getPostalCode());
            if (addr.hasCountry()) demo.setCountry(addr.getCountry());
        }
        if (!incoming.getContact().isEmpty()) {
            Patient.ContactComponent cc = incoming.getContactFirstRep();
            if (cc.hasName()) {
                demo.setEmergencyContactName(cc.getName().hasText() ? cc.getName().getText() : FhirHumanNameFormatter.display(cc.getName()));
            }
            for (ContactPoint cp : cc.getTelecom()) {
                if (!cp.hasValue()) continue;
                if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL) demo.setEmergencyContactEmail(cp.getValue());
                else demo.setEmergencyContactPhone(cp.getValue());
            }
            if (!cc.getRelationship().isEmpty() && !cc.getRelationshipFirstRep().getCoding().isEmpty()) {
                Coding rel = cc.getRelationshipFirstRep().getCodingFirstRep();
                demo.setEmergencyRelationship(rel.hasDisplay() ? rel.getDisplay() : rel.getCode());
            }
        }
        if (!incoming.getCommunication().isEmpty() && incoming.getCommunicationFirstRep().hasPreferred()) {
            demo.setCommunicationPreferred(incoming.getCommunicationFirstRep().getPreferred());
        }
        return demographicsRepo.save(demo);
    }

    private static void applyFhirFieldsToEntity(PatientAggregateEntity entity, Patient incoming) {
        if (!incoming.getName().isEmpty()) {
            HumanName hn = incoming.getNameFirstRep();
            if (!hn.getPrefix().isEmpty()) entity.setNamePrefix(FhirHumanNameFormatter.prefixes(hn));
            if (hn.hasFamily()) entity.setFamilyName(hn.getFamily());
            List<StringType> givens = hn.getGiven();
            if (!givens.isEmpty()) {
                entity.setGivenName(givens.getFirst().getValue());
                if (givens.size() > 1) {
                    entity.setMiddleName(String.join(" ", givens.subList(1, givens.size()).stream().map(StringType::getValue).toList()));
                }
            } else if (hn.hasText() && isBlank(entity.getGivenName())) {
                entity.setGivenName(hn.getText());
            }
        }
        if (incoming.hasActive()) entity.setActive(incoming.getActive());
        if (incoming.hasGender()) entity.setGender(incoming.getGender().toCode().toUpperCase(Locale.ROOT));
        if (incoming.hasBirthDate()) {
            entity.setBirthDate(incoming.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
        }
        if (incoming.hasDeceasedBooleanType()) {
            entity.setDeceased(incoming.getDeceasedBooleanType().booleanValue());
            if (!entity.isDeceased()) entity.setDeceasedAt(null);
        } else if (incoming.hasDeceasedDateTimeType()) {
            entity.setDeceased(true);
            entity.setDeceasedAt(incoming.getDeceasedDateTimeType().getValue().toInstant());
        }
        if (incoming.hasMaritalStatus() && !incoming.getMaritalStatus().getCoding().isEmpty()) {
            entity.setMaritalStatusCode(incoming.getMaritalStatus().getCodingFirstRep().getCode());
        }
        if (!incoming.getCommunication().isEmpty() && incoming.getCommunicationFirstRep().hasLanguage()
                && !incoming.getCommunicationFirstRep().getLanguage().getCoding().isEmpty()) {
            entity.setPreferredLanguage(incoming.getCommunicationFirstRep().getLanguage().getCodingFirstRep().getCode());
        }
        boolean sawPrimaryPhone = false;
        for (ContactPoint cp : incoming.getTelecom()) {
            if (!cp.hasValue()) continue;
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL) {
                entity.setEmail(cp.getValue().trim());
            } else if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE || cp.getSystem() == null) {
                if (!sawPrimaryPhone) {
                    entity.setPrimaryPhone(cp.getValue().trim());
                    sawPrimaryPhone = true;
                } else {
                    entity.setSecondaryPhone(cp.getValue().trim());
                }
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

    private static String extractMrn(Patient incoming) {
        for (Identifier ident : incoming.getIdentifier()) {
            if (MRN_SYSTEM.equals(ident.getSystem()) && ident.hasValue()) {
                return ident.getValue().trim();
            }
        }
        return null;
    }

    private static boolean hasUsableName(HumanName hn) {
        return !isBlank(hn.getFamily()) || !hn.getGiven().isEmpty() || !isBlank(hn.getText());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
