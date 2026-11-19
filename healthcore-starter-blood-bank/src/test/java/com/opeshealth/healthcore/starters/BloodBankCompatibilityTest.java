/*
 * =============================================================================
 * Opes HealthCore SDK — Blood Bank Compatibility & Hemovigilance Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodDonorRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.TransfusionReactionRepository;
import com.opeshealth.healthcore.starters.BloodBankStarter.BloodBankHemovigilanceService;
import com.opeshealth.healthcore.starters.BloodBankStarter.CompatibilityEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BloodBankCompatibilityTest {

    private BloodBankHemovigilanceService service;

    @BeforeEach
    void setUp() {
        service = new BloodBankHemovigilanceService(
                mock(HospitalOpsService.class),
                mock(BloodUnitRepository.class),
                mock(BloodDonorRepository.class),
                mock(TransfusionReactionRepository.class));
    }

    @Test
    void enforcesPrbcUniversalDonorAndBlocksIncompatibleTransfusion() {
        CompatibilityEvaluation universal = service.evaluateCompatibility("O-", "AB+", "PRBC");
        assertTrue(universal.compatible());
        assertTrue(universal.emergencyUniversalDonor());

        CompatibilityEvaluation incompatible = service.evaluateCompatibility("A+", "O-", "PRBC");
        assertFalse(incompatible.compatible());
    }

    @Test
    void reversesCompatibilityRulesForFreshFrozenPlasma() {
        // AB plasma is universal plasma donor; O plasma is only compatible with O recipients
        CompatibilityEvaluation abPlasmaToO = service.evaluateCompatibility("AB+", "O-", "FFP");
        assertTrue(abPlasmaToO.compatible());

        CompatibilityEvaluation oPlasmaToA = service.evaluateCompatibility("O-", "A+", "FFP");
        assertFalse(oPlasmaToA.compatible());
    }

    @Test
    void rejectsUnsupportedComponentOrMalformedAboRhAndIsbt128() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluateCompatibility("A+", "A+", "PLATELETS"));
        assertThrows(IllegalArgumentException.class, () -> BloodBankHemovigilanceService.normalizeAboRh("C+"));
        assertThrows(IllegalArgumentException.class, () -> BloodBankHemovigilanceService.validateIsbt128Barcode("123"));
        assertDoesNotThrow(() -> BloodBankHemovigilanceService.validateIsbt128Barcode("W000026001234"));
    }
}
