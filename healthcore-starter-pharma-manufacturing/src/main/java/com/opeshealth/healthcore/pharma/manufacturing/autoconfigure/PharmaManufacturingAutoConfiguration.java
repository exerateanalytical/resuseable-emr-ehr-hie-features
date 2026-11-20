/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Manufacturing AutoConfiguration
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.manufacturing.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = "com.opeshealth.healthcore.pharma.manufacturing")
@EntityScan(basePackages = "com.opeshealth.healthcore.pharma.manufacturing.domain")
@EnableJpaRepositories(basePackages = "com.opeshealth.healthcore.pharma.manufacturing.service", considerNestedRepositories = true)
public class PharmaManufacturingAutoConfiguration {
}
