/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Observation CRUD & Validation Service
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
import com.opeshealth.healthcore.dto.ObservationDto;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full CRUD and HL7 FHIR R4 validation service for {@link ObservationEntity}
 * backed by {@link ObservationRepository} with HAPI FHIR R4 schema validation
 * ({@link StrictErrorHandler} + {@link FhirValidator}), {@link ObservationDto}
 * interoperability, FHIR date-range/LOINC search, and ETag optimistic concurrency.
 */
@Service
@Transactional
public class ObservationService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String DEFAULT_IDENT_SYSTEM = "urn:opes:observation";
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);
    private static final Instant MIN_EFFECTIVE_AT = LocalDate.of(1900, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Pattern UUID_TAIL_PATTERN = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");
    private static final Set<String> VALID_FHIR_STATUSES = Set.of(
            "registered", "preliminary", "final", "amended",
            "corrected", "cancelled", "entered-in-error", "unknown");

    /**
     * HAPI FHIR R4 structural &amp; schema validator embedded within {@link ObservationService}.
     * Enforces strict JSON grammar via {@link StrictErrorHandler} and validates against
     * the standard HL7 FHIR R4 XSD schema via {@link FhirValidator}.
     */
    public static final class FhirObservationValidatorService {

        private final FhirContext fhirContext;
        private final FhirValidator schemaValidator;

        public FhirObservationValidatorService() {
            this(FHIR_R4);
        }

        public FhirObservationValidatorService(FhirContext fhirContext) {
            this.fhirContext = Objects.requireNonNull(fhirContext, "FhirContext is required");
            this.schemaValidator = this.fhirContext.newValidator();
            this.schemaValidator.setValidateAgainstStandardSchema(true);
            this.schemaValidator.setValidateAgainstStandardSchematron(false);
        }

        public Observation parseStrict(String fhirObservationJson) {
            if (isBlank(fhirObservationJson)) {
                throw new IllegalArgumentException("FHIR Observation JSON payload must not be empty");
            }
            try {
                IParser strictParser = fhirContext.newJsonParser()
                        .setParserErrorHandler(new StrictErrorHandler());
                Observation parsed = strictParser.parseResource(Observation.class, fhirObservationJson);
                if (parsed == null || !"Observation".equals(parsed.fhirType())) {
                    throw new IllegalArgumentException("FHIR payload resourceType must be 'Observation'");
                }
                return parsed;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("HL7 FHIR R4 strict schema parse failure: " + ex.getMessage(), ex);
            }
        }

        public ValidationResult validateWithResult(Observation observation) {
            if (observation == null) {
                throw new IllegalArgumentException("FHIR R4 Observation resource must not be null");
            }
            return schemaValidator.validateWithResult(observation);
        }

        public OperationOutcome validateToOperationOutcome(Observation observation) {
            ValidationResult result = validateWithResult(observation);
            return result.toOperationOutcome() instanceof OperationOutcome oo ? oo : new OperationOutcome();
        }

        public void validateAgainstSchema(Observation observation) {
            ValidationResult result = validateWithResult(observation);
            if (!result.isSuccessful()) {
                List<String> diagnostics = result.getMessages().stream()
                        .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
                        .map(SingleValidationMessage::toString)
                        .toList();
                String summary = diagnostics.isEmpty()
                        ? result.getMessages().stream().map(SingleValidationMessage::toString).reduce((a, b) -> a + "; " + b).orElse("Unknown FHIR R4 schema violation")
                        : String.join("; ", diagnostics);
                throw new IllegalArgumentException("HL7 FHIR R4 Observation schema validation failed: " + summary);
            }
        }
    }

    public record ObservationSearchCriteria(
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
            List<String> dateExpressions,
            LocalDate dateFrom,
            LocalDate dateTo,
            int offset,
            int count
    ) {
        public ObservationSearchCriteria {
            dateExpressions = normalizeDateExpressions(dateExpressions);
            if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
                throw new IllegalArgumentException("dateFrom (" + dateFrom + ") cannot be after dateTo (" + dateTo + ")");
            }
            if (offset < 0) offset = 0;
            if (count <= 0) count = 20;
            if (count > 100) count = 100;
        }

        private static List<String> normalizeDateExpressions(List<String> raw) {
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

    public record ObservationSearchResult(
            List<ObservationEntity> observations,
            int total,
            int offset,
            int count
    ) {}

    private final ObservationRepository observationRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final FhirObservationValidatorService fhirValidatorService;

    public ObservationService(ObservationRepository observationRepo) {
        this(observationRepo, (OutboxRelayService.OutboxRepository) null);
    }

    @Autowired
    public ObservationService(
            ObservationRepository observationRepo,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider) {
        this(observationRepo, outboxRepoProvider != null ? outboxRepoProvider.getIfAvailable() : null);
    }

    public ObservationService(
            ObservationRepository observationRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.observationRepo = Objects.requireNonNull(observationRepo, "ObservationRepository is required");
        this.outboxRepo = outboxRepo;
        this.fhirValidatorService = new FhirObservationValidatorService(FHIR_R4);
    }

    public FhirObservationValidatorService getFhirValidatorService() {
        return fhirValidatorService;
    }

    public ValidationResult validateObservationSchema(Observation fhirObservation) {
        return fhirValidatorService.validateWithResult(fhirObservation);
    }

    public void validateEntity(ObservationEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Observation entity must not be null");
        }
        if (entity.getFacilityId() == null) {
            throw new IllegalArgumentException("Observation facilityId is required");
        }
        if (isBlank(entity.getStatus())
                || !VALID_FHIR_STATUSES.contains(entity.getStatus().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Observation status must be a valid HL7 FHIR ObservationStatus code");
        }
        if (isBlank(entity.getCode())) {
            throw new IllegalArgumentException("Observation code is required");
        }
        if (isBlank(entity.getSubjectReference())) {
            throw new IllegalArgumentException("Observation subject reference is required");
        }
        if (entity.getEffectiveAt() != null) {
            if (entity.getEffectiveAt().isBefore(MIN_EFFECTIVE_AT)) {
                throw new IllegalArgumentException("Observation effectiveDateTime precedes 1900-01-01");
            }
            if (entity.getEffectiveAt().isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
                throw new IllegalArgumentException("Observation effectiveDateTime cannot be in the future");
            }
        }
        if (entity.getRefRangeLow() != null && entity.getRefRangeHigh() != null
                && entity.getRefRangeLow().compareTo(entity.getRefRangeHigh()) > 0) {
            throw new IllegalArgumentException("Observation referenceRange.low cannot exceed referenceRange.high");
        }
    }

    public void validateFhirObservation(
            Observation fhirObservation,
            UUID facilityId,
            boolean isCreate,
            UUID existingObservationId) {
        fhirValidatorService.validateAgainstSchema(fhirObservation);

        if (!fhirObservation.hasStatus() || fhirObservation.getStatus() == null) {
            throw new IllegalArgumentException("FHIR Observation.status is required");
        }
        if (!fhirObservation.hasCode() || (!fhirObservation.getCode().hasText()
                && fhirObservation.getCode().getCoding().stream().noneMatch(c -> !isBlank(c.getCode())))) {
            throw new IllegalArgumentException("FHIR Observation.code must include at least one coding.code or code.text");
        }
        if (!fhirObservation.hasSubject() || !fhirObservation.getSubject().hasReference()
                || isBlank(fhirObservation.getSubject().getReference())) {
            throw new IllegalArgumentException("FHIR Observation.subject.reference is required (e.g., Patient/<uuid>)");
        }
        if (fhirObservation.hasEffectiveDateTimeType() && fhirObservation.getEffectiveDateTimeType().hasValue()) {
            Instant eff = fhirObservation.getEffectiveDateTimeType().getValue().toInstant();
            if (eff.isBefore(MIN_EFFECTIVE_AT)) {
                throw new IllegalArgumentException("FHIR Observation.effectiveDateTime precedes 1900-01-01");
            }
            if (eff.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
                throw new IllegalArgumentException("FHIR Observation.effectiveDateTime cannot be in the future");
            }
        }
        if (fhirObservation.hasValueQuantity() && !fhirObservation.getValueQuantity().hasValue()
                && !fhirObservation.hasDataAbsentReason()) {
            throw new IllegalArgumentException("FHIR Observation.valueQuantity requires a numeric value unless dataAbsentReason is set");
        }
        for (Observation.ObservationReferenceRangeComponent rr : fhirObservation.getReferenceRange()) {
            if (rr.hasLow() && rr.getLow().hasValue() && rr.hasHigh() && rr.getHigh().hasValue()
                    && rr.getLow().getValue().compareTo(rr.getHigh().getValue()) > 0) {
                throw new IllegalArgumentException("FHIR Observation.referenceRange.low cannot exceed referenceRange.high");
            }
        }
        for (Observation.ObservationComponentComponent comp : fhirObservation.getComponent()) {
            if (!comp.hasCode() || (!comp.getCode().hasText()
                    && comp.getCode().getCoding().stream().noneMatch(c -> !isBlank(c.getCode())))) {
                throw new IllegalArgumentException("FHIR Observation.component entries must specify a code");
            }
        }
        UUID scopedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        for (org.hl7.fhir.r4.model.Identifier ident : fhirObservation.getIdentifier()) {
            if (!ident.hasValue() || isBlank(ident.getValue())) {
                throw new IllegalArgumentException("FHIR Observation.identifier entries must specify a non-blank value");
            }
            String sys = ident.hasSystem() && !isBlank(ident.getSystem()) ? ident.getSystem().trim() : DEFAULT_IDENT_SYSTEM;
            String val = ident.getValue().trim();
            observationRepo.findByFacilityIdAndIdentifierSystemAndIdentifierValue(scopedFacility, sys, val)
                    .ifPresent(found -> {
                        if (isCreate || !Objects.equals(found.getId(), existingObservationId)) {
                            throw new IllegalStateException("Duplicate Observation identifier in facility: " + sys + "|" + val);
                        }
                    });
        }
    }

    @ClinicalAudit(action = "OBSERVATION_CREATE", resourceType = "Observation")
    public ObservationEntity createObservation(ObservationEntity entity) {
        if (entity.getFacilityId() == null) {
            entity.setFacilityId(DEFAULT_FACILITY_ID);
        }
        if (isBlank(entity.getStatus())) {
            entity.setStatus("final");
        }
        if (entity.getEffectiveAt() == null) {
            entity.setEffectiveAt(Instant.now());
        }
        if (entity.getIssuedAt() == null) {
            entity.setIssuedAt(Instant.now());
        }
        validateEntity(entity);
        if (!isBlank(entity.getIdentifierValue())) {
            String sys = !isBlank(entity.getIdentifierSystem()) ? entity.getIdentifierSystem().trim() : DEFAULT_IDENT_SYSTEM;
            entity.setIdentifierSystem(sys);
            observationRepo.findByFacilityIdAndIdentifierSystemAndIdentifierValue(
                            entity.getFacilityId(), sys, entity.getIdentifierValue().trim())
                    .ifPresent(existing -> {
                        throw new IllegalStateException("Duplicate Observation identifier in facility: " + sys + "|" + existing.getIdentifierValue());
                    });
        }
        entity.setStatus(entity.getStatus().trim().toLowerCase(Locale.ROOT));
        entity.setUpdatedAt(Instant.now());
        fhirValidatorService.validateAgainstSchema(toFhirObservation(entity));

        ObservationEntity saved = observationRepo.save(entity);
        emitOutboxEvent("Observation", saved.getId(), "ObservationCreatedEvent",
                "{\"observationId\":\"" + saved.getId() + "\",\"code\":\"" + saved.getCode() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "OBSERVATION_CREATE_FHIR", resourceType = "Observation")
    public Observation createFromFhirJson(UUID facilityId, String fhirObservationJson) {
        Observation incoming = parseFhirObservation(fhirObservationJson);
        UUID resolvedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        validateFhirObservation(incoming, resolvedFacility, true, null);

        ObservationEntity entity = new ObservationEntity();
        entity.setFacilityId(resolvedFacility);
        applyFhirFieldsToEntity(entity, incoming);
        if (entity.getEffectiveAt() == null) entity.setEffectiveAt(Instant.now());
        if (entity.getIssuedAt() == null) entity.setIssuedAt(Instant.now());
        validateEntity(entity);

        ObservationEntity saved = observationRepo.save(entity);
        emitOutboxEvent("Observation", saved.getId(), "ObservationCreatedEvent",
                "{\"observationId\":\"" + saved.getId() + "\",\"code\":\"" + saved.getCode() + "\"}");
        return toFhirObservation(saved);
    }

    public ObservationDto createFromDto(UUID facilityId, ObservationDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("ObservationDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return ObservationDto.fromFhirR4(createFromFhirJson(facilityId, json));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "OBSERVATION_READ", resourceType = "Observation")
    public ObservationEntity getObservationById(UUID id) {
        return observationRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Observation not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<ObservationEntity> findObservationById(UUID id) {
        return observationRepo.findById(id);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "OBSERVATION_READ_FHIR", resourceType = "Observation")
    public Observation getFhirObservationById(UUID id) {
        return toFhirObservation(getObservationById(id));
    }

    @Transactional(readOnly = true)
    public ObservationDto getObservationDtoById(UUID id) {
        return ObservationDto.fromFhirR4(getFhirObservationById(id));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "OBSERVATION_SEARCH", resourceType = "Observation")
    public ObservationSearchResult searchObservations(ObservationSearchCriteria criteria) {
        for (String expr : criteria.dateExpressions()) {
            parseDatePredicate(expr);
        }
        if (criteria.id() != null) {
            Optional<ObservationEntity> one = findObservationById(criteria.id())
                    .filter(o -> matchesCriteria(o, criteria));
            List<ObservationEntity> list = one.map(List::of).orElseGet(List::of);
            return new ObservationSearchResult(list, list.size(), criteria.offset(), criteria.count());
        }

        List<ObservationEntity> candidates = resolveCandidatePool(criteria);
        List<ObservationEntity> matched = candidates.stream()
                .filter(o -> matchesCriteria(o, criteria))
                .sorted(Comparator.comparing(ObservationEntity::getEffectiveAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ObservationEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int total = matched.size();
        int from = Math.min(criteria.offset(), total);
        int to = Math.min(from + criteria.count(), total);
        return new ObservationSearchResult(matched.subList(from, to), total, criteria.offset(), criteria.count());
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "OBSERVATION_SEARCH_FHIR", resourceType = "Observation")
    public Bundle searchFhirBundle(ObservationSearchCriteria criteria) {
        ObservationSearchResult result = searchObservations(criteria);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.total());
        for (ObservationEntity entity : result.observations()) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl("Observation/" + entity.getId());
            entry.setResource(toFhirObservation(entity));
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    @ClinicalAudit(action = "OBSERVATION_UPDATE", resourceType = "Observation")
    public ObservationEntity updateObservation(UUID id, ObservationEntity updates, Long expectedVersion) {
        ObservationEntity existing = getObservationById(id);
        if (expectedVersion != null && existing.getVersion() != expectedVersion) {
            throw new OptimisticLockingFailureException(
                    "Stale Observation version; expected W/\"" + existing.getVersion() + "\"");
        }
        if (!isBlank(updates.getStatus())) existing.setStatus(updates.getStatus().trim().toLowerCase(Locale.ROOT));
        if (!isBlank(updates.getCode())) existing.setCode(updates.getCode());
        if (updates.getCodeSystem() != null) existing.setCodeSystem(updates.getCodeSystem());
        if (updates.getCodeDisplay() != null) existing.setCodeDisplay(updates.getCodeDisplay());
        if (updates.getCodeText() != null) existing.setCodeText(updates.getCodeText());
        if (!isBlank(updates.getSubjectReference())) {
            existing.setSubjectReference(updates.getSubjectReference());
            existing.setPatientId(extractUuidSuffix(updates.getSubjectReference()));
        }
        if (updates.getEffectiveAt() != null) existing.setEffectiveAt(updates.getEffectiveAt());
        if (updates.getValueQuantity() != null) {
            existing.setValueType("Quantity");
            existing.setValueQuantity(updates.getValueQuantity());
            existing.setValueUnit(updates.getValueUnit());
            existing.setValueUnitSystem(updates.getValueUnitSystem());
            existing.setValueUnitCode(updates.getValueUnitCode());
        } else if (updates.getValueString() != null) {
            existing.setValueType("String");
            existing.setValueString(updates.getValueString());
        } else if (updates.getValueBoolean() != null) {
            existing.setValueType("Boolean");
            existing.setValueBoolean(updates.getValueBoolean());
        }
        if (updates.getInterpretationCode() != null) existing.setInterpretationCode(updates.getInterpretationCode());
        if (updates.getInterpretationDisplay() != null) existing.setInterpretationDisplay(updates.getInterpretationDisplay());
        if (updates.getNoteText() != null) existing.setNoteText(updates.getNoteText());
        existing.setUpdatedAt(Instant.now());

        validateEntity(existing);
        fhirValidatorService.validateAgainstSchema(toFhirObservation(existing));
        ObservationEntity saved = observationRepo.save(existing);
        emitOutboxEvent("Observation", saved.getId(), "ObservationUpdatedEvent",
                "{\"observationId\":\"" + saved.getId() + "\",\"status\":\"" + saved.getStatus() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "OBSERVATION_UPDATE_FHIR", resourceType = "Observation")
    public Observation updateFromFhirJson(UUID id, String fhirObservationJson, String ifMatchHeader) {
        ObservationEntity existing = getObservationById(id);
        enforceIfMatch(existing, ifMatchHeader);

        Observation incoming = parseFhirObservation(fhirObservationJson);
        validateFhirObservation(incoming, existing.getFacilityId(), false, existing.getId());
        applyFhirFieldsToEntity(existing, incoming);
        existing.setUpdatedAt(Instant.now());
        validateEntity(existing);

        ObservationEntity saved = observationRepo.save(existing);
        emitOutboxEvent("Observation", saved.getId(), "ObservationUpdatedEvent",
                "{\"observationId\":\"" + saved.getId() + "\",\"status\":\"" + saved.getStatus() + "\"}");
        return toFhirObservation(saved);
    }

    public ObservationDto updateFromDto(UUID id, ObservationDto dto, String ifMatchHeader) {
        if (dto == null) {
            throw new IllegalArgumentException("ObservationDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return ObservationDto.fromFhirR4(updateFromFhirJson(id, json, ifMatchHeader));
    }

    @ClinicalAudit(action = "OBSERVATION_ENTERED_IN_ERROR", resourceType = "Observation")
    public ObservationEntity markEnteredInError(UUID id) {
        ObservationEntity existing = getObservationById(id);
        existing.setStatus("entered-in-error");
        existing.setUpdatedAt(Instant.now());
        ObservationEntity saved = observationRepo.save(existing);
        emitOutboxEvent("Observation", saved.getId(), "ObservationEnteredInErrorEvent",
                "{\"observationId\":\"" + saved.getId() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "OBSERVATION_DELETE", resourceType = "Observation")
    public void deleteObservation(UUID id) {
        ObservationEntity existing = getObservationById(id);
        observationRepo.deleteById(existing.getId());
        emitOutboxEvent("Observation", existing.getId(), "ObservationDeletedEvent",
                "{\"observationId\":\"" + existing.getId() + "\"}");
    }

    public String encodeFhirResource(org.hl7.fhir.instance.model.api.IBaseResource resource) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    public Observation parseFhirObservation(String fhirObservationJson) {
        return fhirValidatorService.parseStrict(fhirObservationJson);
    }

    public static Observation toFhirObservation(ObservationEntity e) {
        Observation obs = new Observation();
        obs.setId(e.getId().toString());
        obs.getMeta().setVersionId(Long.toString(e.getVersion()));
        if (e.getUpdatedAt() != null) {
            obs.getMeta().setLastUpdated(Date.from(e.getUpdatedAt()));
        }

        if (!isBlank(e.getIdentifierValue())) {
            obs.addIdentifier()
                    .setSystem(!isBlank(e.getIdentifierSystem()) ? e.getIdentifierSystem() : DEFAULT_IDENT_SYSTEM)
                    .setValue(e.getIdentifierValue());
        }

        try {
            obs.setStatus(ObservationStatus.fromCode(e.getStatus().toLowerCase(Locale.ROOT)));
        } catch (Exception ignored) {
            obs.setStatus(ObservationStatus.FINAL);
        }

        if (!isBlank(e.getCategoryCode()) || !isBlank(e.getCategoryDisplay())) {
            CodeableConcept cat = obs.addCategory();
            if (!isBlank(e.getCategoryDisplay())) cat.setText(e.getCategoryDisplay());
            if (!isBlank(e.getCategoryCode())) {
                cat.addCoding(new Coding()
                        .setSystem(!isBlank(e.getCategorySystem())
                                ? e.getCategorySystem()
                                : "http://terminology.hl7.org/CodeSystem/observation-category")
                        .setCode(e.getCategoryCode())
                        .setDisplay(e.getCategoryDisplay()));
            }
        }

        CodeableConcept codeCc = new CodeableConcept();
        if (!isBlank(e.getCodeText())) codeCc.setText(e.getCodeText());
        if (!isBlank(e.getCode())) {
            codeCc.addCoding(new Coding()
                    .setSystem(!isBlank(e.getCodeSystem()) ? e.getCodeSystem() : "http://loinc.org")
                    .setCode(e.getCode())
                    .setDisplay(e.getCodeDisplay()));
        }
        obs.setCode(codeCc);

        if (!isBlank(e.getSubjectReference())) {
            obs.setSubject(new Reference(e.getSubjectReference()));
        }
        if (!isBlank(e.getEncounterReference())) {
            obs.setEncounter(new Reference(e.getEncounterReference()));
        }
        if (!isBlank(e.getPerformerReference()) || !isBlank(e.getPerformerDisplay())) {
            Reference perf = new Reference();
            if (!isBlank(e.getPerformerReference())) perf.setReference(e.getPerformerReference());
            if (!isBlank(e.getPerformerDisplay())) perf.setDisplay(e.getPerformerDisplay());
            obs.addPerformer(perf);
        }
        if (e.getEffectiveAt() != null) {
            obs.setEffective(new DateTimeType(Date.from(e.getEffectiveAt())));
        }
        if (e.getIssuedAt() != null) {
            obs.setIssued(Date.from(e.getIssuedAt()));
        }

        if ("Quantity".equals(e.getValueType()) && e.getValueQuantity() != null) {
            Quantity q = new Quantity().setValue(e.getValueQuantity());
            if (!isBlank(e.getValueUnit())) q.setUnit(e.getValueUnit());
            if (!isBlank(e.getValueUnitSystem())) q.setSystem(e.getValueUnitSystem());
            if (!isBlank(e.getValueUnitCode())) q.setCode(e.getValueUnitCode());
            obs.setValue(q);
        } else if ("String".equals(e.getValueType()) && e.getValueString() != null) {
            obs.setValue(new StringType(e.getValueString()));
        } else if ("Boolean".equals(e.getValueType()) && e.getValueBoolean() != null) {
            obs.setValue(new BooleanType(e.getValueBoolean()));
        } else if ("CodeableConcept".equals(e.getValueType())
                && (!isBlank(e.getValueCode()) || !isBlank(e.getValueCodeDisplay()))) {
            CodeableConcept vcc = new CodeableConcept();
            if (!isBlank(e.getValueCodeDisplay())) vcc.setText(e.getValueCodeDisplay());
            if (!isBlank(e.getValueCode())) {
                vcc.addCoding(new Coding()
                        .setSystem(!isBlank(e.getValueCodeSystem()) ? e.getValueCodeSystem() : "http://snomed.info/sct")
                        .setCode(e.getValueCode())
                        .setDisplay(e.getValueCodeDisplay()));
            }
            obs.setValue(vcc);
        }

        if (!isBlank(e.getInterpretationCode()) || !isBlank(e.getInterpretationDisplay())) {
            CodeableConcept interp = obs.addInterpretation();
            if (!isBlank(e.getInterpretationDisplay())) interp.setText(e.getInterpretationDisplay());
            if (!isBlank(e.getInterpretationCode())) {
                interp.addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation")
                        .setCode(e.getInterpretationCode())
                        .setDisplay(e.getInterpretationDisplay()));
            }
        }

        if (e.getRefRangeLow() != null || e.getRefRangeHigh() != null || !isBlank(e.getRefRangeText())) {
            Observation.ObservationReferenceRangeComponent rr = obs.addReferenceRange();
            if (e.getRefRangeLow() != null) {
                Quantity low = new Quantity().setValue(e.getRefRangeLow());
                if (!isBlank(e.getValueUnit())) low.setUnit(e.getValueUnit());
                rr.setLow(low);
            }
            if (e.getRefRangeHigh() != null) {
                Quantity high = new Quantity().setValue(e.getRefRangeHigh());
                if (!isBlank(e.getValueUnit())) high.setUnit(e.getValueUnit());
                rr.setHigh(high);
            }
            if (!isBlank(e.getRefRangeText())) rr.setText(e.getRefRangeText());
        }

        if (!isBlank(e.getNoteText())) {
            obs.addNote(new Annotation().setText(e.getNoteText()));
        }

        if (!isBlank(e.getComponentsJson())) {
            try {
                Observation shell = FHIR_R4.newJsonParser().parseResource(Observation.class, e.getComponentsJson());
                shell.getComponent().forEach(c -> obs.addComponent(c.copy()));
            } catch (Exception ignored) {
                // Keep resource valid if malformed component JSON was persisted externally
            }
        }
        return obs;
    }

    private List<ObservationEntity> resolveCandidatePool(ObservationSearchCriteria c) {
        if (c.facilityId() != null && !isBlank(c.identifier())) {
            String raw = c.identifier().trim();
            int pipe = raw.indexOf('|');
            if (pipe >= 0) {
                Optional<ObservationEntity> exact = observationRepo.findByFacilityIdAndIdentifierSystemAndIdentifierValue(
                        c.facilityId(), raw.substring(0, pipe), raw.substring(pipe + 1));
                if (exact.isPresent()) {
                    return List.of(exact.get());
                }
            }
        }
        UUID patUuid = extractUuidSuffix(c.patient());
        if (c.facilityId() != null && patUuid != null) {
            return observationRepo.findByFacilityIdAndPatientIdOrderByEffectiveAtDesc(c.facilityId(), patUuid);
        }
        UUID encUuid = extractUuidSuffix(c.encounter());
        if (c.facilityId() != null && encUuid != null) {
            return observationRepo.findByFacilityIdAndEncounterIdOrderByEffectiveAtDesc(c.facilityId(), encUuid);
        }
        if (c.facilityId() != null) {
            return observationRepo.findByFacilityIdOrderByEffectiveAtDesc(c.facilityId());
        }
        return observationRepo.findAll();
    }

    private static boolean matchesCriteria(ObservationEntity o, ObservationSearchCriteria c) {
        if (c.facilityId() != null && !Objects.equals(c.facilityId(), o.getFacilityId())) {
            return false;
        }
        if (!isBlank(c.status()) && !c.status().trim().equalsIgnoreCase(o.getStatus())) {
            return false;
        }
        if (!isBlank(c.category())) {
            String needle = c.category().trim();
            int pipe = needle.indexOf('|');
            String sys = pipe >= 0 ? needle.substring(0, pipe) : null;
            String code = pipe >= 0 ? needle.substring(pipe + 1) : needle;
            if (o.getCategoryCode() == null || !o.getCategoryCode().equalsIgnoreCase(code)) {
                return false;
            }
            if (sys != null && (o.getCategorySystem() == null || !sys.equals(o.getCategorySystem()))) {
                return false;
            }
        }
        if (!isBlank(c.code())) {
            String needle = c.code().trim();
            int pipe = needle.indexOf('|');
            String sys = pipe >= 0 ? needle.substring(0, pipe) : null;
            String code = pipe >= 0 ? needle.substring(pipe + 1) : needle;
            if (o.getCode() == null || !o.getCode().equalsIgnoreCase(code)) {
                return false;
            }
            if (sys != null && (o.getCodeSystem() == null || !sys.equals(o.getCodeSystem()))) {
                return false;
            }
        }
        if (!isBlank(c.codeText())) {
            String needle = c.codeText().trim().toLowerCase(Locale.ROOT);
            boolean hit = (o.getCodeDisplay() != null && o.getCodeDisplay().toLowerCase(Locale.ROOT).contains(needle))
                    || (o.getCodeText() != null && o.getCodeText().toLowerCase(Locale.ROOT).contains(needle))
                    || (o.getCode() != null && o.getCode().toLowerCase(Locale.ROOT).contains(needle));
            if (!hit) {
                return false;
            }
        }
        if (!isBlank(c.patient()) && !matchesReferenceFilter(o.getSubjectReference(), o.getPatientId(), c.patient(), "Patient")) {
            return false;
        }
        if (!isBlank(c.encounter()) && !matchesReferenceFilter(o.getEncounterReference(), o.getEncounterId(), c.encounter(), "Encounter")) {
            return false;
        }
        if (!isBlank(c.performer()) && !matchesReferenceFilter(o.getPerformerReference(), o.getPractitionerId(), c.performer(), "Practitioner")) {
            return false;
        }
        if (!isBlank(c.identifier())) {
            String raw = c.identifier().trim();
            int pipe = raw.indexOf('|');
            String sys = pipe >= 0 ? raw.substring(0, pipe) : null;
            String val = pipe >= 0 ? raw.substring(pipe + 1) : raw;
            if (o.getIdentifierValue() == null || !o.getIdentifierValue().equalsIgnoreCase(val)) {
                return false;
            }
            if (sys != null && (o.getIdentifierSystem() == null || !sys.equals(o.getIdentifierSystem()))) {
                return false;
            }
        }
        LocalDate effDate = o.getEffectiveAt() != null ? o.getEffectiveAt().atZone(ZoneOffset.UTC).toLocalDate() : null;
        if (c.dateFrom() != null && (effDate == null || effDate.isBefore(c.dateFrom()))) {
            return false;
        }
        if (c.dateTo() != null && (effDate == null || effDate.isAfter(c.dateTo()))) {
            return false;
        }
        for (String expr : c.dateExpressions()) {
            if (!matchesEffectiveDate(effDate, expr)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesReferenceFilter(String actualRef, UUID actualUuid, String filter, String defaultType) {
        String trimmed = filter.trim();
        UUID filterUuid = extractUuidSuffix(trimmed);
        if (filterUuid != null && Objects.equals(filterUuid, actualUuid)) {
            return true;
        }
        String canonical = trimmed.contains("/") ? trimmed : defaultType + "/" + trimmed;
        return actualRef != null && (actualRef.equalsIgnoreCase(trimmed) || actualRef.equalsIgnoreCase(canonical));
    }

    private record ParsedDateQuery(String op, LocalDate start, LocalDate end) {}

    private static ParsedDateQuery parseDatePredicate(String expr) {
        String op = "eq";
        String datePart = expr;
        if (expr.length() > 2 && Character.isLetter(expr.charAt(0)) && Character.isLetter(expr.charAt(1))) {
            op = expr.substring(0, 2).toLowerCase(Locale.ROOT);
            datePart = expr.substring(2);
        }
        if (datePart.length() > 10 && datePart.charAt(10) == 'T') {
            datePart = datePart.substring(0, 10);
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
            throw new IllegalArgumentException("Invalid FHIR Observation date query parameter: " + expr, ex);
        }
        if (!Set.of("eq", "ne", "gt", "lt", "ge", "le", "sa", "eb", "ap").contains(op)) {
            throw new IllegalArgumentException("Unsupported FHIR date search prefix: " + op);
        }
        return new ParsedDateQuery(op, start, end);
    }

    private static boolean matchesEffectiveDate(LocalDate actual, String expr) {
        if (actual == null) return false;
        ParsedDateQuery q = parseDatePredicate(expr);
        return switch (q.op()) {
            case "eq" -> !actual.isBefore(q.start()) && !actual.isAfter(q.end());
            case "ne" -> actual.isBefore(q.start()) || actual.isAfter(q.end());
            case "gt", "sa" -> actual.isAfter(q.end());
            case "lt", "eb" -> actual.isBefore(q.start());
            case "ge" -> !actual.isBefore(q.start());
            case "le" -> !actual.isAfter(q.end());
            case "ap" -> !actual.isBefore(q.start().minusDays(7)) && !actual.isAfter(q.end().plusDays(7));
            default -> throw new IllegalArgumentException("Unsupported FHIR date search prefix: " + q.op());
        };
    }

    private static void enforceIfMatch(ObservationEntity existing, String ifMatch) {
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

    private static void applyFhirFieldsToEntity(ObservationEntity entity, Observation incoming) {
        if (!incoming.getIdentifier().isEmpty()) {
            org.hl7.fhir.r4.model.Identifier ident = incoming.getIdentifierFirstRep();
            entity.setIdentifierSystem(ident.hasSystem() && !isBlank(ident.getSystem()) ? ident.getSystem().trim() : DEFAULT_IDENT_SYSTEM);
            if (ident.hasValue()) entity.setIdentifierValue(ident.getValue().trim());
        }
        if (incoming.hasStatus() && incoming.getStatus() != null) {
            entity.setStatus(incoming.getStatus().toCode().toLowerCase(Locale.ROOT));
        }
        if (!incoming.getCategory().isEmpty()) {
            CodeableConcept cat = incoming.getCategoryFirstRep();
            if (cat.hasText()) entity.setCategoryDisplay(cat.getText());
            if (!cat.getCoding().isEmpty()) {
                Coding c = cat.getCodingFirstRep();
                if (c.hasSystem()) entity.setCategorySystem(c.getSystem());
                if (c.hasCode()) entity.setCategoryCode(c.getCode());
                if (c.hasDisplay()) entity.setCategoryDisplay(c.getDisplay());
            }
        }
        if (incoming.hasCode()) {
            CodeableConcept cc = incoming.getCode();
            if (cc.hasText()) entity.setCodeText(cc.getText());
            if (!cc.getCoding().isEmpty()) {
                Coding c = cc.getCodingFirstRep();
                if (c.hasSystem()) entity.setCodeSystem(c.getSystem());
                if (c.hasCode()) entity.setCode(c.getCode());
                if (c.hasDisplay()) entity.setCodeDisplay(c.getDisplay());
            } else if (cc.hasText() && isBlank(entity.getCode())) {
                entity.setCode(cc.getText().trim().replaceAll("\\s+", "-").toLowerCase(Locale.ROOT));
            }
        }
        if (incoming.hasSubject() && incoming.getSubject().hasReference()) {
            String ref = incoming.getSubject().getReference().trim();
            entity.setSubjectReference(ref);
            entity.setPatientId(extractUuidSuffix(ref));
        }
        if (incoming.hasEncounter() && incoming.getEncounter().hasReference()) {
            String ref = incoming.getEncounter().getReference().trim();
            entity.setEncounterReference(ref);
            entity.setEncounterId(extractUuidSuffix(ref));
        }
        if (!incoming.getPerformer().isEmpty()) {
            Reference perf = incoming.getPerformerFirstRep();
            if (perf.hasReference()) {
                String ref = perf.getReference().trim();
                entity.setPerformerReference(ref);
                entity.setPractitionerId(extractUuidSuffix(ref));
            }
            if (perf.hasDisplay()) entity.setPerformerDisplay(perf.getDisplay());
        }
        if (incoming.hasEffectiveDateTimeType() && incoming.getEffectiveDateTimeType().hasValue()) {
            entity.setEffectiveAt(incoming.getEffectiveDateTimeType().getValue().toInstant());
        }
        if (incoming.hasIssued()) {
            entity.setIssuedAt(incoming.getIssued().toInstant());
        }

        // Clear scalar values before applying incoming value[x]
        entity.setValueType(null);
        entity.setValueQuantity(null);
        entity.setValueUnit(null);
        entity.setValueUnitSystem(null);
        entity.setValueUnitCode(null);
        entity.setValueString(null);
        entity.setValueBoolean(null);
        entity.setValueCodeSystem(null);
        entity.setValueCode(null);
        entity.setValueCodeDisplay(null);

        if (incoming.hasValueQuantity()) {
            Quantity q = incoming.getValueQuantity();
            entity.setValueType("Quantity");
            entity.setValueQuantity(q.getValue());
            entity.setValueUnit(q.getUnit());
            entity.setValueUnitSystem(q.getSystem());
            entity.setValueUnitCode(q.getCode());
        } else if (incoming.hasValueStringType()) {
            entity.setValueType("String");
            entity.setValueString(incoming.getValueStringType().getValue());
        } else if (incoming.hasValueBooleanType()) {
            entity.setValueType("Boolean");
            entity.setValueBoolean(incoming.getValueBooleanType().booleanValue());
        } else if (incoming.hasValueCodeableConcept()) {
            CodeableConcept vcc = incoming.getValueCodeableConcept();
            entity.setValueType("CodeableConcept");
            if (vcc.hasText()) entity.setValueCodeDisplay(vcc.getText());
            if (!vcc.getCoding().isEmpty()) {
                Coding c = vcc.getCodingFirstRep();
                entity.setValueCodeSystem(c.getSystem());
                entity.setValueCode(c.getCode());
                if (c.hasDisplay()) entity.setValueCodeDisplay(c.getDisplay());
            }
        } else if (!incoming.getComponent().isEmpty()) {
            entity.setValueType("Panel");
        }

        if (!incoming.getInterpretation().isEmpty()) {
            CodeableConcept interp = incoming.getInterpretationFirstRep();
            if (interp.hasText()) entity.setInterpretationDisplay(interp.getText());
            if (!interp.getCoding().isEmpty()) {
                Coding c = interp.getCodingFirstRep();
                if (c.hasCode()) entity.setInterpretationCode(c.getCode());
                if (c.hasDisplay()) entity.setInterpretationDisplay(c.getDisplay());
            }
        }
        if (!incoming.getReferenceRange().isEmpty()) {
            Observation.ObservationReferenceRangeComponent rr = incoming.getReferenceRangeFirstRep();
            if (rr.hasLow() && rr.getLow().hasValue()) entity.setRefRangeLow(rr.getLow().getValue());
            if (rr.hasHigh() && rr.getHigh().hasValue()) entity.setRefRangeHigh(rr.getHigh().getValue());
            if (rr.hasText()) entity.setRefRangeText(rr.getText());
        }
        if (!incoming.getNote().isEmpty()) {
            entity.setNoteText(String.join("\n", incoming.getNote().stream().map(Annotation::getText).toList()));
        }
        if (!incoming.getComponent().isEmpty()) {
            Observation shell = new Observation();
            shell.setStatus(ObservationStatus.FINAL);
            shell.setCode(incoming.getCode().copy());
            incoming.getComponent().forEach(c -> shell.addComponent(c.copy()));
            entity.setComponentsJson(FHIR_R4.newJsonParser().encodeResourceToString(shell));
        } else {
            entity.setComponentsJson(null);
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

    private static UUID extractUuidSuffix(String ref) {
        if (isBlank(ref)) return null;
        Matcher m = UUID_TAIL_PATTERN.matcher(ref.trim());
        if (!m.find()) return null;
        try {
            return UUID.fromString(m.group(1));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
