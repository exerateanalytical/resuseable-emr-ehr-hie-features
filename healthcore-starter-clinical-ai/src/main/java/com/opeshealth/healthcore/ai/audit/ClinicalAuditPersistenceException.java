/*
 * =============================================================================
 * Opes HealthCore SDK — Mandatory Clinical Audit Persistence Exception
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.audit;

/**
 * Thrown when a mandatory clinical AI or privacy audit record cannot be durably persisted.
 * Callers must fail closed rather than silently swallowing compliance audit failures.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public class ClinicalAuditPersistenceException extends IllegalStateException {

    public ClinicalAuditPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public ClinicalAuditPersistenceException(String message) {
        super(message);
    }
}
