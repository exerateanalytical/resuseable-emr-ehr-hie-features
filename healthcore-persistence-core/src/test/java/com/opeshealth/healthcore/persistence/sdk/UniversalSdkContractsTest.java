package com.opeshealth.healthcore.persistence.sdk;

import com.opeshealth.healthcore.persistence.sdk.EhrProvenanceReconciliationContract.*;
import com.opeshealth.healthcore.persistence.sdk.SpecialtyExtensionContract.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class UniversalSdkContractsTest {

    @Test
    void detectsDependencyConflictsAndIncompatibleMajorVersions() {
        var clean = CapabilityPackManifestContract.detectDependencyConflicts(
                "custom-lab-billing", Map.of(
                        "healthcore-starter-diagnostics-core", "1.0.0",
                        "healthcore-starter-uhc-billing", "1.0.0"
                ));
        assertTrue(clean.compatible());

        var conflict = CapabilityPackManifestContract.detectDependencyConflicts(
                "bad-mix", Map.of(
                        "healthcore-starter-pharmacy-commerce", "1.0.0",
                        "healthcore-starter-pharmacy-lis", "2.0.0"
                ));
        assertFalse(conflict.compatible());
        assertEquals(2, conflict.violations().size());
    }

    @Test
    void validatesAllSixClinicalSpecialtyExtensionTemplates() {
        var templates = SpecialtyExtensionContract.defaultTemplates();
        assertEquals(6, templates.size());

        var obTemplate = templates.get(ClinicalSpecialty.OBSTETRICS);
        assertFalse(SpecialtyExtensionContract.validateSubmission(obTemplate, Map.of()).isEmpty());
        assertTrue(SpecialtyExtensionContract.validateSubmission(obTemplate, Map.of(
                "gestational_age_weeks", 28,
                "fetal_heart_rate_bpm", 142
        )).isEmpty());
    }

    @Test
    void reconcilesLongitudinalEhrWithProvenancePrivacySegmentationAndConflictDetection() {
        Instant t1 = Instant.parse("2026-01-10T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-15T10:00:00Z");

        var clinicMed = ProvenanceRecord.create("R1", "CLINIC-DOUALA", "OPD-EMR", "MEDICATION",
                "RXNORM:860975", "Metformin 500mg", "ACTIVE", SensitivitySegment.GENERAL_CLINICAL, t1);
        var hospMed = ProvenanceRecord.create("R2", "HOSP-YAOUNDE", "HIS-CORE", "MEDICATION",
                "RXNORM:860975", "Metformin 500mg", "INACTIVE", SensitivitySegment.GENERAL_CLINICAL, t2);
        var hivNote = ProvenanceRecord.create("R3", "CLINIC-DOUALA", "OPD-EMR", "PROBLEM",
                "ICD10:B20", "HIV Care Enrolled", "ACTIVE", SensitivitySegment.HIV_REPRODUCTIVE, t2);

        // Privacy segmentation filters HIV_REPRODUCTIVE when not in authorizedSegments
        var generalOnly = EhrProvenanceReconciliationContract.reconcile(
                List.of(clinicMed, hospMed, hivNote), Set.of(SensitivitySegment.GENERAL_CLINICAL));
        assertEquals(1, generalOnly.size());
        assertTrue(generalOnly.get(0).conflictDetected());
        assertEquals("INACTIVE", generalOnly.get(0).resolvedStatus());
        assertEquals(2, generalOnly.get(0).contributingSources().size());
    }
}
