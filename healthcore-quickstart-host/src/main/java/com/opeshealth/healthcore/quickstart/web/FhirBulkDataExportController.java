/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 Bulk Data Access ($export) Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.FhirBulkDataExportService;
import com.opeshealth.healthcore.quickstart.service.FhirBulkDataExportService.BulkExportJob;
import com.opeshealth.healthcore.quickstart.service.FhirBulkDataExportService.BulkExportManifest;
import com.opeshealth.healthcore.quickstart.service.FhirBulkDataExportService.BulkExportRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * HL7 FHIR R4 Bulk Data Access IG v2.0 ({@code $export}) REST controller enabling
 * authorized practitioners to export patient cohorts (system/patient cohort or
 * practitioner-attributed panel) in {@code application/fhir+ndjson} or FHIR R4
 * collection {@code Bundle} format.
 *
 * <p>Intercepted by {@link PatientAuditAspect} for tamper-evident HIPAA/GDPR audit
 * logging and throttled under the heavy-operation Bucket4j tier in
 * {@code FhirApiRateLimitFilter}.</p>
 */
@RestController
@Tag(
        name = "FHIR R4 Bulk Data Export ($export)",
        description = "HL7 FHIR R4 Bulk Data Access IG v2.0 cohort export for practitioners (/Practitioner/{id}/$export and /Patient/$export) producing application/fhir+ndjson streams and status manifests"
)
public class FhirBulkDataExportController {

    private static final String NDJSON_CONTENT_TYPE = "application/fhir+ndjson";
    private static final String FHIR_JSON_CONTENT_TYPE = "application/fhir+json";

    public record BulkCohortExportPayload(
            UUID facilityId,
            UUID practitionerId,
            List<UUID> patientIds,
            List<String> resourceTypes,
            Instant since,
            String outputFormat,
            boolean synchronous
    ) {}

    private final FhirBulkDataExportService bulkExportService;

    public FhirBulkDataExportController(FhirBulkDataExportService bulkExportService) {
        this.bulkExportService = Objects.requireNonNull(bulkExportService, "FhirBulkDataExportService is required");
    }

    @GetMapping(
            value = {"/api/v1/fhir/Patient/$export", "/api/v1/patients/$export"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON_CONTENT_TYPE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Initiate an HL7 FHIR R4 Patient-level Bulk Data cohort export (/Patient/$export)",
            description = "Kicks off a Bulk Data cohort export across Patient, Encounter, Observation, and Practitioner resources. Returns HTTP 202 Accepted with Content-Location polling URL by default, or HTTP 200 OK when sync=true."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Bulk Data export job accepted; poll Content-Location URL for completion manifest",
                    headers = @Header(name = "Content-Location", description = "Status polling endpoint for this export job")
            ),
            @ApiResponse(responseCode = "200", description = "Synchronous Bulk Data export manifest returned"),
            @ApiResponse(responseCode = "400", description = "Invalid _type, _since, or _outputFormat query parameter"),
            @ApiResponse(responseCode = "429", description = "Bucket4j Bulk Data rate limit exceeded")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_PATIENT_COHORT", resourceType = "BulkDataExport")
    public ResponseEntity<Map<String, Object>> kickOffPatientCohortExport(
            @Parameter(description = "Comma-separated FHIR resource types (Patient,Encounter,Observation,Practitioner)")
            @RequestParam(name = "_type", required = false) String typeParam,
            @Parameter(description = "Only include resources updated at or after this ISO-8601 instant")
            @RequestParam(name = "_since", required = false) Instant since,
            @Parameter(description = "Output format (application/fhir+ndjson)")
            @RequestParam(name = "_outputFormat", defaultValue = NDJSON_CONTENT_TYPE) String outputFormat,
            @Parameter(description = "Optional comma-separated patient UUID cohort filter")
            @RequestParam(name = "patientIds", required = false) String patientIdsCsv,
            @Parameter(description = "Optional attending practitioner UUID filter")
            @RequestParam(name = "practitionerId", required = false) UUID practitionerId,
            @Parameter(description = "Return completed manifest synchronously (200 OK) instead of 202 Accepted")
            @RequestParam(name = "sync", defaultValue = "false") boolean sync,
            @RequestParam(name = "facilityId", required = false) UUID facilityParam,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestHeader(value = "Prefer", required = false) String preferHeader,
            HttpServletResponse response) {
        UUID scopedFacility = resolveFacilityId(facilityParam, headerFacilityId);
        List<UUID> patientIds = parseUuidList(patientIdsCsv);
        List<String> types = FhirBulkDataExportService.parseTypeQueryParam(typeParam);

        BulkExportJob job = bulkExportService.initiateCohortExport(new BulkExportRequest(
                scopedFacility,
                practitionerId,
                patientIds,
                types,
                since,
                outputFormat,
                resolveCurrentActor(),
                "/api/v1/fhir/Patient/$export"
        ));
        return buildKickOffResponse(job, sync, preferHeader, response);
    }

    @GetMapping(
            value = {
                    "/api/v1/fhir/Practitioner/{practitionerId}/$export",
                    "/api/v1/practitioners/{practitionerId}/$export"
            },
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON_CONTENT_TYPE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Export the patient cohort attributed to a specific Practitioner (/Practitioner/{id}/$export)",
            description = "Resolves all patients with Encounters or Observations performed by the specified Practitioner and exports their clinical cohort as FHIR R4 NDJSON."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Practitioner cohort export accepted; poll Content-Location header",
                    headers = @Header(name = "Content-Location", description = "Status polling endpoint for this export job")
            ),
            @ApiResponse(responseCode = "200", description = "Synchronous practitioner cohort export manifest returned"),
            @ApiResponse(responseCode = "404", description = "Practitioner not found"),
            @ApiResponse(responseCode = "429", description = "Bucket4j Bulk Data rate limit exceeded")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_PRACTITIONER_COHORT", resourceType = "BulkDataExport")
    public ResponseEntity<Map<String, Object>> kickOffPractitionerCohortExport(
            @PathVariable UUID practitionerId,
            @RequestParam(name = "_type", required = false) String typeParam,
            @RequestParam(name = "_since", required = false) Instant since,
            @RequestParam(name = "_outputFormat", defaultValue = NDJSON_CONTENT_TYPE) String outputFormat,
            @RequestParam(name = "patientIds", required = false) String patientIdsCsv,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync,
            @RequestParam(name = "facilityId", required = false) UUID facilityParam,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestHeader(value = "Prefer", required = false) String preferHeader,
            HttpServletResponse response) {
        UUID scopedFacility = resolveFacilityId(facilityParam, headerFacilityId);
        List<UUID> patientIds = parseUuidList(patientIdsCsv);
        List<String> types = FhirBulkDataExportService.parseTypeQueryParam(typeParam);

        BulkExportJob job = bulkExportService.initiateCohortExport(new BulkExportRequest(
                scopedFacility,
                practitionerId,
                patientIds,
                types,
                since,
                outputFormat,
                resolveCurrentActor(),
                "/api/v1/fhir/Practitioner/" + practitionerId + "/$export"
        ));
        return buildKickOffResponse(job, sync, preferHeader, response);
    }

    @PostMapping(
            value = {"/api/v1/fhir/$export", "/api/v1/bulk-export/cohorts"},
            consumes = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON_CONTENT_TYPE},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON_CONTENT_TYPE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Initiate a customized patient cohort Bulk Data export via POST payload",
            description = "Allows practitioners to specify explicit patient UUID lists, attending practitioner filters, resource types, and incremental _since timestamps."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Asynchronous cohort export accepted"),
            @ApiResponse(responseCode = "200", description = "Synchronous cohort export manifest returned"),
            @ApiResponse(responseCode = "400", description = "Invalid cohort export specification")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_CUSTOM_COHORT", resourceType = "BulkDataExport")
    public ResponseEntity<Map<String, Object>> createCustomCohortExport(
            @RequestBody BulkCohortExportPayload payload,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestHeader(value = "Prefer", required = false) String preferHeader,
            HttpServletResponse response) {
        UUID scopedFacility = resolveFacilityId(payload != null ? payload.facilityId() : null, headerFacilityId);
        BulkExportJob job = bulkExportService.initiateCohortExport(new BulkExportRequest(
                scopedFacility,
                payload != null ? payload.practitionerId() : null,
                payload != null ? payload.patientIds() : List.of(),
                payload != null ? payload.resourceTypes() : FhirBulkDataExportService.DEFAULT_EXPORT_TYPES,
                payload != null ? payload.since() : null,
                payload != null && payload.outputFormat() != null ? payload.outputFormat() : NDJSON_CONTENT_TYPE,
                resolveCurrentActor(),
                "/api/v1/bulk-export/cohorts"
        ));
        boolean sync = payload != null && payload.synchronous();
        return buildKickOffResponse(job, sync, preferHeader, response);
    }

    @GetMapping(
            value = {"/api/v1/fhir/$export-status/{jobId}", "/api/v1/bulk-export/jobs/{jobId}"},
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON_CONTENT_TYPE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Poll FHIR Bulk Data export job status and retrieve the NDJSON file manifest",
            description = "Returns the normative HL7 FHIR Bulk Data Complete Status response with output file URLs, record counts, and SHA-256 checksums."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bulk Data export completed; manifest returned"),
            @ApiResponse(responseCode = "404", description = "Export job not found or deleted")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_STATUS", resourceType = "BulkDataExport")
    public ResponseEntity<Map<String, Object>> getExportJobStatus(
            @PathVariable UUID jobId,
            HttpServletResponse response) {
        BulkExportManifest manifest = bulkExportService.getExportJobManifest(
                jobId, "/api/v1/fhir/$export-files/" + jobId);
        if (response != null) {
            response.setHeader("ETag", "W/\"" + manifest.totalExportedResources() + "\"");
            response.setHeader("Expires", manifest.transactionTime().plusSeconds(86_400).toString());
        }
        return ResponseEntity.ok(toManifestMap(manifest));
    }

    @GetMapping(
            value = {
                    "/api/v1/fhir/$export-files/{jobId}/{resourceType}",
                    "/api/v1/bulk-export/jobs/{jobId}/files/{resourceType}"
            },
            produces = {NDJSON_CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Download a generated FHIR R4 NDJSON file (Patient.ndjson, Encounter.ndjson, Observation.ndjson, Practitioner.ndjson)",
            description = "Streams newline-delimited HL7 FHIR R4 resources (application/fhir+ndjson) for the exported patient cohort."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 NDJSON stream returned"),
            @ApiResponse(responseCode = "404", description = "Export job or resource type file not found")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_DOWNLOAD_NDJSON", resourceType = "BulkDataExport")
    public ResponseEntity<String> downloadExportNdjsonFile(
            @PathVariable UUID jobId,
            @PathVariable String resourceType,
            HttpServletResponse response) {
        String ndjson = bulkExportService.downloadNdjsonFile(jobId, resourceType);
        if (response != null) {
            response.setHeader("ETag", "W/\"" + ndjson.length() + "\"");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON_CONTENT_TYPE))
                .body(ndjson);
    }

    @GetMapping(
            value = "/api/v1/bulk-export/jobs/{jobId}/bundle",
            produces = {FHIR_JSON_CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE}
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Retrieve the entire exported patient cohort as an HL7 FHIR R4 collection Bundle")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 collection Bundle returned"),
            @ApiResponse(responseCode = "404", description = "Export job not found")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_DOWNLOAD_BUNDLE", resourceType = "BulkDataExport")
    public ResponseEntity<String> downloadExportAsBundle(
            @PathVariable UUID jobId,
            HttpServletResponse response) {
        Bundle bundle = bulkExportService.exportJobAsFhirBundle(jobId);
        if (response != null) {
            response.setHeader("ETag", "W/\"" + bundle.getTotal() + "\"");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(FHIR_JSON_CONTENT_TYPE))
                .body(bulkExportService.encodeFhirBundle(bundle));
    }

    @DeleteMapping(
            value = {"/api/v1/fhir/$export-status/{jobId}", "/api/v1/bulk-export/jobs/{jobId}"},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Cancel or delete a FHIR Bulk Data export job and purge cached NDJSON files")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Bulk Data export job deleted"),
            @ApiResponse(responseCode = "404", description = "Export job not found")
    })
    @ClinicalAudit(action = "FHIR_BULK_EXPORT_DELETE", resourceType = "BulkDataExport")
    public ResponseEntity<Map<String, Object>> deleteExportJob(@PathVariable UUID jobId) {
        bulkExportService.deleteExportJob(jobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "jobId", jobId.toString(),
                "status", "CANCELLED_AND_PURGED",
                "deletedAt", Instant.now().toString()
        ));
    }

    private ResponseEntity<Map<String, Object>> buildKickOffResponse(
            BulkExportJob job,
            boolean sync,
            String preferHeader,
            HttpServletResponse response) {
        String statusUrl = "/api/v1/fhir/$export-status/" + job.jobId();
        BulkExportManifest manifest = bulkExportService.getExportJobManifest(
                job.jobId(), "/api/v1/fhir/$export-files/" + job.jobId());

        if (response != null) {
            response.setHeader("Content-Location", statusUrl);
            response.setHeader("ETag", "W/\"" + manifest.totalExportedResources() + "\"");
        }

        boolean asyncPreferred = !sync || (preferHeader != null && preferHeader.toLowerCase(Locale.ROOT).contains("respond-async") && !sync);
        if (asyncPreferred) {
            Map<String, Object> body = new LinkedHashMap<>(toManifestMap(manifest));
            body.put("status", "ACCEPTED");
            body.put("statusPollingUrl", statusUrl);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header("Content-Location", statusUrl)
                    .body(body);
        }
        return ResponseEntity.ok()
                .header("Content-Location", statusUrl)
                .body(toManifestMap(manifest));
    }

    private static Map<String, Object> toManifestMap(BulkExportManifest manifest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", manifest.jobId().toString());
        map.put("facilityId", manifest.facilityId().toString());
        if (manifest.practitionerId() != null) {
            map.put("practitionerId", manifest.practitionerId().toString());
        }
        map.put("transactionTime", manifest.transactionTime().toString());
        map.put("request", manifest.request());
        map.put("requiresAccessToken", manifest.requiresAccessToken());
        map.put("cohortPatientCount", manifest.cohortPatientCount());
        map.put("totalExportedResources", manifest.totalExportedResources());
        map.put("output", manifest.output());
        map.put("error", manifest.error());
        return map;
    }

    private static UUID resolveFacilityId(UUID facilityParam, UUID headerFacilityId) {
        if (facilityParam != null) return facilityParam;
        if (headerFacilityId != null) return headerFacilityId;
        UUID ctxFacility = FacilityTenantInterceptor.currentFacility();
        return ctxFacility != null ? ctxFacility : new UUID(0L, 1L);
    }

    private static List<UUID> parseUuidList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<UUID> list = new ArrayList<>();
        for (String token : csv.split(",")) {
            if (!token.isBlank()) {
                list.add(UUID.fromString(token.trim()));
            }
        }
        return List.copyOf(list);
    }

    private static String resolveCurrentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        return "practitioner.export";
    }
}
