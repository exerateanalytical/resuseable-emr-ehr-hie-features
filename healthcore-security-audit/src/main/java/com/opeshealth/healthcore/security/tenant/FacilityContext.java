/*
 * =============================================================================
 * Opes HealthCore SDK — Multi-Facility Tenant Context Holder
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * ThreadLocal (and Virtual-Thread safe) holder for the active healthcare facility tenant.
 *
 * <p>Populated from verified JWT {@code facility_id} claims first, or {@code X-Facility-Id}
 * when authorized for multi-facility administrators / HIE gateways, and always cleared in
 * a servlet {@code finally} block to prevent tenant bleed across pooled threads.</p>
 */
public final class FacilityContext {

    private static final ThreadLocal<UUID> CURRENT_FACILITY = new ThreadLocal<>();

    private FacilityContext() {}

    public static void setFacilityId(UUID facilityId) {
        CURRENT_FACILITY.set(facilityId);
    }

    public static Optional<UUID> getFacilityId() {
        return Optional.ofNullable(CURRENT_FACILITY.get());
    }

    public static UUID requireFacilityId() {
        return getFacilityId().orElseThrow(() ->
                new IllegalStateException("Missing active facility tenant context (JWT facility_id or X-Facility-Id)"));
    }

    public static void clear() {
        CURRENT_FACILITY.remove();
    }
}
