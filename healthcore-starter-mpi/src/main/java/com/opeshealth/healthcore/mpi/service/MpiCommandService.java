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
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * CQRS Command & Query service managing Patient Golden Records, AES-256-GCM National ID
 * blind-index lookups, probabilistic Fellegi-Sunter duplicate detection, and reversible merges.
 */
public class MpiCommandService {

    public interface PatientRepository extends JpaRepository<PatientAggregateEntity, UUID> {
        Optional<PatientAggregateEntity> findByFacilityIdAndMrn(UUID facilityId, String mrn);
        List<PatientAggregateEntity> findByIdIn(Collection<UUID> ids);
    }

    public interface PatientIdentifierRepository extends JpaRepository<PatientIdentifierEntity, UUID> {
        Optional<PatientIdentifierEntity> findByIdentifierSystemAndBlindIndex(String system, String blindIndex);
        List<PatientIdentifierEntity> findByPatientId(UUID patientId);
    }

    public interface PatientDemographicsRepository extends JpaRepository<PatientDemographicsEntity, UUID> {
        Optional<PatientDemographicsEntity> findByPatientId(UUID patientId);
    }

    public interface PatientPhoneticRepository extends JpaRepository<PatientPhoneticIndexEntity, UUID> {
        List<PatientPhoneticIndexEntity> findByFamilyMetaphoneAndBirthYearBetween(
                String familyMetaphone, int minYear, int maxYear);
    }

    public interface PatientMergeRepository extends JpaRepository<PatientMergeHistoryEntity, UUID> {}

    public record RegisterPatientCommand(
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
            String emergencyContactPhone
    ) {}

    private final PatientRepository patientRepo;
    private final PatientIdentifierRepository identifierRepo;
    private final PatientDemographicsRepository demographicsRepo;
    private final PatientPhoneticRepository phoneticRepo;
    private final PatientMergeRepository mergeRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;

    public MpiCommandService(
            PatientRepository patientRepo,
            PatientIdentifierRepository identifierRepo,
            PatientDemographicsRepository demographicsRepo,
            PatientPhoneticRepository phoneticRepo,
            PatientMergeRepository mergeRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.patientRepo = patientRepo;
        this.identifierRepo = identifierRepo;
        this.demographicsRepo = demographicsRepo;
        this.phoneticRepo = phoneticRepo;
        this.mergeRepo = mergeRepo;
        this.outboxRepo = outboxRepo;
    }

    @Transactional
    @ClinicalAudit(action = "MPI_REGISTER_PATIENT", resourceType = "Patient")
    public PatientAggregateEntity registerPatient(RegisterPatientCommand cmd) {
        PatientAggregateEntity patient = new PatientAggregateEntity();
        patient.setFacilityId(cmd.facilityId());
        patient.setMrn(cmd.mrn() != null && !cmd.mrn().isBlank()
                ? cmd.mrn()
                : "OPES-" + System.currentTimeMillis());
        patient.setFamilyName(cmd.familyName());
        patient.setGivenName(cmd.givenName());
        patient.setBirthDate(cmd.birthDate());
        patient.setGender(cmd.gender());
        patient.setBloodGroup(cmd.bloodGroup());
        patient.setPrimaryPhone(cmd.primaryPhone());
        patient.setPreferredLanguage(cmd.preferredLanguage() != null ? cmd.preferredLanguage() : "en");
        patient = patientRepo.save(patient);

        if (cmd.nationalIdValue() != null && !cmd.nationalIdValue().isBlank()) {
            PatientIdentifierEntity ident = new PatientIdentifierEntity();
            ident.setPatientId(patient.getId());
            ident.setIdentifierSystem(cmd.nationalIdSystem() != null ? cmd.nationalIdSystem() : "urn:opes:national-id");
            ident.setEncryptedValue(cmd.nationalIdValue());
            identifierRepo.save(ident);
        }

        PatientDemographicsEntity demo = new PatientDemographicsEntity();
        demo.setPatientId(patient.getId());
        demo.setCityOrVillage(cmd.cityOrVillage());
        demo.setEmergencyContactName(cmd.emergencyContactName());
        demo.setEmergencyContactPhone(cmd.emergencyContactPhone());
        demographicsRepo.save(demo);

        PatientPhoneticIndexEntity phonetic = new PatientPhoneticIndexEntity();
        phonetic.setPatientId(patient.getId());
        phonetic.setFamilyMetaphone(FellegiSunterMatcher.phoneticKey(cmd.familyName()));
        phonetic.setGivenMetaphone(FellegiSunterMatcher.phoneticKey(cmd.givenName()));
        phonetic.setBirthYear(cmd.birthDate().getYear());
        phoneticRepo.save(phonetic);

        emitOutboxEvent("Patient", patient.getId(), "PatientRegisteredEvent",
                "{\"patientId\":\"" + patient.getId() + "\",\"mrn\":\"" + patient.getMrn() + "\"}");
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
