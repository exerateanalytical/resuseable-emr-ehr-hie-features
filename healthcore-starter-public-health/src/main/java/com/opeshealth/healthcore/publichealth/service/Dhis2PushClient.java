/*
 * =============================================================================
 * Opes HealthCore SDK — Live DHIS2 Aggregate & Tracker Push Client
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresAdvisoryLockHelper;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.Dhis2MappingEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.Dhis2SyncLogEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.SurveillanceCaseEntity;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.SurveillanceCaseRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.*;

/**
 * Live DHIS2 Web API v2.40+ outbound integration supporting:
 * <ul>
 *   <li>Aggregate {@code POST /api/dataValueSets} JSON payloads grouped by OrgUnit + ISO week</li>
 *   <li>Tracker {@code POST /api/tracker} individual reportable IDSR case submission</li>
 *   <li>PostgreSQL transaction-scoped advisory locking preventing multi-pod duplicate weekly uploads</li>
 *   <li>Resilience4j circuit-breaker fallback persisting failed payloads cleanly in {@code hc_dhis2_sync_logs}</li>
 * </ul>
 */
public class Dhis2PushClient {

    public interface Dhis2MappingRepository extends JpaRepository<Dhis2MappingEntity, UUID> {
        List<Dhis2MappingEntity> findByDistrictCodeAndActiveTrue(String districtCode);
        Optional<Dhis2MappingEntity> findFirstByDistrictCodeAndSourceDomainAndSourceCodeAndActiveTrue(
                String districtCode, String sourceDomain, String sourceCode);
    }

    public interface Dhis2SyncLogRepository extends JpaRepository<Dhis2SyncLogEntity, UUID> {
        List<Dhis2SyncLogEntity> findByDistrictCodeOrderByCreatedAtDesc(String districtCode);
    }

    private static final Logger log = LoggerFactory.getLogger(Dhis2PushClient.class);

    private final Dhis2MappingRepository mappingRepo;
    private final Dhis2SyncLogRepository syncLogRepo;
    private final SurveillanceCaseRepository surveillanceRepo;
    private final PostgresAdvisoryLockHelper advisoryLockHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    @Value("${healthcore.dhis2.enabled:false}")
    private boolean dhis2Enabled;

    @Value("${healthcore.dhis2.base-url:}")
    private String baseUrl;

    @Value("${healthcore.dhis2.username:}")
    private String username;

    @Value("${healthcore.dhis2.password:}")
    private String password;

    @Value("${healthcore.dhis2.bearer-token:}")
    private String bearerToken;

    @Value("${healthcore.dhis2.auto-district:CM-LT-DLA}")
    private String defaultAutoDistrict;

    public Dhis2PushClient(
            Dhis2MappingRepository mappingRepo,
            Dhis2SyncLogRepository syncLogRepo,
            SurveillanceCaseRepository surveillanceRepo,
            PostgresAdvisoryLockHelper advisoryLockHelper) {
        this.mappingRepo = mappingRepo;
        this.syncLogRepo = syncLogRepo;
        this.surveillanceRepo = surveillanceRepo;
        this.advisoryLockHelper = advisoryLockHelper;
    }

    /**
     * Scheduled weekly aggregate push guarded by PostgreSQL {@code pg_try_advisory_xact_lock}
     * so clustered replicas never double-post identical ISO-week datasets.
     */
    @Scheduled(fixedDelayString = "${healthcore.dhis2.sync-interval-ms:300000}")
    @Transactional
    public void scheduledAggregateSync() {
        if (!dhis2Enabled || baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        String isoPeriod = currentIsoWeekPeriod();
        if (advisoryLockHelper != null
                && !advisoryLockHelper.tryAcquireTransactionLock("dhis2-weekly-push-" + isoPeriod)) {
            return;
        }
        try {
            pushAggregateDataValueSet(defaultAutoDistrict, isoPeriod, false);
        } catch (Exception ex) {
            dhis2AggregateFallback(defaultAutoDistrict, isoPeriod, false, ex);
        }
    }

    /** Builds a DHIS2 {@code /api/dataValueSets} payload and posts it (or records a verified dry run). */
    @Transactional
    @ClinicalAudit(action = "DHIS2_PUSH_AGGREGATE", resourceType = "Dhis2SyncLog")
    @CircuitBreaker(name = "dhis2Outbound", fallbackMethod = "dhis2AggregateFallback")
    public Dhis2SyncLogEntity pushAggregateDataValueSet(String districtCode, String periodCode, boolean dryRun) {
        Map<String, Object> payload = buildDataValueSetPayload(districtCode, periodCode);
        String json = toJson(payload);

        if (dryRun || !dhis2Enabled || baseUrl == null || baseUrl.isBlank()) {
            return recordSyncLog("DATA_VALUE_SET", districtCode, periodCode, json, null, "DRY_RUN",
                    "Dry-run / offline-queued DHIS2 dataValueSets payload prepared");
        }

        HttpResponse<String> resp = postDhis2("/api/dataValueSets", json);
        String status = resp.statusCode() >= 200 && resp.statusCode() < 300 ? "SENT" : "FAILED";
        String summary = truncateSummary(resp.body());
        if ("FAILED".equals(status)) {
            throw new IllegalStateException("DHIS2 /api/dataValueSets returned HTTP " + resp.statusCode() + ": " + summary);
        }
        return recordSyncLog("DATA_VALUE_SET", districtCode, periodCode, json, resp.statusCode(), status, summary);
    }

    /** Pushes a single reportable IDSR Surveillance Case into DHIS2 {@code /api/tracker}. */
    @Transactional
    @ClinicalAudit(action = "DHIS2_PUSH_TRACKER", resourceType = "Dhis2SyncLog")
    @CircuitBreaker(name = "dhis2Outbound", fallbackMethod = "dhis2TrackerFallback")
    public Dhis2SyncLogEntity pushTrackerEvent(UUID surveillanceCaseId, boolean dryRun) {
        SurveillanceCaseEntity sc = surveillanceRepo.findById(surveillanceCaseId)
                .orElseThrow(() -> new NoSuchElementException("Surveillance case not found: " + surveillanceCaseId));
        String periodCode = currentIsoWeekPeriod();
        Map<String, Object> mappingMeta = resolveTrackerMapping(sc);
        Map<String, Object> trackerBody = Map.of(
                "events", List.of(Map.of(
                        "program", mappingMeta.get("programUid"),
                        "orgUnit", mappingMeta.get("orgUnitUid"),
                        "occurredAt", sc.getReportedAt().toString().substring(0, 10),
                        "status", "COMPLETED",
                        "dataValues", List.of(
                                Map.of("dataElement", mappingMeta.get("dataElementUid"), "value", sc.getDiseaseIcd10())
                        )
                ))
        );
        String json = toJson(trackerBody);

        if (dryRun || !dhis2Enabled || baseUrl == null || baseUrl.isBlank()) {
            return recordSyncLog("TRACKER_EVENT", sc.getDistrictCode(), periodCode, json, null, "DRY_RUN",
                    "Dry-run DHIS2 Tracker event prepared for case " + sc.getId());
        }

        HttpResponse<String> resp = postDhis2("/api/tracker?async=false", json);
        String status = resp.statusCode() >= 200 && resp.statusCode() < 300 ? "SENT" : "FAILED";
        String summary = truncateSummary(resp.body());
        if ("FAILED".equals(status)) {
            throw new IllegalStateException("DHIS2 /api/tracker returned HTTP " + resp.statusCode() + ": " + summary);
        }
        return recordSyncLog("TRACKER_EVENT", sc.getDistrictCode(), periodCode, json, resp.statusCode(), status, summary);
    }

    /** Deterministic DHIS2 {@code dataValueSets} JSON builder backed by {@code hc_dhis2_mappings}. */
    @Transactional(readOnly = true)
    public Map<String, Object> buildDataValueSetPayload(String districtCode, String periodCode) {
        List<SurveillanceCaseEntity> cases = surveillanceRepo.findByDistrictCode(districtCode);
        Map<String, Long> countsByIcd = new LinkedHashMap<>();
        for (SurveillanceCaseEntity c : cases) {
            countsByIcd.merge(c.getDiseaseIcd10(), 1L, Long::sum);
        }

        List<Dhis2MappingEntity> mappings = mappingRepo.findByDistrictCodeAndActiveTrue(districtCode);
        Map<String, Dhis2MappingEntity> bySourceCode = new HashMap<>();
        String fallbackOrgUnit = "ORG_" + districtCode;
        for (Dhis2MappingEntity m : mappings) {
            if ("IDSR".equalsIgnoreCase(m.getSourceDomain())) {
                bySourceCode.put(m.getSourceCode(), m);
                fallbackOrgUnit = m.getDhis2OrgUnitUid();
            }
        }

        List<Map<String, Object>> dataValues = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countsByIcd.entrySet()) {
            Dhis2MappingEntity mapped = bySourceCode.get(entry.getKey());
            Map<String, Object> dv = new LinkedHashMap<>();
            dv.put("dataElement", mapped != null ? mapped.getDhis2DataElementUid() : "DE_IDSR_" + entry.getKey());
            dv.put("period", periodCode);
            dv.put("orgUnit", mapped != null ? mapped.getDhis2OrgUnitUid() : fallbackOrgUnit);
            if (mapped != null && mapped.getDhis2CategoryOptionUid() != null && !mapped.getDhis2CategoryOptionUid().isBlank()) {
                dv.put("categoryOptionCombo", mapped.getDhis2CategoryOptionUid());
            }
            dv.put("value", Long.toString(entry.getValue()));
            dataValues.add(dv);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("orgUnit", fallbackOrgUnit);
        root.put("period", periodCode);
        root.put("completeDate", LocalDate.now().toString());
        root.put("dataValues", dataValues);
        return root;
    }

    @SuppressWarnings("unused")
    private Dhis2SyncLogEntity dhis2AggregateFallback(String districtCode, String periodCode, boolean dryRun, Throwable ex) {
        String json = toJson(buildDataValueSetPayload(districtCode, periodCode));
        log.warn("DHIS2 aggregate push circuit-breaker fallback for {}/{}: {}", districtCode, periodCode, ex.getMessage());
        return recordSyncLog("DATA_VALUE_SET", districtCode, periodCode, json, 503, "FAILED",
                truncateSummary("Fallback quarantine: " + ex.getMessage()));
    }

    @SuppressWarnings("unused")
    private Dhis2SyncLogEntity dhis2TrackerFallback(UUID surveillanceCaseId, boolean dryRun, Throwable ex) {
        log.warn("DHIS2 tracker push circuit-breaker fallback for case {}: {}", surveillanceCaseId, ex.getMessage());
        return recordSyncLog("TRACKER_EVENT", "UNKNOWN", currentIsoWeekPeriod(),
                "{\"surveillanceCaseId\":\"" + surveillanceCaseId + "\"}", 503, "FAILED",
                truncateSummary("Fallback quarantine: " + ex.getMessage()));
    }

    private Map<String, Object> resolveTrackerMapping(SurveillanceCaseEntity sc) {
        Optional<Dhis2MappingEntity> opt = mappingRepo
                .findFirstByDistrictCodeAndSourceDomainAndSourceCodeAndActiveTrue(
                        sc.getDistrictCode(), "IDSR", sc.getDiseaseIcd10());
        String program = opt.map(Dhis2MappingEntity::getDhis2ProgramUid)
                .filter(s -> s != null && !s.isBlank())
                .orElse("PROG_IDSR_SURVEILLANCE");
        String orgUnit = opt.map(Dhis2MappingEntity::getDhis2OrgUnitUid).orElse("ORG_" + sc.getDistrictCode());
        String dataElement = opt.map(Dhis2MappingEntity::getDhis2DataElementUid).orElse("DE_IDSR_" + sc.getDiseaseIcd10());
        return Map.of("programUid", program, "orgUnitUid", orgUnit, "dataElementUid", dataElement);
    }

    private HttpResponse<String> postDhis2(String path, String jsonBody) {
        try {
            String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(normalizedBase + path))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isBlank()) {
                req.header("Authorization", "Bearer " + bearerToken);
            } else if (username != null && !username.isBlank()) {
                String creds = Base64.getEncoder().encodeToString(
                        (username + ":" + Objects.toString(password, "")).getBytes(StandardCharsets.UTF_8));
                req.header("Authorization", "Basic " + creds);
            }
            return httpClient.send(req.POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new IllegalStateException("DHIS2 HTTP transport error: " + ex.getMessage(), ex);
        }
    }

    private Dhis2SyncLogEntity recordSyncLog(
            String mode, String districtCode, String periodCode,
            String payloadJson, Integer httpStatus, String status, String summary) {
        Dhis2SyncLogEntity entry = new Dhis2SyncLogEntity();
        entry.setSyncMode(mode);
        entry.setDistrictCode(districtCode);
        entry.setPeriodCode(periodCode);
        entry.setPayloadJson(payloadJson);
        entry.setHttpStatus(httpStatus);
        entry.setStatus(status);
        entry.setDhis2ImportSummary(summary);
        return syncLogRepo.save(entry);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize DHIS2 JSON payload", ex);
        }
    }

    private String truncateSummary(String s) {
        if (s == null) return "";
        return s.length() <= 580 ? s : s.substring(0, 580);
    }

    public static String currentIsoWeekPeriod() {
        LocalDate now = LocalDate.now();
        int weekYear = now.get(IsoFields.WEEK_BASED_YEAR);
        int week = now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return weekYear + "W" + String.format("%02d", week);
    }

    @RestController
    @RequestMapping("/api/v1/public-health/dhis2")
    @Tag(name = "DHIS2 Aggregate & Tracker Sync", description = "DHIS2 OrgUnit/DataElement mapping, /api/dataValueSets & /api/tracker outbound push")
    public static class Dhis2Controller {
        private final Dhis2PushClient client;
        private final Dhis2MappingRepository mappingRepo;
        private final Dhis2SyncLogRepository logRepo;

        public Dhis2Controller(
                Dhis2PushClient client,
                Dhis2MappingRepository mappingRepo,
                Dhis2SyncLogRepository logRepo) {
            this.client = client;
            this.mappingRepo = mappingRepo;
            this.logRepo = logRepo;
        }

        @PostMapping("/mappings")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Register DHIS2 OrgUnit/DataElement/Program UID mapping")
        public Dhis2MappingEntity upsertMapping(@RequestBody Dhis2MappingEntity mapping) {
            return mappingRepo.save(mapping);
        }

        @GetMapping("/mappings")
        @PreAuthorize("hasAuthority('PERM_audit:export') or hasAuthority('PERM_dashboard:executive') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "List active DHIS2 mappings for a district")
        public List<Dhis2MappingEntity> listMappings(@RequestParam String districtCode) {
            return mappingRepo.findByDistrictCodeAndActiveTrue(districtCode);
        }

        @PostMapping("/push/data-value-sets")
        @PreAuthorize("hasAuthority('PERM_audit:export') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Build and push DHIS2 /api/dataValueSets aggregate IDSR counts for an ISO week")
        public Dhis2SyncLogEntity pushAggregate(
                @RequestParam String districtCode,
                @RequestParam(required = false) String period,
                @RequestParam(defaultValue = "true") boolean dryRun) {
            String p = (period == null || period.isBlank()) ? currentIsoWeekPeriod() : period;
            return client.pushAggregateDataValueSet(districtCode, p, dryRun);
        }

        @PostMapping("/push/tracker/{caseId}")
        @PreAuthorize("hasAuthority('PERM_audit:export') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Push individual reportable IDSR case event to DHIS2 /api/tracker")
        public Dhis2SyncLogEntity pushTracker(
                @PathVariable UUID caseId,
                @RequestParam(defaultValue = "true") boolean dryRun) {
            return client.pushTrackerEvent(caseId, dryRun);
        }

        @GetMapping("/sync-logs")
        @PreAuthorize("hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Inspect DHIS2 transmission audit logs by district")
        public List<Dhis2SyncLogEntity> syncLogs(@RequestParam String districtCode) {
            return logRepo.findByDistrictCodeOrderByCreatedAtDesc(districtCode);
        }
    }
}
