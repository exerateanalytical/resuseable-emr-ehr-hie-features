/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-Tenant Governance & Organization Hierarchy Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.governance.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.UUID;

/**
 * Durable multi-tenant governance entities modeling Organizations, Facilities,
 * Departments, per-facility Feature Flags / Kill Switches, and explicit time-bound
 * Cross-Facility Access Grants.
 */
public final class TenantGovernanceEntities {

    private TenantGovernanceEntities() {}

    public enum FacilityLifecycleStatus {
        ONBOARDING,
        ACTIVE,
        READ_ONLY,
        SUSPENDED,
        OFFBOARDED
    }

    @Entity
    @Table(name = "hc_tenant_organizations")
    @BatchSize(size = 32)
    public static class TenantOrganizationEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "org_code", nullable = false, unique = true, length = 64)
        private String orgCode;

        @Column(name = "name", nullable = false, length = 200)
        private String name;

        @Column(name = "organization_type", nullable = false, length = 40)
        private String organizationType = "HOSPITAL_NETWORK";

        @Column(name = "country_iso", nullable = false, length = 8)
        private String countryIso = "CM";

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public String getOrgCode() { return orgCode; }
        public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getOrganizationType() { return organizationType; }
        public void setOrganizationType(String organizationType) { this.organizationType = organizationType; }
        public String getCountryIso() { return countryIso; }
        public void setCountryIso(String countryIso) { this.countryIso = countryIso; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public long getVersion() { return version; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "hc_tenant_facilities")
    @BatchSize(size = 32)
    public static class TenantFacilityEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "organization_id", nullable = false)
        private UUID organizationId;

        @Column(name = "facility_code", nullable = false, unique = true, length = 64)
        private String facilityCode;

        @Column(name = "name", nullable = false, length = 200)
        private String name;

        @Column(name = "facility_type", nullable = false, length = 40)
        private String facilityType = "DISTRICT_HOSPITAL";

        @Column(name = "country_iso", nullable = false, length = 8)
        private String countryIso = "CM";

        @Column(name = "timezone_id", nullable = false, length = 64)
        private String timezoneId = "Africa/Douala";

        @Column(name = "currency_code", nullable = false, length = 8)
        private String currencyCode = "XAF";

        @Column(name = "default_locale", nullable = false, length = 16)
        private String defaultLocale = "en";

        @Enumerated(EnumType.STRING)
        @Column(name = "lifecycle_status", nullable = false, length = 24)
        private FacilityLifecycleStatus lifecycleStatus = FacilityLifecycleStatus.ONBOARDING;

        @Column(name = "status_reason", length = 400)
        private String statusReason;

        @Column(name = "onboarded_at")
        private Instant onboardedAt;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getOrganizationId() { return organizationId; }
        public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
        public String getFacilityCode() { return facilityCode; }
        public void setFacilityCode(String facilityCode) { this.facilityCode = facilityCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFacilityType() { return facilityType; }
        public void setFacilityType(String facilityType) { this.facilityType = facilityType; }
        public String getCountryIso() { return countryIso; }
        public void setCountryIso(String countryIso) { this.countryIso = countryIso; }
        public String getTimezoneId() { return timezoneId; }
        public void setTimezoneId(String timezoneId) { this.timezoneId = timezoneId; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public String getDefaultLocale() { return defaultLocale; }
        public void setDefaultLocale(String defaultLocale) { this.defaultLocale = defaultLocale; }
        public FacilityLifecycleStatus getLifecycleStatus() { return lifecycleStatus; }
        public void setLifecycleStatus(FacilityLifecycleStatus lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
        public String getStatusReason() { return statusReason; }
        public void setStatusReason(String statusReason) { this.statusReason = statusReason; }
        public Instant getOnboardedAt() { return onboardedAt; }
        public void setOnboardedAt(Instant onboardedAt) { this.onboardedAt = onboardedAt; }
        public long getVersion() { return version; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Entity
    @Table(name = "hc_facility_departments")
    @BatchSize(size = 32)
    public static class FacilityDepartmentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "parent_department_id")
        private UUID parentDepartmentId;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Column(name = "name", nullable = false, length = 160)
        private String name;

        @Column(name = "department_type", nullable = false, length = 40)
        private String departmentType;

        @Column(name = "bed_capacity", nullable = false)
        private int bedCapacity = 0;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getParentDepartmentId() { return parentDepartmentId; }
        public void setParentDepartmentId(UUID parentDepartmentId) { this.parentDepartmentId = parentDepartmentId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDepartmentType() { return departmentType; }
        public void setDepartmentType(String departmentType) { this.departmentType = departmentType; }
        public int getBedCapacity() { return bedCapacity; }
        public void setBedCapacity(int bedCapacity) { this.bedCapacity = bedCapacity; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_facility_feature_flags")
    @BatchSize(size = 32)
    public static class FacilityFeatureFlagEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "flag_key", nullable = false, length = 96)
        private String flagKey;

        @Column(name = "enabled", nullable = false)
        private boolean enabled = false;

        @Column(name = "kill_switch", nullable = false)
        private boolean killSwitch = false;

        @Column(name = "updated_by", nullable = false, length = 120)
        private String updatedBy;

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getFlagKey() { return flagKey; }
        public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isKillSwitch() { return killSwitch; }
        public void setKillSwitch(boolean killSwitch) { this.killSwitch = killSwitch; }
        public String getUpdatedBy() { return updatedBy; }
        public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    /**
     * Explicit, time-bound, audited authorization granting an actor or destination facility
     * read access to records originating from a source facility (e.g., inter-hospital referral).
     */
    @Entity
    @Table(name = "hc_cross_facility_grants")
    @BatchSize(size = 32)
    public static class CrossFacilityAccessGrantEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "source_facility_id", nullable = false)
        private UUID sourceFacilityId;

        @Column(name = "target_facility_id", nullable = false)
        private UUID targetFacilityId;

        @Column(name = "grantee_actor_id", nullable = false, length = 120)
        private String granteeActorId;

        @Column(name = "patient_id")
        private UUID patientId;

        @Column(name = "grant_scope", nullable = false, length = 64)
        private String grantScope;

        @Column(name = "reason", nullable = false, length = 400)
        private String reason;

        @Column(name = "granted_by", nullable = false, length = 120)
        private String grantedBy;

        @Column(name = "expires_at", nullable = false)
        private Instant expiresAt;

        @Column(name = "revoked", nullable = false)
        private boolean revoked = false;

        @Column(name = "revoked_by", length = 120)
        private String revokedBy;

        @Column(name = "revoked_at")
        private Instant revokedAt;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getSourceFacilityId() { return sourceFacilityId; }
        public void setSourceFacilityId(UUID sourceFacilityId) { this.sourceFacilityId = sourceFacilityId; }
        public UUID getTargetFacilityId() { return targetFacilityId; }
        public void setTargetFacilityId(UUID targetFacilityId) { this.targetFacilityId = targetFacilityId; }
        public String getGranteeActorId() { return granteeActorId; }
        public void setGranteeActorId(String granteeActorId) { this.granteeActorId = granteeActorId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getGrantScope() { return grantScope; }
        public void setGrantScope(String grantScope) { this.grantScope = grantScope; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
        public String getGrantedBy() { return grantedBy; }
        public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public boolean isRevoked() { return revoked; }
        public void setRevoked(boolean revoked) { this.revoked = revoked; }
        public String getRevokedBy() { return revokedBy; }
        public void setRevokedBy(String revokedBy) { this.revokedBy = revokedBy; }
        public Instant getRevokedAt() { return revokedAt; }
        public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
