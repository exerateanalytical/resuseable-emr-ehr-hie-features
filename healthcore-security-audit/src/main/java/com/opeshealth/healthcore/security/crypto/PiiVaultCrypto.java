/*
 * =============================================================================
 * Opes HealthCore SDK — AES-256-GCM PII Field Encryption & HMAC Blind Index
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Provides application-layer AES-256-GCM encryption for sensitive PII/PHI database columns
 * (National IDs, restricted HIV/psychiatric notes) plus deterministic HMAC-SHA256 blind
 * indexes so encrypted identifiers remain exact-match searchable across PG, MSSQL, and MySQL.
 */
public final class PiiVaultCrypto {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile boolean usingDefaultDevKeys = true;
    private static volatile SecretKey activeAesKey = initAesKey();
    private static volatile byte[] activeHmacKey = initHmacKey();

    private PiiVaultCrypto() {}

    /** Configures production 256-bit AES and HMAC keys from Vault/Env at startup. */
    public static void configureKeys(byte[] aes256KeyBytes, byte[] hmacKeyBytes) {
        if (aes256KeyBytes == null || aes256KeyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
        }
        if (hmacKeyBytes == null || hmacKeyBytes.length < 32) {
            throw new IllegalArgumentException("HMAC-SHA256 key must be at least 32 bytes");
        }
        activeAesKey = new SecretKeySpec(aes256KeyBytes, "AES");
        activeHmacKey = hmacKeyBytes.clone();
        usingDefaultDevKeys = false;
    }

    /** Returns true if fallback local-dev keys are still active instead of externalized Vault/Env keys. */
    public static boolean isUsingDefaultDevKeys() {
        return usingDefaultDevKeys;
    }

    /** Fails fast at startup if default local-dev keys are still active in a production profile. */
    public static void assertProductionKeysConfigured(boolean productionProfileActive) {
        if (productionProfileActive && usingDefaultDevKeys) {
            throw new IllegalStateException(
                    "Default development PII encryption/HMAC keys cannot be used in production. "
                            + "Configure HEALTHCORE_PII_AES_KEY_B64 and HEALTHCORE_PII_HMAC_KEY_B64 or call PiiVaultCrypto.configureKeys(...).");
        }
    }

    private static SecretKey initAesKey() {
        String envAes = System.getenv("HEALTHCORE_PII_AES_KEY_B64");
        String envHmac = System.getenv("HEALTHCORE_PII_HMAC_KEY_B64");
        if (envAes != null && !envAes.isBlank() && envHmac != null && !envHmac.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(envAes.trim());
            if (decoded.length != 32) {
                throw new IllegalStateException("HEALTHCORE_PII_AES_KEY_B64 must decode to exactly 32 bytes");
            }
            usingDefaultDevKeys = false;
            return new SecretKeySpec(decoded, "AES");
        }
        return defaultDevKey();
    }

    private static byte[] initHmacKey() {
        String envHmac = System.getenv("HEALTHCORE_PII_HMAC_KEY_B64");
        if (envHmac != null && !envHmac.isBlank()) {
            byte[] decoded = Base64.getDecoder().decode(envHmac.trim());
            if (decoded.length < 32) {
                throw new IllegalStateException("HEALTHCORE_PII_HMAC_KEY_B64 must decode to at least 32 bytes");
            }
            return decoded;
        }
        return "opes-healthcore-dev-hmac-key-32b".getBytes(StandardCharsets.UTF_8);
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, activeAesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer packed = ByteBuffer.allocate(iv.length + ciphertext.length);
            packed.put(iv);
            packed.put(ciphertext);
            return "v1:" + Base64.getEncoder().encodeToString(packed.array());
        } catch (Exception ex) {
            throw new IllegalStateException("PII encryption failure", ex);
        }
    }

    public static String decrypt(String encoded) {
        if (encoded == null) return null;
        if (!encoded.startsWith("v1:")) {
            return encoded; // Graceful fallback for legacy unencrypted dev fixtures.
        }
        try {
            byte[] packed = Base64.getDecoder().decode(encoded.substring(3));
            ByteBuffer buf = ByteBuffer.wrap(packed);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, activeAesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("PII decryption failure", ex);
        }
    }

    /** Computes a deterministic, case-normalized HMAC-SHA256 blind index for SQL equality lookups. */
    public static String blindIndex(String value) {
        if (value == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(activeHmacKey, "HmacSHA256"));
            byte[] digest = mac.doFinal(value.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Blind index failure", ex);
        }
    }

    private static SecretKey defaultDevKey() {
        byte[] key = "OpesHealthSystemsDoualaCameroon!".getBytes(StandardCharsets.UTF_8); // 32 bytes dev-only
        return new SecretKeySpec(key, "AES");
    }

    /** JPA AttributeConverter encrypting String fields transparently on persist and decrypting on load. */
    @Converter
    public static class EncryptedStringConverter implements AttributeConverter<String, String> {
        @Override
        public String convertToDatabaseColumn(String attribute) { return encrypt(attribute); }

        @Override
        public String convertToEntityAttribute(String dbData) { return decrypt(dbData); }
    }
}
