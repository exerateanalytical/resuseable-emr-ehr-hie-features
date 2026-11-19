/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Tenant SaaS Facility Onboarding, Module Licensing, Terminology Pack Versioning,
 * API Quota Metering, and Per-Facility Feature-Flag Governance Service.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class TenantGovernanceService {

    public enum DeploymentArchetype {
        CLINIC_EMR,
        HOSPITAL_EHR,
        REGIONAL_HIE,
        STANDALONE_PIS,
        STANDALONE_LIS,
        PUBLIC_HEALTH_CHW
    }

    public record OnboardTenantRequest(
            String facilityName,
            String countryIsoCode,
            String city,
            DeploymentArchetype archetype,
            String primaryDatabaseTarget,
            Integer apiRateLimitRpm,
            Set<String> enabledModules,
            Map<String, Boolean> initialFeatureFlags
    ) {}

    public record TenantGovernanceProfile(
            UUID facilityId,
            String tenantCode,
            String facilityName,
            String countryIsoCode,
            String city,
            DeploymentArchetype archetype,
            String primaryDatabaseTarget,
            String status,
            int apiRateLimitRpm,
            Set<String> licensedModules,
            Map<String, String> pinnedTerminologyVersions,
            Map<String, Boolean> featureFlags,
            Instant onboardedAt,
            Instant updatedAt
    ) {}

    public record FeatureFlagUpdateRequest(
            String flagKey,
            boolean enabled,
            String updatedBy
    ) {}

    public record TerminologyVersionPinRequest(
            String codeSystem,
            String versionTag
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, TenantGovernanceProfile> tenantsById = new ConcurrentHashMap<>();

    @Autowired
    public TenantGovernanceService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public TenantGovernanceService() {
        this(null);
    }

    /**
     * Provisions a new multi-tenant healthcare facility with default licensed modules,
     * terminology pack versions, API quotas, and clinical feature flags tailored to its archetype.
     */
    @ClinicalAudit(action = "TENANT_ONBOARD", resourceType = "Organization")
    public TenantGovernanceProfile onboardFacilityTenant(OnboardTenantRequest request) {
        if (request == null || request.facilityName() == null || request.facilityName().isBlank()) {
            throw new IllegalArgumentException("OnboardTenantRequest with facilityName is required");
        }
        UUID facilityId = UUID.randomUUID();
        DeploymentArchetype archetype = request.archetype() != null ? request.archetype() : DeploymentArchetype.HOSPITAL_EHR;
        String country = request.countryIsoCode() != null && !request.countryIsoCode().isBlank()
                ? request.countryIsoCode().trim().toUpperCase(Locale.ROOT)
                : "CM";
        String tenantCode = "OPES-" + country + "-" + facilityId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        int rpmQuota = (request.apiRateLimitRpm() != null && request.apiRateLimitRpm() > 0)
                ? request.apiRateLimitRpm()
                : defaultQuotaForArchetype(archetype);

        Set<String> modules = (request.enabledModules() != null && !request.enabledModules().isEmpty())
                ? Set.copyOf(request.enabledModules())
                : defaultModulesForArchetype(archetype);

        Map<String, Boolean> flags = new LinkedHashMap<>(defaultFlagsForArchetype(archetype));
        if (request.initialFeatureFlags() != null) {
            flags.putAll(request.initialFeatureFlags());
        }

        Map<String, String> terminologyVersions = Map.of(
                "LOINC", "2.78",
                "SNOMED-CT", "2026-03-01",
                "ICD-10", "WHO-2026",
                "RXNORM", "2026-04"
        );

        Instant now = Instant.now();
        TenantGovernanceProfile profile = new TenantGovernanceProfile(
                facilityId,
                tenantCode,
                request.facilityName().trim(),
                country,
                request.city() != null ? request.city().trim() : "Douala",
                archetype,
                request.primaryDatabaseTarget() != null ? request.primaryDatabaseTarget().trim().toUpperCase(Locale.ROOT) : "POSTGRESQL",
                "ACTIVE",
                rpmQuota,
                modules,
                terminologyVersions,
                Map.copyOf(flags),
                now,
                now
        );
        tenantsById.put(facilityId, profile);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    "SAAS_CONTROL_PLANE",
                    "ROLE_PLATFORM_ADMIN",
                    "TENANT_FACILITY_ONBOARDED",
                    "Organization",
                    facilityId.toString(),
                    null,
                    "POST",
                    "/api/v1/admin/tenants/onboard",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Onboarded tenant=" + tenantCode + " archetype=" + archetype + " modules=" + modules.size()
            ));
        }

        return profile;
    }

    public TenantGovernanceProfile updateFeatureFlag(UUID facilityId, FeatureFlagUpdateRequest request) {
        TenantGovernanceProfile existing = getTenantProfile(facilityId);
        if (request == null || request.flagKey() == null || request.flagKey().isBlank()) {
            throw new IllegalArgumentException("FeatureFlagUpdateRequest with flagKey is required");
        }
        Map<String, Boolean> updatedFlags = new LinkedHashMap<>(existing.featureFlags());
        updatedFlags.put(request.flagKey().trim(), request.enabled());

        TenantGovernanceProfile updated = new TenantGovernanceProfile(
                existing.facilityId(),
                existing.tenantCode(),
                existing.facilityName(),
                existing.countryIsoCode(),
                existing.city(),
                existing.archetype(),
                existing.primaryDatabaseTarget(),
                existing.status(),
                existing.apiRateLimitRpm(),
                existing.licensedModules(),
                existing.pinnedTerminologyVersions(),
                Map.copyOf(updatedFlags),
                existing.onboardedAt(),
                Instant.now()
        );
        tenantsById.put(facilityId, updated);
        return updated;
    }

    public TenantGovernanceProfile pinTerminologyVersion(UUID facilityId, TerminologyVersionPinRequest request) {
        TenantGovernanceProfile existing = getTenantProfile(facilityId);
        if (request == null || request.codeSystem() == null || request.versionTag() == null) {
            throw new IllegalArgumentException("codeSystem and versionTag are required");
        }
        Map<String, String> updatedVersions = new LinkedHashMap<>(existing.pinnedTerminologyVersions());
        updatedVersions.put(request.codeSystem().trim().toUpperCase(Locale.ROOT), request.versionTag().trim());

        TenantGovernanceProfile updated = new TenantGovernanceProfile(
                existing.facilityId(),
                existing.tenantCode(),
                existing.facilityName(),
                existing.countryIsoCode(),
                existing.city(),
                existing.archetype(),
                existing.primaryDatabaseTarget(),
                existing.status(),
                existing.apiRateLimitRpm(),
                existing.licensedModules(),
                Map.copyOf(updatedVersions),
                existing.featureFlags(),
                existing.onboardedAt(),
                Instant.now()
        );
        tenantsById.put(facilityId, updated);
        return updated;
    }

    public boolean isFeatureEnabled(UUID facilityId, String flagKey) {
        TenantGovernanceProfile profile = getTenantProfile(facilityId);
        return Boolean.TRUE.equals(profile.featureFlags().get(flagKey));
    }

    public TenantGovernanceProfile getTenantProfile(UUID facilityId) {
        TenantGovernanceProfile profile = tenantsById.get(facilityId);
        if (profile == null) {
            throw new NoSuchElementException("Facility tenant profile not found: " + facilityId);
        }
        return profile;
    }

    public List<TenantGovernanceProfile> listTenants() {
        return tenantsById.values().stream()
                .sorted(Comparator.comparing(TenantGovernanceProfile::onboardedAt).reversed())
                .toList();
    }

    private static int defaultQuotaForArchetype(DeploymentArchetype archetype) {
        return switch (archetype) {
            case REGIONAL_HIE -> 2400;
            case HOSPITAL_EHR -> 600;
            case CLINIC_EMR, STANDALONE_PIS, STANDALONE_LIS, PUBLIC_HEALTH_CHW -> 240;
        };
    }

    private static Set<String> defaultModulesForArchetype(DeploymentArchetype archetype) {
        return switch (archetype) {
            case CLINIC_EMR -> Set.of("mpi", "emr-clinical", "terminology-cdss", "workflow-engine", "consent-ds4p", "messaging");
            case HOSPITAL_EHR -> Set.of("mpi", "emr-clinical", "terminology-cdss", "workflow-engine", "pharmacy-inventory",
                    "lis-diagnostics", "radiology-mwl", "uhc-billing", "clearinghouse-x12", "bcma", "ams", "consent-ds4p", "care-plans");
            case REGIONAL_HIE -> Set.of("mpi", "fhir-hie", "federated-xca-udap", "ips-jws-qr", "shl", "bulk-export",
                    "consent-ds4p", "ecqm-measures", "dlq-replay");
            case STANDALONE_PIS -> Set.of("mpi", "pharmacy-inventory", "cold-chain", "bcma", "terminology-cdss");
            case STANDALONE_LIS -> Set.of("mpi", "lis-diagnostics", "terminology-cdss", "hl7-astm-dicom");
            case PUBLIC_HEALTH_CHW -> Set.of("mpi", "chw-homecare", "programs-immunization", "disease-surveillance",
                    "offline-sync-kmp", "messaging", "care-plans");
        };
    }

    private static Map<String, Boolean> defaultFlagsForArchetype(DeploymentArchetype archetype) {
        return Map.of(
                "ds4p.breakTheGlass.enabled", true,
                "cdsHooks.renalAndPgx.enabled", archetype != DeploymentArchetype.REGIONAL_HIE,
                "ips.jwsEd25519Qr.enabled", true,
                "offlineSync.threeWayMerge.enabled", true,
                "messaging.ussdFallback.enabled", true,
                "audit.sha256HashChain.enforced", true
        );
    }
}
