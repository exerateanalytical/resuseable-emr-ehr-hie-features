/*
 * =============================================================================
 * Opes HealthCore SDK — Production Secret & Security Fail-Fast Startup Validator
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enforces fail-closed startup validation whenever {@code prod} or {@code production}
 * Spring profiles are active. Aborts application boot immediately if cryptographic keys,
 * webhook HMAC secrets, database passwords, or dev bypass flags are insecure or missing.
 */
public class ProductionSecretsStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretsStartupValidator.class);

    private static final int MIN_SECRET_LENGTH = 32;
    private static final Set<String> FORBIDDEN_PLACEHOLDERS = Set.of(
            "change_me",
            "change_me_in_prod",
            "changeit",
            "changeme",
            "default",
            "dev-secret",
            "secret",
            "password",
            "opeshealth!2026strong",
            "test-secret"
    );

    private final Environment environment;

    public ProductionSecretsStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    /**
     * Validates all critical production secrets and security flags.
     * No-op unless {@code prod} or {@code production} is active.
     */
    public void validate() {
        if (!isProductionProfileActive()) {
            return;
        }
        List<String> violations = new ArrayList<>();

        if (isProfileActive("dev-seed")) {
            violations.add("Spring profile 'dev-seed' MUST NOT be enabled alongside 'prod'/'production'.");
        }

        if (PiiVaultCrypto.isUsingDefaultDevKeys()) {
            violations.add("PII Vault is still using built-in development AES/HMAC keys; configure HEALTHCORE_PII_AES_KEY_B64 and HEALTHCORE_PII_HMAC_KEY_B64.");
        }

        validateSecretProperty("healthcore.hie.webhook.signing-secret", "HEALTHCORE_HIE_WEBHOOK_SECRET", violations);
        validateSecretProperty("healthcore.uhc.offline-qr.signing-secret", "HEALTHCORE_UHC_QR_SIGNING_SECRET", violations);
        validateSecretProperty("healthcore.uhc.momo.webhook-secret", "HEALTHCORE_UHC_MOMO_WEBHOOK_SECRET", violations);

        String dbPassword = environment.getProperty("spring.datasource.password", "");
        if (isWeakOrPlaceholder(dbPassword, 12)) {
            violations.add("spring.datasource.password is missing, weak (<12 chars), or uses a default placeholder.");
        }

        boolean allowUnregistered = environment.getProperty(
                "healthcore.workforce.allow-unregistered-in-dev", Boolean.class, false);
        if (allowUnregistered) {
            violations.add("healthcore.workforce.allow-unregistered-in-dev MUST be false in production.");
        }

        if (!violations.isEmpty()) {
            String message = "Opes HealthCore production fail-fast security validation failed ("
                    + violations.size() + " violation(s)):\n - " + String.join("\n - ", violations);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("Production fail-fast secret and security configuration verified.");
    }

    public boolean isProductionProfileActive() {
        return isProfileActive("prod") || isProfileActive("production");
    }

    private boolean isProfileActive(String profileName) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase(profileName));
    }

    private void validateSecretProperty(String propertyName, String envHint, List<String> violations) {
        String value = environment.getProperty(propertyName, "");
        if (isWeakOrPlaceholder(value, MIN_SECRET_LENGTH)) {
            violations.add("Property '" + propertyName + "' (" + envHint
                    + ") must be configured with at least " + MIN_SECRET_LENGTH
                    + " non-placeholder characters in production.");
        }
    }

    static boolean isWeakOrPlaceholder(String value, int minLength) {
        if (value == null) {
            return true;
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String placeholder : FORBIDDEN_PLACEHOLDERS) {
            if (lower.equals(placeholder) || lower.contains("change_me") || lower.contains("replace-with")) {
                return true;
            }
        }
        return false;
    }
}
