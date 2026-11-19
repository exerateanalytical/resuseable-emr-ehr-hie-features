/*
 * =============================================================================
 * Opes HealthCore SDK — Centralized HIPAA & GDPR FHIR Audit Logging Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.persistence.observability.CorrelationAndPhiGuard;
import com.opeshealth.healthcore.security.audit.AuditLogEntity;
import com.opeshealth.healthcore.security.audit.AuditRepositories.AuditLogRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService.ChainVerificationResult;
import com.opeshealth.healthcore.security.audit.PhiRedactionConverter;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAction;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAgentComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAgentNetworkComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventAgentNetworkType;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventEntityComponent;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventOutcome;
import org.hl7.fhir.r4.model.AuditEvent.AuditEventSourceComponent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Centralized audit logging service capturing all HL7 FHIR R4 resource access events
 * across REST controllers, FHIR Bundle transactions/batches, Spring GraphQL resolvers,
 * and CDS Hooks 2.0 clinical decision evaluations, persisting them to the database
 * ({@code hc_audit_logs} and {@code hc_audit_chain_heads}) with tamper-evident SHA-256
 * hash chaining for full HIPAA (§ 164.312(b) &amp; § 164.528) and GDPR (Articles 15, 30, 32)
 * regulatory compliance.
 */
@Service
public class FhirAuditLoggingService {

    private static final Logger log = LoggerFactory.getLogger(FhirAuditLoggingService.class);
    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String GENESIS_HASH = "0".repeat(64);
    private static final String SYSTEM_AUDIT_EVENT_TYPE = "http://terminology.hl7.org/CodeSystem/audit-event-type";
    private static final String SYSTEM_RESTFUL_INTERACTION = "http://hl7.org/fhir/restful-interaction";
    private static final String SYSTEM_V3_PURPOSE_OF_USE = "http://terminology.hl7.org/CodeSystem/v3-ActReason";
    private static final String SYSTEM_HEALTHCORE_COMPLIANCE = "https://opeshealthsystems.com/fhir/CodeSystem/compliance-framework";

    /**
     * Command encapsulating a FHIR resource access event to be persisted in the database.
     */
    public record FhirAccessAuditCommand(
            String facilityKey,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String httpMethod,
            String requestUri,
            String sourceIp,
            String correlationId,
            String outcome,
            long durationMs,
            String reason
    ) {}

    /**
     * Immutable representation of a persisted, SHA-256 hash-chained FHIR resource access event.
     */
    public record FhirResourceAccessEvent(
            UUID id,
            String facilityKey,
            long sequenceNumber,
            Instant occurredAt,
            String actorId,
            String actorRole,
            String action,
            String fhirInteractionCode,
            String dicomActionCode,
            String resourceType,
            String resourceId,
            UUID patientId,
            String httpMethod,
            String requestUri,
            String sourceIp,
            String correlationId,
            String outcome,
            long durationMs,
            String reason,
            String prevHash,
            String entryHash
    ) {}

    /**
     * Search criteria for querying persisted FHIR resource access audit logs.
     */
    public record AuditSearchCriteria(
            String facilityKey,
            UUID patientId,
            String resourceType,
            String resourceId,
            String actorId,
            String action,
            String outcome,
            LocalDate dateFrom,
            LocalDate dateTo,
            int offset,
            int count
    ) {
        public AuditSearchCriteria {
            if (offset < 0) offset = 0;
            if (count <= 0) count = 20;
            if (count > 200) count = 200;
        }
    }

    /**
     * Paginated search result of persisted {@link AuditLogEntity} records.
     */
    public record AuditSearchResult(
            List<AuditLogEntity> entries,
            int total,
            int offset,
            int count
    ) {}

    /**
     * Individual disclosure item in a HIPAA § 164.528 / GDPR Article 15 patient access report.
     */
    public record PatientDisclosureItem(
            UUID auditEventId,
            long sequenceNumber,
            Instant occurredAt,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String outcome,
            String sourceIp,
            String correlationId,
            String entryHash
    ) {}

    /**
     * Comprehensive HIPAA § 164.528 Accounting of Disclosures &amp; GDPR Article 15
     * Data Subject Access Report (DSAR) for a specific patient.
     */
    public record HipaaGdprPatientComplianceReport(
            String reportStandard,
            Instant generatedAt,
            String facilityKey,
            UUID patientId,
            int totalAccessEvents,
            int successfulAccessEvents,
            int failedOrDeniedAccessEvents,
            List<String> distinctActors,
            Map<String, Integer> accessesByResourceType,
            Map<String, Integer> accessesByAction,
            boolean cryptographicChainValid,
            long verifiedFacilityChainLength,
            List<PatientDisclosureItem> disclosures
    ) {}

    private final ClinicalAuditService clinicalAuditService;
    private final AuditLogRepository auditLogRepository;
    private final List<Consumer<FhirResourceAccessEvent>> eventListeners = new CopyOnWriteArrayList<>();

    // Fallback in-memory ledger used only when running standalone unit tests without a JPA DataSource
    private final List<AuditLogEntity> fallbackMemoryLedger = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicLong> fallbackSequenceByFacility = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> fallbackLastHashByFacility = new java.util.concurrent.ConcurrentHashMap<>();

    public FhirAuditLoggingService() {
        this((ClinicalAuditService) null, (AuditLogRepository) null);
    }

    public FhirAuditLoggingService(ClinicalAuditService clinicalAuditService) {
        this(clinicalAuditService, (AuditLogRepository) null);
    }

    @Autowired
    public FhirAuditLoggingService(
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider,
            ObjectProvider<AuditLogRepository> auditLogRepositoryProvider) {
        this(
                clinicalAuditServiceProvider != null ? clinicalAuditServiceProvider.getIfAvailable() : null,
                auditLogRepositoryProvider != null ? auditLogRepositoryProvider.getIfAvailable() : null
        );
    }

    public FhirAuditLoggingService(
            ClinicalAuditService clinicalAuditService,
            AuditLogRepository auditLogRepository) {
        this.clinicalAuditService = clinicalAuditService;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Registers an observer notified whenever a FHIR resource access event is recorded.
     */
    public void registerEventListener(Consumer<FhirResourceAccessEvent> listener) {
        if (listener != null) {
            eventListeners.add(listener);
        }
    }

    /**
     * Records a FHIR resource access event from the active Spring Security &amp; FacilityContext,
     * persisting it to the database in an isolated transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FhirResourceAccessEvent recordFhirResourceAccess(
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String outcome,
            String reason) {
        String facilityKey = FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actorId = (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank())
                ? auth.getName()
                : "SYSTEM";
        String actorRole = resolveRoleFromAuth(auth);
        String correlationId = MDC.get(CorrelationAndPhiGuard.MDC_CORRELATION_ID);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return recordFhirAccess(new FhirAccessAuditCommand(
                facilityKey,
                actorId,
                actorRole,
                action,
                resourceType,
                resourceId,
                patientId,
                resolveHttpMethodFromAction(action),
                "/api/v1/" + (resourceType != null ? resourceType.toLowerCase(Locale.ROOT) + "s" : "fhir"),
                "internal",
                correlationId,
                outcome,
                0L,
                reason
        ));
    }

    /**
     * Central entry point that persists a FHIR resource access event into {@code hc_audit_logs}
     * with SHA-256 cryptographic hash chaining, PHI redaction, and structured compliance logging.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FhirResourceAccessEvent recordFhirAccess(FhirAccessAuditCommand command) {
        Objects.requireNonNull(command, "FhirAccessAuditCommand must not be null");

        String facilityKey = !isBlank(command.facilityKey())
                ? command.facilityKey().trim()
                : FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");
        String actorId = !isBlank(command.actorId()) ? command.actorId().trim() : "ANONYMOUS";
        String actorRole = !isBlank(command.actorRole()) ? command.actorRole().trim() : "UNKNOWN";
        String action = !isBlank(command.action()) ? command.action().trim() : "HIPAA_FHIR_ACCESS";
        String resourceType = !isBlank(command.resourceType()) ? command.resourceType().trim() : "Resource";
        String resourceId = !isBlank(command.resourceId()) ? command.resourceId().trim() : null;
        String outcome = !isBlank(command.outcome()) ? command.outcome().trim().toUpperCase(Locale.ROOT) : "SUCCESS";
        String sourceIp = !isBlank(command.sourceIp()) ? command.sourceIp().trim() : "internal";
        String correlationId = !isBlank(command.correlationId())
                ? command.correlationId().trim()
                : Optional.ofNullable(MDC.get(CorrelationAndPhiGuard.MDC_CORRELATION_ID))
                        .filter(s -> !s.isBlank())
                        .orElseGet(() -> UUID.randomUUID().toString());
        String redactedReason = command.reason() != null ? PhiRedactionConverter.redact(command.reason()) : null;

        AuditLogEntity persisted = persistAuditEntity(
                facilityKey,
                actorId,
                actorRole,
                action,
                resourceType,
                resourceId,
                command.patientId(),
                outcome,
                redactedReason,
                sourceIp,
                correlationId
        );

        FhirResourceAccessEvent accessEvent = new FhirResourceAccessEvent(
                persisted.getId(),
                persisted.getFacilityKey(),
                persisted.getSequenceNumber(),
                persisted.getOccurredAt(),
                persisted.getActorId(),
                persisted.getActorRole(),
                persisted.getAction(),
                resolveFhirInteractionCode(action, command.httpMethod()),
                resolveDicomActionCode(action, command.httpMethod()).toCode(),
                persisted.getResourceType(),
                persisted.getResourceId(),
                persisted.getPatientId(),
                command.httpMethod() != null ? command.httpMethod() : resolveHttpMethodFromAction(action),
                command.requestUri() != null ? command.requestUri() : "/api/v1/fhir",
                persisted.getSourceIp(),
                persisted.getCorrelationId(),
                persisted.getOutcome(),
                Math.max(0L, command.durationMs()),
                persisted.getReason(),
                persisted.getPrevHash(),
                persisted.getEntryHash()
        );

        if ("FAILURE".equalsIgnoreCase(outcome)) {
            log.warn("HIPAA_GDPR_FHIR_AUDIT seq={} timestamp={} actorId={} role={} action={} resourceType={} resourceId={} patientId={} facilityKey={} method={} uri={} ip={} correlationId={} outcome={} durationMs={} entryHash={} reason={}",
                    accessEvent.sequenceNumber(), accessEvent.occurredAt(), accessEvent.actorId(),
                    accessEvent.actorRole(), accessEvent.action(), accessEvent.resourceType(),
                    accessEvent.resourceId(), accessEvent.patientId(), accessEvent.facilityKey(),
                    accessEvent.httpMethod(), accessEvent.requestUri(), accessEvent.sourceIp(),
                    accessEvent.correlationId(), accessEvent.outcome(), accessEvent.durationMs(),
                    accessEvent.entryHash(), accessEvent.reason());
        } else {
            log.info("HIPAA_GDPR_FHIR_AUDIT seq={} timestamp={} actorId={} role={} action={} resourceType={} resourceId={} patientId={} facilityKey={} method={} uri={} ip={} correlationId={} outcome={} durationMs={} entryHash={}",
                    accessEvent.sequenceNumber(), accessEvent.occurredAt(), accessEvent.actorId(),
                    accessEvent.actorRole(), accessEvent.action(), accessEvent.resourceType(),
                    accessEvent.resourceId(), accessEvent.patientId(), accessEvent.facilityKey(),
                    accessEvent.httpMethod(), accessEvent.requestUri(), accessEvent.sourceIp(),
                    accessEvent.correlationId(), accessEvent.outcome(), accessEvent.durationMs(),
                    accessEvent.entryHash());
        }

        for (Consumer<FhirResourceAccessEvent> listener : eventListeners) {
            try {
                listener.accept(accessEvent);
            } catch (Exception ex) {
                log.debug("Audit event listener exception ignored: {}", ex.getMessage());
            }
        }

        return accessEvent;
    }

    /**
     * Retrieves a single persisted {@link AuditLogEntity} by its UUID primary key, throwing
     * {@link NoSuchElementException} if not found.
     */
    @Transactional(readOnly = true)
    public AuditLogEntity getAuditLogEntityById(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("AuditEvent UUID must not be null");
        }
        if (clinicalAuditService != null) {
            Optional<AuditLogEntity> found = clinicalAuditService.findById(id);
            if (found.isPresent()) {
                return found.get();
            }
        }
        if (auditLogRepository != null) {
            Optional<AuditLogEntity> found = auditLogRepository.findById(id);
            if (found.isPresent()) {
                return found.get();
            }
        }
        return fallbackMemoryLedger.stream()
                .filter(e -> id.equals(e.getId()))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("FHIR AuditEvent not found for id: " + id));
    }

    /**
     * Retrieves a single persisted audit entry converted to an HL7 FHIR R4 {@link AuditEvent}.
     */
    @Transactional(readOnly = true)
    public AuditEvent getFhirAuditEventById(UUID id) {
        return toFhirAuditEvent(getAuditLogEntityById(id));
    }

    /**
     * Searches persisted audit entries across facility, patient, resourceType, resourceId,
     * actorId, action, outcome, and date window criteria.
     */
    @Transactional(readOnly = true)
    public AuditSearchResult searchAuditLogs(AuditSearchCriteria criteria) {
        AuditSearchCriteria c = criteria != null
                ? criteria
                : new AuditSearchCriteria("GLOBAL", null, null, null, null, null, null, null, null, 0, 20);
        String facilityKey = !isBlank(c.facilityKey())
                ? c.facilityKey().trim()
                : FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");

        List<AuditLogEntity> baseList = fetchFacilityLogsDescending(facilityKey);
        Instant fromInstant = c.dateFrom() != null
                ? c.dateFrom().atStartOfDay(ZoneOffset.UTC).toInstant()
                : null;
        Instant toInstant = c.dateTo() != null
                ? c.dateTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
                : null;

        List<AuditLogEntity> filtered = baseList.stream()
                .filter(e -> c.patientId() == null || c.patientId().equals(e.getPatientId())
                        || ("Patient".equalsIgnoreCase(e.getResourceType()) && c.patientId().toString().equalsIgnoreCase(e.getResourceId())))
                .filter(e -> isBlank(c.resourceType()) || c.resourceType().trim().equalsIgnoreCase(e.getResourceType()))
                .filter(e -> isBlank(c.resourceId()) || matchesResourceId(e.getResourceId(), c.resourceId().trim()))
                .filter(e -> isBlank(c.actorId()) || c.actorId().trim().equalsIgnoreCase(e.getActorId()))
                .filter(e -> isBlank(c.action()) || (e.getAction() != null && e.getAction().toUpperCase(Locale.ROOT).contains(c.action().trim().toUpperCase(Locale.ROOT))))
                .filter(e -> isBlank(c.outcome()) || c.outcome().trim().equalsIgnoreCase(e.getOutcome()))
                .filter(e -> fromInstant == null || (e.getOccurredAt() != null && !e.getOccurredAt().isBefore(fromInstant)))
                .filter(e -> toInstant == null || (e.getOccurredAt() != null && e.getOccurredAt().isBefore(toInstant)))
                .toList();

        int total = filtered.size();
        int fromIdx = Math.min(c.offset(), total);
        int toIdx = Math.min(fromIdx + c.count(), total);
        return new AuditSearchResult(filtered.subList(fromIdx, toIdx), total, c.offset(), c.count());
    }

    /**
     * Searches persisted audit logs and returns an HL7 FHIR R4 {@link Bundle} ({@code searchset})
     * containing standards-compliant {@link AuditEvent} resources.
     */
    @Transactional(readOnly = true)
    public Bundle searchFhirAuditEventBundle(AuditSearchCriteria criteria) {
        AuditSearchResult result = searchAuditLogs(criteria);
        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(BundleType.SEARCHSET);
        bundle.setTotal(result.total());
        bundle.setTimestamp(new Date());

        for (AuditLogEntity entity : result.entries()) {
            AuditEvent fhirEvent = toFhirAuditEvent(entity);
            bundle.addEntry()
                    .setFullUrl("urn:uuid:" + entity.getId())
                    .setResource(fhirEvent);
        }
        return bundle;
    }

    /**
     * Verifies the cryptographic SHA-256 hash chain for a facility's persisted audit logs.
     */
    @Transactional(readOnly = true)
    public ChainVerificationResult verifyFacilityHashChain(String facilityKey) {
        String normalizedFacility = !isBlank(facilityKey)
                ? facilityKey.trim()
                : FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");
        if (clinicalAuditService != null) {
            return clinicalAuditService.verifyChainIntegrity(normalizedFacility);
        }
        List<AuditLogEntity> entries = fallbackMemoryLedger.stream()
                .filter(e -> normalizedFacility.equals(e.getFacilityKey()))
                .sorted(Comparator.comparingLong(AuditLogEntity::getSequenceNumber))
                .toList();
        String expectedPrev = GENESIS_HASH;
        long expectedSeq = 1L;
        for (AuditLogEntity row : entries) {
            if (row.getSequenceNumber() != expectedSeq || !expectedPrev.equals(row.getPrevHash())) {
                return new ChainVerificationResult(normalizedFacility, false, expectedSeq - 1,
                        "Sequence/prevHash mismatch at seq=" + row.getSequenceNumber());
            }
            String recomputed = computeFallbackSha256(
                    row.getPrevHash(), row.getFacilityKey(), row.getSequenceNumber(),
                    row.getActorId(), row.getAction(), row.getResourceType(),
                    row.getResourceId(), row.getOutcome());
            if (!recomputed.equals(row.getEntryHash())) {
                return new ChainVerificationResult(normalizedFacility, false, expectedSeq - 1,
                        "Hash tampering detected at seq=" + row.getSequenceNumber());
            }
            expectedPrev = row.getEntryHash();
            expectedSeq++;
        }
        return new ChainVerificationResult(normalizedFacility, true, entries.size(), null);
    }

    /**
     * Generates a comprehensive HIPAA § 164.528 Accounting of Disclosures and GDPR Article 15
     * Data Subject Access Report (DSAR) for a specific patient across all FHIR resources.
     */
    @Transactional(readOnly = true)
    public HipaaGdprPatientComplianceReport generatePatientComplianceReport(String facilityKey, UUID patientId) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required for HIPAA/GDPR compliance disclosure report");
        }
        String normalizedFacility = !isBlank(facilityKey)
                ? facilityKey.trim()
                : FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");

        AuditSearchResult searchResult = searchAuditLogs(new AuditSearchCriteria(
                normalizedFacility, patientId, null, null, null, null, null, null, null, 0, 200
        ));
        ChainVerificationResult chainStatus = verifyFacilityHashChain(normalizedFacility);

        int successCount = 0;
        int failureCount = 0;
        Set<String> actors = new LinkedHashSet<>();
        Map<String, Integer> byResourceType = new LinkedHashMap<>();
        Map<String, Integer> byAction = new LinkedHashMap<>();
        List<PatientDisclosureItem> disclosures = new ArrayList<>();

        for (AuditLogEntity entry : searchResult.entries()) {
            if ("SUCCESS".equalsIgnoreCase(entry.getOutcome())) {
                successCount++;
            } else {
                failureCount++;
            }
            if (entry.getActorId() != null) {
                actors.add(entry.getActorId());
            }
            if (entry.getResourceType() != null) {
                byResourceType.merge(entry.getResourceType(), 1, Integer::sum);
            }
            if (entry.getAction() != null) {
                byAction.merge(entry.getAction(), 1, Integer::sum);
            }
            disclosures.add(new PatientDisclosureItem(
                    entry.getId(),
                    entry.getSequenceNumber(),
                    entry.getOccurredAt(),
                    entry.getActorId(),
                    entry.getActorRole(),
                    entry.getAction(),
                    entry.getResourceType(),
                    entry.getResourceId(),
                    entry.getOutcome(),
                    entry.getSourceIp(),
                    entry.getCorrelationId(),
                    entry.getEntryHash()
            ));
        }

        return new HipaaGdprPatientComplianceReport(
                "HIPAA_45_CFR_164_312B_164_528_AND_GDPR_ART_15_30_32",
                Instant.now(),
                normalizedFacility,
                patientId,
                searchResult.total(),
                successCount,
                failureCount,
                List.copyOf(actors),
                Collections.unmodifiableMap(byResourceType),
                Collections.unmodifiableMap(byAction),
                chainStatus.valid(),
                chainStatus.verifiedCount(),
                List.copyOf(disclosures)
        );
    }

    /**
     * Converts a persisted {@link AuditLogEntity} row into an HL7 FHIR R4 {@link AuditEvent} resource.
     */
    public static AuditEvent toFhirAuditEvent(AuditLogEntity entity) {
        Objects.requireNonNull(entity, "AuditLogEntity must not be null");

        AuditEvent auditEvent = new AuditEvent();
        auditEvent.setId(entity.getId().toString());
        auditEvent.setMeta(new Meta()
                .setVersionId(Long.toString(entity.getSequenceNumber()))
                .setLastUpdated(Date.from(entity.getOccurredAt() != null ? entity.getOccurredAt() : Instant.now()))
                .addProfile("http://hl7.org/fhir/StructureDefinition/AuditEvent"));

        // 1. Primary Event Type (RESTful Operation)
        auditEvent.setType(new Coding()
                .setSystem(SYSTEM_AUDIT_EVENT_TYPE)
                .setCode("rest")
                .setDisplay("RESTful Operation"));

        // 2. Subtype (FHIR Interaction + Internal HIPAA Action code)
        String interactionCode = resolveFhirInteractionCode(entity.getAction(), null);
        auditEvent.addSubtype(new Coding()
                .setSystem(SYSTEM_RESTFUL_INTERACTION)
                .setCode(interactionCode)
                .setDisplay("FHIR " + interactionCode + " interaction"));
        if (!isBlank(entity.getAction())) {
            auditEvent.addSubtype(new Coding()
                    .setSystem(SYSTEM_HEALTHCORE_COMPLIANCE)
                    .setCode(entity.getAction())
                    .setDisplay(entity.getAction()));
        }

        // 3. DICOM / FHIR Action Code (C, R, U, D, E)
        auditEvent.setAction(resolveDicomActionCode(entity.getAction(), null));

        // 4. Timestamp & Outcome
        auditEvent.setRecorded(Date.from(entity.getOccurredAt() != null ? entity.getOccurredAt() : Instant.now()));
        boolean isSuccess = "SUCCESS".equalsIgnoreCase(entity.getOutcome());
        auditEvent.setOutcome(isSuccess ? AuditEventOutcome._0 : AuditEventOutcome._4);
        if (!isBlank(entity.getReason())) {
            auditEvent.setOutcomeDesc(PhiRedactionConverter.redact(entity.getReason()));
        } else {
            auditEvent.setOutcomeDesc(isSuccess ? "HIPAA/GDPR compliant FHIR resource access succeeded" : "FHIR resource access failed");
        }

        // 5. Purpose of Event (HIPAA Treatment/Operations & GDPR Article 30 Record of Processing)
        auditEvent.addPurposeOfEvent(new CodeableConcept().addCoding(new Coding()
                .setSystem(SYSTEM_V3_PURPOSE_OF_USE)
                .setCode("TREAT")
                .setDisplay("Healthcare Treatment & Clinical Interoperability")));
        auditEvent.addPurposeOfEvent(new CodeableConcept().addCoding(new Coding()
                .setSystem(SYSTEM_HEALTHCORE_COMPLIANCE)
                .setCode("HIPAA_GDPR_AUDIT")
                .setDisplay("HIPAA 45 CFR 164.312(b) & GDPR Art. 30/32 Security Audit Trail")));

        // 6. Agent (Authenticated Clinician / User / System Client)
        AuditEventAgentComponent agent = auditEvent.addAgent();
        agent.setRequestor(true);
        agent.setWho(new Reference()
                .setIdentifier(new Identifier().setSystem("urn:opes:actor").setValue(entity.getActorId()))
                .setDisplay(entity.getActorId()));
        if (!isBlank(entity.getActorRole())) {
            agent.addRole(new CodeableConcept().addCoding(new Coding()
                    .setSystem("https://opeshealthsystems.com/fhir/CodeSystem/healthcare-role")
                    .setCode(entity.getActorRole())
                    .setDisplay(entity.getActorRole())));
        }
        if (!isBlank(entity.getSourceIp())) {
            agent.setNetwork(new AuditEventAgentNetworkComponent()
                    .setAddress(entity.getSourceIp())
                    .setType(AuditEventAgentNetworkType._2));
        }

        // 7. Audit Source (Opes HealthCore Facility Node)
        AuditEventSourceComponent source = auditEvent.getSource();
        source.setSite(entity.getFacilityKey() != null ? entity.getFacilityKey() : "GLOBAL");
        source.setObserver(new Reference()
                .setIdentifier(new Identifier().setSystem("urn:opes:audit-engine").setValue("opes-healthcore-audit-service"))
                .setDisplay("Opes HealthCore Tamper-Evident SHA-256 Audit Service"));

        // 8. Audited FHIR Resource Entity + Cryptographic Hash Chain Proof
        AuditEventEntityComponent resourceEntity = auditEvent.addEntity();
        String targetRef = !isBlank(entity.getResourceId())
                ? entity.getResourceType() + "/" + entity.getResourceId()
                : entity.getResourceType();
        resourceEntity.setWhat(new Reference().setReference(targetRef).setType(entity.getResourceType()));
        resourceEntity.setName(entity.getResourceType());
        resourceEntity.addDetail()
                .setType("sequenceNumber")
                .setValue(new StringType(Long.toString(entity.getSequenceNumber())));
        resourceEntity.addDetail()
                .setType("prevHash")
                .setValue(new StringType(entity.getPrevHash() != null ? entity.getPrevHash() : GENESIS_HASH));
        resourceEntity.addDetail()
                .setType("entryHash")
                .setValue(new StringType(entity.getEntryHash() != null ? entity.getEntryHash() : GENESIS_HASH));
        if (!isBlank(entity.getCorrelationId())) {
            resourceEntity.addDetail()
                    .setType("correlationId")
                    .setValue(new StringType(entity.getCorrelationId()));
        }

        // 9. Patient Subject Entity (when access involves a specific patient chart)
        if (entity.getPatientId() != null) {
            AuditEventEntityComponent patientEntity = auditEvent.addEntity();
            patientEntity.setWhat(new Reference("Patient/" + entity.getPatientId()).setType("Patient"));
            patientEntity.setName("Patient Data Subject");
        }

        return auditEvent;
    }

    public String encodeFhirResource(IBaseResource resource) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    private AuditLogEntity persistAuditEntity(
            String facilityKey,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String outcome,
            String redactedReason,
            String sourceIp,
            String correlationId) {
        if (clinicalAuditService != null) {
            return clinicalAuditService.recordEventWithCorrelation(
                    facilityKey,
                    actorId,
                    actorRole,
                    action,
                    resourceType,
                    resourceId,
                    patientId,
                    outcome,
                    redactedReason,
                    sourceIp,
                    correlationId
            );
        }

        // Synchronized fallback ledger when running in unit test isolation without JPA repositories
        synchronized (fallbackMemoryLedger) {
            long seq = fallbackSequenceByFacility
                    .computeIfAbsent(facilityKey, k -> new AtomicLong(0L))
                    .incrementAndGet();
            String prevHash = fallbackLastHashByFacility.getOrDefault(facilityKey, GENESIS_HASH);
            String entryHash = computeFallbackSha256(
                    prevHash, facilityKey, seq, actorId, action, resourceType, resourceId, outcome);
            fallbackLastHashByFacility.put(facilityKey, entryHash);

            AuditLogEntity entity = new AuditLogEntity();
            entity.setFacilityKey(facilityKey);
            entity.setSequenceNumber(seq);
            entity.setActorId(actorId);
            entity.setActorRole(actorRole);
            entity.setAction(action);
            entity.setResourceType(resourceType);
            entity.setResourceId(resourceId);
            entity.setPatientId(patientId);
            entity.setOutcome(outcome);
            entity.setReason(redactedReason);
            entity.setSourceIp(sourceIp);
            entity.setCorrelationId(correlationId);
            entity.setPrevHash(prevHash);
            entity.setEntryHash(entryHash);

            if (auditLogRepository != null) {
                return auditLogRepository.save(entity);
            }
            fallbackMemoryLedger.add(entity);
            return entity;
        }
    }

    private List<AuditLogEntity> fetchFacilityLogsDescending(String facilityKey) {
        List<AuditLogEntity> asc;
        if (clinicalAuditService != null) {
            asc = clinicalAuditService.findFacilityLogs(facilityKey);
        } else if (auditLogRepository != null) {
            asc = auditLogRepository.findByFacilityKeyOrderBySequenceNumberAsc(facilityKey);
        } else {
            asc = fallbackMemoryLedger.stream()
                    .filter(e -> facilityKey.equals(e.getFacilityKey()))
                    .toList();
        }
        List<AuditLogEntity> sortedDesc = new ArrayList<>(asc);
        sortedDesc.sort(Comparator.comparingLong(AuditLogEntity::getSequenceNumber).reversed());
        return sortedDesc;
    }

    public static AuditEventAction resolveDicomActionCode(String action, String httpMethod) {
        String upper = action != null ? action.toUpperCase(Locale.ROOT) : "";
        if (upper.contains("CREATE") || "POST".equalsIgnoreCase(httpMethod) && !upper.contains("PROCESS") && !upper.contains("GRAPHQL") && !upper.contains("CDS")) {
            return AuditEventAction.C;
        }
        if (upper.contains("READ") || upper.contains("SEARCH") || upper.contains("PATIENT") && "GET".equalsIgnoreCase(httpMethod)) {
            return AuditEventAction.R;
        }
        if (upper.contains("UPDATE") || "PUT".equalsIgnoreCase(httpMethod) || "PATCH".equalsIgnoreCase(httpMethod)) {
            return AuditEventAction.U;
        }
        if (upper.contains("DEACTIVATE") || upper.contains("DELETE") || upper.contains("CANCEL") || upper.contains("ENTERED_IN_ERROR") || "DELETE".equalsIgnoreCase(httpMethod)) {
            return AuditEventAction.D;
        }
        if (upper.contains("PROCESS") || upper.contains("BUNDLE") || upper.contains("CDS") || upper.contains("INVOKE") || upper.contains("FEEDBACK")) {
            return AuditEventAction.E;
        }
        if (upper.contains("GRAPHQL")) {
            if (upper.contains("CREATE") || upper.contains("UPDATE") || upper.contains("DEACTIVATE") || upper.contains("CANCEL") || upper.contains("MARK")) {
                return AuditEventAction.E;
            }
            return AuditEventAction.R;
        }
        return AuditEventAction.R;
    }

    public static String resolveFhirInteractionCode(String action, String httpMethod) {
        String upper = action != null ? action.toUpperCase(Locale.ROOT) : "";
        if (upper.contains("SEARCH") || upper.endsWith("PATIENTS") || upper.endsWith("PRACTITIONERS") || upper.endsWith("ENCOUNTERS") || upper.endsWith("OBSERVATIONS")) {
            return "search-type";
        }
        if (upper.contains("READ")) {
            return "read";
        }
        if (upper.contains("CREATE")) {
            return "create";
        }
        if (upper.contains("UPDATE")) {
            return "update";
        }
        if (upper.contains("DEACTIVATE") || upper.contains("DELETE") || upper.contains("CANCEL") || upper.contains("ENTEREDINERROR")) {
            return "delete";
        }
        if (upper.contains("BUNDLE") || upper.contains("TRANSACTION") || upper.contains("PROCESS")) {
            return "transaction";
        }
        if (upper.contains("BATCH")) {
            return "batch";
        }
        if ("POST".equalsIgnoreCase(httpMethod)) {
            return "create";
        }
        if ("PUT".equalsIgnoreCase(httpMethod)) {
            return "update";
        }
        if ("DELETE".equalsIgnoreCase(httpMethod)) {
            return "delete";
        }
        return "read";
    }

    private static String resolveHttpMethodFromAction(String action) {
        String upper = action != null ? action.toUpperCase(Locale.ROOT) : "";
        if (upper.contains("CREATE") || upper.contains("PROCESS") || upper.contains("GRAPHQL") || upper.contains("CDS")) {
            return "POST";
        }
        if (upper.contains("UPDATE")) {
            return "PUT";
        }
        if (upper.contains("DEACTIVATE") || upper.contains("DELETE") || upper.contains("CANCEL")) {
            return "DELETE";
        }
        return "GET";
    }

    private static String resolveRoleFromAuth(Authentication auth) {
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a != null && a.startsWith("ROLE_"))
                    .findFirst()
                    .orElseGet(() -> auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .findFirst()
                            .orElse("UNKNOWN"));
        }
        return "UNKNOWN";
    }

    private static boolean matchesResourceId(String storedResourceId, String queryResourceId) {
        if (isBlank(storedResourceId) || isBlank(queryResourceId)) {
            return false;
        }
        String normalizedQuery = queryResourceId.contains("/")
                ? queryResourceId.substring(queryResourceId.lastIndexOf('/') + 1)
                : queryResourceId;
        String normalizedStored = storedResourceId.contains("/")
                ? storedResourceId.substring(storedResourceId.lastIndexOf('/') + 1)
                : storedResourceId;
        return normalizedStored.equalsIgnoreCase(normalizedQuery);
    }

    private static String computeFallbackSha256(
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
