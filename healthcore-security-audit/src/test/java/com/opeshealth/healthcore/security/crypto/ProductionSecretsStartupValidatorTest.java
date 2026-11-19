/*
 * =============================================================================
 * Opes HealthCore SDK — Production Secret Fail-Fast Validator Unit Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class ProductionSecretsStartupValidatorTest {

    @Test
    void devProfileAllowsEmptySecrets() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.password", "change_me_in_prod");
        env.setActiveProfiles("postgres", "dev-seed");
        ProductionSecretsStartupValidator validator = new ProductionSecretsStartupValidator(env);
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void prodProfileRejectsMissingSecretsAndDevSeedProfile() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "change_me_in_prod")
                .withProperty("healthcore.hie.webhook.signing-secret", "")
                .withProperty("healthcore.uhc.offline-qr.signing-secret", "short")
                .withProperty("healthcore.uhc.momo.webhook-secret", "change_me_secret_value_for_dev_testing")
                .withProperty("healthcore.workforce.allow-unregistered-in-dev", "true");
        env.setActiveProfiles("prod", "dev-seed");

        ProductionSecretsStartupValidator validator = new ProductionSecretsStartupValidator(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, validator::validate);
        assertTrue(ex.getMessage().contains("dev-seed"));
        assertTrue(ex.getMessage().contains("HEALTHCORE_HIE_WEBHOOK_SECRET"));
        assertTrue(ex.getMessage().contains("HEALTHCORE_UHC_QR_SIGNING_SECRET"));
        assertTrue(ex.getMessage().contains("HEALTHCORE_UHC_MOMO_WEBHOOK_SECRET"));
        assertTrue(ex.getMessage().contains("allow-unregistered-in-dev"));
    }

    @Test
    void prodProfilePassesWhenStrongKeysAndSecretsAreConfigured() {
        byte[] aesKey = new byte[32];
        byte[] hmacKey = new byte[32];
        for (int i = 0; i < 32; i++) {
            aesKey[i] = (byte) (i + 11);
            hmacKey[i] = (byte) (i + 41);
        }
        PiiVaultCrypto.configureKeys(aesKey, hmacKey);

        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "Str0ngProdDbPass!987654")
                .withProperty("healthcore.hie.webhook.signing-secret", "8a9b2c3d4e5f60718293a4b5c6d7e8f9a0b1c2d3")
                .withProperty("healthcore.uhc.offline-qr.signing-secret", "1f2e3d4c5b6a79887766554433221100ffeeddcc")
                .withProperty("healthcore.uhc.momo.webhook-secret", "99aa88bb77cc66dd55ee44ff33221100aabbccdd")
                .withProperty("healthcore.workforce.allow-unregistered-in-dev", "false");
        env.setActiveProfiles("production");

        ProductionSecretsStartupValidator validator = new ProductionSecretsStartupValidator(env);
        assertDoesNotThrow(validator::validate);
    }
}
