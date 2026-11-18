/*
 * =============================================================================
 * Opes HealthCore SDK — Encounter JPA Repository (Server Module)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.domain;

import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for {@link EncounterEntity} in the server module. */
public interface EncounterRepository extends JpaRepository<EncounterEntity, UUID> {

    List<EncounterEntity> findByFacilityIdAndPatientIdOrderByStartedAtDesc(UUID facilityId, UUID patientId);

    List<EncounterEntity> findByFacilityIdAndStatusIgnoreCaseOrderByStartedAtDesc(UUID facilityId, String status);

    List<EncounterEntity> findByFacilityIdOrderByStartedAtDesc(UUID facilityId);

    List<EncounterEntity> findByPatientIdOrderByStartedAtDesc(UUID patientId);
}
