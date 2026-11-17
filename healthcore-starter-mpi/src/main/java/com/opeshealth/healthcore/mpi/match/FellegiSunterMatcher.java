/*
 * =============================================================================
 * Opes HealthCore SDK — Cross-DB Phonetic Encoder & Fellegi-Sunter MPI Matcher
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.match;

import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure-Java phonetic encoder and probabilistic Fellegi-Sunter patient deduplication scorer.
 *
 * <p>Computing phonetic blocking keys in Java eliminates database-extension drift across
 * PostgreSQL ({@code fuzzystrmatch}), Windows SQL Server ({@code SOUNDEX}), and MySQL.</p>
 */
public final class FellegiSunterMatcher {

    public record MatchCandidate(PatientAggregateEntity candidate, double score, String classification) {}

    private FellegiSunterMatcher() {}

    /** Computes an uppercase phonetic key robust against FR/EN spelling variants and diacritics. */
    public static String phoneticKey(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z]", "");
        if (cleaned.isEmpty()) return "";

        String normalized = cleaned
                .replace("PH", "F")
                .replace("GH", "G")
                .replace("KN", "N")
                .replace("QU", "K")
                .replace("CK", "K")
                .replace("SCH", "S")
                .replace("SH", "S")
                .replace("CH", "X")
                .replace("TH", "T");

        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (int i = 0; i < normalized.length() && sb.length() < 8; i++) {
            char c = normalized.charAt(i);
            if (c == prev) continue;
            if (i == 0 || "AEIOUYHW".indexOf(c) < 0) {
                sb.append(c);
            }
            prev = c;
        }
        return sb.toString();
    }

    /**
     * Evaluates deterministic + probabilistic Fellegi-Sunter match score in range [0.0, 1.0].
     * Thresholds: {@code >= 0.88 AUTO_MATCH}, {@code >= 0.68 PROBABLE_REVIEW}, {@code < 0.68 NON_MATCH}.
     */
    public static double score(
            String familyName, String givenName, java.time.LocalDate birthDate,
            String gender, String phone, PatientAggregateEntity candidate) {
        double weight = 0.0;

        if (Objects.equals(birthDate, candidate.getBirthDate())) {
            weight += 0.30;
        } else if (birthDate != null && candidate.getBirthDate() != null
                && birthDate.getYear() == candidate.getBirthDate().getYear()) {
            weight += 0.10;
        }

        if (phoneticKey(familyName).equals(phoneticKey(candidate.getFamilyName()))) {
            weight += familyName.equalsIgnoreCase(candidate.getFamilyName()) ? 0.28 : 0.20;
        }
        if (phoneticKey(givenName).equals(phoneticKey(candidate.getGivenName()))) {
            weight += givenName.equalsIgnoreCase(candidate.getGivenName()) ? 0.22 : 0.15;
        }
        if (gender != null && gender.equalsIgnoreCase(candidate.getGender())) {
            weight += 0.08;
        }
        if (phone != null && !phone.isBlank() && phone.equals(candidate.getPrimaryPhone())) {
            weight += 0.12;
        }

        double bounded = Math.min(1.0, weight);
        return Math.round(bounded * 1000.0) / 1000.0;
    }

    public static String classify(double score) {
        if (score >= 0.88) return "AUTO_MATCH";
        if (score >= 0.68) return "PROBABLE_REVIEW";
        return "NON_MATCH";
    }
}
