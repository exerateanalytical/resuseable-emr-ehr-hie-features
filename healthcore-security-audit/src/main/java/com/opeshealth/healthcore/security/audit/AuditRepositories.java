/*
 * =============================================================================
 * Opes HealthCore SDK — Security & Audit JPA Repositories
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository contracts for tamper-evident audit chains and consent policies.
 *
 * <p>Uses standard JPQL and JPA {@link LockModeType#PESSIMISTIC_WRITE} so Hibernate issues
 * the dialect-appropriate row lock ({@code FOR UPDATE} on PostgreSQL/MySQL and
 * {@code WITH (UPDLOCK, ROWLOCK)} on Windows SQL Server).</p>
 */
public interface AuditRepositories {

    /**
     * Repository for per-facility cryptographic chain head locks.
     */
    interface AuditChainHeadRepository extends JpaRepository<AuditChainHeadEntity, String> {

        /**
         * Acquires a pessimistic write lock on the facility's chain-head row so parallel
         * transactions serialize their SHA-256 chain link without forking.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("SELECT h FROM AuditChainHeadEntity h WHERE h.facilityKey = :facilityKey")
        Optional<AuditChainHeadEntity> findByFacilityKeyForUpdate(@Param("facilityKey") String facilityKey);
    }

    /**
     * Repository for immutable SHA-256 hash-chained audit ledger entries.
     */
    interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

        /**
         * Fetches ordered audit rows for cryptographic chain integrity verification.
         */
        List<AuditLogEntity> findByFacilityKeyOrderBySequenceNumberAsc(String facilityKey);

        /**
         * Keyset-friendly paginated lookup of patient-specific chart access history.
         */
        Page<AuditLogEntity> findByFacilityKeyAndPatientIdOrderBySequenceNumberDesc(
                String facilityKey, UUID patientId, Pageable pageable);

        /**
         * Full ordered history of all FHIR resource access events for a specific patient
         * (HIPAA § 164.528 Accounting of Disclosures / GDPR Article 15 Right of Access).
         */
        List<AuditLogEntity> findByFacilityKeyAndPatientIdOrderBySequenceNumberDesc(
                String facilityKey, UUID patientId);

        /**
         * Fetches audit entries for a specific FHIR resource type within a facility.
         */
        List<AuditLogEntity> findByFacilityKeyAndResourceTypeOrderBySequenceNumberDesc(
                String facilityKey, String resourceType);

        /**
         * Fetches audit entries for a specific FHIR resource instance (e.g., Patient/uuid).
         */
        List<AuditLogEntity> findByFacilityKeyAndResourceTypeAndResourceIdOrderBySequenceNumberDesc(
                String facilityKey, String resourceType, String resourceId);

        /**
         * Fetches audit entries initiated by a specific clinician, staff member, or client actor.
         */
        List<AuditLogEntity> findByFacilityKeyAndActorIdOrderBySequenceNumberDesc(
                String facilityKey, String actorId);

        /**
         * Fetches audit entries filtered by execution outcome (SUCCESS / FAILURE).
         */
        List<AuditLogEntity> findByFacilityKeyAndOutcomeOrderBySequenceNumberDesc(
                String facilityKey, String outcome);

        /**
         * Fetches audit entries within a timestamp window for compliance auditing.
         */
        List<AuditLogEntity> findByFacilityKeyAndOccurredAtBetweenOrderBySequenceNumberDesc(
                String facilityKey, java.time.Instant fromInclusive, java.time.Instant toInclusive);
    }
}
