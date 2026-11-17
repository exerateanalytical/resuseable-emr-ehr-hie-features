/*
 * =============================================================================
 * Opes HealthCore SDK — Patient Consent & Sensitivity Tag Policy Entity
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.consent;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists fine-grained patient consent directives and sensitivity compartment rules
 * (ABAC) across facilities, providers, HIE sharing networks, and research de-identification.
 *
 * <p>Supports sensitivity tags such as {@code HIV}, {@code PSYCH}, {@code SGBV}, {@code REPRO},
 * and {@code VIP}, enabling compartmented clinical access unless overridden via Break-the-Glass.</p>
 */
@Entity
@Table(name = "hc_consent_policies")
public class ConsentPolicyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7Generator.nextUuid();

    @Column(name = "facility_key", nullable = false, length = 64)
    private String facilityKey;

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** Policy scope: TREATMENT, HIE_SHARE, RESEARCH, CHW_FOLLOWUP, or BILLING_PAYER. */
    @Column(name = "scope", nullable = false, length = 40)
    private String scope;

    /** PERMIT or DENY decision for the designated sensitivity tag or grantee. */
    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    /** Optional sensitivity compartment: HIV, PSYCH, VIP, SGBV, ALL. */
    @Column(name = "sensitivity_tag", nullable = false, length = 32)
    private String sensitivityTag = "ALL";

    /** Optional specific practitioner/facility/organization grantee identifier. */
    @Column(name = "grantee_ref", length = 120)
    private String granteeRef;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @Column(name = "witness_or_signature_ref", length = 256)
    private String witnessOrSignatureRef;

    public UUID getId() { return id; }
    public String getFacilityKey() { return facilityKey; }
    public void setFacilityKey(String facilityKey) { this.facilityKey = facilityKey; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getSensitivityTag() { return sensitivityTag; }
    public void setSensitivityTag(String sensitivityTag) { this.sensitivityTag = sensitivityTag; }
    public String getGranteeRef() { return granteeRef; }
    public void setGranteeRef(String granteeRef) { this.granteeRef = granteeRef; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public String getWitnessOrSignatureRef() { return witnessOrSignatureRef; }
    public void setWitnessOrSignatureRef(String witnessOrSignatureRef) { this.witnessOrSignatureRef = witnessOrSignatureRef; }
}
