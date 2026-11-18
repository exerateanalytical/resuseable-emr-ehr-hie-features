/*
 * =============================================================================
 * Opes HealthCore SDK — Workforce Practitioner Credential Verification SPI
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.rbac;

import java.util.UUID;

/**
 * Service Provider Interface decoupling clinical prescribing/signing modules
 * ({@code healthcore-starter-emr-clinical}) from HR/licensing registry modules
 * ({@code healthcore-starter-hospital-ops} / {@code healthcore-starter-workforce-roster}).
 *
 * <p>When a credentialing module is active on the classpath, clinical note signing
 * and CPOE prescribing invoke {@link #isPractitionerActive(UUID, String)} to block
 * practitioners with expired medical licenses or suspended facility privileges.</p>
 */
@FunctionalInterface
public interface WorkforceCredentialSpi {

    /**
     * Verifies whether a practitioner holds an active, unexpired credential at the given facility.
     *
     * @param facilityId     tenant facility UUID
     * @param practitionerId practitioner username / subject identifier
     * @return {@code true} if licensed and active (or if no restriction applies), {@code false} if expired/suspended
     */
    boolean isPractitionerActive(UUID facilityId, String practitionerId);
}
