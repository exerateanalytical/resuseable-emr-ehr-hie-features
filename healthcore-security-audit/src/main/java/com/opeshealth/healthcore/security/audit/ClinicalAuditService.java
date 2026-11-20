/*
 * =============================================================================
 * Opes HealthCore SDK — Tamper-Evident SHA-256 Hash-Chained Audit Service & AOP
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import com.opeshealth.healthcore.persistence.observability.CorrelationAndPhiGuard;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Appends immutable audit entries into a per-facility SHA-256 cryptographic hash chain
 * in an isolated {@link Propagation#REQUIRES_NEW} transaction so denied or rolled-back
 * clinical mutations still leave an indelible forensic trail.
 */
@Aspect
public class ClinicalAuditService {

    private static final String GENESIS_HASH = "0".repeat(64);

    /** Verification result returned to compliance auditors and health checks. */
    public record ChainVerificationResult(String facilityKey, boolean valid, long verifiedCount, String brokenAtSequenceOrNull) {}

    private final AuditRepositories.AuditChainHeadRepository headRepository;
    private final AuditRepositories.AuditLogRepository logRepository;

    public ClinicalAuditService(
            AuditRepositories.AuditChainHeadRepository headRepository,
            AuditRepositories.AuditLogRepository logRepository) {
        this.headRepository = headRepository;
        this.logRepository = logRepository;
    }

    /**
     * AOP interceptor automatically auditing every method annotated with {@link ClinicalAudit}.
     */
    @Around("@annotation(clinicalAudit)")
    public Object auditAnnotatedMethod(ProceedingJoinPoint pjp, ClinicalAudit clinicalAudit) throws Throwable {
        String facilityKey = FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = auth != null ? auth.getName() : "SYSTEM";
        String role = auth != null && !auth.getAuthorities().isEmpty()
                ? auth.getAuthorities().iterator().next().getAuthority()
                : "UNKNOWN";

        try {
            Object result = pjp.proceed();
            recordEvent(facilityKey, actorId, role, clinicalAudit.action(),
                    clinicalAudit.resourceType(), null, null, "SUCCESS", null, "internal");
            return result;
        } catch (Throwable ex) {
            recordEvent(facilityKey, actorId, role, clinicalAudit.action(),
                    clinicalAudit.resourceType(), null, null, "FAILURE",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), "internal");
            throw ex;
        }
    }

    /**
     * Appends one tamper-evident row using pessimistic lock serialization on {@link AuditChainHeadEntity}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntity recordEvent(
            String facilityKey,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String outcome,
            String reason,
            String sourceIp) {
        return recordEventWithCorrelation(
                facilityKey,
                actorId,
                actorRole,
                action,
                resourceType,
                resourceId,
                patientId,
                outcome,
                reason,
                sourceIp,
                MDC.get(CorrelationAndPhiGuard.MDC_KEY)
        );
    }

    /**
     * Appends one tamper-evident row with an explicit correlation ID and automatic PHI redaction
     * on the diagnostic reason field for HIPAA/GDPR compliance.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLogEntity recordEventWithCorrelation(
            String facilityKey,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String outcome,
            String reason,
            String sourceIp,
            String correlationId) {

        String normalizedFacility = (facilityKey != null && !facilityKey.isBlank()) ? facilityKey.trim() : "GLOBAL";
        String normalizedActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "ANONYMOUS";
        String normalizedOutcome = (outcome != null && !outcome.isBlank()) ? outcome.trim() : "SUCCESS";
        String redactedReason = reason != null ? PhiRedactionConverter.redact(reason) : null;
        if (redactedReason != null && redactedReason.length() > 500) {
            redactedReason = redactedReason.substring(0, 500);
        }
        String resolvedCorrelation = (correlationId != null && !correlationId.isBlank())
                ? correlationId.trim()
                : MDC.get(CorrelationAndPhiGuard.MDC_KEY);

        AuditChainHeadEntity head = headRepository.findByFacilityKeyForUpdate(normalizedFacility)
                .orElseGet(() -> {
                    AuditChainHeadEntity created = new AuditChainHeadEntity();
                    created.setFacilityKey(normalizedFacility);
                    created.setLastHash(GENESIS_HASH);
                    created.setSequenceNumber(0L);
                    return headRepository.saveAndFlush(created);
                });

        long nextSeq = head.getSequenceNumber() + 1;
        String prevHash = head.getLastHash();

        AuditLogEntity entry = new AuditLogEntity();
        entry.setFacilityKey(normalizedFacility);
        entry.setSequenceNumber(nextSeq);
        entry.setActorId(normalizedActor);
        entry.setActorRole(actorRole);
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setPatientId(patientId);
        entry.setOutcome(normalizedOutcome);
        entry.setReason(redactedReason);
        entry.setSourceIp(sourceIp);
        entry.setCorrelationId(resolvedCorrelation);
        entry.setPrevHash(prevHash);

        String entryHash = computeSha256(
                prevHash, normalizedFacility, nextSeq, normalizedActor, action, resourceType, resourceId, normalizedOutcome);
        entry.setEntryHash(entryHash);

        head.setLastHash(entryHash);
        head.setSequenceNumber(nextSeq);
        headRepository.save(head);

        return logRepository.save(entry);
    }

    /**
     * Looks up a single immutable audit ledger row by its UUIDv7 primary key.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<AuditLogEntity> findById(UUID id) {
        return logRepository.findById(id);
    }

    /**
     * Returns all persisted audit ledger entries for a facility ordered by sequence number ascending.
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> findFacilityLogs(String facilityKey) {
        return logRepository.findByFacilityKeyOrderBySequenceNumberAsc(facilityKey);
    }

    /**
     * Returns all persisted audit ledger entries for a specific patient in reverse chronological sequence
     * (HIPAA § 164.528 Accounting of Disclosures / GDPR Article 15 Right of Access).
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> findPatientLogs(String facilityKey, UUID patientId) {
        return logRepository.findByFacilityKeyAndPatientIdOrderBySequenceNumberDesc(facilityKey, patientId);
    }

    /**
     * Returns persisted audit entries for a specific FHIR resource type (and optional resource ID).
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> findResourceLogs(String facilityKey, String resourceType, String resourceId) {
        if (resourceId != null && !resourceId.isBlank()) {
            return logRepository.findByFacilityKeyAndResourceTypeAndResourceIdOrderBySequenceNumberDesc(
                    facilityKey, resourceType, resourceId);
        }
        return logRepository.findByFacilityKeyAndResourceTypeOrderBySequenceNumberDesc(facilityKey, resourceType);
    }

    /**
     * Returns persisted audit entries initiated by a specific clinician or client actor.
     */
    @Transactional(readOnly = true)
    public List<AuditLogEntity> findActorLogs(String facilityKey, String actorId) {
        return logRepository.findByFacilityKeyAndActorIdOrderBySequenceNumberDesc(facilityKey, actorId);
    }

    /**
     * Re-computes every SHA-256 link in the facility's audit ledger to prove zero tampering
     * across PostgreSQL, Windows SQL Server, or MySQL storage.
     */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyChainIntegrity(String facilityKey) {
        List<AuditLogEntity> entries = logRepository.findByFacilityKeyOrderBySequenceNumberAsc(facilityKey);
        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 1L;
        for (AuditLogEntity row : entries) {
            if (row.getSequenceNumber() != expectedSeq || !expectedPrev.equals(row.getPrevHash())) {
                return new ChainVerificationResult(facilityKey, false, expectedSeq - 1,
                        "Sequence/prevHash mismatch at seq=" + row.getSequenceNumber());
            }
            String recomputed = computeSha256(
                    row.getPrevHash(), row.getFacilityKey(), row.getSequenceNumber(),
                    row.getActorId(), row.getAction(), row.getResourceType(),
                    row.getResourceId(), row.getOutcome());
            if (!recomputed.equals(row.getEntryHash())) {
                return new ChainVerificationResult(facilityKey, false, expectedSeq - 1,
                        "Hash tampering detected at seq=" + row.getSequenceNumber());
            }
            expectedPrev = row.getEntryHash();
            expectedSeq++;
        }
        return new ChainVerificationResult(facilityKey, true, entries.size(), null);
    }

    private String computeSha256(
            String prevHash, String facilityKey, long seq,
            String actorId, String action, String resourceType, String resourceId, String outcome) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String canonical = String.join("|",
                    prevHash,
                    facilityKey,
                    Long.toString(seq),
                    actorId != null ? actorId : "",
                    action != null ? action : "",
                    resourceType != null ? resourceType : "",
                    resourceId != null ? resourceId : "",
                    outcome != null ? outcome : "");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 computation failure", ex);
        }
    }
}
