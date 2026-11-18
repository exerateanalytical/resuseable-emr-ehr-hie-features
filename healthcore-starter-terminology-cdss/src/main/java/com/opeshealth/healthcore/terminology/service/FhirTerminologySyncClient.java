/*
 * =============================================================================
 * Opes HealthCore SDK — External FHIR R4 Terminology Sync & Import Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.terminology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.resilience.ResilienceAndGovernanceSupport.PostgresAdvisoryLockHelper;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Synchronizes external HL7 FHIR R4 Terminology Servers (Ontoserver, Snowstorm, or HAPI FHIR
 * {@code /ValueSet/$expand} and {@code /CodeSystem}) into {@code hc_code_systems}, and exposes
 * bulk terminology import APIs ({@code CSV}, {@code SNOMED_RF2}, {@code SNOMED_RF2_RELATIONSHIP},
 * and {@code FHIR_CODESYSTEM}).
 */
public class FhirTerminologySyncClient {

    private static final Logger log = LoggerFactory.getLogger(FhirTerminologySyncClient.class);

    private final TerminologyService terminologyService;
    private final PostgresAdvisoryLockHelper advisoryLockHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    @Value("${healthcore.terminology.remote-sync.enabled:false}")
    private boolean remoteSyncEnabled;

    @Value("${healthcore.terminology.remote-sync.fhir-server-url:}")
    private String fhirServerUrl;

    @Value("${healthcore.terminology.remote-sync.default-valueset-url:http://hl7.org/fhir/ValueSet/observation-codes}")
    private String defaultValueSetUrl;

    public FhirTerminologySyncClient(
            TerminologyService terminologyService,
            PostgresAdvisoryLockHelper advisoryLockHelper) {
        this.terminologyService = terminologyService;
        this.advisoryLockHelper = advisoryLockHelper;
    }

    @Scheduled(fixedDelayString = "${healthcore.terminology.remote-sync.interval-ms:86400000}")
    @Transactional
    public void scheduledValueSetSync() {
        if (!remoteSyncEnabled || fhirServerUrl == null || fhirServerUrl.isBlank()) {
            return;
        }
        if (advisoryLockHelper != null
                && !advisoryLockHelper.tryAcquireTransactionLock("terminology-valueset-sync")) {
            return;
        }
        try {
            expandAndImportValueSet(defaultValueSetUrl);
        } catch (Exception ex) {
            log.warn("Scheduled FHIR Terminology ValueSet sync failed for {}: {}", defaultValueSetUrl, ex.getMessage());
        }
    }

    /** Calls {@code GET {fhirServerUrl}/ValueSet/$expand?url=...} and imports all expanded concepts. */
    @ClinicalAudit(action = "TERMINOLOGY_FHIR_SYNC", resourceType = "CodeSystem")
    @CircuitBreaker(name = "terminologyRemote", fallbackMethod = "syncFallback")
    public Map<String, Object> expandAndImportValueSet(String valueSetUrl) {
        if (fhirServerUrl == null || fhirServerUrl.isBlank()) {
            return Map.of("status", "SKIPPED_NO_SERVER", "valueSetUrl", valueSetUrl, "importedCount", 0);
        }
        try {
            String base = fhirServerUrl.endsWith("/")
                    ? fhirServerUrl.substring(0, fhirServerUrl.length() - 1)
                    : fhirServerUrl;
            String url = base + "/ValueSet/$expand?url=" + URLEncoder.encode(valueSetUrl, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/fhir+json, application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Terminology server HTTP " + resp.statusCode());
            }
            return importExpandedValueSetJson(resp.body());
        } catch (Exception ex) {
            throw new IllegalStateException("External FHIR Terminology sync error: " + ex.getMessage(), ex);
        }
    }

    /** Converts a FHIR R4 expanded {@code ValueSet} JSON document into normalized concepts in {@code hc_code_systems}. */
    public Map<String, Object> importExpandedValueSetJson(String valueSetJson) {
        try {
            JsonNode root = objectMapper.readTree(valueSetJson);
            JsonNode contains = root.path("expansion").path("contains");
            if (!contains.isArray()) {
                return terminologyService.bulkImportConcepts("FHIR_CODESYSTEM", valueSetJson);
            }
            Map<String, List<Map<String, String>>> bySystem = new LinkedHashMap<>();
            for (JsonNode item : contains) {
                String sys = item.path("system").asText(TerminologyService.LOINC);
                String code = item.path("code").asText("");
                String display = item.path("display").asText(code);
                if (!code.isBlank()) {
                    bySystem.computeIfAbsent(sys, k -> new ArrayList<>())
                            .add(Map.of("code", code, "display", display));
                }
            }
            int totalImported = 0;
            int totalSkipped = 0;
            for (Map.Entry<String, List<Map<String, String>>> e : bySystem.entrySet()) {
                String csJson = objectMapper.writeValueAsString(Map.of("url", e.getKey(), "concept", e.getValue()));
                Map<String, Object> res = terminologyService.bulkImportConcepts("FHIR_CODESYSTEM", csJson);
                totalImported += ((Number) res.getOrDefault("importedCount", 0)).intValue();
                totalSkipped += ((Number) res.getOrDefault("skippedCount", 0)).intValue();
            }
            return Map.of("format", "FHIR_VALUESET_EXPANSION", "importedCount", totalImported, "skippedCount", totalSkipped);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid FHIR ValueSet expansion JSON: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unused")
    private Map<String, Object> syncFallback(String valueSetUrl, Throwable ex) {
        return Map.of(
                "status", "FALLBACK_OFFLINE",
                "valueSetUrl", valueSetUrl,
                "reason", Objects.toString(ex.getMessage(), "Terminology server unreachable")
        );
    }

    @RestController
    @RequestMapping("/api/v1/terminology")
    @Tag(name = "Terminology Loader & FHIR Sync", description = "Bulk CSV/SNOMED RF2/Relationship closure import and external FHIR ValueSet/$expand sync")
    public static class TerminologyManagementController {
        private final TerminologyService terminologyService;
        private final FhirTerminologySyncClient syncClient;

        public TerminologyManagementController(
                TerminologyService terminologyService,
                FhirTerminologySyncClient syncClient) {
            this.terminologyService = terminologyService;
            this.syncClient = syncClient;
        }

        @PostMapping(value = "/import", consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE})
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Import LOINC/SNOMED/ICD/RxNorm concepts or SNOMED RF2 IS-A relationships")
        public Map<String, Object> bulkImport(
                @RequestParam(defaultValue = "CSV") String format,
                @RequestBody String payload) {
            return terminologyService.bulkImportConcepts(format, payload);
        }

        @PostMapping("/sync-valueset")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Sync and cache expanded FHIR R4 ValueSet from configured Terminology Server")
        public Map<String, Object> syncValueSet(@RequestParam String valueSetUrl) {
            return syncClient.expandAndImportValueSet(valueSetUrl);
        }
    }
}
