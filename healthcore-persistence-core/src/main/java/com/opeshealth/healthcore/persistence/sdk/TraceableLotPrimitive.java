/*
 * =============================================================================
 * Opes HealthCore SDK — Domain-Neutral Traceable Lot & GS1 Barcode Primitive
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Domain-neutral lot/batch traceability and GS1 DataMatrix parsing primitive shared across
 * retail pharmacy, diagnostic lab reagents, biobank consumables, and pharmaceutical manufacturing
 * without coupling their distinct JPA entities or approval workflows.
 */
public record TraceableLotPrimitive(
        String gtin14,
        String lotOrBatchNumber,
        LocalDate expiryDate,
        String serialNumber,
        boolean quarantined
) {

    private static final Pattern GTIN_PATTERN = Pattern.compile("^\\d{8,14}$");

    public TraceableLotPrimitive {
        if (gtin14 != null && !gtin14.isBlank() && !GTIN_PATTERN.matcher(gtin14.trim()).matches()) {
            throw new IllegalArgumentException("Invalid GS1 GTIN format (expected 8-14 digits): " + gtin14);
        }
        if (lotOrBatchNumber == null || lotOrBatchNumber.isBlank()) {
            throw new IllegalArgumentException("lotOrBatchNumber is required for traceable lot records.");
        }
        Objects.requireNonNull(expiryDate, "expiryDate is required for traceable lot records.");
    }

    public boolean isExpiredOn(LocalDate referenceDate) {
        return expiryDate.isBefore(Objects.requireNonNull(referenceDate));
    }

    public boolean isNearExpiry(LocalDate referenceDate, int warningDays) {
        LocalDate threshold = Objects.requireNonNull(referenceDate).plusDays(Math.max(0, warningDays));
        return !isExpiredOn(referenceDate) && !expiryDate.isAfter(threshold);
    }

    public boolean isEligibleForUse(LocalDate referenceDate) {
        return !quarantined && !isExpiredOn(referenceDate);
    }

    /**
     * Renders a GS1 Human-Readable Application Identifier string:
     * {@code (01)GTIN(17)YYMMDD(10)LOT(21)SERIAL}.
     */
    public String toGs1ApplicationIdentifierString() {
        StringBuilder sb = new StringBuilder();
        if (gtin14 != null && !gtin14.isBlank()) {
            sb.append("(01)").append(gtin14.trim());
        }
        String yymmdd = String.format("%02d%02d%02d",
                expiryDate.getYear() % 100, expiryDate.getMonthValue(), expiryDate.getDayOfMonth());
        sb.append("(17)").append(yymmdd);
        sb.append("(10)").append(lotOrBatchNumber.trim());
        if (serialNumber != null && !serialNumber.isBlank()) {
            sb.append("(21)").append(serialNumber.trim());
        }
        return sb.toString();
    }
}
