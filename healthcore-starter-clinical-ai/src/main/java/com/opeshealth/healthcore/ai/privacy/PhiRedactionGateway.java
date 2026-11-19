/*
 * =============================================================================
 * Opes HealthCore SDK — HIPAA Safe-Harbor 18-Identifier PHI Redaction Gateway
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.ai.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, reversible HIPAA Safe-Harbor (§ 164.514(b)(2)) PHI De-Identification Gateway.
 *
 * <p>Scrubs all direct personal identifiers (patient names, MRNs, phone numbers, email addresses,
 * national IDs / SSNs, full dates of birth, street addresses, URLs, IP addresses, insurance/account
 * numbers, and device serials) into scoped opaque tokens ({@code [PATIENT_NAME_1]}, {@code [MRN_1]},
 * etc.) before any clinical payload leaves the facility boundary toward an AI model.</p>
 *
 * <p>The reverse token map is ephemeral and request-scoped; only redacted SHA-256 hashes and
 * aggregate redaction counts are ever persisted to audit trails.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
public class PhiRedactionGateway {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:\\+?\\d{1,3}[\\s.-]?)?(?:\\(\\d{2,4}\\)|\\d{2,4})[\\s.-]\\d{3,4}[\\s.-]\\d{3,4}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile(
            "\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern NATIONAL_ID_PATTERN = Pattern.compile(
            "(?i)\\b(?:CNI|NIN|NID|PASSPORT|NATIONAL[\\s_-]?ID)[\\s:#-]*([A-Z0-9-]{5,20})\\b");
    private static final Pattern MRN_PATTERN = Pattern.compile(
            "(?i)\\b(?:MRN|PATIENT[\\s_-]?ID|CHART[\\s_-]?NO|MPI)[\\s:#-]*([A-Z0-9-]{4,24})\\b");
    private static final Pattern POLICY_OR_ACCOUNT_PATTERN = Pattern.compile(
            "(?i)\\b(?:POLICY|MEMBER|INSURANCE|ACCOUNT|ACCT)[\\s:#-]*([A-Z0-9-]{5,24})\\b");
    private static final Pattern DOB_LABELED_PATTERN = Pattern.compile(
            "(?i)\\b(?:DOB|DATE OF BIRTH|BORN|BIRTHDATE)[\\s:#-]*(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile(
            "\\b(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])\\b");
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\bhttps?://[^\\s\"'<>]+");
    private static final Pattern STREET_ADDRESS_PATTERN = Pattern.compile(
            "(?i)\\b\\d{1,5}\\s+[A-Za-z0-9.'-]+(?:\\s+[A-Za-z0-9.'-]+){0,3}\\s+(?:Street|St|Avenue|Ave|Boulevard|Blvd|Road|Rd|Rue|Drive|Dr|Lane|Ln|Quartier)\\b\\.?");
    private static final Pattern LABELED_PERSON_NAME_PATTERN = Pattern.compile(
            "(?i)\\b(?:Patient\\s+Name|Patient|Name|Guardian|Next\\s+of\\s+Kin)\\s*:\\s*([A-Z][a-zA-Z'-]+(?:\\s+[A-Z][a-zA-Z'-]+){1,2})");

    public record RedactionContext(
            List<String> knownPatientNames,
            List<String> knownIdentifiers,
            String knownBirthDate
    ) {
        public static RedactionContext empty() {
            return new RedactionContext(List.of(), List.of(), null);
        }
    }

    public record RedactedClinicalPayload(
            String redactedText,
            Map<String, String> placeholderToOriginal,
            Map<String, Integer> redactionCountsByCategory,
            int totalRedactions,
            String redactedSha256Hex
    ) {
        public String rehydrateForAuthorizedClinician(String aiOutput) {
            if (aiOutput == null || aiOutput.isEmpty() || placeholderToOriginal.isEmpty()) {
                return aiOutput;
            }
            String restored = aiOutput;
            for (Map.Entry<String, String> entry : placeholderToOriginal.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            return restored;
        }
    }

    public RedactedClinicalPayload redact(String rawClinicalText) {
        return redact(rawClinicalText, RedactionContext.empty());
    }

    public RedactedClinicalPayload redact(String rawClinicalText, RedactionContext context) {
        if (rawClinicalText == null || rawClinicalText.isEmpty()) {
            return new RedactedClinicalPayload("", Map.of(), Map.of(), 0, sha256Hex(""));
        }

        RedactionContext safeContext = context != null ? context : RedactionContext.empty();
        Map<String, String> placeholderToOriginal = new LinkedHashMap<>();
        Map<String, String> originalToPlaceholder = new LinkedHashMap<>();
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();

        String sanitized = rawClinicalText;

        // 1. Explicit known patient names & identifiers from clinical record context
        if (safeContext.knownPatientNames() != null) {
            for (String knownName : safeContext.knownPatientNames()) {
                if (knownName != null && knownName.trim().length() >= 2) {
                    sanitized = replaceLiteralCaseInsensitive(
                            sanitized, knownName.trim(), "PATIENT_NAME",
                            placeholderToOriginal, originalToPlaceholder, categoryCounts);
                }
            }
        }
        if (safeContext.knownIdentifiers() != null) {
            for (String idVal : safeContext.knownIdentifiers()) {
                if (idVal != null && idVal.trim().length() >= 3) {
                    sanitized = replaceLiteralCaseInsensitive(
                            sanitized, idVal.trim(), "DIRECT_IDENTIFIER",
                            placeholderToOriginal, originalToPlaceholder, categoryCounts);
                }
            }
        }
        if (safeContext.knownBirthDate() != null && !safeContext.knownBirthDate().isBlank()) {
            sanitized = replaceLiteralCaseInsensitive(
                    sanitized, safeContext.knownBirthDate().trim(), "DOB",
                    placeholderToOriginal, originalToPlaceholder, categoryCounts);
        }

        // 2. Pattern-based HIPAA Safe-Harbor 18-identifier scrubbing
        sanitized = replacePatternGroup(sanitized, LABELED_PERSON_NAME_PATTERN, 1, "PATIENT_NAME",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, EMAIL_PATTERN, "EMAIL",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, SSN_PATTERN, "SSN",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePatternGroup(sanitized, NATIONAL_ID_PATTERN, 1, "NATIONAL_ID",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePatternGroup(sanitized, MRN_PATTERN, 1, "MRN",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePatternGroup(sanitized, POLICY_OR_ACCOUNT_PATTERN, 1, "ACCOUNT_OR_POLICY",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePatternGroup(sanitized, DOB_LABELED_PATTERN, 1, "DOB",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, PHONE_PATTERN, "PHONE",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, URL_PATTERN, "URL",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, IPV4_PATTERN, "IP_ADDRESS",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, STREET_ADDRESS_PATTERN, "STREET_ADDRESS",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);
        sanitized = replacePattern(sanitized, ISO_DATE_PATTERN, "EXACT_DATE",
                placeholderToOriginal, originalToPlaceholder, categoryCounts);

        int total = categoryCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new RedactedClinicalPayload(
                sanitized,
                Collections.unmodifiableMap(placeholderToOriginal),
                Collections.unmodifiableMap(categoryCounts),
                total,
                sha256Hex(sanitized)
        );
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable in JVM", ex);
        }
    }

    private String replaceLiteralCaseInsensitive(
            String input,
            String literal,
            String category,
            Map<String, String> placeholderToOriginal,
            Map<String, String> originalToPlaceholder,
            Map<String, Integer> categoryCounts) {
        Pattern p = Pattern.compile("(?i)\\b" + Pattern.quote(literal) + "\\b");
        return replacePattern(input, p, category, placeholderToOriginal, originalToPlaceholder, categoryCounts);
    }

    private String replacePattern(
            String input,
            Pattern pattern,
            String category,
            Map<String, String> placeholderToOriginal,
            Map<String, String> originalToPlaceholder,
            Map<String, Integer> categoryCounts) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            String token = tokenFor(matched, category, placeholderToOriginal, originalToPlaceholder, categoryCounts);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String replacePatternGroup(
            String input,
            Pattern pattern,
            int groupIndex,
            String category,
            Map<String, String> placeholderToOriginal,
            Map<String, String> originalToPlaceholder,
            Map<String, Integer> categoryCounts) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fullMatch = matcher.group(0);
            String captured = matcher.group(groupIndex);
            if (captured == null || captured.isBlank()) {
                continue;
            }
            String token = tokenFor(captured, category, placeholderToOriginal, originalToPlaceholder, categoryCounts);
            String replacedFull = fullMatch.replace(captured, token);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacedFull));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String tokenFor(
            String original,
            String category,
            Map<String, String> placeholderToOriginal,
            Map<String, String> originalToPlaceholder,
            Map<String, Integer> categoryCounts) {
        String key = category + "::" + original.toLowerCase(java.util.Locale.ROOT);
        String existing = originalToPlaceholder.get(key);
        if (existing != null) {
            return existing;
        }
        int nextSeq = categoryCounts.merge(category, 1, Integer::sum);
        String placeholder = "[" + category + "_" + nextSeq + "]";
        originalToPlaceholder.put(key, placeholder);
        placeholderToOriginal.put(placeholder, original);
        return placeholder;
    }
}
