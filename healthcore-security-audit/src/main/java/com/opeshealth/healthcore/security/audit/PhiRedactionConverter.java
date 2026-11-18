/*
 * =============================================================================
 * Opes HealthCore SDK — Logback PHI/PII Redaction Converter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.audit;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Custom Logback {@link MessageConverter} that scrubs sensitive Protected Health
 * Information (PHI) and Personally Identifiable Information (PII) from application logs
 * prior to console/file/SIEM emission.
 *
 * <p>Redacts:</p>
 * <ul>
 *   <li>Medical Record Numbers ({@code MRN-...}, {@code mrn=...})</li>
 *   <li>National IDs / Passports ({@code nationalId=...}, {@code nid=...})</li>
 *   <li>International &amp; Cameroonian phone numbers ({@code +237...}, {@code phone=...})</li>
 *   <li>Email addresses and JWT Bearer tokens</li>
 * </ul>
 */
public class PhiRedactionConverter extends MessageConverter {

    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(Bearer\\s+)[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MRN_PATTERN =
            Pattern.compile("(?i)(mrn[\"':=\\s]+)([A-Z0-9\\-]{4,32})");
    private static final Pattern NID_PATTERN =
            Pattern.compile("(?i)(national[_-]?id[\"':=\\s]+)([A-Z0-9\\-]{5,32})");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d{1,3}[\\s\\-]?)?(\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{3,4})");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Override
    public String convert(ILoggingEvent event) {
        String raw = super.convert(event);
        return redact(raw);
    }

    /** Public static helper reusable in audit serializers and test suites. */
    public static String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String sanitized = BEARER_PATTERN.matcher(input).replaceAll("$1[REDACTED_JWT]");
        sanitized = MRN_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_MRN]");
        sanitized = NID_PATTERN.matcher(sanitized).replaceAll("$1[REDACTED_NID]");
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("[REDACTED_EMAIL]");
        sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PHONE]");
        return sanitized;
    }
}
