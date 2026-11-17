package com.healthcore.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConsentRepository extends JpaRepository<PatientConsentEntity, UUID> {

    @Query("""
            SELECT c FROM PatientConsentEntity c
            WHERE c.patientId = :patientId
              AND c.status = 'ACTIVE'
              AND c.effectiveFrom <= :now
              AND (c.expiresAt IS NULL OR c.expiresAt > :now)
              AND (c.targetFacilityId IS NULL OR c.targetFacilityId = :requestingFacilityId)
            """)
    List<PatientConsentEntity> findActiveConsentsForFacility(
            @Param("patientId") UUID patientId,
            @Param("requestingFacilityId") UUID requestingFacilityId,
            @Param("now") Instant now
    );
}
