/*
 * =============================================================================
 * Opes HealthCore SDK — Observation JPA Repository (Server Module)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for {@link ObservationEntity}. */
public interface ObservationRepository extends JpaRepository<ObservationEntity, UUID> {

    Optional<ObservationEntity> findByFacilityIdAndIdentifierSystemAndIdentifierValue(
            UUID facilityId, String identifierSystem, String identifierValue);

    List<ObservationEntity> findByFacilityIdAndPatientIdOrderByEffectiveAtDesc(UUID facilityId, UUID patientId);

    List<ObservationEntity> findByFacilityIdAndEncounterIdOrderByEffectiveAtDesc(UUID facilityId, UUID encounterId);

    List<ObservationEntity> findByFacilityIdOrderByEffectiveAtDesc(UUID facilityId);
}
