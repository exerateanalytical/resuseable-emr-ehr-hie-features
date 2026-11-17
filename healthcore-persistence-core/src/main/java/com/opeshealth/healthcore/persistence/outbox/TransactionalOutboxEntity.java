/*
 * =============================================================================
 * Opes HealthCore SDK — Transactional Outbox Entity (ANSI SQL Multi-DB)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.outbox;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * ANSI-portable Transactional Outbox event persisted atomically inside the same ACID
 * database transaction as the clinical/financial domain mutation.
 *
 * <p>Guarantees zero lost events between PostgreSQL/MSSQL/MySQL commits and external
 * publication (Spring in-process bus, Kafka, RabbitMQ, or HIE FHIR endpoints).</p>
 */
@Entity
@Table(name = "hc_outbox_events")
public class TransactionalOutboxEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UuidV7Generator.nextUuid();

    /** Deterministic idempotency key preventing duplicate remote deliveries across retries. */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 140)
    private String idempotencyKey;

    /** Originating facility tenant UUID (nullable only for global system events). */
    @Column(name = "facility_id")
    private UUID facilityId;

    /** Logical DDD bounded context / aggregate name (e.g. "Patient", "Encounter", "Claim"). */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /** Domain event type (e.g. "PatientMergedEvent", "VitalPanicAlertEvent"). */
    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    /** Portable CLOB/NVARCHAR(MAX)/TEXT JSON payload (avoids vendor-locked JSONB types). */
    @Lob
    @Column(name = "payload_json", nullable = false)
    private String payloadJson;

    /** Correlation ID propagated from HTTP/MLLP ingress for end-to-end distributed tracing. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Lifecycle status: PENDING -> SENT | FAILED -> DEAD_LETTER. */
    @Column(name = "status", nullable = false, length = 24)
    private String status = "PENDING";

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Lob
    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public UUID getFacilityId() { return facilityId; }
    public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
    public String getAggregateType() { return aggregateType; }
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
