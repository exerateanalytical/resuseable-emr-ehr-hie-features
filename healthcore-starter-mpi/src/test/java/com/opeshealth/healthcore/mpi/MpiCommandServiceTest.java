/*
 * =============================================================================
 * Opes HealthCore SDK — Master Patient Index (MPI) Command & Merge Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi;

import com.opeshealth.healthcore.mpi.domain.MpiEntities.*;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.*;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MpiCommandService} covering Golden Record registration,
 * encrypted identifier blind-index lookup, Fellegi-Sunter probabilistic duplicate
 * scoring, and reversible Golden Record merge/unmerge lifecycle.
 */
class MpiCommandServiceTest {

    private PatientRepository patientRepo;
    private PatientIdentifierRepository identifierRepo;
    private PatientDemographicsRepository demographicsRepo;
    private PatientPhoneticRepository phoneticRepo;
    private PatientMergeRepository mergeRepo;
    private OutboxRelayService.OutboxRepository outboxRepo;
    private ApplicationEventPublisher eventPublisher;
    private MpiCommandService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        patientRepo = mock(PatientRepository.class);
        identifierRepo = mock(PatientIdentifierRepository.class);
        demographicsRepo = mock(PatientDemographicsRepository.class);
        phoneticRepo = mock(PatientPhoneticRepository.class);
        mergeRepo = mock(PatientMergeRepository.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new MpiCommandService(
                patientRepo, identifierRepo, demographicsRepo, phoneticRepo, mergeRepo, outboxRepo, eventPublisher);
        facilityId = UUID.randomUUID();
    }

    @Test
    void registerPatientPersistsAggregateIdentifierDemographicsPhoneticIndexAndOutbox() {
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(identifierRepo.save(any(PatientIdentifierEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(demographicsRepo.save(any(PatientDemographicsEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(phoneticRepo.save(any(PatientPhoneticIndexEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RegisterPatientCommand cmd = new RegisterPatientCommand(
                facilityId, "MRN-MPI-100", "Kamga", "Aline",
                LocalDate.of(1989, 3, 12), "female", "O+",
                "+237677001122", "fr", "urn:cm:cni", "CM-CNI-998877",
                "Douala", "Paul Kamga", "+237699001122");

        PatientAggregateEntity saved = service.registerPatient(cmd);

        assertEquals(facilityId, saved.getFacilityId());
        assertEquals("MRN-MPI-100", saved.getMrn());
        assertEquals("Kamga", saved.getFamilyName());
        assertTrue(saved.isGoldenRecord());
        verify(identifierRepo).save(any(PatientIdentifierEntity.class));
        verify(demographicsRepo).save(any(PatientDemographicsEntity.class));
        verify(phoneticRepo).save(any(PatientPhoneticIndexEntity.class));
        verify(outboxRepo).save(any(TransactionalOutboxEntity.class));
    }

    @Test
    void findByEncryptedIdentifierResolvesSurvivingGoldenRecordAfterMerge() {
        PatientAggregateEntity mergedSource = new PatientAggregateEntity();
        mergedSource.setFacilityId(facilityId);
        mergedSource.setMrn("MRN-OLD");
        mergedSource.setGoldenRecord(false);

        PatientAggregateEntity survivor = new PatientAggregateEntity();
        survivor.setFacilityId(facilityId);
        survivor.setMrn("MRN-GOLDEN");
        survivor.setGoldenRecord(true);
        mergedSource.setSurvivingPatientId(survivor.getId());

        PatientIdentifierEntity ident = new PatientIdentifierEntity();
        ident.setPatientId(mergedSource.getId());
        ident.setIdentifierSystem("urn:cm:cni");
        ident.setEncryptedValue("CM-CNI-112233");

        String expectedBlind = PiiVaultCrypto.blindIndex("CM-CNI-112233");
        when(identifierRepo.findByIdentifierSystemAndBlindIndex("urn:cm:cni", expectedBlind))
                .thenReturn(Optional.of(ident));
        when(patientRepo.findById(mergedSource.getId())).thenReturn(Optional.of(mergedSource));
        when(patientRepo.findById(survivor.getId())).thenReturn(Optional.of(survivor));

        Optional<PatientAggregateEntity> found = service.findByEncryptedIdentifier("urn:cm:cni", "CM-CNI-112233");

        assertTrue(found.isPresent());
        assertEquals(survivor.getId(), found.get().getId());
        assertEquals("MRN-GOLDEN", found.get().getMrn());
    }

    @Test
    void findProbabilisticMatchesReturnsHighConfidenceGoldenDuplicate() {
        PatientAggregateEntity existing = new PatientAggregateEntity();
        existing.setFacilityId(facilityId);
        existing.setFamilyName("Mbappe");
        existing.setGivenName("Jean");
        existing.setBirthDate(LocalDate.of(1990, 4, 15));
        existing.setGender("male");
        existing.setPrimaryPhone("+237670112233");
        existing.setGoldenRecord(true);

        PatientPhoneticIndexEntity block = new PatientPhoneticIndexEntity();
        block.setPatientId(existing.getId());
        block.setFamilyMetaphone(FellegiSunterMatcher.phoneticKey("Mbappe"));
        block.setGivenMetaphone(FellegiSunterMatcher.phoneticKey("Jean"));
        block.setBirthYear(1990);

        when(phoneticRepo.findByFamilyMetaphoneAndBirthYearBetween(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(block));
        when(patientRepo.findByIdIn(List.of(existing.getId()))).thenReturn(List.of(existing));

        List<FellegiSunterMatcher.MatchCandidate> matches = service.findProbabilisticMatches(
                "Mbappe", "Jean", LocalDate.of(1990, 4, 15), "male", "+237670112233");

        assertEquals(1, matches.size());
        assertTrue(matches.getFirst().score() >= 0.85);
    }

    @Test
    void mergeAndUnmergeLifecycleUpdatesGoldenPointersAndPublishesEvents() {
        PatientAggregateEntity source = new PatientAggregateEntity();
        source.setFacilityId(facilityId);
        source.setMrn("MRN-DUP");
        source.setFamilyName("Tchoumi");
        source.setGivenName("Luc");
        source.setBirthDate(LocalDate.of(1985, 1, 10));
        source.setGender("male");

        PatientAggregateEntity survivor = new PatientAggregateEntity();
        survivor.setFacilityId(facilityId);
        survivor.setMrn("MRN-GOLD");
        survivor.setFamilyName("Tchoumi");
        survivor.setGivenName("Luc");
        survivor.setBirthDate(LocalDate.of(1985, 1, 10));
        survivor.setGender("male");

        when(patientRepo.findById(source.getId())).thenReturn(Optional.of(source));
        when(patientRepo.findById(survivor.getId())).thenReturn(Optional.of(survivor));
        when(patientRepo.save(any(PatientAggregateEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mergeRepo.save(any(PatientMergeHistoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(IllegalArgumentException.class, () ->
                service.mergePatients(facilityId, source.getId(), source.getId(), "admin", "invalid"));

        PatientMergeHistoryEntity history = service.mergePatients(
                facilityId, source.getId(), survivor.getId(), "him-officer-1", "Confirmed duplicate CNI");

        assertFalse(source.isGoldenRecord());
        assertFalse(source.isActive());
        assertEquals(survivor.getId(), source.getSurvivingPatientId());
        verify(eventPublisher).publishEvent(any(PatientMergedApplicationEvent.class));

        when(mergeRepo.findById(history.getId())).thenReturn(Optional.of(history));
        PatientMergeHistoryEntity reversed = service.unmergePatients(
                history.getId(), "him-supervisor-1", "Twins mistakenly merged");

        assertTrue(reversed.isReversed());
        assertTrue(source.isGoldenRecord());
        assertTrue(source.isActive());
        assertNull(source.getSurvivingPatientId());
        verify(eventPublisher).publishEvent(any(PatientUnmergedApplicationEvent.class));

        assertThrows(IllegalStateException.class, () ->
                service.unmergePatients(history.getId(), "him-supervisor-1", "Duplicate reversal"));
    }
}
