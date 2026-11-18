/*
 * =============================================================================
 * Opes HealthCore SDK — Server Module FHIR R4 Bundle Batch/Transaction Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.BundleService;
import com.opeshealth.healthcore.quickstart.service.BundleService.BundleExecutionResult;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Server-module REST controller exposing HL7 FHIR R4 {@code Bundle} batch and
 * atomic transaction endpoints ({@code POST /api/v1/fhir} and {@code POST /api/v1/bundles}),
 * backed by {@link BundleService}, audited by {@link PatientAuditAspect}, and
 * propagating aggregated CDS Hooks 2.0 clinical alert headers.
 */
@RestController
@Tag(name = "FHIR R4 Bundle (Batch & Transaction)", description = "Atomic HL7 FHIR R4 Transaction and independent Batch Bundle processing across Patient, Practitioner, Encounter, and Observation")
public class BundleController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final BundleService bundleService;

    public BundleController(BundleService bundleService) {
        this.bundleService = bundleService;
    }

    @PostMapping(
            value = {"/api/v1/fhir", "/api/v1/bundles"},
            consumes = {"application/fhir+json", "application/json"},
            produces = {"application/fhir+json", "application/json"}
    )
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Process an HL7 FHIR R4 transaction (atomic) or batch Bundle across Patient, Practitioner, Encounter, and Observation",
            description = "Executes a FHIR R4 Bundle of type 'transaction' (all-or-nothing atomic commit with urn:uuid cross-reference resolution) "
                    + "or 'batch' (isolated per-entry savepoints with embedded OperationOutcome on individual entry failures)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 transaction-response or batch-response Bundle",
                    headers = {
                            @Header(name = "X-CDS-Hooks-Alert-Count", description = "Total CDS Hooks 2.0 clinical alert cards triggered across bundle entries"),
                            @Header(name = "X-CDS-Hooks-Max-Indicator", description = "Highest CDS Hooks 2.0 severity indicator (info | warning | critical)")
                    },
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirBundleResource"))),
            @ApiResponse(responseCode = "400", description = "Atomic transaction rollback due to HAPI FHIR R4 validation failure or invalid Bundle structure",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer JWT",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "403", description = "Insufficient permission to execute FHIR Bundle operations",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Referenced FHIR resource in atomic transaction not found",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "409", description = "Duplicate identifier or concurrent version conflict during atomic transaction",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "412", description = "If-Match ETag precondition failed on transaction PUT entry",
                    content = @Content(mediaType = "application/json", schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
            @ApiResponse(responseCode = "429", description = "Bucket4j Bundle rate limit exceeded (30 requests/minute default)",
                    content = @Content(mediaType = "application/fhir+json", schema = @Schema(ref = "#/components/schemas/FhirOperationOutcome")))
    })
    public ResponseEntity<String> processBundle(
            @RequestParam(required = false) UUID facilityId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirBundleJson) {
        UUID scopedFacility = facilityId != null
                ? facilityId
                : (headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility());

        BundleExecutionResult result = bundleService.processBundleWithSummary(scopedFacility, fhirBundleJson);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(FHIR_JSON);
        if (result.cdsAlertCount() > 0) {
            builder.header("X-CDS-Hooks-Alert-Count", Integer.toString(result.cdsAlertCount()));
            builder.header("X-CDS-Hooks-Max-Indicator", result.maxCdsIndicator());
        }
        return builder.body(bundleService.encodeFhirResource(result.responseBundle()));
    }
}
