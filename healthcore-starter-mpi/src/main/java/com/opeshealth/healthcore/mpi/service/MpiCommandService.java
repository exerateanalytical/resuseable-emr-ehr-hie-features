/*
 * =============================================================================
 * Opes HealthCore SDK — MPI Repositories, CQRS Commands & Reversible Merge Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.service;

import com.opeshealth.healthcore.mpi.domain.MpiEntities.*;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher.MatchCandidate;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * CQRS Command & Query service managing Patient Golden Records, AES-256-GCM National ID
 * blind-index lookups, probabilistic Fellegi-Sunter duplicate detection, and reversible merges.
 */
public class MpiCommandService {

    public interface PatientRepository extends JpaRepository<PatientAggregateEntity, UUID> {
        Optional<PatientAggregateEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PatientAggregateEntity> findByFacilityId(UUID facilityId);
        Optional<PatientAggregateEntity> findByFacilityIdAndMrn(UUID facilityId, String mrn);
        List<PatientAggregateEntity> findByIdIn(Collection<UUID> ids);
        List<PatientAggregateEntity> findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(UUID facilityId);
        Optional<PatientAggregateEntity> findByFacilityIdAndEmailIgnoreCase(UUID facilityId, String email);
        List<PatientAggregateEntity> findByFacilityIdAndPrimaryPhone(UUID facilityId, String primaryPhone);
        List<PatientAggregateEntity> findByFacilityIdAndFamilyNameIgnoreCaseAndBirthDate(
                UUID facilityId, String familyName, LocalDate birthDate);

        @Query("""
                SELECT p FROM MpiPatientAggregate p
                WHERE p.facilityId = :facilityId
                  AND p.goldenRecord = true
                  AND p.active = true
                  AND (LOWER(p.familyName) LIKE LOWER(CONCAT(:prefix, '%'))
                       OR LOWER(p.givenName) LIKE LOWER(CONCAT(:prefix, '%')))
                ORDER BY p.familyName ASC, p.givenName ASC
                """)
        List<PatientAggregateEntity> searchActiveGoldenByNamePrefix(
                @Param("facilityId") UUID facilityId, @Param("prefix") String prefix);
    }

    public interface PatientIdentifierRepository extends JpaRepository<PatientIdentifierEntity, UUID> {
        Optional<PatientIdentifierEntity> findByIdentifierSystemAndBlindIndex(String system, String blindIndex);
        List<PatientIdentifierEntity> findByPatientId(UUID patientId);
        List<PatientIdentifierEntity> findByPatientIdOrderByIdentifierSystemAsc(UUID patientId);
        Optional<PatientIdentifierEntity> findFirstByPatientIdAndIdentifierSystem(UUID patientId, String identifierSystem);
    }

    public interface PatientDemographicsRepository extends JpaRepository<PatientDemographicsEntity, UUID> {
        Optional<PatientDemographicsEntity> findByPatientId(UUID patientId);
        void deleteByPatientId(UUID patientId);
    }

    public interface PatientPhoneticRepository extends JpaRepository<PatientPhoneticIndexEntity, UUID> {
        List<PatientPhoneticIndexEntity> findByFamilyMetaphoneAndBirthYearBetween(
                String familyMetaphone, int minYear, int maxYear);
    }

    public interface PatientMergeRepository extends JpaRepository<PatientMergeHistoryEntity, UUID> {}

    public record RegisterPatientCommand(
            UUID facilityId,
            String mrn,
            String namePrefix,
            String familyName,
            String givenName,
            String middleName,
            LocalDate birthDate,
            String gender,
            String bloodGroup,
            String primaryPhone,
            String secondaryPhone,
            String email,
            String maritalStatusCode,
            String preferredLanguage,
            String nationalIdSystem,
            String nationalIdValue,
            String addressLine,
            String cityOrVillage,
            String district,
            String regionOrState,
            String postalCode,
            String country,
            String emergencyContactName,
            String emergencyContactPhone,
            String emergencyContactEmail,
            String emergencyRelationship
    ) {
        /** Backward-compatible constructor preserving existing 14-argument callers. */
        public RegisterPatientCommand(
                UUID facilityId,
                String mrn,
                String familyName,
                String givenName,
                LocalDate birthDate,
                String gender,
                String bloodGroup,
                String primaryPhone,
                String preferredLanguage,
                String nationalIdSystem,
                String nationalIdValue,
                String cityOrVillage,
                String emergencyContactName,
                String emergencyContactPhone) {
            this(facilityId, mrn, null, familyName, givenName, null, birthDate, gender, bloodGroup,
                    primaryPhone, null, null, null, preferredLanguage, nationalIdSystem, nationalIdValue,
                    null, cityOrVillage, null, null, null, null,
                    emergencyContactName, emergencyContactPhone, null, null);
        }
    }

    public record PatientMergedApplicationEvent(
            UUID facilityId, UUID sourcePatientId, UUID survivingPatientId, UUID mergeHistoryId) {}

    public record PatientUnmergedApplicationEvent(
            UUID facilityId, UUID sourcePatientId, UUID survivingPatientId, UUID mergeHistoryId) {}

    private final PatientRepository patientRepo;
    private final PatientIdentifierRepository identifierRepo;
    private final PatientDemographicsRepository demographicsRepo;
    private final PatientPhoneticRepository phoneticRepo;
    private final PatientMergeRepository mergeRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    public MpiCommandService(
            PatientRepository patientRepo,
            PatientIdentifierRepository identifierRepo,
            PatientDemographicsRepository demographicsRepo,
            PatientPhoneticRepository phoneticRepo,
            PatientMergeRepository mergeRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            ApplicationEventPublisher eventPublisher) {
        this(patientRepo, identifierRepo, demographicsRepo, phoneticRepo, mergeRepo, outboxRepo, eventPublisher, null);
    }

    public MpiCommandService(
            PatientRepository patientRepo,
            PatientIdentifierRepository identifierRepo,
            PatientDemographicsRepository demographicsRepo,
            PatientPhoneticRepository phoneticRepo,
            PatientMergeRepository mergeRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            ApplicationEventPublisher eventPublisher,
            JdbcTemplate jdbcTemplate) {
        this.patientRepo = patientRepo;
        this.identifierRepo = identifierRepo;
        this.demographicsRepo = demographicsRepo;
        this.phoneticRepo = phoneticRepo;
        this.mergeRepo = mergeRepo;
        this.outboxRepo = outboxRepo;
        this.eventPublisher = eventPublisher;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    @ClinicalAudit(action = "MPI_REGISTER_PATIENT", resourceType = "Patient")
    public PatientAggregateEntity registerPatient(RegisterPatientCommand cmd) {
        PatientAggregateEntity patient = new PatientAggregateEntity();
        patient.setFacilityId(cmd.facilityId());
        patient.setMrn(cmd.mrn() != null && !cmd.mrn().isBlank()
                ? cmd.mrn()
                : "OPES-" + System.currentTimeMillis());
        patient.setNamePrefix(cmd.namePrefix());
        patient.setFamilyName(cmd.familyName());
        patient.setGivenName(cmd.givenName());
        patient.setMiddleName(cmd.middleName());
        patient.setBirthDate(cmd.birthDate());
        patient.setGender(cmd.gender());
        patient.setBloodGroup(cmd.bloodGroup());
        patient.setPrimaryPhone(cmd.primaryPhone());
        patient.setSecondaryPhone(cmd.secondaryPhone());
        patient.setEmail(cmd.email());
        patient.setMaritalStatusCode(cmd.maritalStatusCode());
        patient.setPreferredLanguage(cmd.preferredLanguage() != null ? cmd.preferredLanguage() : "en");
        patient = patientRepo.save(patient);

        if (cmd.nationalIdValue() != null && !cmd.nationalIdValue().isBlank() && identifierRepo != null) {
            PatientIdentifierEntity ident = new PatientIdentifierEntity();
            ident.setPatientId(patient.getId());
            ident.setIdentifierSystem(cmd.nationalIdSystem() != null ? cmd.nationalIdSystem() : "urn:opes:national-id");
            ident.setIdentifierUse("OFFICIAL");
            ident.setTypeCode("NI");
            ident.setEncryptedValue(cmd.nationalIdValue());
            identifierRepo.save(ident);
        }

        if (demographicsRepo != null) {
            PatientDemographicsEntity demo = new PatientDemographicsEntity();
            demo.setPatientId(patient.getId());
            demo.setAddressLine(cmd.addressLine());
            demo.setCityOrVillage(cmd.cityOrVillage());
            demo.setDistrict(cmd.district());
            demo.setRegionOrState(cmd.regionOrState());
            demo.setPostalCode(cmd.postalCode());
            demo.setCountry(cmd.country());
            demo.setEmergencyContactName(cmd.emergencyContactName());
            demo.setEmergencyContactPhone(cmd.emergencyContactPhone());
            demo.setEmergencyContactEmail(cmd.emergencyContactEmail());
            demo.setEmergencyRelationship(cmd.emergencyRelationship());
            demographicsRepo.save(demo);
        }

        if (phoneticRepo != null) {
            PatientPhoneticIndexEntity phonetic = new PatientPhoneticIndexEntity();
            phonetic.setPatientId(patient.getId());
            phonetic.setFamilyMetaphone(FellegiSunterMatcher.phoneticKey(cmd.familyName()));
            phonetic.setGivenMetaphone(FellegiSunterMatcher.phoneticKey(cmd.givenName()));
            phonetic.setBirthYear(cmd.birthDate().getYear());
            phoneticRepo.save(phonetic);
        }

        if (outboxRepo != null) {
            emitOutboxEvent("Patient", patient.getId(), "PatientRegisteredEvent",
                    "{\"patientId\":\"" + patient.getId() + "\",\"mrn\":\"" + patient.getMrn() + "\"}");
        }
        return patient;
    }

    @Transactional(readOnly = true)
    public Optional<PatientAggregateEntity> findByEncryptedIdentifier(String system, String plaintextId) {
        String blind = PiiVaultCrypto.blindIndex(plaintextId);
        return identifierRepo.findByIdentifierSystemAndBlindIndex(system, blind)
                .flatMap(ident -> patientRepo.findById(ident.getPatientId()))
                .map(this::resolveSurviving);
    }

    @Transactional(readOnly = true)
    public List<MatchCandidate> findProbabilisticMatches(
            String familyName, String givenName, LocalDate birthDate, String gender, String phone) {
        String blockKey = FellegiSunterMatcher.phoneticKey(familyName);
        int year = birthDate.getYear();
        List<UUID> blockIds = phoneticRepo
                .findByFamilyMetaphoneAndBirthYearBetween(blockKey, year - 2, year + 2)
                .stream()
                .map(PatientPhoneticIndexEntity::getPatientId)
                .toList();
        if (blockIds.isEmpty()) return List.of();

        return patientRepo.findByIdIn(blockIds).stream()
                .filter(PatientAggregateEntity::isGoldenRecord)
                .map(c -> {
                    double score = FellegiSunterMatcher.score(familyName, givenName, birthDate, gender, phone, c);
                    return new MatchCandidate(c, score, FellegiSunterMatcher.classify(score));
                })
                .filter(mc -> mc.score() >= 0.50)
                .sorted(Comparator.comparingDouble(MatchCandidate::score).reversed())
                .toList();
    }

    /**
     * Fuzzy patient lookup leveraging PostgreSQL {@code pg_trgm} GIN trigram indexes
     * ({@code gin_trgm_ops} on {@code hc_patients}) when running on PostgreSQL 16+,
     * with a portable JPA substring fallback for MSSQL, MySQL, and H2 test suites.
     */
    @Transactional(readOnly = true)
    public List<PatientAggregateEntity> findByFacilityAndFuzzyName(UUID facilityId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String trimmed = query.trim();

        if (isPostgreSql()) {
            try {
                String likePattern = "%" + trimmed + "%";
                List<UUID> orderedIds = jdbcTemplate.queryForList(
                        """
                        SELECT id FROM hc_patients
                        WHERE facility_id = ?
                          AND golden_record = true
                          AND active = true
                          AND (
                            family_name % ? OR given_name % ? OR mrn ILIKE ?
                            OR lower(family_name) LIKE lower(?) OR lower(given_name) LIKE lower(?)
                          )
                        ORDER BY GREATEST(similarity(family_name, ?), similarity(given_name, ?)) DESC, updated_at DESC
                        LIMIT ?
                        """,
                        UUID.class,
                        facilityId, trimmed, trimmed, likePattern, likePattern, likePattern, trimmed, trimmed, safeLimit
                );
                if (orderedIds.isEmpty()) {
                    return List.of();
                }
                Map<UUID, PatientAggregateEntity> byId = new HashMap<>();
                for (PatientAggregateEntity p : patientRepo.findByIdIn(orderedIds)) {
                    byId.put(p.getId(), p);
                }
                return orderedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
            } catch (Exception ignored) {
                // Fall back cleanly if pg_trgm extension is not enabled in the current database
            }
        }

        String needle = trimmed.toLowerCase(Locale.ROOT);
        return patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facilityId).stream()
                .filter(p -> Objects.equals(p.getFacilityId(), facilityId) && p.isGoldenRecord() && p.isActive())
                .filter(p -> p.getFamilyName().toLowerCase(Locale.ROOT).contains(needle)
                        || p.getGivenName().toLowerCase(Locale.ROOT).contains(needle)
                        || p.getMrn().toLowerCase(Locale.ROOT).contains(needle))
                .limit(safeLimit)
                .toList();
    }

    private boolean isPostgreSql() {
        if (jdbcTemplate == null) {
            return false;
        }
        DataSource ds = jdbcTemplate.getDataSource();
        if (ds == null) {
            return false;
        }
        try (Connection conn = ds.getConnection()) {
            String product = conn.getMetaData().getDatabaseProductName();
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgresql");
        } catch (Exception ex) {
            return false;
        }
    }

    /** Executes a reversible Golden Record merge and emits {@code PatientMergedEvent}. */
    @Transactional
    @ClinicalAudit(action = "MPI_MERGE_PATIENT", resourceType = "Patient")
    public PatientMergeHistoryEntity mergePatients(
            UUID facilityId, UUID sourceId, UUID survivingId, String mergedBy, String reason) {
        if (Objects.equals(sourceId, survivingId)) {
            throw new IllegalArgumentException("Source and surviving patient IDs cannot be identical");
        }
        PatientAggregateEntity source = patientRepo.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException("Source patient not found: " + sourceId));
        PatientAggregateEntity survivor = patientRepo.findById(survivingId)
                .orElseThrow(() -> new NoSuchElementException("Surviving patient not found: " + survivingId));

        source.setGoldenRecord(false);
        source.setActive(false);
        source.setSurvivingPatientId(survivor.getId());
        source.setUpdatedAt(Instant.now());
        patientRepo.save(source);

        PatientMergeHistoryEntity history = new PatientMergeHistoryEntity();
        history.setFacilityId(facilityId);
        history.setSourcePatientId(sourceId);
        history.setSurvivingPatientId(survivingId);
        history.setMergedBy(mergedBy);
        history.setReason(reason);
        history = mergeRepo.save(history);

        emitOutboxEvent("Patient", survivingId, "PatientMergedEvent",
                "{\"sourceId\":\"" + sourceId + "\",\"survivingId\":\"" + survivingId + "\"}");
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PatientMergedApplicationEvent(
                    facilityId, sourceId, survivingId, history.getId()));
        }
        return history;
    }

    /** Reverses a prior patient merge and publishes {@code PatientUnmergedApplicationEvent}. */
    @Transactional
    @ClinicalAudit(action = "MPI_UNMERGE_PATIENT", resourceType = "Patient")
    public PatientMergeHistoryEntity unmergePatients(UUID mergeHistoryId, String reversedBy, String reason) {
        PatientMergeHistoryEntity history = mergeRepo.findById(mergeHistoryId)
                .orElseThrow(() -> new NoSuchElementException("Merge history not found: " + mergeHistoryId));
        if (history.isReversed()) {
            throw new IllegalStateException("Merge " + mergeHistoryId + " was already reversed");
        }
        PatientAggregateEntity source = patientRepo.findById(history.getSourcePatientId())
                .orElseThrow(() -> new NoSuchElementException("Merged source patient not found"));
        source.setGoldenRecord(true);
        source.setActive(true);
        source.setSurvivingPatientId(null);
        source.setUpdatedAt(Instant.now());
        patientRepo.save(source);

        history.setReversed(true);
        history.setReason((history.getReason() != null ? history.getReason() + " | REVERSED by " : "REVERSED by ")
                + reversedBy + ": " + reason);
        history = mergeRepo.save(history);

        emitOutboxEvent("Patient", source.getId(), "PatientUnmergedEvent",
                "{\"mergeHistoryId\":\"" + history.getId() + "\",\"restoredId\":\"" + source.getId() + "\"}");
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new PatientUnmergedApplicationEvent(
                    history.getFacilityId(), source.getId(), history.getSurvivingPatientId(), history.getId()));
        }
        return history;
    }

    public PatientAggregateEntity resolveSurviving(PatientAggregateEntity entity) {
        UUID pointer = entity.getSurvivingPatientId();
        if (!entity.isGoldenRecord() && pointer != null) {
            return patientRepo.findById(pointer).orElse(entity);
        }
        return entity;
    }

    private void emitOutboxEvent(String aggType, UUID aggId, String eventType, String json) {
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(aggType + ":" + eventType + ":" + aggId + ":" + evt.getId());
        evt.setAggregateType(aggType);
        evt.setAggregateId(aggId);
        evt.setEventType(eventType);
        evt.setPayloadJson(json);
        outboxRepo.save(evt);
    }
}
