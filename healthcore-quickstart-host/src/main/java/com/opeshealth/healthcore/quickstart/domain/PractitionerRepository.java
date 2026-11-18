/*
 * =============================================================================
 * Opes HealthCore SDK — Practitioner JPA Repository (Server Module)
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

/** Spring Data JPA repository for {@link PractitionerEntity}. */
public interface PractitionerRepository extends JpaRepository<PractitionerEntity, UUID> {

    Optional<PractitionerEntity> findByFacilityIdAndIdentifierSystemAndIdentifierValue(
            UUID facilityId, String identifierSystem, String identifierValue);

    List<PractitionerEntity> findByFacilityIdAndActiveTrueOrderByFamilyNameAsc(UUID facilityId);

    List<PractitionerEntity> findByFacilityIdOrderByFamilyNameAsc(UUID facilityId);
}
