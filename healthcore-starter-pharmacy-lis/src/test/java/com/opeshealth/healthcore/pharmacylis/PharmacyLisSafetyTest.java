/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmacy FEFO & LIS Westgard QC Safety Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacylis;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.DiagnosticReportEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.PharmacyDispenseEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for {@link PharmacyLisService} validating PHARM-001 (atomic FEFO
 * stock deduction, concurrency depletion protection, controlled substance dual-witness,
 * tenant isolation) and LIS Westgard QC gating + panic lab escalation.
 */
class PharmacyLisSafetyTest {

    private PharmacyLisService.MedicationBatchRepository batchRepo;
    private PharmacyLisService.PharmacyDispenseRepository dispenseRepo;
    private PharmacyLisService.LabSpecimenRepository specimenRepo;
    private PharmacyLisService.DiagnosticReportRepository reportRepo;
    private CdsHooksEngine cdsHooksEngine;
    private OutboxRelayService.OutboxRepository outboxRepo;
    private PharmacyLisService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        batchRepo = mock(PharmacyLisService.MedicationBatchRepository.class);
        dispenseRepo = mock(PharmacyLisService.PharmacyDispenseRepository.class);
        specimenRepo = mock(PharmacyLisService.LabSpecimenRepository.class);
        reportRepo = mock(PharmacyLisService.DiagnosticReportRepository.class);
        cdsHooksEngine = mock(CdsHooksEngine.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        service = new PharmacyLisService(batchRepo, dispenseRepo, specimenRepo, reportRepo, cdsHooksEngine, outboxRepo);
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void dispenseFefoPicksEarliestExpiringLotAndDeductsAtomically() {
        MedicationBatchEntity earlierLot = buildBatch("AMOX-500", "LOT-A", LocalDate.now().plusDays(45), 30, null);
        MedicationBatchEntity laterLot = buildBatch("AMOX-500", "LOT-B", LocalDate.now().plusDays(180), 100, null);

        when(batchRepo.findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                eq(facilityId), eq("AMOX-500"), any(LocalDate.class)))
                .thenReturn(List.of(earlierLot, laterLot));
        when(batchRepo.decrementStockAtomically(earlierLot.getId(), facilityId, 10)).thenReturn(1);
        when(dispenseRepo.save(any(PharmacyDispenseEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PharmacyDispenseEntity dispensed = service.dispenseFefo(
                facilityId, UUID.randomUUID(), UUID.randomUUID(),
                "AMOX-500", "AMOX-500", 10, false, "pharm-1", null);

        assertEquals(earlierLot.getId(), dispensed.getBatchId());
        assertEquals(10, dispensed.getQuantityDispensed());
        assertFalse(dispensed.isPartialFill());
        assertEquals(20, earlierLot.getQuantityOnHand());
        verify(batchRepo).decrementStockAtomically(earlierLot.getId(), facilityId, 10);
        verify(outboxRepo).save(any(TransactionalOutboxEntity.class));
    }

    @Test
    void dispenseFefoThrowsOptimisticLockFailureWhenConcurrentDepletionOccurs() {
        MedicationBatchEntity lot = buildBatch("AMOX-500", "LOT-RACE", LocalDate.now().plusDays(30), 15, null);

        when(batchRepo.findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                eq(facilityId), eq("AMOX-500"), any(LocalDate.class)))
                .thenReturn(List.of(lot));
        when(batchRepo.decrementStockAtomically(lot.getId(), facilityId, 15)).thenReturn(0);

        assertThrows(OptimisticLockingFailureException.class, () -> service.dispenseFefo(
                facilityId, UUID.randomUUID(), UUID.randomUUID(),
                "AMOX-500", "AMOX-500", 15, false, "pharm-1", null));

        verify(dispenseRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void dispenseFefoBlocksControlledDrugWithoutWitness() {
        MedicationBatchEntity morphineLot = buildBatch("MORPH-10", "LOT-C2", LocalDate.now().plusDays(90), 25, "SCHEDULE_II");
        when(batchRepo.findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                eq(facilityId), eq("MORPH-10"), any(LocalDate.class)))
                .thenReturn(List.of(morphineLot));

        assertThrows(SecurityException.class, () -> service.dispenseFefo(
                facilityId, UUID.randomUUID(), UUID.randomUUID(),
                "MORPH-10", "MORPH-10", 2, false, "pharm-1", null));

        verify(batchRepo, never()).decrementStockAtomically(any(), any(), anyInt());
    }

    @Test
    void dispenseFefoRejectsInsufficientStockWhenPartialFillDisabled() {
        MedicationBatchEntity lot = buildBatch("INSULIN-R", "LOT-LOW", LocalDate.now().plusDays(60), 4, null);
        when(batchRepo.findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                eq(facilityId), eq("INSULIN-R"), any(LocalDate.class)))
                .thenReturn(List.of(lot));

        assertThrows(IllegalStateException.class, () -> service.dispenseFefo(
                facilityId, UUID.randomUUID(), UUID.randomUUID(),
                "INSULIN-R", "INSULIN-R", 10, false, "pharm-1", null));
    }

    @Test
    void dispenseFefoDeniesCrossTenantFacilityRequest() {
        UUID otherFacility = UUID.randomUUID();
        assertThrows(AccessDeniedException.class, () -> service.dispenseFefo(
                otherFacility, UUID.randomUUID(), UUID.randomUUID(),
                "AMOX-500", "AMOX-500", 5, false, "pharm-1", null));
    }

    @Test
    void verifyLabResultBlocksReleaseWhenWestgardQcFails() {
        DiagnosticReportEntity report = new DiagnosticReportEntity();
        report.setFacilityId(facilityId);
        report.setSpecimenId(UUID.randomUUID());
        report.setPatientId(UUID.randomUUID());
        report.setLoincCode("2823-3");
        report.setTestName("Serum Potassium");
        report.setNumericValue(new BigDecimal("4.2"));
        report.setWestgardQcPassed(false);

        assertThrows(IllegalStateException.class, () -> service.verifyLabResult(report));
        verify(reportRepo, never()).save(any());
    }

    @Test
    void verifyLabResultFlagsCriticalPotassiumPanicAndEmitsAlertEvents() {
        DiagnosticReportEntity report = new DiagnosticReportEntity();
        report.setFacilityId(facilityId);
        report.setSpecimenId(UUID.randomUUID());
        report.setPatientId(UUID.randomUUID());
        report.setLoincCode("2823-3");
        report.setTestName("Serum Potassium");
        report.setNumericValue(new BigDecimal("6.8"));
        report.setWestgardQcPassed(true);

        CdsHooksEngine.CdsCard panicCard = new CdsHooksEngine.CdsCard(
                UUID.randomUUID().toString(),
                "Critical Hyperkalemia",
                "Serum Potassium 6.8 mmol/L exceeds critical panic threshold (>= 6.2)",
                "critical",
                "Opes HealthCore CDSS",
                List.of("Notify attending physician immediately"),
                true);
        when(cdsHooksEngine.evaluateVitalsAndLabPanic(any())).thenReturn(List.of(panicCard));
        when(reportRepo.save(any(DiagnosticReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.verifyLabResult(report);

        assertEquals("HH", report.getAbnormalFlag());
        assertNotNull(report.getVerifiedAt());
        assertEquals(1, ((List<?>) result.get("panicAlerts")).size());
        verify(outboxRepo, times(2)).save(any(TransactionalOutboxEntity.class));
    }

    private MedicationBatchEntity buildBatch(
            String drugCode, String lotNumber, LocalDate expiry, int qty, String schedule) {
        MedicationBatchEntity b = new MedicationBatchEntity();
        b.setFacilityId(facilityId);
        b.setDrugCode(drugCode);
        b.setDrugName("Test Drug " + drugCode);
        b.setLotNumber(lotNumber);
        b.setExpiryDate(expiry);
        b.setQuantityOnHand(qty);
        b.setControlledSchedule(schedule);
        b.setQuarantined(false);
        return b;
    }
}
