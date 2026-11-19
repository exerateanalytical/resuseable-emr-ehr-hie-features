/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-Tenant Governance Service, Guard Filter & REST API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.governance.service;

import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates multi-tenant organization/facility onboarding, lifecycle enforcement
 * ({@code ACTIVE}, {@code READ_ONLY}, {@code SUSPENDED}, {@code OFFBOARDED}), durable
 * feature flags and kill switches, and time-bound cross-facility access grants.
 */
public class TenantGovernanceService {

    private static final Duration MAX_GRANT_DURATION = Duration.ofDays(30);
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    public interface OrganizationRepository extends JpaRepository<TenantOrganizationEntity, UUID> {
        Optional<TenantOrganizationEntity> findByOrgCode(String orgCode);
    }

    public interface FacilityRepository extends JpaRepository<TenantFacilityEntity, UUID> {
        Optional<TenantFacilityEntity> findByFacilityCode(String facilityCode);
        List<TenantFacilityEntity> findByOrganizationIdOrderByNameAsc(UUID organizationId);
    }

    public interface DepartmentRepository extends JpaRepository<FacilityDepartmentEntity, UUID> {
        List<FacilityDepartmentEntity> findByFacilityIdAndActiveTrueOrderByNameAsc(UUID facilityId);
        Optional<FacilityDepartmentEntity> findByFacilityIdAndDepartmentCode(UUID facilityId, String departmentCode);
    }

    public interface FeatureFlagRepository extends JpaRepository<FacilityFeatureFlagEntity, UUID> {
        Optional<FacilityFeatureFlagEntity> findByFacilityIdAndFlagKey(UUID facilityId, String flagKey);
        List<FacilityFeatureFlagEntity> findByFacilityIdOrderByFlagKeyAsc(UUID facilityId);
    }

    public interface CrossFacilityGrantRepository extends JpaRepository<CrossFacilityAccessGrantEntity, UUID> {
        List<CrossFacilityAccessGrantEntity> findBySourceFacilityIdOrderByCreatedAtDesc(UUID sourceFacilityId);
        List<CrossFacilityAccessGrantEntity> findBySourceFacilityIdAndTargetFacilityIdAndGranteeActorIdAndRevokedFalse(
                UUID sourceFacilityId, UUID targetFacilityId, String granteeActorId);
    }

    public record CreateOrganizationRequest(String orgCode, String name, String organizationType, String countryIso) {}

    public record OnboardFacilityRequest(
            UUID organizationId,
            String facilityCode,
            String name,
            String facilityType,
            String countryIso,
            String timezoneId,
            String currencyCode,
            String defaultLocale,
            boolean activateImmediately) {}

    public record FacilityStatusTransitionRequest(FacilityLifecycleStatus targetStatus, String reason) {}

    public record CreateDepartmentRequest(
            UUID parentDepartmentId, String departmentCode, String name, String departmentType, int bedCapacity) {}

    public record FeatureFlagUpdateRequest(String flagKey, boolean enabled, boolean killSwitch) {}

    public record IssueCrossFacilityGrantRequest(
            UUID targetFacilityId,
            String granteeActorId,
            UUID patientId,
            String grantScope,
            String reason,
            Instant expiresAt) {}

    private final OrganizationRepository organizationRepository;
    private final FacilityRepository facilityRepository;
    private final DepartmentRepository departmentRepository;
    private final FeatureFlagRepository featureFlagRepository;
    private final CrossFacilityGrantRepository grantRepository;
    private final ClinicalAuditService auditService;

    public TenantGovernanceService(
            OrganizationRepository organizationRepository,
            FacilityRepository facilityRepository,
            DepartmentRepository departmentRepository,
            FeatureFlagRepository featureFlagRepository,
            CrossFacilityGrantRepository grantRepository,
            ClinicalAuditService auditService) {
        this.organizationRepository = organizationRepository;
        this.facilityRepository = facilityRepository;
        this.departmentRepository = departmentRepository;
        this.featureFlagRepository = featureFlagRepository;
        this.grantRepository = grantRepository;
        this.auditService = auditService;
    }

    @Transactional
    @ClinicalAudit(action = "GOVERNANCE_CREATE_ORG", resourceType = "Organization")
    public TenantOrganizationEntity createOrganization(CreateOrganizationRequest req) {
        if (req == null || isBlank(req.orgCode()) || isBlank(req.name())) {
            throw new IllegalArgumentException("Organization code and name are required.");
        }
        String normalizedCode = req.orgCode().trim().toUpperCase(Locale.ROOT);
        organizationRepository.findByOrgCode(normalizedCode).ifPresent(existing -> {
            throw new IllegalStateException("Organization code already exists: " + normalizedCode);
        });
        TenantOrganizationEntity entity = new TenantOrganizationEntity();
        entity.setOrgCode(normalizedCode);
        entity.setName(req.name().trim());
        entity.setOrganizationType(isBlank(req.organizationType()) ? "HOSPITAL_NETWORK" : req.organizationType().trim().toUpperCase(Locale.ROOT));
        entity.setCountryIso(isBlank(req.countryIso()) ? "CM" : req.countryIso().trim().toUpperCase(Locale.ROOT));
        return organizationRepository.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "GOVERNANCE_ONBOARD_FACILITY", resourceType = "Facility")
    public TenantFacilityEntity onboardFacility(OnboardFacilityRequest req) {
        if (req == null || req.organizationId() == null || isBlank(req.facilityCode()) || isBlank(req.name())) {
            throw new IllegalArgumentException("organizationId, facilityCode, and name are required.");
        }
        organizationRepository.findById(req.organizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + req.organizationId()));
        String normalizedCode = req.facilityCode().trim().toUpperCase(Locale.ROOT);
        facilityRepository.findByFacilityCode(normalizedCode).ifPresent(existing -> {
            throw new IllegalStateException("Facility code already exists: " + normalizedCode);
        });

        TenantFacilityEntity facility = new TenantFacilityEntity();
        facility.setOrganizationId(req.organizationId());
        facility.setFacilityCode(normalizedCode);
        facility.setName(req.name().trim());
        facility.setFacilityType(isBlank(req.facilityType()) ? "DISTRICT_HOSPITAL" : req.facilityType().trim().toUpperCase(Locale.ROOT));
        facility.setCountryIso(isBlank(req.countryIso()) ? "CM" : req.countryIso().trim().toUpperCase(Locale.ROOT));
        facility.setTimezoneId(isBlank(req.timezoneId()) ? "Africa/Douala" : req.timezoneId().trim());
        facility.setCurrencyCode(isBlank(req.currencyCode()) ? "XAF" : req.currencyCode().trim().toUpperCase(Locale.ROOT));
        facility.setDefaultLocale(isBlank(req.defaultLocale()) ? "en" : req.defaultLocale().trim().toLowerCase(Locale.ROOT));
        if (req.activateImmediately()) {
            facility.setLifecycleStatus(FacilityLifecycleStatus.ACTIVE);
            facility.setOnboardedAt(Instant.now());
        } else {
            facility.setLifecycleStatus(FacilityLifecycleStatus.ONBOARDING);
        }
        return facilityRepository.save(facility);
    }

    @Transactional
    public TenantFacilityEntity transitionFacilityStatus(
            UUID facilityId, FacilityLifecycleStatus targetStatus, String reason, String actorId) {
        if (facilityId == null || targetStatus == null) {
            throw new IllegalArgumentException("facilityId and targetStatus are required.");
        }
        TenantFacilityEntity facility = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new IllegalArgumentException("Facility not found: " + facilityId));
        if (facility.getLifecycleStatus() == FacilityLifecycleStatus.OFFBOARDED
                && targetStatus != FacilityLifecycleStatus.OFFBOARDED) {
            throw new IllegalStateException("Offboarded facilities cannot be reactivated.");
        }
        FacilityLifecycleStatus previous = facility.getLifecycleStatus();
        facility.setLifecycleStatus(targetStatus);
        facility.setStatusReason(reason);
        facility.setUpdatedAt(Instant.now());
        if (targetStatus == FacilityLifecycleStatus.ACTIVE && facility.getOnboardedAt() == null) {
            facility.setOnboardedAt(Instant.now());
        }
        TenantFacilityEntity saved = facilityRepository.save(facility);
        if (auditService != null) {
            auditService.recordEvent(
                    facilityId.toString(),
                    actorId != null ? actorId : "SYSTEM",
                    "GOVERNANCE",
                    "FACILITY_LIFECYCLE_" + previous + "_TO_" + targetStatus,
                    "Facility",
                    facilityId.toString(),
                    null,
                    "SUCCESS",
                    reason,
                    "internal");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<TenantFacilityEntity> findFacility(UUID facilityId) {
        return facilityId == null ? Optional.empty() : facilityRepository.findById(facilityId);
    }

    @Transactional
    @ClinicalAudit(action = "GOVERNANCE_CREATE_DEPARTMENT", resourceType = "Department")
    public FacilityDepartmentEntity createDepartment(UUID facilityId, CreateDepartmentRequest req) {
        if (facilityId == null || req == null || isBlank(req.departmentCode()) || isBlank(req.name())) {
            throw new IllegalArgumentException("facilityId, departmentCode, and name are required.");
        }
        String code = req.departmentCode().trim().toUpperCase(Locale.ROOT);
        departmentRepository.findByFacilityIdAndDepartmentCode(facilityId, code).ifPresent(existing -> {
            throw new IllegalStateException("Department code already exists in facility: " + code);
        });
        FacilityDepartmentEntity dept = new FacilityDepartmentEntity();
        dept.setFacilityId(facilityId);
        dept.setParentDepartmentId(req.parentDepartmentId());
        dept.setDepartmentCode(code);
        dept.setName(req.name().trim());
        dept.setDepartmentType(isBlank(req.departmentType()) ? "OUTPATIENT" : req.departmentType().trim().toUpperCase(Locale.ROOT));
        dept.setBedCapacity(Math.max(0, req.bedCapacity()));
        return departmentRepository.save(dept);
    }

    @Transactional(readOnly = true)
    public List<FacilityDepartmentEntity> listDepartments(UUID facilityId) {
        return departmentRepository.findByFacilityIdAndActiveTrueOrderByNameAsc(facilityId);
    }

    @Transactional
    public FacilityFeatureFlagEntity upsertFeatureFlag(
            UUID facilityId, String flagKey, boolean enabled, boolean killSwitch, String actorId) {
        if (facilityId == null || isBlank(flagKey)) {
            throw new IllegalArgumentException("facilityId and flagKey are required.");
        }
        String normalizedKey = flagKey.trim().toLowerCase(Locale.ROOT);
        FacilityFeatureFlagEntity flag = featureFlagRepository.findByFacilityIdAndFlagKey(facilityId, normalizedKey)
                .orElseGet(() -> {
                    FacilityFeatureFlagEntity created = new FacilityFeatureFlagEntity();
                    created.setFacilityId(facilityId);
                    created.setFlagKey(normalizedKey);
                    return created;
                });
        flag.setEnabled(enabled);
        flag.setKillSwitch(killSwitch);
        flag.setUpdatedBy(actorId != null ? actorId : "SYSTEM");
        flag.setUpdatedAt(Instant.now());
        FacilityFeatureFlagEntity saved = featureFlagRepository.save(flag);
        if (auditService != null) {
            auditService.recordEvent(
                    facilityId.toString(),
                    flag.getUpdatedBy(),
                    "GOVERNANCE",
                    killSwitch ? "FEATURE_KILL_SWITCH_ACTIVE" : "FEATURE_FLAG_UPDATED",
                    "FeatureFlag",
                    normalizedKey,
                    null,
                    "SUCCESS",
                    "enabled=" + enabled + ", killSwitch=" + killSwitch,
                    "internal");
        }
        return saved;
    }

    /**
     * Evaluates a facility feature flag. An active {@code killSwitch} always forces {@code false}.
     */
    @Transactional(readOnly = true)
    public boolean isFeatureEnabled(UUID facilityId, String flagKey, boolean defaultValue) {
        if (facilityId == null || isBlank(flagKey)) {
            return defaultValue;
        }
        return featureFlagRepository.findByFacilityIdAndFlagKey(facilityId, flagKey.trim().toLowerCase(Locale.ROOT))
                .map(flag -> !flag.isKillSwitch() && flag.isEnabled())
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public List<FacilityFeatureFlagEntity> listFeatureFlags(UUID facilityId) {
        return featureFlagRepository.findByFacilityIdOrderByFlagKeyAsc(facilityId);
    }

    @Transactional
    public CrossFacilityAccessGrantEntity issueCrossFacilityGrant(
            UUID sourceFacilityId, IssueCrossFacilityGrantRequest req, String grantedBy) {
        if (sourceFacilityId == null || req == null || req.targetFacilityId() == null) {
            throw new IllegalArgumentException("sourceFacilityId and targetFacilityId are required.");
        }
        if (sourceFacilityId.equals(req.targetFacilityId())) {
            throw new IllegalArgumentException("Cross-facility grant target must differ from source facility.");
        }
        if (isBlank(req.granteeActorId()) || isBlank(req.grantScope()) || isBlank(req.reason())) {
            throw new IllegalArgumentException("granteeActorId, grantScope, and reason are required.");
        }
        Instant now = Instant.now();
        if (req.expiresAt() == null || !req.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be a future timestamp.");
        }
        if (Duration.between(now, req.expiresAt()).compareTo(MAX_GRANT_DURATION) > 0) {
            throw new IllegalArgumentException("Cross-facility grant duration cannot exceed 30 days.");
        }

        CrossFacilityAccessGrantEntity grant = new CrossFacilityAccessGrantEntity();
        grant.setSourceFacilityId(sourceFacilityId);
        grant.setTargetFacilityId(req.targetFacilityId());
        grant.setGranteeActorId(req.granteeActorId().trim());
        grant.setPatientId(req.patientId());
        grant.setGrantScope(req.grantScope().trim().toUpperCase(Locale.ROOT));
        grant.setReason(req.reason().trim());
        grant.setGrantedBy(grantedBy != null ? grantedBy : "SYSTEM");
        grant.setExpiresAt(req.expiresAt());
        CrossFacilityAccessGrantEntity saved = grantRepository.save(grant);

        if (auditService != null) {
            auditService.recordEvent(
                    sourceFacilityId.toString(),
                    grant.getGrantedBy(),
                    "GOVERNANCE",
                    "CROSS_FACILITY_GRANT_ISSUED",
                    "CrossFacilityAccessGrant",
                    saved.getId().toString(),
                    req.patientId(),
                    "SUCCESS",
                    "target=" + req.targetFacilityId() + ", grantee=" + grant.getGranteeActorId() + ", scope=" + grant.getGrantScope(),
                    "internal");
        }
        return saved;
    }

    @Transactional
    public CrossFacilityAccessGrantEntity revokeCrossFacilityGrant(UUID sourceFacilityId, UUID grantId, String actorId) {
        CrossFacilityAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("Cross-facility grant not found: " + grantId));
        if (!grant.getSourceFacilityId().equals(sourceFacilityId)) {
            throw new AccessDeniedException("Cannot revoke a grant belonging to another facility.");
        }
        grant.setRevoked(true);
        grant.setRevokedBy(actorId != null ? actorId : "SYSTEM");
        grant.setRevokedAt(Instant.now());
        CrossFacilityAccessGrantEntity saved = grantRepository.save(grant);
        if (auditService != null) {
            auditService.recordEvent(
                    sourceFacilityId.toString(),
                    grant.getRevokedBy(),
                    "GOVERNANCE",
                    "CROSS_FACILITY_GRANT_REVOKED",
                    "CrossFacilityAccessGrant",
                    saved.getId().toString(),
                    saved.getPatientId(),
                    "SUCCESS",
                    "Revoked explicit cross-facility grant",
                    "internal");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveCrossFacilityAccess(
            UUID sourceFacilityId, UUID targetFacilityId, String granteeActorId, UUID patientId, String requiredScope) {
        if (sourceFacilityId == null || targetFacilityId == null || isBlank(granteeActorId) || isBlank(requiredScope)) {
            return false;
        }
        Instant now = Instant.now();
        String normalizedScope = requiredScope.trim().toUpperCase(Locale.ROOT);
        List<CrossFacilityAccessGrantEntity> candidates =
                grantRepository.findBySourceFacilityIdAndTargetFacilityIdAndGranteeActorIdAndRevokedFalse(
                        sourceFacilityId, targetFacilityId, granteeActorId.trim());
        for (CrossFacilityAccessGrantEntity grant : candidates) {
            if (grant.getExpiresAt().isAfter(now)
                    && normalizedScope.equals(grant.getGrantScope())
                    && (grant.getPatientId() == null || grant.getPatientId().equals(patientId))) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<CrossFacilityAccessGrantEntity> listGrantsForSourceFacility(UUID sourceFacilityId) {
        return grantRepository.findBySourceFacilityIdOrderByCreatedAtDesc(sourceFacilityId);
    }

    /**
     * Evaluates whether an HTTP request targeting {@code facilityId} is permitted under
     * the facility's lifecycle status.
     */
    @Transactional(readOnly = true)
    public Optional<FacilityGuardRejection> evaluateRequestAccess(UUID facilityId, String httpMethod, String requestPath) {
        if (facilityId == null) {
            return Optional.empty();
        }
        if (requestPath != null && requestPath.startsWith("/api/v1/governance/")) {
            return Optional.empty();
        }
        Optional<TenantFacilityEntity> facilityOpt = facilityRepository.findById(facilityId);
        if (facilityOpt.isEmpty()) {
            return Optional.empty();
        }
        FacilityLifecycleStatus status = facilityOpt.get().getLifecycleStatus();
        if (status == FacilityLifecycleStatus.SUSPENDED || status == FacilityLifecycleStatus.OFFBOARDED) {
            return Optional.of(new FacilityGuardRejection(
                    HttpStatus.FORBIDDEN,
                    "Facility access blocked: facility status is " + status + "."));
        }
        if (status == FacilityLifecycleStatus.READ_ONLY && httpMethod != null
                && MUTATING_METHODS.contains(httpMethod.toUpperCase(Locale.ROOT))) {
            return Optional.of(new FacilityGuardRejection(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Facility is currently in READ_ONLY maintenance mode; write operations are temporarily disabled."));
        }
        return Optional.empty();
    }

    public record FacilityGuardRejection(HttpStatus status, String detail) {}

    /**
     * Servlet filter enforcing facility suspension (403) and read-only maintenance mode (503)
     * using durable database state.
     */
    public static class TenantGovernanceGuardFilter extends OncePerRequestFilter {
        private final TenantGovernanceService governanceService;

        public TenantGovernanceGuardFilter(TenantGovernanceService governanceService) {
            this.governanceService = governanceService;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            Optional<UUID> facilityOpt = FacilityContext.getFacilityId();
            if (facilityOpt.isPresent()) {
                Optional<FacilityGuardRejection> rejection = governanceService.evaluateRequestAccess(
                        facilityOpt.get(), request.getMethod(), request.getRequestURI());
                if (rejection.isPresent()) {
                    FacilityGuardRejection denied = rejection.get();
                    response.setStatus(denied.status().value());
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    String body = "{\"type\":\"https://opeshealthsystems.com/errors/facility-governance\","
                            + "\"title\":\"" + denied.status().getReasonPhrase() + "\","
                            + "\"status\":" + denied.status().value() + ","
                            + "\"detail\":\"" + denied.detail().replace("\"", "\\\"") + "\"}";
                    response.getWriter().write(body);
                    return;
                }
            }
            filterChain.doFilter(request, response);
        }
    }

    @RestController
    @RequestMapping("/api/v1/governance")
    public static class TenantGovernanceController {
        private final TenantGovernanceService service;

        public TenantGovernanceController(TenantGovernanceService service) {
            this.service = service;
        }

        @PostMapping("/organizations")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:onboard')")
        public TenantOrganizationEntity createOrganization(@RequestBody CreateOrganizationRequest req) {
            return service.createOrganization(req);
        }

        @PostMapping("/facilities")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:onboard')")
        public TenantFacilityEntity onboardFacility(@RequestBody OnboardFacilityRequest req) {
            return service.onboardFacility(req);
        }

        @PostMapping("/facilities/{facilityId}/status")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        public TenantFacilityEntity transitionStatus(
                @PathVariable UUID facilityId,
                @RequestBody FacilityStatusTransitionRequest req,
                Authentication auth) {
            return service.transitionFacilityStatus(
                    facilityId, req.targetStatus(), req.reason(), auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/departments")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        public FacilityDepartmentEntity createDepartment(@RequestBody CreateDepartmentRequest req) {
            return service.createDepartment(requireActiveFacility(), req);
        }

        @GetMapping("/departments")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_patient:read')")
        public List<FacilityDepartmentEntity> listDepartments() {
            return service.listDepartments(requireActiveFacility());
        }

        @PutMapping("/feature-flags")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        public FacilityFeatureFlagEntity updateFlag(@RequestBody FeatureFlagUpdateRequest req, Authentication auth) {
            return service.upsertFeatureFlag(
                    requireActiveFacility(), req.flagKey(), req.enabled(), req.killSwitch(),
                    auth != null ? auth.getName() : "SYSTEM");
        }

        @GetMapping("/feature-flags")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public List<FacilityFeatureFlagEntity> listFlags() {
            return service.listFeatureFlags(requireActiveFacility());
        }

        @PostMapping("/cross-facility-grants")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        public CrossFacilityAccessGrantEntity issueGrant(
                @RequestBody IssueCrossFacilityGrantRequest req, Authentication auth) {
            return service.issueCrossFacilityGrant(
                    requireActiveFacility(), req, auth != null ? auth.getName() : "SYSTEM");
        }

        @DeleteMapping("/cross-facility-grants/{grantId}")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        public CrossFacilityAccessGrantEntity revokeGrant(@PathVariable UUID grantId, Authentication auth) {
            return service.revokeCrossFacilityGrant(
                    requireActiveFacility(), grantId, auth != null ? auth.getName() : "SYSTEM");
        }

        @GetMapping("/cross-facility-grants")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public List<CrossFacilityAccessGrantEntity> listGrants() {
            return service.listGrantsForSourceFacility(requireActiveFacility());
        }

        private UUID requireActiveFacility() {
            return FacilityContext.getFacilityId()
                    .orElseThrow(() -> new AccessDeniedException("Active facility tenant context is required."));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
