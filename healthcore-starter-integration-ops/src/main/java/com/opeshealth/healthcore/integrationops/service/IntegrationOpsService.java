/*
 * =============================================================================
 * Opes HealthCore SDK — Integration Operations Service & REST API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.integrationops.service;

import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationMessageLedgerEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationReplayAuditEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class IntegrationOpsService {

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("FHIR_R4", "HL7_V2", "IHE", "DICOMWEB", "X12");
    private static final Set<String> ALLOWED_AUTH_MODES = Set.of("MTLS", "OAUTH2_CLIENT_CREDENTIALS", "HMAC_SHA256");
    private static final int CIRCUIT_BREAKER_TRIP_THRESHOLD = 5;

    public interface PartnerProfileRepository extends JpaRepository<PartnerConformanceProfileEntity, UUID> {
        Optional<PartnerConformanceProfileEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<PartnerConformanceProfileEntity> findByFacilityIdAndPartnerCode(UUID facilityId, String partnerCode);
        List<PartnerConformanceProfileEntity> findByFacilityIdOrderByPartnerNameAsc(UUID facilityId);
    }

    public interface MessageLedgerRepository extends JpaRepository<IntegrationMessageLedgerEntity, UUID> {
        Optional<IntegrationMessageLedgerEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<IntegrationMessageLedgerEntity> findByFacilityIdAndDirectionAndIdempotencyKey(
                UUID facilityId, String direction, String idempotencyKey);
        List<IntegrationMessageLedgerEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        List<IntegrationMessageLedgerEntity> findByFacilityIdAndDeliveryStatusOrderByCreatedAtAsc(
                UUID facilityId, String deliveryStatus);
        List<IntegrationMessageLedgerEntity> findByFacilityIdAndPartnerIdAndDeliveryStatusOrderByCreatedAtAsc(
                UUID facilityId, UUID partnerId, String deliveryStatus);
    }

    public interface ReplayAuditRepository extends JpaRepository<IntegrationReplayAuditEntity, UUID> {
        List<IntegrationReplayAuditEntity> findByFacilityIdAndMessageIdOrderByReplayedAtDesc(UUID facilityId, UUID messageId);
    }

    public record UpsertPartnerRequest(
            String partnerCode,
            String partnerName,
            String protocolFamily,
            String endpointUrl,
            String authMode,
            String allowedMessageTypes,
            Integer rateLimitPerMin,
            Boolean active
    ) {}

    public record EnqueueMessageRequest(
            UUID partnerId,
            String idempotencyKey,
            String messageType,
            String payloadBody,
            Integer maxAttempts
    ) {}

    public record RecordFailureRequest(
            Integer httpStatus,
            String errorCode,
            String errorMessage
    ) {}

    public record ReplayRequest(
            String replayedBy,
            String justification
    ) {}

    public record PartnerHealthSummary(
            UUID partnerId,
            String partnerCode,
            String partnerName,
            String protocolFamily,
            String circuitState,
            int consecutiveFailures,
            long queuedCount,
            long deliveredCount,
            long deadLetterCount
    ) {}

    private final PartnerProfileRepository partnerRepo;
    private final MessageLedgerRepository messageRepo;
    private final ReplayAuditRepository replayRepo;
    private final ClinicalAuditService auditService;

    public IntegrationOpsService(
            PartnerProfileRepository partnerRepo,
            MessageLedgerRepository messageRepo,
            ReplayAuditRepository replayRepo,
            ClinicalAuditService auditService) {
        this.partnerRepo = partnerRepo;
        this.messageRepo = messageRepo;
        this.replayRepo = replayRepo;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Missing authenticated facility context for Integration Operations."));
    }

    @Transactional
    public PartnerConformanceProfileEntity upsertPartnerProfile(UpsertPartnerRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.partnerCode() == null || req.partnerCode().isBlank()
                || req.partnerName() == null || req.partnerName().isBlank()
                || req.endpointUrl() == null || req.endpointUrl().isBlank()) {
            throw new IllegalArgumentException("partnerCode, partnerName, and endpointUrl are required.");
        }
        String protocol = req.protocolFamily() == null ? "" : req.protocolFamily().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("Unsupported protocolFamily: " + req.protocolFamily());
        }
        String authMode = (req.authMode() == null || req.authMode().isBlank())
                ? "MTLS" : req.authMode().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_AUTH_MODES.contains(authMode)) {
            throw new IllegalArgumentException("Unsupported authMode: " + req.authMode());
        }
        if (req.allowedMessageTypes() == null || req.allowedMessageTypes().isBlank()) {
            throw new IllegalArgumentException("allowedMessageTypes must specify at least one conformance profile type.");
        }
        if (!req.endpointUrl().startsWith("https://") && !req.endpointUrl().startsWith("mllp+tls://")) {
            throw new IllegalArgumentException("Partner endpointUrl must use TLS transport (https:// or mllp+tls://).");
        }

        String code = req.partnerCode().trim().toUpperCase(Locale.ROOT);
        PartnerConformanceProfileEntity entity = partnerRepo
                .findByFacilityIdAndPartnerCode(facilityId, code)
                .orElseGet(PartnerConformanceProfileEntity::new);
        entity.setFacilityId(facilityId);
        entity.setPartnerCode(code);
        entity.setPartnerName(req.partnerName().trim());
        entity.setProtocolFamily(protocol);
        entity.setEndpointUrl(req.endpointUrl().trim());
        entity.setAuthMode(authMode);
        entity.setAllowedMessageTypes(req.allowedMessageTypes().trim());
        entity.setRateLimitPerMin(req.rateLimitPerMin() != null ? Math.max(1, req.rateLimitPerMin()) : 120);
        entity.setActive(req.active() == null || req.active());
        entity.setUpdatedAt(Instant.now());

        PartnerConformanceProfileEntity saved = partnerRepo.save(entity);
        audit(facilityId, actor, "INTOPS_PARTNER_UPSERT", saved.getPartnerCode(),
                "protocol=" + protocol + " auth=" + authMode);
        return saved;
    }

    @Transactional
    public PartnerConformanceProfileEntity updateCircuitState(UUID partnerId, String nextState, String actor) {
        UUID facilityId = requireFacilityId();
        String normalized = nextState == null ? "" : nextState.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CLOSED", "OPEN", "HALF_OPEN").contains(normalized)) {
            throw new IllegalArgumentException("Invalid circuit breaker state: " + nextState);
        }
        PartnerConformanceProfileEntity partner = partnerRepo.findByIdAndFacilityId(partnerId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Partner profile not found"));
        partner.setCircuitState(normalized);
        if ("CLOSED".equals(normalized)) {
            partner.setConsecutiveFailures(0);
        }
        partner.setUpdatedAt(Instant.now());
        PartnerConformanceProfileEntity saved = partnerRepo.save(partner);
        audit(facilityId, actor, "INTOPS_CIRCUIT_" + normalized, saved.getPartnerCode(),
                "failures=" + saved.getConsecutiveFailures());
        return saved;
    }

    @Transactional
    public IntegrationMessageLedgerEntity enqueueOutbound(EnqueueMessageRequest req, String actor) {
        return recordMessage("OUTBOUND", req, actor);
    }

    @Transactional
    public IntegrationMessageLedgerEntity recordInbound(EnqueueMessageRequest req, String actor) {
        return recordMessage("INBOUND", req, actor);
    }

    private IntegrationMessageLedgerEntity recordMessage(String direction, EnqueueMessageRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.partnerId() == null
                || req.idempotencyKey() == null || req.idempotencyKey().isBlank()
                || req.messageType() == null || req.messageType().isBlank()
                || req.payloadBody() == null || req.payloadBody().isBlank()) {
            throw new IllegalArgumentException("partnerId, idempotencyKey, messageType, and payloadBody are required.");
        }
        String idemKey = req.idempotencyKey().trim();
        Optional<IntegrationMessageLedgerEntity> existing =
                messageRepo.findByFacilityIdAndDirectionAndIdempotencyKey(facilityId, direction, idemKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        PartnerConformanceProfileEntity partner = partnerRepo.findByIdAndFacilityId(req.partnerId(), facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Partner profile not found"));
        if (!partner.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Partner profile is inactive: " + partner.getPartnerCode());
        }
        if ("OUTBOUND".equals(direction) && "OPEN".equals(partner.getCircuitState())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Circuit breaker is OPEN for partner " + partner.getPartnerCode() + "; reset or switch to HALF_OPEN before dispatch.");
        }
        String msgType = req.messageType().trim();
        Set<String> allowed = new HashSet<>();
        for (String token : partner.getAllowedMessageTypes().split(",")) {
            if (!token.isBlank()) {
                allowed.add(token.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (!allowed.contains("*") && !allowed.contains(msgType.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Message type '" + msgType + "' violates partner conformance profile for " + partner.getPartnerCode());
        }

        IntegrationMessageLedgerEntity msg = new IntegrationMessageLedgerEntity();
        msg.setFacilityId(facilityId);
        msg.setPartnerId(partner.getId());
        msg.setDirection(direction);
        msg.setIdempotencyKey(idemKey);
        msg.setProtocolFamily(partner.getProtocolFamily());
        msg.setMessageType(msgType);
        msg.setPayloadSha256(sha256Hex(req.payloadBody()));
        msg.setPayloadBody(req.payloadBody());
        msg.setDeliveryStatus("INBOUND".equals(direction) ? "DELIVERED" : "QUEUED");
        msg.setAttemptCount("INBOUND".equals(direction) ? 1 : 0);
        msg.setMaxAttempts(req.maxAttempts() != null ? Math.max(1, Math.min(10, req.maxAttempts())) : 5);
        if ("INBOUND".equals(direction)) {
            msg.setDeliveredAt(Instant.now());
        }

        IntegrationMessageLedgerEntity saved = messageRepo.save(msg);
        audit(facilityId, actor, "INTOPS_" + direction + "_RECORDED", saved.getIdempotencyKey(),
                "partner=" + partner.getPartnerCode() + " type=" + msgType);
        return saved;
    }

    @Transactional
    public IntegrationMessageLedgerEntity markDelivered(UUID messageId, int httpStatus, String actor) {
        UUID facilityId = requireFacilityId();
        IntegrationMessageLedgerEntity msg = messageRepo.findByIdAndFacilityId(messageId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integration message not found"));
        msg.setAttemptCount(msg.getAttemptCount() + 1);
        msg.setDeliveryStatus("DELIVERED");
        msg.setLastHttpStatus(httpStatus);
        msg.setLastErrorCode(null);
        msg.setLastErrorMessage(null);
        msg.setNextRetryAt(null);
        msg.setDeliveredAt(Instant.now());
        IntegrationMessageLedgerEntity saved = messageRepo.save(msg);

        partnerRepo.findByIdAndFacilityId(msg.getPartnerId(), facilityId).ifPresent(partner -> {
            partner.setConsecutiveFailures(0);
            if ("HALF_OPEN".equals(partner.getCircuitState())) {
                partner.setCircuitState("CLOSED");
            }
            partner.setUpdatedAt(Instant.now());
            partnerRepo.save(partner);
        });
        audit(facilityId, actor, "INTOPS_DELIVERED", saved.getIdempotencyKey(), "httpStatus=" + httpStatus);
        return saved;
    }

    @Transactional
    public IntegrationMessageLedgerEntity recordDeliveryFailure(UUID messageId, RecordFailureRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        IntegrationMessageLedgerEntity msg = messageRepo.findByIdAndFacilityId(messageId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integration message not found"));
        int attempts = msg.getAttemptCount() + 1;
        msg.setAttemptCount(attempts);
        msg.setLastHttpStatus(req != null ? req.httpStatus() : null);
        msg.setLastErrorCode(req != null && req.errorCode() != null ? req.errorCode().trim() : "DELIVERY_ERROR");
        msg.setLastErrorMessage(req != null && req.errorMessage() != null ? req.errorMessage().trim() : "Partner endpoint error");

        if (attempts >= msg.getMaxAttempts()) {
            msg.setDeliveryStatus("DEAD_LETTERED");
            msg.setNextRetryAt(null);
        } else {
            msg.setDeliveryStatus("RETRYING");
            long backoffSeconds = 30L * (1L << Math.min(attempts - 1, 6));
            msg.setNextRetryAt(Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
        }
        IntegrationMessageLedgerEntity saved = messageRepo.save(msg);

        partnerRepo.findByIdAndFacilityId(msg.getPartnerId(), facilityId).ifPresent(partner -> {
            int failures = partner.getConsecutiveFailures() + 1;
            partner.setConsecutiveFailures(failures);
            if (failures >= CIRCUIT_BREAKER_TRIP_THRESHOLD) {
                partner.setCircuitState("OPEN");
            }
            partner.setUpdatedAt(Instant.now());
            partnerRepo.save(partner);
        });
        audit(facilityId, actor, "INTOPS_" + saved.getDeliveryStatus(), saved.getIdempotencyKey(),
                "attempt=" + attempts + "/" + msg.getMaxAttempts() + " code=" + saved.getLastErrorCode());
        return saved;
    }

    @Transactional
    public IntegrationMessageLedgerEntity replayMessage(UUID messageId, ReplayRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.replayedBy() == null || req.replayedBy().isBlank()
                || req.justification() == null || req.justification().isBlank()) {
            throw new IllegalArgumentException("replayedBy and justification are mandatory for message replay.");
        }
        IntegrationMessageLedgerEntity msg = messageRepo.findByIdAndFacilityId(messageId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Integration message not found"));
        String prev = msg.getDeliveryStatus();
        if (!Set.of("DEAD_LETTERED", "RETRYING").contains(prev)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DEAD_LETTERED or RETRYING messages can be replayed (current=" + prev + ").");
        }
        msg.setDeliveryStatus("REPLAYED");
        msg.setAttemptCount(0);
        msg.setNextRetryAt(Instant.now());
        msg.setLastErrorCode(null);
        msg.setLastErrorMessage(null);
        IntegrationMessageLedgerEntity saved = messageRepo.save(msg);

        IntegrationReplayAuditEntity replay = new IntegrationReplayAuditEntity();
        replay.setFacilityId(facilityId);
        replay.setMessageId(saved.getId());
        replay.setReplayedBy(req.replayedBy().trim());
        replay.setJustification(req.justification().trim());
        replay.setPreviousStatus(prev);
        replay.setOutcomeStatus("REPLAYED");
        replayRepo.save(replay);

        audit(facilityId, req.replayedBy(), "INTOPS_DLQ_REPLAY", saved.getIdempotencyKey(),
                "prev=" + prev + " reason=" + req.justification().trim());
        return saved;
    }

    @Transactional
    public int batchReplayPartnerDeadLetters(UUID partnerId, ReplayRequest req) {
        UUID facilityId = requireFacilityId();
        List<IntegrationMessageLedgerEntity> dlq = messageRepo
                .findByFacilityIdAndPartnerIdAndDeliveryStatusOrderByCreatedAtAsc(facilityId, partnerId, "DEAD_LETTERED");
        for (IntegrationMessageLedgerEntity item : dlq) {
            replayMessage(item.getId(), req);
        }
        return dlq.size();
    }

    @Transactional(readOnly = true)
    public List<PartnerConformanceProfileEntity> listPartners() {
        return partnerRepo.findByFacilityIdOrderByPartnerNameAsc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public List<IntegrationMessageLedgerEntity> listMessages(String status) {
        UUID facilityId = requireFacilityId();
        if (status != null && !status.isBlank()) {
            return messageRepo.findByFacilityIdAndDeliveryStatusOrderByCreatedAtAsc(
                    facilityId, status.trim().toUpperCase(Locale.ROOT));
        }
        return messageRepo.findByFacilityIdOrderByCreatedAtDesc(facilityId);
    }

    @Transactional(readOnly = true)
    public List<PartnerHealthSummary> getPartnerHealthSummaries() {
        UUID facilityId = requireFacilityId();
        List<PartnerConformanceProfileEntity> partners = partnerRepo.findByFacilityIdOrderByPartnerNameAsc(facilityId);
        List<IntegrationMessageLedgerEntity> all = messageRepo.findByFacilityIdOrderByCreatedAtDesc(facilityId);
        List<PartnerHealthSummary> out = new ArrayList<>();
        for (PartnerConformanceProfileEntity p : partners) {
            long queued = all.stream().filter(m -> p.getId().equals(m.getPartnerId())
                    && ("QUEUED".equals(m.getDeliveryStatus()) || "RETRYING".equals(m.getDeliveryStatus()) || "REPLAYED".equals(m.getDeliveryStatus()))).count();
            long delivered = all.stream().filter(m -> p.getId().equals(m.getPartnerId())
                    && "DELIVERED".equals(m.getDeliveryStatus())).count();
            long dlq = all.stream().filter(m -> p.getId().equals(m.getPartnerId())
                    && "DEAD_LETTERED".equals(m.getDeliveryStatus())).count();
            out.add(new PartnerHealthSummary(p.getId(), p.getPartnerCode(), p.getPartnerName(),
                    p.getProtocolFamily(), p.getCircuitState(), p.getConsecutiveFailures(), queued, delivered, dlq));
        }
        return out;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void audit(UUID facilityId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.record(facilityId, null, actor != null && !actor.isBlank() ? actor : "INTOPS_SYSTEM",
                    action, "IntegrationOps", resourceId, "SUCCESS", detail);
        }
    }

    @RestController
    @RequestMapping("/api/v1/integration-ops")
    @Tag(name = "Integration Operations & DLQ Replay", description = "Partner conformance profiles, idempotent outbox/inbox ledger, exponential retries, circuit breakers, and audited DLQ replay")
    public static class IntegrationOpsController {
        private final IntegrationOpsService service;

        public IntegrationOpsController(IntegrationOpsService service) {
            this.service = service;
        }

        @PostMapping("/partners")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "Register or update an external trading partner conformance profile")
        public PartnerConformanceProfileEntity upsertPartner(@RequestBody UpsertPartnerRequest request) {
            return service.upsertPartnerProfile(request, "api.intops");
        }

        @GetMapping("/partners")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "List facility trading partner conformance profiles")
        public List<PartnerConformanceProfileEntity> listPartners() {
            return service.listPartners();
        }

        @GetMapping("/partners/health")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "Get per-partner delivery health and circuit-breaker telemetry")
        public List<PartnerHealthSummary> partnerHealth() {
            return service.getPartnerHealthSummaries();
        }

        @PatchMapping("/partners/{partnerId}/circuit")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "Transition partner circuit breaker state (CLOSED, HALF_OPEN, OPEN)")
        public PartnerConformanceProfileEntity updateCircuit(
                @PathVariable UUID partnerId,
                @RequestParam String state) {
            return service.updateCircuitState(partnerId, state, "api.intops");
        }

        @PostMapping("/outbox")
        @PreAuthorize("hasAuthority('PERM_hie:interop')")
        @Operation(summary = "Enqueue an idempotent outbound partner message")
        public IntegrationMessageLedgerEntity enqueueOutbound(@RequestBody EnqueueMessageRequest request) {
            return service.enqueueOutbound(request, "api.intops");
        }

        @PostMapping("/inbox")
        @PreAuthorize("hasAuthority('PERM_hie:interop')")
        @Operation(summary = "Record an idempotent inbound partner message")
        public IntegrationMessageLedgerEntity recordInbound(@RequestBody EnqueueMessageRequest request) {
            return service.recordInbound(request, "api.intops");
        }

        @GetMapping("/messages")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "List outbox/inbox ledger messages optionally filtered by delivery status")
        public List<IntegrationMessageLedgerEntity> listMessages(@RequestParam(required = false) String status) {
            return service.listMessages(status);
        }

        @PostMapping("/messages/{messageId}/replay")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "Replay a DEAD_LETTERED or RETRYING message with immutable audit trail")
        public IntegrationMessageLedgerEntity replay(
                @PathVariable UUID messageId,
                @RequestBody ReplayRequest request) {
            return service.replayMessage(messageId, request);
        }

        @PostMapping("/partners/{partnerId}/replay-dlq")
        @PreAuthorize("hasAuthority('PERM_hie:interop') or hasAuthority('PERM_tenant:admin')")
        @Operation(summary = "Batch replay all DEAD_LETTERED messages for a partner")
        public Map<String, Object> batchReplay(@PathVariable UUID partnerId, @RequestBody ReplayRequest request) {
            int count = service.batchReplayPartnerDeadLetters(partnerId, request);
            return Map.of("partnerId", partnerId, "replayedCount", count);
        }
    }
}
