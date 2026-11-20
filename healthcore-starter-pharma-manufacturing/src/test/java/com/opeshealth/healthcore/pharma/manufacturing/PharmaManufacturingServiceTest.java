/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Manufacturing & eBMR/eBPR Unit Tests
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.manufacturing;

import com.opeshealth.healthcore.pharma.manufacturing.domain.PharmaManufacturingEntities.*;
import com.opeshealth.healthcore.pharma.manufacturing.service.PharmaManufacturingService;
import com.opeshealth.healthcore.pharma.manufacturing.service.PharmaManufacturingService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PharmaManufacturingServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void enforcesMakerCheckerFormulaApprovalLineClearanceDualWitnessAndQaRelease() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        PharmaProductRepository productRepo = mock(PharmaProductRepository.class);
        MasterFormulaRepository formulaRepo = mock(MasterFormulaRepository.class);
        ElectronicBatchRecordRepository batchRepo = mock(ElectronicBatchRecordRepository.class);
        MaterialDispensingRepository dispRepo = mock(MaterialDispensingRepository.class);
        InProcessControlRepository ipcRepo = mock(InProcessControlRepository.class);

        when(productRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(formulaRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dispRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(ipcRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmaManufacturingService service = new PharmaManufacturingService(
                productRepo, formulaRepo, batchRepo, dispRepo, ipcRepo, null, null);

        PharmaProductEntity prod = service.registerProduct(new CreateProductRequest(
                "FG-AMOX-500", "Amoxicillin 500mg Capsules", "FINISHED_GOOD",
                "CAPSULE", "500mg", "15-25C DRY", 36));
        when(productRepo.findByIdAndFacilityId(prod.getId(), facId)).thenReturn(Optional.of(prod));

        MasterFormulaEntity formula = service.createMasterFormula(new CreateMasterFormulaRequest(
                prod.getId(), "MF-AMOX-500", "v1.0", new BigDecimal("100000"), "CAPSULES",
                "[{\"material\":\"API-AMOX\",\"qtyKg\":50}]", "[\"Granulate\",\"Encapsulate\"]", "formulator.alice"));
        when(formulaRepo.findByIdAndFacilityId(formula.getId(), facId)).thenReturn(Optional.of(formula));

        // Self-approval violates maker-checker segregation of duties
        assertThrows(SecurityException.class, () ->
                service.approveMasterFormula(formula.getId(), "formulator.alice", "SELF_APPROVE"));

        // Unapproved formula blocks batch creation
        assertThrows(IllegalStateException.class, () -> service.createBatchOrder(new CreateBatchOrderRequest(
                formula.getId(), "PO-2026-01", "BN-2026-001", "BMR", "LINE-A",
                new BigDecimal("100000"), LocalDate.now(), LocalDate.now().plusYears(3), "op.bob")));

        // Independent QA approver signs master formula
        MasterFormulaEntity approved = service.approveMasterFormula(formula.getId(), "qa.director", "APPROVED_FOR_GMP");
        assertEquals("APPROVED", approved.getApprovalStatus());
        assertNotNull(approved.getApprovalSigSha256());

        ElectronicBatchRecordEntity batch = service.createBatchOrder(new CreateBatchOrderRequest(
                formula.getId(), "PO-2026-01", "BN-2026-001", "BMR", "LINE-A",
                new BigDecimal("100000"), LocalDate.now(), LocalDate.now().plusYears(3), "op.bob"));
        when(batchRepo.findByIdAndFacilityId(batch.getId(), facId)).thenReturn(Optional.of(batch));

        // Dispensing blocked prior to line clearance
        assertThrows(IllegalStateException.class, () -> service.recordWeighedMaterial(batch.getId(),
                new RecordMaterialWeighingRequest("API-AMOX", "LOT-API-99", new BigDecimal("50"),
                        new BigDecimal("50.02"), "KG", "SCALE-01", "op.bob", "checker.claire")));

        service.verifyLineClearance(batch.getId(), "qa.inspector");
        assertTrue(batch.isLineClearanceVerified());

        // Same-person weighing witness rejected
        assertThrows(SecurityException.class, () -> service.recordWeighedMaterial(batch.getId(),
                new RecordMaterialWeighingRequest("API-AMOX", "LOT-API-99", new BigDecimal("50"),
                        new BigDecimal("50.02"), "KG", "SCALE-01", "op.bob", "op.bob")));

        MaterialDispensingEntity dispensed = service.recordWeighedMaterial(batch.getId(),
                new RecordMaterialWeighingRequest("API-AMOX", "LOT-API-99", new BigDecimal("50"),
                        new BigDecimal("50.02"), "KG", "SCALE-01", "op.bob", "checker.claire"));
        when(dispRepo.findByFacilityIdAndBatchRecordIdOrderByDispensedAtAsc(facId, batch.getId()))
                .thenReturn(List.of(dispensed));

        InProcessControlEntity ipc = service.recordInProcessControl(batch.getId(), new RecordIpcRequest(
                "ENCAPSULATION", "Average Fill Weight", new BigDecimal("502.5"),
                new BigDecimal("490.0"), new BigDecimal("510.0"), "mg", "ipc.tech"));
        assertTrue(ipc.isWithinSpec());
        when(ipcRepo.findByFacilityIdAndBatchRecordIdOrderByRecordedAtAsc(facId, batch.getId()))
                .thenReturn(List.of(ipc));

        service.reconcileYieldAndSubmitToQa(batch.getId(), new BigDecimal("98500"));
        assertEquals(new BigDecimal("98.50"), batch.getYieldPercent());

        // Operator cannot QA-release their own batch
        assertThrows(SecurityException.class, () -> service.recordQaDisposition(batch.getId(),
                new QaBatchDispositionRequest("op.bob", "RELEASED", "SELF_RELEASE")));

        ElectronicBatchRecordEntity released = service.recordQaDisposition(batch.getId(),
                new QaBatchDispositionRequest("qp.marie", "RELEASED", "QUALIFIED_PERSON_RELEASE"));
        assertEquals("RELEASED", released.getBatchStatus());
        assertNotNull(released.getQaSignatureSha256());

        FacilityContext.clear();
        assertThrows(AccessDeniedException.class, service::listBatches);
    }
}
