/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Manufacturing & eBMR/eBPR Service & API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.manufacturing.service;

import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureCommand;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureProof;
import com.opeshealth.healthcore.pharma.manufacturing.domain.PharmaManufacturingEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Transactional pharmaceutical manufacturing service enforcing WHO GMP / EU GMP Annex 11 /
 * 21 CFR Part 11 technical controls: maker-checker master formula approval, mandatory line
 * clearance before dispensing, dual-witness weighing, in-process controls (IPC), yield
 * reconciliation, batch genealogy, and QA electronic-signature batch disposition.
 *
 * <p>Strictly independent of clinical MPI, EMR, or HIS modules.</p>
 */
@Service
public class PharmaManufacturingService {

    /** Extension SPI allowing manufacturers to customize yield tolerance &amp; QA disposition gates. */
    public interface PharmaBatchReleasePolicySpi {
        void validateBeforeQaRelease(
                ElectronicBatchRecordEntity batch,
                List<MaterialDispensingEntity> dispensedMaterials,
                List<InProcessControlEntity> ipcChecks);
    }

    public interface PharmaProductRepository extends JpaRepository<PharmaProductEntity, UUID> {
        List<PharmaProductEntity> findByFacilityIdOrderByProductCodeAsc(UUID facilityId);
        Optional<PharmaProductEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface MasterFormulaRepository extends JpaRepository<MasterFormulaEntity, UUID> {
        List<MasterFormulaEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        Optional<MasterFormulaEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface ElectronicBatchRecordRepository extends JpaRepository<ElectronicBatchRecordEntity, UUID> {
        List<ElectronicBatchRecordEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        Optional<ElectronicBatchRecordEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<ElectronicBatchRecordEntity> findByFacilityIdAndBatchNumber(UUID facilityId, String batchNumber);
    }

    public interface MaterialDispensingRepository extends JpaRepository<MaterialDispensingEntity, UUID> {
        List<MaterialDispensingEntity> findByFacilityIdAndBatchRecordIdOrderByDispensedAtAsc(UUID facilityId, UUID batchRecordId);
        List<MaterialDispensingEntity> findByFacilityIdAndSourceMaterialLot(UUID facilityId, String sourceMaterialLot);
    }

    public interface InProcessControlRepository extends JpaRepository<InProcessControlEntity, UUID> {
        List<InProcessControlEntity> findByFacilityIdAndBatchRecordIdOrderByRecordedAtAsc(UUID facilityId, UUID batchRecordId);
    }

    public record CreateProductRequest(
            String productCode,
            String productName,
            String materialClass,
            String dosageForm,
            String strengthSpec,
            String storageCondition,
            int shelfLifeMonths
    ) {}

    public record CreateMasterFormulaRequest(
            UUID productId,
            String formulaCode,
            String formulaVersion,
            BigDecimal standardBatchSize,
            String batchUnit,
            String bomSpecJson,
            String instructionsJson,
            String authoredBy
    ) {}

    public record CreateBatchOrderRequest(
            UUID formulaId,
            String productionOrderNo,
            String batchNumber,
            String recordKind,
            String equipmentLineCode,
            BigDecimal theoreticalYield,
            LocalDate manufacturedDate,
            LocalDate expiryDate,
            String operatorId
    ) {}

    public record RecordMaterialWeighingRequest(
            String materialProductCode,
            String sourceMaterialLot,
            BigDecimal targetQuantity,
            BigDecimal actualWeighedQuantity,
            String unitOfMeasure,
            String scaleEquipmentId,
            String weighedBy,
            String verifiedBy
    ) {}

    public record RecordIpcRequest(
            String stageCode,
            String parameterName,
            BigDecimal measuredValue,
            BigDecimal minLimit,
            BigDecimal maxLimit,
            String unitOfMeasure,
            String recordedBy
    ) {}

    public record QaBatchDispositionRequest(
            String qaReviewerId,
            String disposition, // RELEASED, REJECTED, QUARANTINED
            String meaningOfSignature
    ) {}

    public record BatchGenealogyReport(
            UUID batchRecordId,
            String batchNumber,
            String batchStatus,
            BigDecimal yieldPercent,
            List<MaterialDispensingEntity> inputMaterialLots,
            List<InProcessControlEntity> inProcessControls
    ) {}

    private final PharmaProductRepository productRepo;
    private final MasterFormulaRepository formulaRepo;
    private final ElectronicBatchRecordRepository batchRepo;
    private final MaterialDispensingRepository dispensingRepo;
    private final InProcessControlRepository ipcRepo;
    private final ElectronicSignatureSpi signatureSpi;
    private final List<PharmaBatchReleasePolicySpi> releasePolicies;

    public PharmaManufacturingService(
            PharmaProductRepository productRepo,
            MasterFormulaRepository formulaRepo,
            ElectronicBatchRecordRepository batchRepo,
            MaterialDispensingRepository dispensingRepo,
            InProcessControlRepository ipcRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider,
            ObjectProvider<PharmaBatchReleasePolicySpi> releasePoliciesProvider) {
        this.productRepo = productRepo;
        this.formulaRepo = formulaRepo;
        this.batchRepo = batchRepo;
        this.dispensingRepo = dispensingRepo;
        this.ipcRepo = ipcRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
        this.releasePolicies = releasePoliciesProvider != null
                ? releasePoliciesProvider.orderedStream().toList()
                : List.of();
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_PRODUCT_CREATE", resourceType = "PharmaProduct")
    public PharmaProductEntity registerProduct(CreateProductRequest req) {
        UUID facId = requireFacility();
        PharmaProductEntity entity = new PharmaProductEntity();
        entity.setFacilityId(facId);
        entity.setProductCode(req.productCode().trim().toUpperCase(Locale.ROOT));
        entity.setProductName(req.productName().trim());
        entity.setMaterialClass(req.materialClass().trim().toUpperCase(Locale.ROOT));
        entity.setDosageForm(req.dosageForm());
        entity.setStrengthSpec(req.strengthSpec());
        entity.setStorageCondition(req.storageCondition().trim());
        entity.setShelfLifeMonths(Math.max(1, req.shelfLifeMonths()));
        return productRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_FORMULA_CREATE", resourceType = "MasterFormula")
    public MasterFormulaEntity createMasterFormula(CreateMasterFormulaRequest req) {
        UUID facId = requireFacility();
        productRepo.findByIdAndFacilityId(req.productId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Product not found in facility: " + req.productId()));
        MasterFormulaEntity formula = new MasterFormulaEntity();
        formula.setFacilityId(facId);
        formula.setProductId(req.productId());
        formula.setFormulaCode(req.formulaCode().trim().toUpperCase(Locale.ROOT));
        formula.setFormulaVersion(req.formulaVersion().trim());
        formula.setStandardBatchSize(req.standardBatchSize());
        formula.setBatchUnit(req.batchUnit().trim());
        formula.setBomSpecJson(req.bomSpecJson());
        formula.setInstructionsJson(req.instructionsJson());
        formula.setAuthoredBy(req.authoredBy().trim());
        formula.setApprovalStatus("DRAFT");
        return formulaRepo.save(formula);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_FORMULA_APPROVE", resourceType = "MasterFormula")
    public MasterFormulaEntity approveMasterFormula(UUID formulaId, String approverId, String meaningOfSignature) {
        UUID facId = requireFacility();
        MasterFormulaEntity formula = formulaRepo.findByIdAndFacilityId(formulaId, facId)
                .orElseThrow(() -> new NoSuchElementException("Master formula not found: " + formulaId));
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                approverId,
                "QA_FORMULA_APPROVER",
                meaningOfSignature == null ? "MASTER_FORMULA_APPROVED" : meaningOfSignature,
                "MasterFormula",
                formulaId.toString(),
                formula.getFormulaCode() + "|" + formula.getFormulaVersion() + "|" + formula.getBomSpecJson(),
                formula.getAuthoredBy(),
                true
        ));
        formula.setApprovalStatus("APPROVED");
        formula.setApprovedBy(proof.signerId());
        formula.setApprovalSigSha256(proof.signatureDigestSha256());
        formula.setApprovedAt(proof.signedAt());
        return formulaRepo.save(formula);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_BATCH_CREATE", resourceType = "ElectronicBatchRecord")
    public ElectronicBatchRecordEntity createBatchOrder(CreateBatchOrderRequest req) {
        UUID facId = requireFacility();
        MasterFormulaEntity formula = formulaRepo.findByIdAndFacilityId(req.formulaId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Master formula not found: " + req.formulaId()));
        if (!"APPROVED".equals(formula.getApprovalStatus())) {
            throw new IllegalStateException("Production batch requires an APPROVED master formula (status=" + formula.getApprovalStatus() + ").");
        }
        if (req.expiryDate().isBefore(req.manufacturedDate())) {
            throw new IllegalArgumentException("Batch expiryDate cannot precede manufacturedDate.");
        }
        ElectronicBatchRecordEntity batch = new ElectronicBatchRecordEntity();
        batch.setFacilityId(facId);
        batch.setFormulaId(formula.getId());
        batch.setProductionOrderNo(req.productionOrderNo().trim());
        batch.setBatchNumber(req.batchNumber().trim().toUpperCase(Locale.ROOT));
        batch.setRecordKind(req.recordKind() == null ? "BMR" : req.recordKind().trim().toUpperCase(Locale.ROOT));
        batch.setEquipmentLineCode(req.equipmentLineCode().trim());
        batch.setTheoreticalYield(req.theoreticalYield());
        batch.setManufacturedDate(req.manufacturedDate());
        batch.setExpiryDate(req.expiryDate());
        batch.setOperatorId(req.operatorId().trim());
        batch.setBatchStatus("PLANNED");
        return batchRepo.save(batch);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_LINE_CLEARANCE", resourceType = "ElectronicBatchRecord")
    public ElectronicBatchRecordEntity verifyLineClearance(UUID batchId, String inspectorId) {
        UUID facId = requireFacility();
        ElectronicBatchRecordEntity batch = batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        if (batch.getOperatorId().equalsIgnoreCase(inspectorId.trim())) {
            throw new SecurityException("Line clearance must be verified independently from primary batch operator.");
        }
        batch.setLineClearanceVerified(true);
        batch.setLineClearedBy(inspectorId.trim());
        batch.setBatchStatus("LINE_CLEARED");
        batch.setUpdatedAt(Instant.now());
        return batchRepo.save(batch);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_MATERIAL_DISPENSE", resourceType = "MaterialDispensing")
    public MaterialDispensingEntity recordWeighedMaterial(UUID batchId, RecordMaterialWeighingRequest req) {
        UUID facId = requireFacility();
        ElectronicBatchRecordEntity batch = batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        if (!batch.isLineClearanceVerified()) {
            throw new IllegalStateException("Material dispensing blocked: line clearance has not been verified.");
        }
        if (req.weighedBy().trim().equalsIgnoreCase(req.verifiedBy().trim())) {
            throw new SecurityException("GMP weighing verification requires a second independent witness (weighedBy != verifiedBy).");
        }
        if (req.actualWeighedQuantity() == null || req.actualWeighedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("actualWeighedQuantity must be positive.");
        }
        MaterialDispensingEntity disp = new MaterialDispensingEntity();
        disp.setFacilityId(facId);
        disp.setBatchRecordId(batch.getId());
        disp.setMaterialProductCode(req.materialProductCode().trim().toUpperCase(Locale.ROOT));
        disp.setSourceMaterialLot(req.sourceMaterialLot().trim().toUpperCase(Locale.ROOT));
        disp.setTargetQuantity(req.targetQuantity());
        disp.setActualWeighedQuantity(req.actualWeighedQuantity());
        disp.setUnitOfMeasure(req.unitOfMeasure().trim());
        disp.setScaleEquipmentId(req.scaleEquipmentId().trim());
        disp.setWeighedBy(req.weighedBy().trim());
        disp.setVerifiedBy(req.verifiedBy().trim());

        if ("LINE_CLEARED".equals(batch.getBatchStatus())) {
            batch.setBatchStatus("IN_PRODUCTION");
            batch.setUpdatedAt(Instant.now());
            batchRepo.save(batch);
        }
        return dispensingRepo.save(disp);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_IPC_RECORD", resourceType = "InProcessControl")
    public InProcessControlEntity recordInProcessControl(UUID batchId, RecordIpcRequest req) {
        UUID facId = requireFacility();
        batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        boolean withinMin = req.minLimit() == null || req.measuredValue().compareTo(req.minLimit()) >= 0;
        boolean withinMax = req.maxLimit() == null || req.measuredValue().compareTo(req.maxLimit()) <= 0;

        InProcessControlEntity ipc = new InProcessControlEntity();
        ipc.setFacilityId(facId);
        ipc.setBatchRecordId(batchId);
        ipc.setStageCode(req.stageCode().trim().toUpperCase(Locale.ROOT));
        ipc.setParameterName(req.parameterName().trim());
        ipc.setMeasuredValue(req.measuredValue());
        ipc.setMinLimit(req.minLimit());
        ipc.setMaxLimit(req.maxLimit());
        ipc.setUnitOfMeasure(req.unitOfMeasure().trim());
        ipc.setWithinSpec(withinMin && withinMax);
        ipc.setRecordedBy(req.recordedBy().trim());
        return ipcRepo.save(ipc);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_YIELD_RECONCILE", resourceType = "ElectronicBatchRecord")
    public ElectronicBatchRecordEntity reconcileYieldAndSubmitToQa(UUID batchId, BigDecimal actualYield) {
        UUID facId = requireFacility();
        ElectronicBatchRecordEntity batch = batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        if (actualYield == null || actualYield.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("actualYield must be non-negative.");
        }
        BigDecimal pct = actualYield.multiply(BigDecimal.valueOf(100))
                .divide(batch.getTheoreticalYield(), 2, RoundingMode.HALF_UP);
        batch.setActualYield(actualYield);
        batch.setYieldPercent(pct);
        batch.setBatchStatus("AWAITING_QA_DISPOSITION");
        batch.setUpdatedAt(Instant.now());
        return batchRepo.save(batch);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_QA_DISPOSITION", resourceType = "ElectronicBatchRecord")
    public ElectronicBatchRecordEntity recordQaDisposition(UUID batchId, QaBatchDispositionRequest req) {
        UUID facId = requireFacility();
        ElectronicBatchRecordEntity batch = batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        String target = req.disposition().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("RELEASED", "REJECTED", "QUARANTINED").contains(target)) {
            throw new IllegalArgumentException("Invalid QA batch disposition: " + target);
        }
        List<MaterialDispensingEntity> dispensed = dispensingRepo.findByFacilityIdAndBatchRecordIdOrderByDispensedAtAsc(facId, batchId);
        List<InProcessControlEntity> ipcs = ipcRepo.findByFacilityIdAndBatchRecordIdOrderByRecordedAtAsc(facId, batchId);

        if ("RELEASED".equals(target)) {
            if (!batch.isLineClearanceVerified() || dispensed.isEmpty() || batch.getActualYield() == null) {
                throw new IllegalStateException("Cannot release batch: line clearance, material dispensing, and yield reconciliation are mandatory.");
            }
            boolean hasOutOfSpecIpc = ipcs.stream().anyMatch(i -> !i.isWithinSpec());
            if (hasOutOfSpecIpc) {
                throw new IllegalStateException("Cannot release batch with uninvestigated out-of-specification In-Process Control results.");
            }
            for (PharmaBatchReleasePolicySpi policy : releasePolicies) {
                policy.validateBeforeQaRelease(batch, dispensed, ipcs);
            }
        }

        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                req.qaReviewerId(),
                "QA_QUALIFIED_PERSON",
                req.meaningOfSignature() == null ? "QA_BATCH_" + target : req.meaningOfSignature(),
                "ElectronicBatchRecord",
                batch.getId().toString(),
                batch.getBatchNumber() + "|" + batch.getYieldPercent() + "|" + target,
                batch.getOperatorId(),
                true
        ));
        batch.setBatchStatus(target);
        batch.setQaReviewerId(proof.signerId());
        batch.setQaSignatureSha256(proof.signatureDigestSha256());
        batch.setUpdatedAt(proof.signedAt());
        return batchRepo.save(batch);
    }

    @Transactional(readOnly = true)
    public BatchGenealogyReport getBatchGenealogy(UUID batchId) {
        UUID facId = requireFacility();
        ElectronicBatchRecordEntity batch = batchRepo.findByIdAndFacilityId(batchId, facId)
                .orElseThrow(() -> new NoSuchElementException("Batch record not found: " + batchId));
        return new BatchGenealogyReport(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getBatchStatus(),
                batch.getYieldPercent(),
                dispensingRepo.findByFacilityIdAndBatchRecordIdOrderByDispensedAtAsc(facId, batchId),
                ipcRepo.findByFacilityIdAndBatchRecordIdOrderByRecordedAtAsc(facId, batchId)
        );
    }

    @Transactional(readOnly = true)
    public List<MaterialDispensingEntity> traceBatchesUsingMaterialLot(String sourceMaterialLot) {
        return dispensingRepo.findByFacilityIdAndSourceMaterialLot(requireFacility(), sourceMaterialLot.trim().toUpperCase(Locale.ROOT));
    }

    @Transactional(readOnly = true)
    public List<PharmaProductEntity> listProducts() {
        return productRepo.findByFacilityIdOrderByProductCodeAsc(requireFacility());
    }

    @Transactional(readOnly = true)
    public List<ElectronicBatchRecordEntity> listBatches() {
        return batchRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for pharmaceutical manufacturing operations."));
    }

    @RestController
    @RequestMapping("/api/v1/pharma/manufacturing")
    public static class PharmaManufacturingController {
        private final PharmaManufacturingService service;

        public PharmaManufacturingController(PharmaManufacturingService service) {
            this.service = service;
        }

        @GetMapping("/products")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public List<PharmaProductEntity> listProducts() {
            return service.listProducts();
        }

        @PostMapping("/products")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public PharmaProductEntity registerProduct(@RequestBody CreateProductRequest req) {
            return service.registerProduct(req);
        }

        @PostMapping("/formulas")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public MasterFormulaEntity createFormula(@RequestBody CreateMasterFormulaRequest req) {
            return service.createMasterFormula(req);
        }

        @PostMapping("/formulas/{id}/approve")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public MasterFormulaEntity approveFormula(
                @PathVariable UUID id,
                @RequestParam String approverId,
                @RequestParam(required = false) String meaningOfSignature) {
            return service.approveMasterFormula(id, approverId, meaningOfSignature);
        }

        @GetMapping("/batches")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public List<ElectronicBatchRecordEntity> listBatches() {
            return service.listBatches();
        }

        @PostMapping("/batches")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public ElectronicBatchRecordEntity createBatch(@RequestBody CreateBatchOrderRequest req) {
            return service.createBatchOrder(req);
        }

        @PostMapping("/batches/{id}/line-clearance")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public ElectronicBatchRecordEntity verifyLineClearance(@PathVariable UUID id, @RequestParam String inspectorId) {
            return service.verifyLineClearance(id, inspectorId);
        }

        @PostMapping("/batches/{id}/dispensing")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public MaterialDispensingEntity recordWeighing(@PathVariable UUID id, @RequestBody RecordMaterialWeighingRequest req) {
            return service.recordWeighedMaterial(id, req);
        }

        @PostMapping("/batches/{id}/ipc")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public InProcessControlEntity recordIpc(@PathVariable UUID id, @RequestBody RecordIpcRequest req) {
            return service.recordInProcessControl(id, req);
        }

        @PostMapping("/batches/{id}/reconcile-yield")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public ElectronicBatchRecordEntity reconcileYield(@PathVariable UUID id, @RequestParam BigDecimal actualYield) {
            return service.reconcileYieldAndSubmitToQa(id, actualYield);
        }

        @PostMapping("/batches/{id}/qa-disposition")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public ElectronicBatchRecordEntity recordQaDisposition(@PathVariable UUID id, @RequestBody QaBatchDispositionRequest req) {
            return service.recordQaDisposition(id, req);
        }

        @GetMapping("/batches/{id}/genealogy")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public BatchGenealogyReport getGenealogy(@PathVariable UUID id) {
            return service.getBatchGenealogy(id);
        }

        @GetMapping("/genealogy/by-material-lot/{lot}")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:manage')")
        public List<MaterialDispensingEntity> traceByLot(@PathVariable String lot) {
            return service.traceBatchesUsingMaterialLot(lot);
        }
    }
}
