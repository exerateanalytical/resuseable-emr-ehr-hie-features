/*
 * =============================================================================
 * Opes HealthCore SDK — Immutable Tamper-Evident Clinical Audit Log Entity
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "hc_audit_logs")
public class AuditLogEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7Generator.nextUuid();

    @Column(name = "facility_key", nullable = false, length = 64)
    private String facilityKey;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "actor_id", nullable = false, length = 120)
    private String actorId;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 120)
    private String resourceId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "outcome", nullable = false, length = 24)
    private String outcome;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(name = "entry_hash", nullable = false, length = 64)
    private String entryHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public UUID getId() { return id; }
    public String getFacilityKey() { return facilityKey; }
    public void setFacilityKey(String facilityKey) { this.facilityKey = facilityKey; }
    public long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public UUID getPatientId() { return patientId; }
    public void setPatientId(UUID patientId) { this.patientId = patientId; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getEntryHash() { return entryHash; }
    public void setEntryHash(String entryHash) { this.entryHash = entryHash; }
    public Instant getOccurredAt() { return occurredAt; }
}
