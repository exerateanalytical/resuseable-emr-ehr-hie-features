/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 CDS Hooks 2.0 REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackReceipt;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsFeedbackRequest;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookRequest;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.ServiceDiscoveryResponse;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * HL7 CDS Hooks 2.0 REST controller exposing standard service discovery,
 * hook invocation, and card feedback endpoints without colliding with
 * legacy terminology {@code /cds-services} endpoints.
 */
@RestController
@Tag(name = "CDS Hooks 2.0 Specification API", description = "HL7 CDS Hooks 2.0 discovery, hook execution, and clinician feedback endpoints")
public class CdsHooksV2Controller {

    private final CdsHooksService cdsHooksService;

    public CdsHooksV2Controller(CdsHooksService cdsHooksService) {
        this.cdsHooksService = cdsHooksService;
    }

    @GetMapping("/api/v1/cds-services")
    @Operation(summary = "CDS Hooks 2.0 Service Discovery")
    public ResponseEntity<ServiceDiscoveryResponse> discoverServices() {
        return ResponseEntity.ok(cdsHooksService.discoverServices());
    }

    @PostMapping("/api/v1/cds-services/{serviceId}")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_order:draft') or hasAuthority('PERM_order:sign')")
    @Operation(summary = "Invoke an HL7 CDS Hooks 2.0 clinical decision support service")
    public ResponseEntity<CdsHookResponse> invokeService(
            @PathVariable String serviceId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody CdsHookRequest request) {
        UUID scopedFacility = headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility();
        return ResponseEntity.ok(cdsHooksService.invokeService(serviceId, request, scopedFacility));
    }

    @PostMapping("/cds-services/opes-patient-care-gaps")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Invoke CDS Hooks 2.0 patient-view care gaps service")
    public ResponseEntity<CdsHookResponse> invokePatientCareGaps(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody CdsHookRequest request) {
        UUID scopedFacility = headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility();
        return ResponseEntity.ok(cdsHooksService.invokeService(CdsHooksService.SERVICE_PATIENT_CARE_GAPS, request, scopedFacility));
    }

    @PostMapping("/cds-services/opes-observation-alerts")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter')")
    @Operation(summary = "Invoke CDS Hooks 2.0 observation clinical alerts service")
    public ResponseEntity<CdsHookResponse> invokeObservationAlerts(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody CdsHookRequest request) {
        UUID scopedFacility = headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility();
        return ResponseEntity.ok(cdsHooksService.invokeService(CdsHooksService.SERVICE_OBSERVATION_ALERTS, request, scopedFacility));
    }

    @PostMapping("/cds-services/opes-encounter-triage")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    @Operation(summary = "Invoke CDS Hooks 2.0 encounter-start triage service")
    public ResponseEntity<CdsHookResponse> invokeEncounterTriage(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody CdsHookRequest request) {
        UUID scopedFacility = headerFacilityId != null ? headerFacilityId : FacilityTenantInterceptor.currentFacility();
        return ResponseEntity.ok(cdsHooksService.invokeService(CdsHooksService.SERVICE_ENCOUNTER_TRIAGE, request, scopedFacility));
    }

    @PostMapping({"/api/v1/cds-services/{serviceId}/feedback", "/cds-services/{serviceId}/feedback"})
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_order:sign')")
    @Operation(summary = "Record CDS Hooks 2.0 card acceptance or override feedback")
    public ResponseEntity<CdsFeedbackReceipt> submitFeedback(
            @PathVariable String serviceId,
            @RequestBody CdsFeedbackRequest feedbackRequest) {
        return ResponseEntity.ok(cdsHooksService.recordFeedback(serviceId, feedbackRequest));
    }
}
