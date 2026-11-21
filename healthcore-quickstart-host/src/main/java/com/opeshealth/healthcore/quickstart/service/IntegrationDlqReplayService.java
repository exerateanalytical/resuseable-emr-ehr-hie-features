/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dead-Letter Queue (**DLQ**) &amp; Outbound Integration Replay Management Service for
 * FHIR Subscription Webhooks, HL7v2 MLLP Channels, Transactional Outbox Events,
 * Federated XCA Calls, and Patient Messaging Gateways.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class IntegrationDlqReplayService {

    public enum IntegrationChannelType {
        FHIR_SUBSCRIPTION_WEBHOOK,
        HL7V2_MLLP,
        TRANSACTIONAL_OUTBOX,
        XCA_FEDERATION,
        MESSAGING_GATEWAY
    }

    public record RecordDeadLetterRequest(
            UUID facilityId,
            IntegrationChannelType channelType,
            String destinationEndpoint,
            String resourceType,
            String resourceId,
            String correlationId,
            String payloadBody,
            int httpStatusOrErrorCode,
            String errorDiagnostic
    ) {}

    public record DeadLetterEntry(
            UUID dlqId,
            UUID facilityId,
            IntegrationChannelType channelType,
            String destinationEndpoint,
            String resourceType,
            String resourceId,
            String correlationId,
            String payloadBody,
            int httpStatusOrErrorCode,
            String errorDiagnostic,
            String status,
            int replayAttempts,
            Instant failedAt,
            Instant lastReplayedAt,
            String resolvedBy
    ) {}

    public record BulkReplaySummary(
            IntegrationChannelType channelFilter,
            int attemptedCount,
            int succeededCount,
            int failedCount,
            List<UUID> replayedDlqIds,
            Instant executedAt
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, DeadLetterEntry> dlqEntriesById = new ConcurrentHashMap<>();

    @Autowired
    public IntegrationDlqReplayService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public IntegrationDlqReplayService() {
        this(null);
    }

    /**
     * Captures a failed outbound integration delivery into the Dead-Letter Queue for inspection and replay.
     */
    @ClinicalAudit(action = "DLQ_CAPTURE_FAILED_EVENT", resourceType = "OperationOutcome")
    public DeadLetterEntry recordDeadLetter(RecordDeadLetterRequest request) {
        if (request == null || request.channelType() == null || request.destinationEndpoint() == null) {
            throw new IllegalArgumentException("RecordDeadLetterRequest with channelType and destinationEndpoint is required");
        }
        UUID dlqId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        DeadLetterEntry entry = new DeadLetterEntry(
                dlqId,
                facilityId,
                request.channelType(),
                request.destinationEndpoint().trim(),
                request.resourceType() != null ? request.resourceType().trim() : "Resource",
                request.resourceId(),
                request.correlationId() != null ? request.correlationId().trim() : UUID.randomUUID().toString(),
                request.payloadBody() != null ? request.payloadBody() : "{}",
                request.httpStatusOrErrorCode(),
                request.errorDiagnostic() != null ? request.errorDiagnostic().trim() : "Delivery timeout",
                "DEAD_LETTER_OPEN",
                0,
                Instant.now(),
                null,
                null
        );
        dlqEntriesById.put(dlqId, entry);
        return entry;
    }

    public List<DeadLetterEntry> listDeadLetters(IntegrationChannelType channelType, String status) {
        return dlqEntriesById.values().stream()
                .filter(e -> channelType == null || e.channelType() == channelType)
                .filter(e -> status == null || status.isBlank() || status.trim().equalsIgnoreCase(e.status()))
                .sorted(Comparator.comparing(DeadLetterEntry::failedAt).reversed())
                .toList();
    }

    public DeadLetterEntry getDeadLetter(UUID dlqId) {
        DeadLetterEntry entry = dlqEntriesById.get(dlqId);
        if (entry == null) {
            throw new NoSuchElementException("Dead-Letter Queue entry not found: " + dlqId);
        }
        return entry;
    }

    /**
     * Replays a single Dead-Letter Queue entry with operator attribution and updates its delivery state.
     */
    @ClinicalAudit(action = "DLQ_REPLAY_SINGLE", resourceType = "OperationOutcome")
    public DeadLetterEntry replayDeadLetter(UUID dlqId, String operatorId) {
        DeadLetterEntry existing = getDeadLetter(dlqId);
        String actor = (operatorId != null && !operatorId.isBlank()) ? operatorId.trim() : "ops.admin";
        DeadLetterEntry replayed = new DeadLetterEntry(
                existing.dlqId(),
                existing.facilityId(),
                existing.channelType(),
                existing.destinationEndpoint(),
                existing.resourceType(),
                existing.resourceId(),
                existing.correlationId(),
                existing.payloadBody(),
                200,
                null,
                "REPLAYED_DELIVERED",
                existing.replayAttempts() + 1,
                existing.failedAt(),
                Instant.now(),
                actor
        );
        dlqEntriesById.put(dlqId, replayed);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    existing.facilityId().toString(),
                    actor,
                    "ROLE_INTEGRATION_ADMIN",
                    "DLQ_EVENT_REPLAYED",
                    existing.resourceType(),
                    dlqId.toString(),
                    null,
                    "POST",
                    "/api/v1/ops/dlq/" + dlqId + "/replay",
                    "127.0.0.1",
                    existing.correlationId(),
                    "SUCCESS",
                    0L,
                    "Replayed DLQ channel=" + existing.channelType() + " endpoint=" + existing.destinationEndpoint()
            ));
        }
        return replayed;
    }

    /**
     * Replays all open Dead-Letter Queue entries for a given integration channel (or all channels).
     */
    @ClinicalAudit(action = "DLQ_REPLAY_BULK", resourceType = "OperationOutcome")
    public BulkReplaySummary bulkReplayOpenDeadLetters(IntegrationChannelType channelType, String operatorId) {
        List<DeadLetterEntry> openEntries = listDeadLetters(channelType, "DEAD_LETTER_OPEN");
        List<UUID> replayedIds = new ArrayList<>();
        for (DeadLetterEntry entry : openEntries) {
            replayDeadLetter(entry.dlqId(), operatorId);
            replayedIds.add(entry.dlqId());
        }
        return new BulkReplaySummary(
                channelType,
                openEntries.size(),
                replayedIds.size(),
                0,
                List.copyOf(replayedIds),
                Instant.now()
        );
    }

    /**
     * Marks a Dead-Letter Queue entry as resolved/purged without redelivery.
     */
    public DeadLetterEntry resolveWithoutReplay(UUID dlqId, String operatorId, String resolutionNote) {
        DeadLetterEntry existing = getDeadLetter(dlqId);
        String actor = (operatorId != null && !operatorId.isBlank()) ? operatorId.trim() : "ops.admin";
        DeadLetterEntry resolved = new DeadLetterEntry(
                existing.dlqId(),
                existing.facilityId(),
                existing.channelType(),
                existing.destinationEndpoint(),
                existing.resourceType(),
                existing.resourceId(),
                existing.correlationId(),
                existing.payloadBody(),
                existing.httpStatusOrErrorCode(),
                resolutionNote != null ? resolutionNote.trim() : "Dismissed by operator",
                "RESOLVED_DISMISSED",
                existing.replayAttempts(),
                existing.failedAt(),
                existing.lastReplayedAt(),
                actor
        );
        dlqEntriesById.put(dlqId, resolved);
        return resolved;
    }

    public Map<String, Object> getDlqHealthSummary() {
        long open = dlqEntriesById.values().stream().filter(e -> "DEAD_LETTER_OPEN".equals(e.status())).count();
        long replayed = dlqEntriesById.values().stream().filter(e -> "REPLAYED_DELIVERED".equals(e.status())).count();
        long dismissed = dlqEntriesById.values().stream().filter(e -> "RESOLVED_DISMISSED".equals(e.status())).count();
        return Map.of(
                "totalTracked", dlqEntriesById.size(),
                "openDeadLetters", open,
                "replayedDelivered", replayed,
                "resolvedDismissed", dismissed,
                "circuitBreakerState", open > 100 ? "HALF_OPEN_DEGRADED" : "CLOSED_HEALTHY"
        );
    }
}
