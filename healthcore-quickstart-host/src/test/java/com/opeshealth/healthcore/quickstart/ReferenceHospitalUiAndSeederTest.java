/*
 * =============================================================================
 * Opes HealthCore SDK — Reference Hospital Seeder & Security Guard Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.quickstart.seed.DemoHospitalDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceHospitalUiAndSeederTest {

    @Test
    void demoHospitalSeederRefusesToRunWhenProdProfileIsActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev-seed", "prod");
        DemoHospitalDataSeeder seeder = new DemoHospitalDataSeeder(
                env, null, null, null, null, null, null, null, null, null, null, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, seeder::seedDemoHospital);
        assertTrue(ex.getMessage().contains("prod"));
    }
}
