/*
 * =============================================================================
 * Opes HealthCore SDK — Core Clinical Safety, Sync, Crypto & MPI Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.emr.specialty.SpecialtyPackSpi;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.security.crypto.PiiVaultCrypto;
import com.opeshealth.healthcore.sync.clock.HybridLogicalClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HealthCoreDomainSafetyTest {

    @Test
    void uuidV7IsStrictlyMonotonic() {
        UUID first = UuidV7Generator.nextUuid();
        UUID second = UuidV7Generator.nextUuid();
        assertTrue(first.toString().compareTo(second.toString()) < 0);
        assertEquals(7, first.version());
    }

    @Test
    void piiVaultEncryptsAndBlindIndexesDeterministically() {
        String plain = "CM-DLA-99887766";
        String c1 = PiiVaultCrypto.encrypt(plain);
        String c2 = PiiVaultCrypto.encrypt(plain);
        assertNotEquals(c1, c2, "Random GCM IV must produce distinct ciphertexts");
        assertEquals(plain, PiiVaultCrypto.decrypt(c1));
        assertEquals(PiiVaultCrypto.blindIndex(plain), PiiVaultCrypto.blindIndex("  cm-dla-99887766 "));
    }

    @Test
    void hlcAndEd25519QrHealthCardWorkOffline() {
        HybridLogicalClock hlc = new HybridLogicalClock("ipad-ward-2");
        String t1 = hlc.nextTimestamp();
        String t2 = hlc.nextTimestamp();
        assertTrue(t1.compareTo(t2) < 0);

        var signer = new HybridLogicalClock.Ed25519QrHealthCardSigner();
        String token = signer.signCardPayload("{\"mrn\":\"OPES-101\",\"blood\":\"O+\"}");
        assertTrue(signer.verifyCardToken(token));
        assertFalse(signer.verifyCardToken(token + "tamper"));
    }

    @Test
    void fellegiSunterMatchesMinorSpellingVariance() {
        PatientAggregateEntity existing = new PatientAggregateEntity();
        existing.setFamilyName("Nshome");
        existing.setGivenName("Jude");
        existing.setBirthDate(LocalDate.of(1990, 5, 14));
        existing.setGender("male");
        existing.setPrimaryPhone("+237670000001");

        double score = FellegiSunterMatcher.score("Nchome", "Jude", LocalDate.of(1990, 5, 14), "male", "+237670000001", existing);
        assertTrue(score >= 0.88, "Expected AUTO_MATCH score >= 0.88, got " + score);
    }

    @Test
    void specialtyPacksValidateRequiredClinicalFields() {
        var eye = new SpecialtyPackSpi.EyeClinicPack();
        assertThrows(IllegalArgumentException.class, () -> eye.validatePayload(Map.of("odIopMmHg", 16)));
        assertDoesNotThrow(() -> eye.validatePayload(Map.of("odVisualAcuity", "20/20", "osVisualAcuity", "20/25")));
    }

    @Test
    void safeHarborDeidentificationRedactsIdentifiers() {
        var ops = new HospitalOpsService(null, null, null, null, null, null, null);
        Map<String, Object> safe = ops.deidentifySafeHarbor(UUID.randomUUID(), 1920, "female", "B50.9", "Littoral");
        assertEquals("90+", safe.get("ageGroup"));
        assertEquals("Lit", safe.get("regionPrefix"));
    }
}
