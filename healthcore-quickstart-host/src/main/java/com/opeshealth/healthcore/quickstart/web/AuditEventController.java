/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 AuditEvent & HIPAA/GDPR Compliance Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.AuditSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.HipaaGdprPatientComplianceReport;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService.ChainVerificationResult;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * HL7 FHIR R4 {@code AuditEvent} and HIPAA (§ 164.312(b) / § 164.528) &amp; GDPR
 * (Articles 15, 30, 32) compliance controller exposing persisted database audit logs,
 * SHA-256 hash-chain integrity verification, and patient disclosure reports.
 */
@RestController
@RequestMapping(produces = {"application/fhir+json", MediaType.APPLICATION_JSON_VALUE})
@Tag(
        name = "FHIR R4 AuditEvent & HIPAA/GDPR Compliance",
        description = "Tamper-evident SHA-256 hash-chained FHIR R4 AuditEvent search, chain integrity verification, and HIPAA § 164.528 / GDPR Art. 15 patient access disclosure reports"
)
public class AuditEventController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json;charset=UTF-8");

    private final FhirAuditLoggingService fhirAuditLoggingService;

    public AuditEventController(FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    /**
     * Searches persisted FHIR resource access events and returns an HL7 FHIR R4 {@code Bundle}
     * ({@code type: "searchset"}) of {@code AuditEvent} resources.
     */
    @GetMapping({"/api/v1/audit-events", "/api/v1/fhir/AuditEvent"})
    @PreAuthorize("hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')")
    @Operation(
            summary = "Search persisted HL7 FHIR R4 AuditEvent resources",
            description = "Queries database-persisted FHIR resource access audit events by facility, patient, resourceType, resourceId, actorId, action, outcome, and date window, returning an HL7 FHIR R4 searchset Bundle."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 Bundle (type=searchset) of AuditEvent resources returned"),
            @ApiResponse(responseCode = "400", description = "Invalid search filter parameter"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 / OIDC Bearer token"),
            @ApiResponse(responseCode = "403", description = "Caller lacks audit:read or facility:manage permission"),
            @ApiResponse(responseCode = "429", description = "Bucket4j rate limit exceeded")
    })
    public ResponseEntity<String> searchAuditEvents(
            @RequestHeader(value = "X-Facility-Id", required = false) String headerFacilityKey,
            @RequestParam(name = "facilityId", required = false) String queryFacilityKey,
            @RequestParam(name = "patient", required = false) UUID patientId,
            @RequestParam(name = "entity-type", required = false) String entityType,
            @RequestParam(name = "resourceType", required = false) String resourceType,
            @RequestParam(name = "entity", required = false) String entityId,
            @RequestParam(name = "resourceId", required = false) String resourceId,
            @RequestParam(name = "agent", required = false) String agentId,
            @RequestParam(name = "actor", required = false) String actorId,
            @RequestParam(name = "action", required = false) String action,
            @RequestParam(name = "outcome", required = false) String outcome,
            @RequestParam(name = "dateFrom", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "dateTo", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "_offset", defaultValue = "0") int offset,
            @RequestParam(name = "_count", defaultValue = "20") int count) {

        String resolvedFacility = resolveFacilityKey(queryFacilityKey, headerFacilityKey);
        String resolvedResourceType = resourceType != null && !resourceType.isBlank() ? resourceType : entityType;
        String resolvedResourceId = resourceId != null && !resourceId.isBlank() ? resourceId : entityId;
        String resolvedActor = actorId != null && !actorId.isBlank() ? actorId : agentId;

        AuditSearchCriteria criteria = new AuditSearchCriteria(
                resolvedFacility,
                patientId,
                resolvedResourceType,
                resolvedResourceId,
                resolvedActor,
                action,
                outcome,
                dateFrom,
                dateTo,
                offset,
                count
        );

        Bundle bundle = fhirAuditLoggingService.searchFhirAuditEventBundle(criteria);
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .body(fhirAuditLoggingService.encodeFhirResource(bundle));
    }

    /**
     * Retrieves a single persisted audit entry as an HL7 FHIR R4 {@code AuditEvent} resource.
     */
    @GetMapping({"/api/v1/audit-events/{id}", "/api/v1/fhir/AuditEvent/{id}"})
    @PreAuthorize("hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')")
    @Operation(
            summary = "Read a single HL7 FHIR R4 AuditEvent by UUID",
            description = "Returns the immutable database-persisted AuditEvent resource including its SHA-256 prevHash, entryHash, sequenceNumber, and correlationId."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FHIR R4 AuditEvent resource returned with ETag header"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 / OIDC Bearer token"),
            @ApiResponse(responseCode = "403", description = "Caller lacks audit:read permission"),
            @ApiResponse(responseCode = "404", description = "FHIR AuditEvent UUID not found in database"),
            @ApiResponse(responseCode = "429", description = "Bucket4j rate limit exceeded")
    })
    public ResponseEntity<String> getAuditEventById(@PathVariable("id") UUID id) {
        AuditEvent auditEvent = fhirAuditLoggingService.getFhirAuditEventById(id);
        String version = auditEvent.hasMeta() && auditEvent.getMeta().hasVersionId()
                ? auditEvent.getMeta().getVersionId()
                : "1";
        return ResponseEntity.ok()
                .contentType(FHIR_JSON)
                .header(HttpHeaders.ETAG, "W/\"" + version + "\"")
                .body(fhirAuditLoggingService.encodeFhirResource(auditEvent));
    }

    /**
     * Cryptographically verifies the SHA-256 hash chain ({@code prev_hash -> entry_hash})
     * across all persisted audit entries for a facility.
     */
    @GetMapping(value = "/api/v1/audit-events/verify-chain", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage')")
    @Operation(
            summary = "Verify SHA-256 cryptographic hash chain integrity of persisted audit logs",
            description = "Recomputes every SHA-256 link in the facility's database audit ledger (hc_audit_logs) to prove zero tampering under HIPAA § 164.312(c)(1) and GDPR Art. 32."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cryptographic chain verification result returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 / OIDC Bearer token"),
            @ApiResponse(responseCode = "403", description = "Caller lacks audit:read or facility:manage permission"),
            @ApiResponse(responseCode = "429", description = "Bucket4j rate limit exceeded")
    })
    public ResponseEntity<ChainVerificationResult> verifyFacilityAuditChain(
            @RequestHeader(value = "X-Facility-Id", required = false) String headerFacilityKey,
            @RequestParam(name = "facilityKey", required = false) String queryFacilityKey) {
        String resolvedFacility = resolveFacilityKey(queryFacilityKey, headerFacilityKey);
        return ResponseEntity.ok(fhirAuditLoggingService.verifyFacilityHashChain(resolvedFacility));
    }

    /**
     * Generates a HIPAA § 164.528 Accounting of Disclosures and GDPR Article 15
     * Data Subject Access Report (DSAR) for a specific patient UUID.
     */
    @GetMapping(value = "/api/v1/audit-events/compliance-report/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('PERM_audit:read') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')")
    @Operation(
            summary = "Generate HIPAA § 164.528 & GDPR Article 15 Patient Access Disclosure Report",
            description = "Compiles a complete patient-centric audit disclosure report detailing every FHIR resource access, actor, role, timestamp, outcome, and SHA-256 chain integrity proof."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "HIPAA/GDPR Patient Access Compliance Report returned"),
            @ApiResponse(responseCode = "400", description = "Invalid patient UUID"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid OAuth2 / OIDC Bearer token"),
            @ApiResponse(responseCode = "403", description = "Caller lacks required audit/patient access permission"),
            @ApiResponse(responseCode = "429", description = "Bucket4j rate limit exceeded")
    })
    public ResponseEntity<HipaaGdprPatientComplianceReport> getPatientComplianceReport(
            @PathVariable("patientId") UUID patientId,
            @RequestHeader(value = "X-Facility-Id", required = false) String headerFacilityKey,
            @RequestParam(name = "facilityKey", required = false) String queryFacilityKey) {
        String resolvedFacility = resolveFacilityKey(queryFacilityKey, headerFacilityKey);
        return ResponseEntity.ok(fhirAuditLoggingService.generatePatientComplianceReport(resolvedFacility, patientId));
    }

    private static String resolveFacilityKey(String queryFacilityKey, String headerFacilityKey) {
        if (queryFacilityKey != null && !queryFacilityKey.isBlank()) {
            return queryFacilityKey.trim();
        }
        if (headerFacilityKey != null && !headerFacilityKey.isBlank()) {
            return headerFacilityKey.trim();
        }
        return FacilityContext.getFacilityId().map(UUID::toString).orElse("GLOBAL");
    }
}
