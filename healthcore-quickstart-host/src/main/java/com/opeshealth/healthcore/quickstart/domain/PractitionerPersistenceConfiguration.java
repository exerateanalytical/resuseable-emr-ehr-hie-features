/*
 * =============================================================================
 * Opes HealthCore SDK — Practitioner Persistence Entity Scan Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.domain;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PractitionerEntity} with Spring Boot's {@code EntityScanPackages}
 * alongside modular starter entities.
 */
@Configuration
@EntityScan(basePackageClasses = PractitionerEntity.class)
public class PractitionerPersistenceConfiguration {
}
