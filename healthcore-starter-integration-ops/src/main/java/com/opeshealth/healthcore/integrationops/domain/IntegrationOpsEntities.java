/*
 * =============================================================================
 * Opes HealthCore SDK — Integration Operations Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.integrationops.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class IntegrationOpsEntities {
    private IntegrationOpsEntities() {}

    @Entity
    @Table(name = "hc_intops_partner_profiles")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PartnerConformanceProfileEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "partner_code", nullable = false, length = 64) private String partnerCode;
        @Column(name = "partner_name", nullable = false, length = 180) private String partnerName;
        @Column(name = "protocol_family", nullable = false, length = 32) private String protocolFamily;
        @Column(name = "endpoint_url", nullable = false, length = 500) private String endpointUrl;
        @Column(name = "auth_mode", nullable = false, length = 32) private String authMode = "MTLS";
        @Column(name = "allowed_message_types", nullable = false, length = 500) private String allowedMessageTypes;
        @Column(name = "rate_limit_per_min", nullable = false) private int rateLimitPerMin = 120;
        @Column(name = "circuit_state", nullable = false, length = 24) private String circuitState = "CLOSED";
        @Column(name = "consecutive_failures", nullable = false) private int consecutiveFailures;
        @Column(name = "active", nullable = false) private boolean active = true;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getPartnerCode() { return partnerCode; }
        public void setPartnerCode(String partnerCode) { this.partnerCode = partnerCode; }
        public String getPartnerName() { return partnerName; }
        public void setPartnerName(String partnerName) { this.partnerName = partnerName; }
        public String getProtocolFamily() { return protocolFamily; }
        public void setProtocolFamily(String protocolFamily) { this.protocolFamily = protocolFamily; }
        public String getEndpointUrl() { return endpointUrl; }
        public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
        public String getAuthMode() { return authMode; }
        public void setAuthMode(String authMode) { this.authMode = authMode; }
        public String getAllowedMessageTypes() { return allowedMessageTypes; }
        public void setAllowedMessageTypes(String allowedMessageTypes) { this.allowedMessageTypes = allowedMessageTypes; }
        public int getRateLimitPerMin() { return rateLimitPerMin; }
        public void setRateLimitPerMin(int rateLimitPerMin) { this.rateLimitPerMin = rateLimitPerMin; }
        public String getCircuitState() { return circuitState; }
        public void setCircuitState(String circuitState) { this.circuitState = circuitState; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_intops_message_ledger")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class IntegrationMessageLedgerEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "partner_id", nullable = false) private UUID partnerId;
        @Column(name = "direction", nullable = false, length = 16) private String direction;
        @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
        @Column(name = "protocol_family", nullable = false, length = 32) private String protocolFamily;
        @Column(name = "message_type", nullable = false, length = 64) private String messageType;
        @Column(name = "payload_sha256", nullable = false, length = 64) private String payloadSha256;
        @Lob @Column(name = "payload_body", nullable = false) private String payloadBody;
        @Column(name = "delivery_status", nullable = false, length = 24) private String deliveryStatus = "QUEUED";
        @Column(name = "attempt_count", nullable = false) private int attemptCount;
        @Column(name = "max_attempts", nullable = false) private int maxAttempts = 5;
        @Column(name = "next_retry_at") private Instant nextRetryAt;
        @Column(name = "last_http_status") private Integer lastHttpStatus;
        @Column(name = "last_error_code", length = 64) private String lastErrorCode;
        @Column(name = "last_error_message", length = 500) private String lastErrorMessage;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "delivered_at") private Instant deliveredAt;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPartnerId() { return partnerId; }
        public void setPartnerId(UUID partnerId) { this.partnerId = partnerId; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public String getProtocolFamily() { return protocolFamily; }
        public void setProtocolFamily(String protocolFamily) { this.protocolFamily = protocolFamily; }
        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
        public String getPayloadSha256() { return payloadSha256; }
        public void setPayloadSha256(String payloadSha256) { this.payloadSha256 = payloadSha256; }
        public String getPayloadBody() { return payloadBody; }
        public void setPayloadBody(String payloadBody) { this.payloadBody = payloadBody; }
        public String getDeliveryStatus() { return deliveryStatus; }
        public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }
        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Instant getNextRetryAt() { return nextRetryAt; }
        public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
        public Integer getLastHttpStatus() { return lastHttpStatus; }
        public void setLastHttpStatus(Integer lastHttpStatus) { this.lastHttpStatus = lastHttpStatus; }
        public String getLastErrorCode() { return lastErrorCode; }
        public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
        public String getLastErrorMessage() { return lastErrorMessage; }
        public void setLastErrorMessage(String lastErrorMessage) { this.lastErrorMessage = lastErrorMessage; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getDeliveredAt() { return deliveredAt; }
        public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_intops_replay_audit")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class IntegrationReplayAuditEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "message_id", nullable = false) private UUID messageId;
        @Column(name = "replayed_by", nullable = false, length = 120) private String replayedBy;
        @Column(name = "justification", nullable = false, length = 500) private String justification;
        @Column(name = "previous_status", nullable = false, length = 24) private String previousStatus;
        @Column(name = "outcome_status", nullable = false, length = 24) private String outcomeStatus;
        @Column(name = "replayed_at", nullable = false) private Instant replayedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getMessageId() { return messageId; }
        public void setMessageId(UUID messageId) { this.messageId = messageId; }
        public String getReplayedBy() { return replayedBy; }
        public void setReplayedBy(String replayedBy) { this.replayedBy = replayedBy; }
        public String getJustification() { return justification; }
        public void setJustification(String justification) { this.justification = justification; }
        public String getPreviousStatus() { return previousStatus; }
        public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
        public String getOutcomeStatus() { return outcomeStatus; }
        public void setOutcomeStatus(String outcomeStatus) { this.outcomeStatus = outcomeStatus; }
        public Instant getReplayedAt() { return replayedAt; }
    }
}
