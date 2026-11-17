package com.healthcore.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * ThreadLocal holder for the active healthcare facility (clinic/hospital) context.
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
        return getFacilityId()
                .orElseThrow(() -> new IllegalStateException("Missing X-Facility-Id tenant context"));
    }

    public static void clear() {
        CURRENT_FACILITY.remove();
    }
}
