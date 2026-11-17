/*
 * =============================================================================
 * Opes HealthCore SDK — Consent, ABAC Sensitivity & Break-the-Glass Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.consent;

import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Attribute-Based Access Control (ABAC) evaluator enforcing patient consent directives,
 * sensitive clinical compartments ({@code HIV}, {@code PSYCH}, {@code VIP}, {@code SGBV}),
 * and audited Break-the-Glass (BTG) emergency overrides.
 */
public class ConsentAndAbacService {

    public interface ConsentPolicyRepository extends JpaRepository<ConsentPolicyEntity, UUID> {
        List<ConsentPolicyEntity> findByFacilityKeyAndPatientIdAndRevokedFalse(String facilityKey, UUID patientId);
    }

    /** Result returned by ABAC decision evaluation. */
    public record AccessDecision(boolean allowed, boolean breakGlassUsed, String reason) {}

    private final ConsentPolicyRepository repository;
    private final ClinicalAuditService auditService;

    public ConsentAndAbacService(ConsentPolicyRepository repository, ClinicalAuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /**
     * Evaluates whether {@code actorId} can access a resource tagged with {@code sensitivityTag}
     * for {@code patientId}. If denied by an active consent rule or restricted compartment,
     * callers must supply a non-blank {@code breakGlassReason} to invoke an audited emergency override.
     */
    @Transactional
    public AccessDecision evaluateAndEnforce(
            String facilityKey,
            String actorId,
            String actorRole,
            UUID patientId,
            String scope,
            String sensitivityTag,
            Optional<String> breakGlassReason,
            String sourceIp) {

        Instant now = Instant.now();
        List<ConsentPolicyEntity> policies =
                repository.findByFacilityKeyAndPatientIdAndRevokedFalse(facilityKey, patientId);

        // Check explicit DENY rules matching scope and sensitivity tag (or ALL).
        Optional<ConsentPolicyEntity> blockingRule = policies.stream()
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                .filter(p -> scope.equalsIgnoreCase(p.getScope()) || "ALL".equalsIgnoreCase(p.getScope()))
                .filter(p -> sensitivityTag.equalsIgnoreCase(p.getSensitivityTag())
                        || "ALL".equalsIgnoreCase(p.getSensitivityTag()))
                .filter(p -> "DENY".equalsIgnoreCase(p.getDecision()))
                .filter(p -> p.getGranteeRef() == null || p.getGranteeRef().equalsIgnoreCase(actorId))
                .findFirst();

        boolean requiresExplicitPermit = "HIV".equalsIgnoreCase(sensitivityTag)
                || "PSYCH".equalsIgnoreCase(sensitivityTag)
                || "VIP".equalsIgnoreCase(sensitivityTag);

        boolean hasExplicitPermit = policies.stream()
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(now))
                .filter(p -> "PERMIT".equalsIgnoreCase(p.getDecision()))
                .anyMatch(p -> sensitivityTag.equalsIgnoreCase(p.getSensitivityTag())
                        || "ALL".equalsIgnoreCase(p.getSensitivityTag()));

        if (blockingRule.isEmpty() && (!requiresExplicitPermit || hasExplicitPermit)) {
            return new AccessDecision(true, false, "PERMITTED_BY_POLICY");
        }

        // Handle Break-the-Glass emergency clinical override with mandatory justification.
        if (breakGlassReason.isPresent() && !breakGlassReason.get().isBlank()) {
            String justification = breakGlassReason.get().trim();
            auditService.recordEvent(
                    facilityKey,
                    actorId,
                    actorRole,
                    "BREAK_THE_GLASS_OVERRIDE",
                    "PatientChart:" + sensitivityTag,
                    patientId.toString(),
                    patientId,
                    "SUCCESS",
                    "EMERGENCY_BTG: " + justification,
                    sourceIp
            );
            return new AccessDecision(true, true, "BREAK_THE_GLASS: " + justification);
        }

        auditService.recordEvent(
                facilityKey,
                actorId,
                actorRole,
                "ACCESS_DENIED",
                "PatientChart:" + sensitivityTag,
                patientId.toString(),
                patientId,
                "DENIED",
                blockingRule.isPresent() ? "Denied by patient consent directive" : "Restricted compartment requires permit or BTG",
                sourceIp
        );
        throw new AccessDeniedException(
                "Access denied for sensitivity compartment [" + sensitivityTag + "]. Provide Break-the-Glass justification if emergency.");
    }
}
