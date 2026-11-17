/*
 * =============================================================================
 * Opes HealthCore SDK — <1 Hour Reference Quickstart Application Host
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Reference Spring Boot 4.0 + Java 25 host demonstrating how any developer or health-tech
 * team can compose a complete EMR, EHR, HIE, PIS, LIS, Specialty Clinic, UHC/CBHI, and
 * Disease Surveillance platform in under one hour purely by importing Opes HealthCore starters.
 *
 * <p>To build a focused standalone system (for example, ONLY a Pharmacy Information System or
 * ONLY an Eye Clinic EMR), simply include only the relevant starters in your Maven POM.</p>
 */
@SpringBootApplication
public class HealthCoreQuickstartApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthCoreQuickstartApplication.class, args);
    }
}
