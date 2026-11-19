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

import com.opeshealth.healthcore.quickstart.service.TenantGovernanceService;
import com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.FeatureFlagUpdateRequest;
import com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.OnboardTenantRequest;
import com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.TenantGovernanceProfile;
import com.opeshealth.healthcore.quickstart.service.TenantGovernanceService.TerminologyVersionPinRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Multi-Tenant SaaS Facility Onboarding, Module Licensing,
 * Terminology Pack Pinning, and Per-Facility Feature Flags.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@Tag(name = "Multi-Tenant SaaS Onboarding & Feature-Flag Governance",
        description = "Self-service healthcare facility onboarding, archetype licensing (EMR/EHR/HIE/PIS/LIS), API quota plans, terminology pack versioning, and per-facility feature flags")
public class TenantGovernanceController {

    private final TenantGovernanceService tenantService;

    public TenantGovernanceController(TenantGovernanceService tenantService) {
        this.tenantService = Objects.requireNonNull(tenantService, "TenantGovernanceService is required");
    }

    @PostMapping(value = "/onboard", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Provision a new multi-tenant healthcare facility (CLINIC_EMR, HOSPITAL_EHR, REGIONAL_HIE, PIS, LIS, CHW)")
    public ResponseEntity<TenantGovernanceProfile> onboardTenant(
            @RequestBody OnboardTenantRequest request) {
        return ResponseEntity.status(201).body(tenantService.onboardFacilityTenant(request));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List all onboarded facility tenants")
    public ResponseEntity<List<TenantGovernanceProfile>> listTenants() {
        return ResponseEntity.ok(tenantService.listTenants());
    }

    @GetMapping(value = "/{facilityId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get governance profile, licensed modules, and feature flags for a facility tenant")
    public ResponseEntity<TenantGovernanceProfile> getTenant(@PathVariable("facilityId") UUID facilityId) {
        return ResponseEntity.ok(tenantService.getTenantProfile(facilityId));
    }

    @PutMapping(value = "/{facilityId}/feature-flags", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Toggle a clinical or platform feature flag for a specific facility tenant")
    public ResponseEntity<TenantGovernanceProfile> updateFeatureFlag(
            @PathVariable("facilityId") UUID facilityId,
            @RequestBody FeatureFlagUpdateRequest request) {
        return ResponseEntity.ok(tenantService.updateFeatureFlag(facilityId, request));
    }

    @GetMapping(value = "/{facilityId}/feature-flags/{flagKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate whether a feature flag is active for a facility tenant")
    public ResponseEntity<Map<String, Object>> checkFeatureFlag(
            @PathVariable("facilityId") UUID facilityId,
            @PathVariable("flagKey") String flagKey) {
        return ResponseEntity.ok(Map.of(
                "facilityId", facilityId,
                "flagKey", flagKey,
                "enabled", tenantService.isFeatureEnabled(facilityId, flagKey)
        ));
    }

    @PutMapping(value = "/{facilityId}/terminology-versions", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pin a specific LOINC, SNOMED-CT, ICD-10, or RxNorm terminology release version for a facility")
    public ResponseEntity<TenantGovernanceProfile> pinTerminologyVersion(
            @PathVariable("facilityId") UUID facilityId,
            @RequestBody TerminologyVersionPinRequest request) {
        return ResponseEntity.ok(tenantService.pinTerminologyVersion(facilityId, request));
    }
}
