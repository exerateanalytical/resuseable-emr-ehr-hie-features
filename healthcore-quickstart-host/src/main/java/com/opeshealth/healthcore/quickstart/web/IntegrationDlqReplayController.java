/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService;
import com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.BulkReplaySummary;
import com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.DeadLetterEntry;
import com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.IntegrationChannelType;
import com.opeshealth.healthcore.quickstart.service.IntegrationDlqReplayService.RecordDeadLetterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Integration Dead-Letter Queue (DLQ) &amp; Webhook/MLLP Replay Management.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/ops/dlq")
@Tag(name = "Integration Dead-Letter Queue (DLQ) & Webhook/MLLP Replay",
        description = "Inspect, replay, bulk-replay, or dismiss failed FHIR Subscription webhooks, HL7v2 MLLP deliveries, transactional outbox events, and federated XCA calls")
public class IntegrationDlqReplayController {

    private final IntegrationDlqReplayService dlqService;

    public IntegrationDlqReplayController(IntegrationDlqReplayService dlqService) {
        this.dlqService = Objects.requireNonNull(dlqService, "IntegrationDlqReplayService is required");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record a failed outbound integration event in the Dead-Letter Queue")
    public ResponseEntity<DeadLetterEntry> recordDeadLetter(@RequestBody RecordDeadLetterRequest request) {
        return ResponseEntity.status(201).body(dlqService.recordDeadLetter(request));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List Dead-Letter Queue entries filtered by integration channel and status")
    public ResponseEntity<List<DeadLetterEntry>> listDeadLetters(
            @RequestParam(value = "channel", required = false) IntegrationChannelType channel,
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(dlqService.listDeadLetters(channel, status));
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get Dead-Letter Queue operational counts and circuit-breaker health status")
    public ResponseEntity<Map<String, Object>> getHealthSummary() {
        return ResponseEntity.ok(dlqService.getDlqHealthSummary());
    }

    @PostMapping(value = "/{dlqId}/replay", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Replay a single Dead-Letter Queue entry")
    public ResponseEntity<DeadLetterEntry> replaySingle(
            @PathVariable("dlqId") UUID dlqId,
            @RequestParam(value = "operatorId", required = false) String operatorId) {
        return ResponseEntity.ok(dlqService.replayDeadLetter(dlqId, operatorId));
    }

    @PostMapping(value = "/replay-bulk", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Bulk-replay all open Dead-Letter Queue entries for a channel")
    public ResponseEntity<BulkReplaySummary> replayBulk(
            @RequestParam(value = "channel", required = false) IntegrationChannelType channel,
            @RequestParam(value = "operatorId", required = false) String operatorId) {
        return ResponseEntity.ok(dlqService.bulkReplayOpenDeadLetters(channel, operatorId));
    }

    @PostMapping(value = "/{dlqId}/dismiss", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Dismiss a Dead-Letter Queue entry without redelivery")
    public ResponseEntity<DeadLetterEntry> dismiss(
            @PathVariable("dlqId") UUID dlqId,
            @RequestParam(value = "operatorId", required = false) String operatorId,
            @RequestParam(value = "note", required = false) String note) {
        return ResponseEntity.ok(dlqService.resolveWithoutReplay(dlqId, operatorId, note));
    }
}
