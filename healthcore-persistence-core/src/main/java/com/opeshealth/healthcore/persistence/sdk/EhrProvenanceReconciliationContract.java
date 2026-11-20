/*
 * =============================================================================
 * Opes HealthCore SDK — Longitudinal EHR Provenance & Multi-Source Reconciliation
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.time.Instant;
import java.util.*;

/**
 * Domain contract distinguishing Pack 06 Longitudinal EHR from single-site EMR:
 * preserves immutable source attribution, privacy segmentation sensitivity tags,
 * and deterministic multi-source medication/allergy/problem reconciliation.
 */
public final class EhrProvenanceReconciliationContract {

    private EhrProvenanceReconciliationContract() {}

    public enum SensitivitySegment {
        GENERAL_CLINICAL,
        HIV_REPRODUCTIVE,
        MENTAL_HEALTH,
        SUBSTANCE_USE,
        GENETIC_DATA
    }

    public record ProvenanceRecord(
            String recordId,
            String sourceOrganizationId,
            String sourceSystemId,
            String clinicalDomain, // MEDICATION, ALLERGY, PROBLEM, IMMUNIZATION
            String standardCode,   // RxNorm / SNOMED / ICD-10
            String displayText,
            String status,         // ACTIVE, INACTIVE, ENTERED_IN_ERROR
            SensitivitySegment sensitivitySegment,
            Instant recordedAt,
            String provenanceSha256
    ) {
        public static ProvenanceRecord create(
                String recordId, String sourceOrgId, String sourceSystemId,
                String domain, String code, String display, String status,
                SensitivitySegment segment, Instant recordedAt) {
            String payload = recordId + "|" + sourceOrgId + "|" + sourceSystemId + "|" + domain + "|" + code + "|" + status + "|" + recordedAt;
            return new ProvenanceRecord(
                    recordId, sourceOrgId, sourceSystemId, domain, code, display, status,
                    segment == null ? SensitivitySegment.GENERAL_CLINICAL : segment,
                    recordedAt, ElectronicSignatureSpi.sha256Hex(payload)
            );
        }
    }

    public record ReconciledTimelineEntry(
            String clinicalDomain,
            String standardCode,
            String authoritativeDisplay,
            String resolvedStatus,
            List<ProvenanceRecord> contributingSources,
            boolean conflictDetected
    ) {}

    /**
     * Reconciles clinical items across multiple external/internal EHR facilities while
     * filtering sensitive segments according to authorized consent scopes and flagging
     * cross-source status conflicts (e.g., ACTIVE at Clinic A vs INACTIVE at Hospital B).
     */
    public static List<ReconciledTimelineEntry> reconcile(
            List<ProvenanceRecord> rawRecords,
            Set<SensitivitySegment> authorizedSegments) {
        Map<String, List<ProvenanceRecord>> grouped = new LinkedHashMap<>();
        for (ProvenanceRecord r : rawRecords) {
            if (!authorizedSegments.contains(r.sensitivitySegment())) {
                continue;
            }
            if ("ENTERED_IN_ERROR".equalsIgnoreCase(r.status())) {
                continue;
            }
            String key = r.clinicalDomain().toUpperCase(Locale.ROOT) + "::" + r.standardCode().toUpperCase(Locale.ROOT);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<ReconciledTimelineEntry> timeline = new ArrayList<>();
        for (List<ProvenanceRecord> bucket : grouped.values()) {
            bucket.sort(Comparator.comparing(ProvenanceRecord::recordedAt).reversed());
            ProvenanceRecord latest = bucket.get(0);
            Set<String> distinctStatuses = new HashSet<>();
            for (ProvenanceRecord r : bucket) {
                distinctStatuses.add(r.status().toUpperCase(Locale.ROOT));
            }
            timeline.add(new ReconciledTimelineEntry(
                    latest.clinicalDomain(),
                    latest.standardCode(),
                    latest.displayText(),
                    latest.status(),
                    List.copyOf(bucket),
                    distinctStatuses.size() > 1
            ));
        }
        return timeline;
    }
}
