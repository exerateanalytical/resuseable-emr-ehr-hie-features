/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Encounter CRUD & Validation Service
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
import com.opeshealth.healthcore.dto.EncounterDto;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.quickstart.domain.EncounterRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full CRUD and HL7 FHIR R4 validation service for {@link EncounterEntity}
 * backed by {@link EncounterRepository} with HAPI FHIR R4 XSD schema validation,
 * strict JSON parsing, {@link EncounterDto} conversion, FHIR date-range/class/status
 * search, and deterministic ETag optimistic concurrency checks.
 */
@Service
@Transactional
public class EncounterService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);
    private static final Pattern UUID_TAIL_PATTERN = Pattern.compile("([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");
    private static final Set<String> VALID_FHIR_STATUSES = Set.of(
            "planned", "arrived", "triaged", "in-progress",
            "onleave", "finished", "cancelled", "entered-in-error", "unknown");

    /**
     * HAPI FHIR R4 structural &amp; schema validator embedded within {@link EncounterService}.
     */
    public static final class FhirEncounterValidatorService {

        private final FhirContext fhirContext;
        private final FhirValidator schemaValidator;

        public FhirEncounterValidatorService() {
            this(FHIR_R4);
        }

        public FhirEncounterValidatorService(FhirContext fhirContext) {
            this.fhirContext = Objects.requireNonNull(fhirContext, "FhirContext is required");
            this.schemaValidator = this.fhirContext.newValidator();
            this.schemaValidator.setValidateAgainstStandardSchema(true);
            this.schemaValidator.setValidateAgainstStandardSchematron(false);
        }

        public Encounter parseStrict(String fhirEncounterJson) {
            if (isBlank(fhirEncounterJson)) {
                throw new IllegalArgumentException("FHIR Encounter JSON payload must not be empty");
            }
            try {
                IParser strictParser = fhirContext.newJsonParser()
                        .setParserErrorHandler(new StrictErrorHandler());
                Encounter parsed = strictParser.parseResource(Encounter.class, fhirEncounterJson);
                if (parsed == null || !"Encounter".equals(parsed.fhirType())) {
                    throw new IllegalArgumentException("FHIR payload resourceType must be 'Encounter'");
                }
                return parsed;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("HL7 FHIR R4 strict schema parse failure: " + ex.getMessage(), ex);
            }
        }

        public ValidationResult validateWithResult(Encounter encounter) {
            if (encounter == null) {
                throw new IllegalArgumentException("FHIR R4 Encounter resource must not be null");
            }
            return schemaValidator.validateWithResult(encounter);
        }

        public OperationOutcome validateToOperationOutcome(Encounter encounter) {
            ValidationResult result = validateWithResult(encounter);
            return result.toOperationOutcome() instanceof OperationOutcome oo ? oo : new OperationOutcome();
        }

        public void validateAgainstSchema(Encounter encounter) {
            ValidationResult result = validateWithResult(encounter);
            if (!result.isSuccessful()) {
                List<String> diagnostics = result.getMessages().stream()
                        .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
                        .map(SingleValidationMessage::toString)
                        .toList();
                String summary = diagnostics.isEmpty()
                        ? result.getMessages().stream().map(SingleValidationMessage::toString).reduce((a, b) -> a + "; " + b).orElse("Unknown FHIR R4 schema violation")
                        : String.join("; ", diagnostics);
                throw new IllegalArgumentException("HL7 FHIR R4 Encounter schema validation failed: " + summary);
            }
        }
    }

    public record EncounterSearchCriteria(
            UUID id,
            UUID facilityId,
            String patient,
            String status,
            String encounterClass,
            String practitioner,
            String location,
            String reasonCode,
            List<String> dateExpressions,
            LocalDate dateFrom,
            LocalDate dateTo,
            int offset,
            int count
    ) {
        public EncounterSearchCriteria {
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

    public record EncounterSearchResult(
            List<EncounterEntity> encounters,
            int total,
            int offset,
            int count
    ) {}

    private final EncounterRepository encounterRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final FhirEncounterValidatorService fhirValidatorService;

    public EncounterService(EncounterRepository encounterRepo) {
        this(encounterRepo, (OutboxRelayService.OutboxRepository) null);
    }

    @Autowired
    public EncounterService(
            EncounterRepository encounterRepo,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider) {
        this(encounterRepo, outboxRepoProvider != null ? outboxRepoProvider.getIfAvailable() : null);
    }

    public EncounterService(
            EncounterRepository encounterRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.encounterRepo = Objects.requireNonNull(encounterRepo, "EncounterRepository is required");
        this.outboxRepo = outboxRepo;
        this.fhirValidatorService = new FhirEncounterValidatorService(FHIR_R4);
    }

    public FhirEncounterValidatorService getFhirValidatorService() {
        return fhirValidatorService;
    }

    public ValidationResult validateEncounterSchema(Encounter fhirEncounter) {
        return fhirValidatorService.validateWithResult(fhirEncounter);
    }

    public void validateEntity(EncounterEntity entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Encounter entity must not be null");
        }
        if (entity.getFacilityId() == null) {
            throw new IllegalArgumentException("Encounter facilityId is required");
        }
        if (entity.getPatientId() == null) {
            throw new IllegalArgumentException("Encounter patientId is required");
        }
        if (isBlank(entity.getEncounterClass())) {
            throw new IllegalArgumentException("Encounter class is required (e.g., AMB, EMER, IMP, HH)");
        }
        if (isBlank(entity.getStatus())) {
            throw new IllegalArgumentException("Encounter status is required");
        }
        if (entity.getStartedAt() != null && entity.getEndedAt() != null
                && entity.getEndedAt().isBefore(entity.getStartedAt())) {
            throw new IllegalArgumentException("Encounter period.end cannot precede period.start");
        }
    }

    public void validateFhirEncounter(Encounter fhirEncounter) {
        fhirValidatorService.validateAgainstSchema(fhirEncounter);

        if (!fhirEncounter.hasStatus() || fhirEncounter.getStatus() == null
                || !VALID_FHIR_STATUSES.contains(fhirEncounter.getStatus().toCode().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("FHIR Encounter.status is required and must be a valid EncounterStatus");
        }
        if (!fhirEncounter.hasClass_() || isBlank(fhirEncounter.getClass_().getCode())) {
            throw new IllegalArgumentException("FHIR Encounter.class.code is required (e.g., AMB, EMER, IMP, HH)");
        }
        if (!fhirEncounter.hasSubject() || !fhirEncounter.getSubject().hasReference()
                || extractUuidSuffix(fhirEncounter.getSubject().getReference()) == null) {
            throw new IllegalArgumentException("FHIR Encounter.subject.reference must contain a valid Patient/<uuid>");
        }
        if (fhirEncounter.hasPeriod() && fhirEncounter.getPeriod().hasStart() && fhirEncounter.getPeriod().hasEnd()
                && fhirEncounter.getPeriod().getEnd().toInstant().isBefore(fhirEncounter.getPeriod().getStart().toInstant())) {
            throw new IllegalArgumentException("FHIR Encounter.period.end cannot precede period.start");
        }
    }

    @ClinicalAudit(action = "ENCOUNTER_CREATE", resourceType = "Encounter")
    public EncounterEntity createEncounter(EncounterEntity entity) {
        if (entity.getFacilityId() == null) {
            entity.setFacilityId(DEFAULT_FACILITY_ID);
        }
        if (isBlank(entity.getStatus())) {
            entity.setStatus("ARRIVED");
        }
        if (entity.getStartedAt() == null) {
            entity.setStartedAt(Instant.now());
        }
        validateEntity(entity);
        fhirValidatorService.validateAgainstSchema(toFhirEncounter(entity));

        EncounterEntity saved = encounterRepo.save(entity);
        emitOutboxEvent("Encounter", saved.getId(), "EncounterCreatedEvent",
                "{\"encounterId\":\"" + saved.getId() + "\",\"patientId\":\"" + saved.getPatientId() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "ENCOUNTER_CREATE_FHIR", resourceType = "Encounter")
    public Encounter createFromFhirJson(UUID facilityId, String fhirEncounterJson) {
        Encounter incoming = parseFhirEncounter(fhirEncounterJson);
        validateFhirEncounter(incoming);

        UUID resolvedFacility = resolveFacilityFromEncounter(facilityId, incoming);
        EncounterEntity entity = new EncounterEntity();
        entity.setFacilityId(resolvedFacility);
        applyFhirFieldsToEntity(entity, incoming);
        if (entity.getStartedAt() == null) {
            entity.setStartedAt(Instant.now());
        }
        validateEntity(entity);

        EncounterEntity saved = encounterRepo.save(entity);
        emitOutboxEvent("Encounter", saved.getId(), "EncounterCreatedEvent",
                "{\"encounterId\":\"" + saved.getId() + "\",\"patientId\":\"" + saved.getPatientId() + "\"}");
        return toFhirEncounter(saved);
    }

    public EncounterDto createFromDto(UUID facilityId, EncounterDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("EncounterDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return EncounterDto.fromFhirR4(createFromFhirJson(facilityId, json));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "ENCOUNTER_READ", resourceType = "Encounter")
    public EncounterEntity getEncounterById(UUID id) {
        return encounterRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Encounter not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<EncounterEntity> findEncounterById(UUID id) {
        return encounterRepo.findById(id);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "ENCOUNTER_READ_FHIR", resourceType = "Encounter")
    public Encounter getFhirEncounterById(UUID id) {
        return toFhirEncounter(getEncounterById(id));
    }

    @Transactional(readOnly = true)
    public EncounterDto getEncounterDtoById(UUID id) {
        return EncounterDto.fromFhirR4(getFhirEncounterById(id));
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "ENCOUNTER_SEARCH", resourceType = "Encounter")
    public EncounterSearchResult searchEncounters(EncounterSearchCriteria criteria) {
        for (String expr : criteria.dateExpressions()) {
            parseDatePredicate(expr);
        }
        if (criteria.id() != null) {
            Optional<EncounterEntity> one = findEncounterById(criteria.id())
                    .filter(e -> matchesCriteria(e, criteria));
            List<EncounterEntity> list = one.map(List::of).orElseGet(List::of);
            return new EncounterSearchResult(list, list.size(), criteria.offset(), criteria.count());
        }

        List<EncounterEntity> candidates = resolveCandidatePool(criteria);
        List<EncounterEntity> matched = candidates.stream()
                .filter(e -> matchesCriteria(e, criteria))
                .sorted(Comparator.comparing(EncounterEntity::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int total = matched.size();
        int from = Math.min(criteria.offset(), total);
        int to = Math.min(from + criteria.count(), total);
        return new EncounterSearchResult(matched.subList(from, to), total, criteria.offset(), criteria.count());
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "ENCOUNTER_SEARCH_FHIR", resourceType = "Encounter")
    public Bundle searchFhirBundle(EncounterSearchCriteria criteria) {
        EncounterSearchResult result = searchEncounters(criteria);
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.total());
        for (EncounterEntity entity : result.encounters()) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setFullUrl("Encounter/" + entity.getId());
            entry.setResource(toFhirEncounter(entity));
            entry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        }
        return bundle;
    }

    @ClinicalAudit(action = "ENCOUNTER_UPDATE", resourceType = "Encounter")
    public EncounterEntity updateEncounter(UUID id, EncounterEntity updates, String ifMatchHeader) {
        EncounterEntity existing = getEncounterById(id);
        enforceIfMatch(existing, ifMatchHeader);

        if (!isBlank(updates.getEncounterClass())) existing.setEncounterClass(updates.getEncounterClass());
        if (!isBlank(updates.getStatus())) existing.setStatus(updates.getStatus());
        if (updates.getAttendingId() != null) existing.setAttendingId(updates.getAttendingId());
        if (updates.getDepartmentCode() != null) existing.setDepartmentCode(updates.getDepartmentCode());
        if (updates.getPrimaryIcd10() != null) existing.setPrimaryIcd10(updates.getPrimaryIcd10());
        if (updates.getStartedAt() != null) existing.setStartedAt(updates.getStartedAt());
        if (updates.getEndedAt() != null) existing.setEndedAt(updates.getEndedAt());

        validateEntity(existing);
        fhirValidatorService.validateAgainstSchema(toFhirEncounter(existing));
        EncounterEntity saved = encounterRepo.save(existing);
        emitOutboxEvent("Encounter", saved.getId(), "EncounterUpdatedEvent",
                "{\"encounterId\":\"" + saved.getId() + "\",\"status\":\"" + saved.getStatus() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "ENCOUNTER_UPDATE_FHIR", resourceType = "Encounter")
    public Encounter updateFromFhirJson(UUID id, String fhirEncounterJson, String ifMatchHeader) {
        EncounterEntity existing = getEncounterById(id);
        enforceIfMatch(existing, ifMatchHeader);

        Encounter incoming = parseFhirEncounter(fhirEncounterJson);
        validateFhirEncounter(incoming);
        applyFhirFieldsToEntity(existing, incoming);
        validateEntity(existing);

        EncounterEntity saved = encounterRepo.save(existing);
        emitOutboxEvent("Encounter", saved.getId(), "EncounterUpdatedEvent",
                "{\"encounterId\":\"" + saved.getId() + "\",\"status\":\"" + saved.getStatus() + "\"}");
        return toFhirEncounter(saved);
    }

    public EncounterDto updateFromDto(UUID id, EncounterDto dto, String ifMatchHeader) {
        if (dto == null) {
            throw new IllegalArgumentException("EncounterDto must not be null");
        }
        String json = encodeFhirResource(dto.toFhirR4());
        return EncounterDto.fromFhirR4(updateFromFhirJson(id, json, ifMatchHeader));
    }

    @ClinicalAudit(action = "ENCOUNTER_CANCEL", resourceType = "Encounter")
    public EncounterEntity cancelEncounter(UUID id) {
        EncounterEntity existing = getEncounterById(id);
        existing.setStatus("CANCELLED");
        if (existing.getEndedAt() == null) {
            existing.setEndedAt(Instant.now());
        }
        EncounterEntity saved = encounterRepo.save(existing);
        emitOutboxEvent("Encounter", saved.getId(), "EncounterCancelledEvent",
                "{\"encounterId\":\"" + saved.getId() + "\"}");
        return saved;
    }

    @ClinicalAudit(action = "ENCOUNTER_DELETE", resourceType = "Encounter")
    public void deleteEncounter(UUID id) {
        EncounterEntity existing = getEncounterById(id);
        encounterRepo.deleteById(existing.getId());
        emitOutboxEvent("Encounter", existing.getId(), "EncounterDeletedEvent",
                "{\"encounterId\":\"" + existing.getId() + "\"}");
    }

    public String encodeFhirResource(org.hl7.fhir.instance.model.api.IBaseResource resource) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    public Encounter parseFhirEncounter(String fhirEncounterJson) {
        return fhirValidatorService.parseStrict(fhirEncounterJson);
    }

    public static String computeEtagVersion(EncounterEntity e) {
        int hash = Objects.hash(
                e.getId(), e.getFacilityId(), e.getPatientId(),
                e.getEncounterClass(), e.getStatus(),
                e.getAttendingId(), e.getDepartmentCode(), e.getPrimaryIcd10(),
                e.getStartedAt(), e.getEndedAt());
        return Long.toUnsignedString(hash & 0xFFFFFFFFL);
    }

    public static Encounter toFhirEncounter(EncounterEntity e) {
        Encounter enc = new Encounter();
        enc.setId(e.getId().toString());
        enc.getMeta().setVersionId(computeEtagVersion(e));
        Instant lastUpd = e.getEndedAt() != null ? e.getEndedAt() : e.getStartedAt();
        if (lastUpd != null) {
            enc.getMeta().setLastUpdated(Date.from(lastUpd));
        }

        enc.setStatus(toFhirStatus(e.getStatus()));
        if (!isBlank(e.getEncounterClass())) {
            enc.setClass_(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode")
                    .setCode(e.getEncounterClass())
                    .setDisplay(e.getEncounterClass()));
        }
        if (e.getPatientId() != null) {
            enc.setSubject(new Reference("Patient/" + e.getPatientId()));
        }
        if (!isBlank(e.getAttendingId())) {
            String ref = e.getAttendingId().contains("/") ? e.getAttendingId() : "Practitioner/" + e.getAttendingId();
            Encounter.EncounterParticipantComponent participant = enc.addParticipant();
            participant.addType(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-ParticipationType")
                    .setCode("ATND")
                    .setDisplay("attender")));
            participant.setIndividual(new Reference(ref).setDisplay(e.getAttendingId()));
        }
        if (e.getStartedAt() != null) {
            enc.getPeriod().setStart(Date.from(e.getStartedAt()));
        }
        if (e.getEndedAt() != null) {
            enc.getPeriod().setEnd(Date.from(e.getEndedAt()));
        }
        if (!isBlank(e.getPrimaryIcd10())) {
            enc.addReasonCode(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://hl7.org/fhir/sid/icd-10")
                    .setCode(e.getPrimaryIcd10())
                    .setDisplay(e.getPrimaryIcd10())));
        }
        if (!isBlank(e.getDepartmentCode())) {
            String locRef = e.getDepartmentCode().contains("/") ? e.getDepartmentCode() : "Location/" + e.getDepartmentCode();
            enc.addLocation()
                    .setLocation(new Reference(locRef).setDisplay(e.getDepartmentCode()))
                    .setStatus(Encounter.EncounterLocationStatus.ACTIVE);
        }
        if (e.getFacilityId() != null) {
            enc.setServiceProvider(new Reference("Organization/" + e.getFacilityId()));
        }
        return enc;
    }

    private List<EncounterEntity> resolveCandidatePool(EncounterSearchCriteria c) {
        UUID patUuid = extractUuidSuffix(c.patient());
        if (c.facilityId() != null && patUuid != null) {
            return encounterRepo.findByFacilityIdAndPatientIdOrderByStartedAtDesc(c.facilityId(), patUuid);
        }
        if (c.facilityId() != null && !isBlank(c.status())) {
            return encounterRepo.findByFacilityIdAndStatusIgnoreCaseOrderByStartedAtDesc(
                    c.facilityId(), toEntityStatus(c.status()));
        }
        if (c.facilityId() != null) {
            return encounterRepo.findByFacilityIdOrderByStartedAtDesc(c.facilityId());
        }
        if (patUuid != null) {
            return encounterRepo.findByPatientIdOrderByStartedAtDesc(patUuid);
        }
        return encounterRepo.findAll();
    }

    private static boolean matchesCriteria(EncounterEntity e, EncounterSearchCriteria c) {
        if (c.facilityId() != null && !Objects.equals(c.facilityId(), e.getFacilityId())) {
            return false;
        }
        if (!isBlank(c.patient())) {
            UUID patUuid = extractUuidSuffix(c.patient());
            if (patUuid == null || !Objects.equals(patUuid, e.getPatientId())) {
                return false;
            }
        }
        if (!isBlank(c.status())) {
            String expectedFhir = toFhirStatus(e.getStatus()).toCode();
            String expectedEntity = toEntityStatus(c.status());
            if (!c.status().trim().equalsIgnoreCase(expectedFhir)
                    && !expectedEntity.equalsIgnoreCase(e.getStatus())) {
                return false;
            }
        }
        if (!isBlank(c.encounterClass())) {
            String needle = c.encounterClass().trim();
            int pipe = needle.indexOf('|');
            String code = pipe >= 0 ? needle.substring(pipe + 1) : needle;
            if (e.getEncounterClass() == null || !e.getEncounterClass().equalsIgnoreCase(code)) {
                return false;
            }
        }
        if (!isBlank(c.practitioner())) {
            String needle = c.practitioner().trim();
            String shortId = needle.contains("/") ? needle.substring(needle.lastIndexOf('/') + 1) : needle;
            if (e.getAttendingId() == null || (!e.getAttendingId().equalsIgnoreCase(needle)
                    && !e.getAttendingId().equalsIgnoreCase(shortId))) {
                return false;
            }
        }
        if (!isBlank(c.location())) {
            String needle = c.location().trim();
            String shortLoc = needle.contains("/") ? needle.substring(needle.lastIndexOf('/') + 1) : needle;
            if (e.getDepartmentCode() == null || !e.getDepartmentCode().toLowerCase(Locale.ROOT).contains(shortLoc.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        if (!isBlank(c.reasonCode())) {
            String needle = c.reasonCode().trim();
            int pipe = needle.indexOf('|');
            String code = pipe >= 0 ? needle.substring(pipe + 1) : needle;
            if (e.getPrimaryIcd10() == null || !e.getPrimaryIcd10().equalsIgnoreCase(code)) {
                return false;
            }
        }
        LocalDate startDate = e.getStartedAt() != null ? e.getStartedAt().atZone(ZoneOffset.UTC).toLocalDate() : null;
        if (c.dateFrom() != null && (startDate == null || startDate.isBefore(c.dateFrom()))) {
            return false;
        }
        if (c.dateTo() != null && (startDate == null || startDate.isAfter(c.dateTo()))) {
            return false;
        }
        for (String expr : c.dateExpressions()) {
            if (!matchesStartDate(startDate, expr)) {
                return false;
            }
        }
        return true;
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
            throw new IllegalArgumentException("Invalid FHIR Encounter date query parameter: " + expr, ex);
        }
        if (!Set.of("eq", "ne", "gt", "lt", "ge", "le", "sa", "eb", "ap").contains(op)) {
            throw new IllegalArgumentException("Unsupported FHIR date search prefix: " + op);
        }
        return new ParsedDateQuery(op, start, end);
    }

    private static boolean matchesStartDate(LocalDate actual, String expr) {
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

    private static void enforceIfMatch(EncounterEntity existing, String ifMatch) {
        if (isBlank(ifMatch)) {
            return;
        }
        String rawVersion = computeEtagVersion(existing);
        String currentEtag = "W/\"" + rawVersion + "\"";
        String trimmed = ifMatch.trim();
        String stripped = trimmed.replace("\"", "").replace("W/", "").trim();
        if (!Objects.equals(trimmed, currentEtag) && !Objects.equals(stripped, rawVersion)) {
            throw new OptimisticLockingFailureException(
                    "Stale If-Match ETag '" + ifMatch + "'; current version is " + currentEtag);
        }
    }

    private static UUID resolveFacilityFromEncounter(UUID facilityId, Encounter incoming) {
        if (facilityId != null) {
            return facilityId;
        }
        if (incoming.hasServiceProvider() && incoming.getServiceProvider().hasReference()) {
            UUID orgUuid = extractUuidSuffix(incoming.getServiceProvider().getReference());
            if (orgUuid != null) {
                return orgUuid;
            }
        }
        return DEFAULT_FACILITY_ID;
    }

    private static void applyFhirFieldsToEntity(EncounterEntity entity, Encounter incoming) {
        if (incoming.hasStatus() && incoming.getStatus() != null) {
            entity.setStatus(toEntityStatus(incoming.getStatus().toCode()));
        }
        if (incoming.hasClass_() && !isBlank(incoming.getClass_().getCode())) {
            entity.setEncounterClass(incoming.getClass_().getCode().trim().toUpperCase(Locale.ROOT));
        }
        if (incoming.hasSubject() && incoming.getSubject().hasReference()) {
            UUID patId = extractUuidSuffix(incoming.getSubject().getReference());
            if (patId != null) {
                entity.setPatientId(patId);
            }
        }
        if (!incoming.getParticipant().isEmpty() && incoming.getParticipantFirstRep().hasIndividual()) {
            Reference ind = incoming.getParticipantFirstRep().getIndividual();
            String raw = ind.hasReference() ? ind.getReference() : ind.getDisplay();
            if (!isBlank(raw)) {
                String clean = raw.startsWith("Practitioner/") ? raw.substring("Practitioner/".length()) : raw;
                entity.setAttendingId(clean);
            }
        }
        if (!incoming.getLocation().isEmpty() && incoming.getLocationFirstRep().hasLocation()) {
            Reference loc = incoming.getLocationFirstRep().getLocation();
            String raw = loc.hasReference() ? loc.getReference() : loc.getDisplay();
            if (!isBlank(raw)) {
                String clean = raw.startsWith("Location/") ? raw.substring("Location/".length()) : raw;
                entity.setDepartmentCode(clean);
            }
        }
        if (!incoming.getReasonCode().isEmpty() && !incoming.getReasonCodeFirstRep().getCoding().isEmpty()) {
            String code = incoming.getReasonCodeFirstRep().getCodingFirstRep().getCode();
            if (!isBlank(code)) {
                entity.setPrimaryIcd10(code.trim());
            }
        }
        if (incoming.hasPeriod()) {
            if (incoming.getPeriod().hasStart()) {
                entity.setStartedAt(incoming.getPeriod().getStart().toInstant());
            }
            if (incoming.getPeriod().hasEnd()) {
                entity.setEndedAt(incoming.getPeriod().getEnd().toInstant());
            }
        }
    }

    private static EncounterStatus toFhirStatus(String rawStatus) {
        if (rawStatus == null) return EncounterStatus.PLANNED;
        String upper = rawStatus.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (upper) {
            case "IN_PROGRESS" -> EncounterStatus.INPROGRESS;
            case "ARRIVED" -> EncounterStatus.ARRIVED;
            case "TRIAGED" -> EncounterStatus.TRIAGED;
            case "ONLEAVE" -> EncounterStatus.ONLEAVE;
            case "FINISHED", "COMPLETED", "DISCHARGED" -> EncounterStatus.FINISHED;
            case "CANCELLED" -> EncounterStatus.CANCELLED;
            case "ENTERED_IN_ERROR" -> EncounterStatus.ENTEREDINERROR;
            case "UNKNOWN" -> EncounterStatus.UNKNOWN;
            default -> EncounterStatus.PLANNED;
        };
    }

    private static String toEntityStatus(String fhirStatus) {
        if (fhirStatus == null) return "PLANNED";
        return switch (fhirStatus.trim().toLowerCase(Locale.ROOT)) {
            case "in-progress" -> "IN_PROGRESS";
            case "arrived" -> "ARRIVED";
            case "triaged" -> "TRIAGED";
            case "onleave" -> "ONLEAVE";
            case "finished" -> "FINISHED";
            case "cancelled" -> "CANCELLED";
            case "entered-in-error" -> "ENTERED_IN_ERROR";
            case "unknown" -> "UNKNOWN";
            default -> "PLANNED";
        };
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
