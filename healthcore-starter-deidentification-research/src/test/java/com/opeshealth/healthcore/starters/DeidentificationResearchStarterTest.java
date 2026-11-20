package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.starters.DeidentificationResearchStarter.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DeidentificationResearchStarterTest {

    @Test
    void enforcesHipaaSafeHarborKAnonymityAndAge89TopCoding() {
        HipaaSafeHarborDeidentificationService service =
                new HipaaSafeHarborDeidentificationService(mock(HospitalOpsService.class));

        List<ClinicalResearchSourceRow> rows = List.of(
                new ClinicalResearchSourceRow(UUID.randomUUID(), 1985, "F", "10025", "E11.9", "Patient phone +237600000001"),
                new ClinicalResearchSourceRow(UUID.randomUUID(), 1985, "F", "10029", "E11.9", "Email a@b.com"),
                new ClinicalResearchSourceRow(UUID.randomUUID(), 1985, "F", "10021", "E11.9", "Clean summary"),
                new ClinicalResearchSourceRow(UUID.randomUUID(), 1930, "M", "90210", "I10", "Unique outlier")
        );

        SafeHarborCohortExportResult result = service.exportSafeHarborCohort(
                new SafeHarborCohortExportRequest("STUDY-2026", "salt-999", 3, rows));
        assertTrue(result.kAnonymitySatisfied());
        assertEquals(3, result.exportedRecordCount());
        assertEquals(1, result.suppressedForKAnonymityCount());
        assertTrue(result.cohort().stream().allMatch(r -> r.scrubbedClinicalSummary().isEmpty()));
        assertTrue(result.cohort().stream().allMatch(r -> "SUPPRESSED".equals(r.region3DigitPrefix())));
        assertThrows(IllegalArgumentException.class, () -> service.exportSafeHarborCohort(
                new SafeHarborCohortExportRequest("STUDY-2026", "", 3, rows)));
        assertFalse(result.cohort().get(0).scrubbedClinicalSummary().contains("a@b.com"));
    }
}
