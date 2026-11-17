/*
 * =============================================================================
 * Opes HealthCore SDK — Declarative @ClinicalAudit Annotation
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks controller or application service methods for automatic tamper-evident
 * SHA-256 hash-chained clinical audit logging via {@link ClinicalAuditService}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ClinicalAudit {

    /** IHE ATNA / clinical action name (e.g., READ_CHART, SIGN_NOTE, DISPENSE_RX). */
    String action();

    /** Target clinical resource type (e.g., Patient, Encounter, MedicationDispense). */
    String resourceType();
}
