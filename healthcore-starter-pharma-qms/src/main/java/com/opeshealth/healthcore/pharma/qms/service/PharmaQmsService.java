/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical QMS, QC Lab, CAPA, CoA, Equipment,
 * Stability, Supplier Qualification & Product Quality Review (PQR) Service
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.qms.service;

import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureCommand;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureProof;
import com.opeshealth.healthcore.pharma.qms.domain.PharmaQmsEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Service
public class PharmaQmsService {

    public interface ControlledDocumentRepository extends JpaRepository<ControlledDocumentEntity, UUID> {
        List<ControlledDocumentEntity> findByFacilityIdOrderByDocumentCodeAsc(UUID facilityId);
        Optional<ControlledDocumentEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface QcCertificateRepository extends JpaRepository<QcCertificateEntity, UUID> {
        List<QcCertificateEntity> findByFacilityIdOrderByIssuedAtDesc(UUID facilityId);
        List<QcCertificateEntity> findByFacilityIdAndProductCode(UUID facilityId, String productCode);
        Optional<QcCertificateEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface DeviationCapaRepository extends JpaRepository<DeviationCapaEntity, UUID> {
        List<DeviationCapaEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        Optional<DeviationCapaEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface EquipmentQualificationRepository extends JpaRepository<EquipmentQualificationEntity, UUID> {
        List<EquipmentQualificationEntity> findByFacilityIdOrderByAssetCodeAsc(UUID facilityId);
        Optional<EquipmentQualificationEntity> findByFacilityIdAndAssetCode(UUID facilityId, String assetCode);
    }

    public interface StabilitySampleRepository extends JpaRepository<StabilitySampleEntity, UUID> {
        List<StabilitySampleEntity> findByFacilityIdAndProductCodeOrderByScheduledPullDateAsc(UUID facilityId, String productCode);
        Optional<StabilitySampleEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface SupplierQualificationRepository extends JpaRepository<SupplierQualificationEntity, UUID> {
        List<SupplierQualificationEntity> findByFacilityIdOrderBySupplierCodeAsc(UUID facilityId);
        Optional<SupplierQualificationEntity> findByFacilityIdAndSupplierCode(UUID facilityId, String supplierCode);
    }

    public record CreateControlledDocRequest(
            String documentCode,
            String documentVersion,
            String documentType,
            String title,
            String documentBody,
            String authoredBy,
            LocalDate reviewDueDate
    ) {}

    public record RecordQcTestRequest(
            String certificateNumber,
            String batchNumber,
            String productCode,
            String sampleStage,
            String testResultsJson,
            boolean oosDetected,
            String analystId
    ) {}

    public record OpenDeviationCapaRequest(
            String recordNumber,
            String recordCategory,
            String severity,
            String affectedBatchNumber,
            String description,
            String reportedBy
    ) {}

    public record CloseDeviationCapaRequest(
            String rootCause,
            String capaPlan,
            boolean effectivenessVerified,
            String qaApproverId
    ) {}

    public record RegisterEquipmentRequest(
            String assetCode,
            String equipmentName,
            String qualificationStage,
            LocalDate lastCalibratedDate,
            LocalDate calibrationDueDate,
            String calibratedBy,
            String qaVerifiedBy
    ) {}

    public record ScheduleStabilitySampleRequest(
            String studyCode,
            String productCode,
            String batchNumber,
            String sampleKind,
            String storageCondition,
            int timepointMonths,
            LocalDate scheduledPullDate
    ) {}

    public record RecordStabilityResultRequest(
            String assaySummaryJson,
            String disposition,
            String analystId
    ) {}

    public record QualifySupplierRequest(
            String supplierCode,
            String supplierName,
            String materialScope,
            LocalDate auditDate,
            LocalDate requalificationDueDate,
            String qaApproverId,
            String auditorId
    ) {}

    private final ControlledDocumentRepository docRepo;
    private final QcCertificateRepository qcRepo;
    private final DeviationCapaRepository capaRepo;
    private final EquipmentQualificationRepository equipmentRepo;
    private final StabilitySampleRepository stabilityRepo;
    private final SupplierQualificationRepository supplierRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public PharmaQmsService(
            ControlledDocumentRepository docRepo,
            QcCertificateRepository qcRepo,
            DeviationCapaRepository capaRepo,
            EquipmentQualificationRepository equipmentRepo,
            StabilitySampleRepository stabilityRepo,
            SupplierQualificationRepository supplierRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.docRepo = docRepo;
        this.qcRepo = qcRepo;
        this.capaRepo = capaRepo;
        this.equipmentRepo = equipmentRepo;
        this.stabilityRepo = stabilityRepo;
        this.supplierRepo = supplierRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SOP_CREATE", resourceType = "ControlledDocument")
    public ControlledDocumentEntity createControlledDocument(CreateControlledDocRequest req) {
        UUID facId = requireFacility();
        ControlledDocumentEntity doc = new ControlledDocumentEntity();
        doc.setFacilityId(facId);
        doc.setDocumentCode(req.documentCode().trim().toUpperCase(Locale.ROOT));
        doc.setDocumentVersion(req.documentVersion().trim());
        doc.setDocumentType(req.documentType().trim().toUpperCase(Locale.ROOT));
        doc.setTitle(req.title().trim());
        doc.setContentSha256(ElectronicSignatureSpi.sha256Hex(req.documentBody() == null ? "" : req.documentBody()));
        doc.setAuthoredBy(req.authoredBy().trim());
        doc.setReviewDueDate(req.reviewDueDate());
        doc.setStatus("DRAFT");
        return docRepo.save(doc);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SOP_APPROVE", resourceType = "ControlledDocument")
    public ControlledDocumentEntity approveControlledDocument(UUID docId, String approverId, LocalDate effectiveDate) {
        UUID facId = requireFacility();
        ControlledDocumentEntity doc = docRepo.findByIdAndFacilityId(docId, facId)
                .orElseThrow(() -> new NoSuchElementException("Controlled document not found: " + docId));
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                approverId, "QA_DOCUMENT_CONTROL", "CONTROLLED_DOC_EFFECTIVE",
                "ControlledDocument", docId.toString(),
                doc.getDocumentCode() + "|" + doc.getDocumentVersion() + "|" + doc.getContentSha256(),
                doc.getAuthoredBy(), true
        ));
        doc.setStatus("EFFECTIVE");
        doc.setApprovedBy(proof.signerId());
        doc.setApprovalSigSha256(proof.signatureDigestSha256());
        doc.setEffectiveDate(effectiveDate != null ? effectiveDate : LocalDate.now());
        return docRepo.save(doc);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_QC_RECORD", resourceType = "QcCertificate")
    public QcCertificateEntity recordQcTest(RecordQcTestRequest req) {
        UUID facId = requireFacility();
        QcCertificateEntity cert = new QcCertificateEntity();
        cert.setFacilityId(facId);
        cert.setCertificateNumber(req.certificateNumber().trim().toUpperCase(Locale.ROOT));
        cert.setBatchNumber(req.batchNumber().trim().toUpperCase(Locale.ROOT));
        cert.setProductCode(req.productCode().trim().toUpperCase(Locale.ROOT));
        cert.setSampleStage(req.sampleStage().trim().toUpperCase(Locale.ROOT));
        cert.setTestResultsJson(req.testResultsJson());
        cert.setOosDetected(req.oosDetected());
        cert.setAnalystId(req.analystId().trim());
        cert.setQcDisposition(req.oosDetected() ? "OOS_HOLD" : "PENDING_REVIEW");
        return qcRepo.save(cert);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_COA_APPROVE", resourceType = "QcCertificate")
    public QcCertificateEntity issueCertificateOfAnalysis(UUID qcId, String qaApproverId) {
        UUID facId = requireFacility();
        QcCertificateEntity cert = qcRepo.findByIdAndFacilityId(qcId, facId)
                .orElseThrow(() -> new NoSuchElementException("QC record not found: " + qcId));
        if (cert.isOosDetected()) {
            throw new IllegalStateException("Cannot issue passing Certificate of Analysis while Out-of-Specification (OOS) flag is active.");
        }
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                qaApproverId, "QC_QA_RELEASE_APPROVER", "COA_ISSUED",
                "QcCertificate", qcId.toString(),
                cert.getCertificateNumber() + "|" + cert.getBatchNumber() + "|" + cert.getTestResultsJson(),
                cert.getAnalystId(), true
        ));
        cert.setQcDisposition("PASS_COA_ISSUED");
        cert.setQaApproverId(proof.signerId());
        cert.setCoaSigSha256(proof.signatureDigestSha256());
        return qcRepo.save(cert);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_CAPA_OPEN", resourceType = "DeviationCapa")
    public DeviationCapaEntity openDeviationOrCapa(OpenDeviationCapaRequest req) {
        UUID facId = requireFacility();
        DeviationCapaEntity entity = new DeviationCapaEntity();
        entity.setFacilityId(facId);
        entity.setRecordNumber(req.recordNumber().trim().toUpperCase(Locale.ROOT));
        entity.setRecordCategory(req.recordCategory().trim().toUpperCase(Locale.ROOT));
        entity.setSeverity(req.severity().trim().toUpperCase(Locale.ROOT));
        entity.setAffectedBatchNumber(req.affectedBatchNumber());
        entity.setDescription(req.description().trim());
        entity.setReportedBy(req.reportedBy().trim());
        entity.setStatus("OPEN");
        return capaRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_COMPLAINT_ESCALATE_RECALL", resourceType = "DeviationCapa")
    public DeviationCapaEntity escalateComplaintToRecall(UUID complaintId, String recallRecordNumber, String qaApproverId, String recallReason) {
        UUID facId = requireFacility();
        DeviationCapaEntity complaint = capaRepo.findByIdAndFacilityId(complaintId, facId)
                .orElseThrow(() -> new NoSuchElementException("Complaint record not found: " + complaintId));
        if (!"COMPLAINT".equals(complaint.getRecordCategory())) {
            throw new IllegalStateException("Only COMPLAINT records can be escalated to RECALL; found=" + complaint.getRecordCategory());
        }
        signatureSpi.sign(new ElectronicSignatureCommand(
                qaApproverId, "QA_RECALL_COORDINATOR", "PRODUCT_RECALL_INITIATED",
                "DeviationCapa", complaintId.toString(),
                complaint.getRecordNumber() + "|" + recallRecordNumber + "|" + recallReason,
                complaint.getReportedBy(), true
        ));
        DeviationCapaEntity recall = new DeviationCapaEntity();
        recall.setFacilityId(facId);
        recall.setRecordNumber(recallRecordNumber.trim().toUpperCase(Locale.ROOT));
        recall.setRecordCategory("RECALL");
        recall.setSeverity("CRITICAL");
        recall.setAffectedBatchNumber(complaint.getAffectedBatchNumber());
        recall.setDescription("Escalated from " + complaint.getRecordNumber() + ": " + recallReason.trim());
        recall.setReportedBy(qaApproverId.trim());
        recall.setStatus("OPEN");
        return capaRepo.save(recall);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_CAPA_CLOSE", resourceType = "DeviationCapa")
    public DeviationCapaEntity closeDeviationOrCapa(UUID capaId, CloseDeviationCapaRequest req) {
        UUID facId = requireFacility();
        DeviationCapaEntity entity = capaRepo.findByIdAndFacilityId(capaId, facId)
                .orElseThrow(() -> new NoSuchElementException("Deviation/CAPA record not found: " + capaId));
        if (req.rootCause() == null || req.rootCause().isBlank() || req.capaPlan() == null || req.capaPlan().isBlank()) {
            throw new IllegalArgumentException("Documented rootCause and capaPlan are mandatory prior to closure.");
        }
        if ("CRITICAL".equals(entity.getSeverity()) && !req.effectivenessVerified()) {
            throw new IllegalStateException("CRITICAL deviations/CAPAs cannot be closed without effectiveness verification.");
        }
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                req.qaApproverId(), "QA_CAPA_APPROVER", "DEVIATION_CAPA_CLOSED",
                "DeviationCapa", capaId.toString(),
                entity.getRecordNumber() + "|" + req.rootCause() + "|" + req.capaPlan(),
                entity.getReportedBy(), true
        ));
        entity.setRootCause(req.rootCause().trim());
        entity.setCapaPlan(req.capaPlan().trim());
        entity.setEffectivenessVerified(req.effectivenessVerified());
        entity.setStatus("CLOSED");
        entity.setClosedBy(proof.signerId());
        entity.setClosureSigSha256(proof.signatureDigestSha256());
        entity.setClosedAt(proof.signedAt());
        return capaRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_EQUIPMENT_REGISTER", resourceType = "EquipmentQualification")
    public EquipmentQualificationEntity registerEquipmentQualification(RegisterEquipmentRequest req) {
        UUID facId = requireFacility();
        EquipmentQualificationEntity eq = new EquipmentQualificationEntity();
        eq.setFacilityId(facId);
        eq.setAssetCode(req.assetCode().trim().toUpperCase(Locale.ROOT));
        eq.setEquipmentName(req.equipmentName().trim());
        eq.setQualificationStage(req.qualificationStage().trim().toUpperCase(Locale.ROOT));
        eq.setLastCalibratedDate(req.lastCalibratedDate());
        eq.setCalibrationDueDate(req.calibrationDueDate());
        eq.setCalibratedBy(req.calibratedBy().trim());
        eq.setQaVerifiedBy(req.qaVerifiedBy());
        eq.setCalibrationStatus(req.calibrationDueDate().isBefore(LocalDate.now()) ? "CALIBRATION_EXPIRED" : "QUALIFIED");
        return equipmentRepo.save(eq);
    }

    @Transactional(readOnly = true)
    public EquipmentQualificationEntity assertEquipmentQualifiedForProduction(String assetCode) {
        UUID facId = requireFacility();
        EquipmentQualificationEntity eq = equipmentRepo.findByFacilityIdAndAssetCode(facId, assetCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Equipment asset not registered: " + assetCode));
        if (!"QUALIFIED".equals(eq.getCalibrationStatus()) || eq.getCalibrationDueDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("GMP calibration gate blocked production allocation for asset "
                    + eq.getAssetCode() + " (status=" + eq.getCalibrationStatus()
                    + ", dueDate=" + eq.getCalibrationDueDate() + ").");
        }
        return eq;
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_STABILITY_SCHEDULE", resourceType = "StabilitySample")
    public StabilitySampleEntity scheduleStabilitySample(ScheduleStabilitySampleRequest req) {
        UUID facId = requireFacility();
        StabilitySampleEntity sample = new StabilitySampleEntity();
        sample.setFacilityId(facId);
        sample.setStudyCode(req.studyCode().trim().toUpperCase(Locale.ROOT));
        sample.setProductCode(req.productCode().trim().toUpperCase(Locale.ROOT));
        sample.setBatchNumber(req.batchNumber().trim().toUpperCase(Locale.ROOT));
        sample.setSampleKind(req.sampleKind().trim().toUpperCase(Locale.ROOT));
        sample.setStorageCondition(req.storageCondition().trim());
        sample.setTimepointMonths(req.timepointMonths());
        sample.setScheduledPullDate(req.scheduledPullDate());
        sample.setDisposition("SCHEDULED");
        return stabilityRepo.save(sample);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_STABILITY_RESULT", resourceType = "StabilitySample")
    public StabilitySampleEntity recordStabilityPullResult(UUID sampleId, RecordStabilityResultRequest req) {
        UUID facId = requireFacility();
        StabilitySampleEntity sample = stabilityRepo.findByIdAndFacilityId(sampleId, facId)
                .orElseThrow(() -> new NoSuchElementException("Stability sample not found: " + sampleId));
        sample.setActualPullDate(LocalDate.now());
        sample.setAssaySummaryJson(req.assaySummaryJson());
        sample.setDisposition(req.disposition().trim().toUpperCase(Locale.ROOT));
        sample.setAnalystId(req.analystId().trim());
        return stabilityRepo.save(sample);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SUPPLIER_QUALIFY", resourceType = "SupplierQualification")
    public SupplierQualificationEntity qualifySupplier(QualifySupplierRequest req) {
        UUID facId = requireFacility();
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                req.qaApproverId(), "QA_SUPPLIER_QUALIFICATION", "SUPPLIER_APPROVED",
                "SupplierQualification", req.supplierCode(),
                req.supplierCode() + "|" + req.materialScope() + "|" + req.auditDate(),
                req.auditorId(), true
        ));
        SupplierQualificationEntity sup = new SupplierQualificationEntity();
        sup.setFacilityId(facId);
        sup.setSupplierCode(req.supplierCode().trim().toUpperCase(Locale.ROOT));
        sup.setSupplierName(req.supplierName().trim());
        sup.setMaterialScope(req.materialScope().trim());
        sup.setAuditDate(req.auditDate());
        sup.setRequalificationDueDate(req.requalificationDueDate());
        sup.setQualificationStatus(req.requalificationDueDate().isBefore(LocalDate.now()) ? "SUSPENDED" : "APPROVED");
        sup.setQaApproverId(proof.signerId());
        sup.setApprovalSigSha256(proof.signatureDigestSha256());
        return supplierRepo.save(sup);
    }

    @Transactional(readOnly = true)
    public SupplierQualificationEntity assertSupplierApproved(String supplierCode) {
        UUID facId = requireFacility();
        SupplierQualificationEntity sup = supplierRepo.findByFacilityIdAndSupplierCode(facId, supplierCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Supplier not registered: " + supplierCode));
        if (!"APPROVED".equals(sup.getQualificationStatus()) || sup.getRequalificationDueDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("GMP supplier gate blocked receipt from supplier " + sup.getSupplierCode());
        }
        return sup;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> generateProductQualityReview(String productCode) {
        UUID facId = requireFacility();
        String code = productCode.trim().toUpperCase(Locale.ROOT);
        List<QcCertificateEntity> certs = qcRepo.findByFacilityIdAndProductCode(facId, code);
        long passedCoa = certs.stream().filter(c -> "PASS_COA_ISSUED".equals(c.getQcDisposition())).count();
        long oosCount = certs.stream().filter(QcCertificateEntity::isOosDetected).count();
        List<StabilitySampleEntity> stability = stabilityRepo.findByFacilityIdAndProductCodeOrderByScheduledPullDateAsc(facId, code);
        long stabilityAlerts = stability.stream().filter(s -> "OOT_ALERT".equals(s.getDisposition()) || "OOS_FAIL".equals(s.getDisposition())).count();
        List<DeviationCapaEntity> capas = capaRepo.findByFacilityIdOrderByCreatedAtDesc(facId);
        long openCapas = capas.stream().filter(c -> !"CLOSED".equals(c.getStatus())).count();
        List<EquipmentQualificationEntity> equipment = equipmentRepo.findByFacilityIdOrderByAssetCodeAsc(facId);
        long expiredEquipment = equipment.stream()
                .filter(e -> !"QUALIFIED".equals(e.getCalibrationStatus()) || e.getCalibrationDueDate().isBefore(LocalDate.now()))
                .count();

        Map<String, Object> pqr = new LinkedHashMap<>();
        pqr.put("productCode", code);
        pqr.put("generatedDate", LocalDate.now().toString());
        pqr.put("totalQcBatchesEvaluated", certs.size());
        pqr.put("coaPassedCount", passedCoa);
        pqr.put("oosBatchCount", oosCount);
        pqr.put("stabilitySamplesTracked", stability.size());
        pqr.put("stabilityAlertOrFailCount", stabilityAlerts);
        pqr.put("facilityOpenDeviationCapaCount", openCapas);
        pqr.put("expiredCalibrationAssetCount", expiredEquipment);
        return pqr;
    }

    @Transactional(readOnly = true)
    public List<ControlledDocumentEntity> listDocuments() {
        return docRepo.findByFacilityIdOrderByDocumentCodeAsc(requireFacility());
    }

    @Transactional(readOnly = true)
    public List<QcCertificateEntity> listQcCertificates() {
        return qcRepo.findByFacilityIdOrderByIssuedAtDesc(requireFacility());
    }

    @Transactional(readOnly = true)
    public List<DeviationCapaEntity> listDeviationsAndCapas() {
        return capaRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for pharmaceutical QMS operations."));
    }

    @RestController
    @RequestMapping("/api/v1/pharma/qms")
    public static class PharmaQmsController {
        private final PharmaQmsService service;

        public PharmaQmsController(PharmaQmsService service) {
            this.service = service;
        }

        @GetMapping("/documents")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public List<ControlledDocumentEntity> listDocs() {
            return service.listDocuments();
        }

        @PostMapping("/documents")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public ControlledDocumentEntity createDoc(@RequestBody CreateControlledDocRequest req) {
            return service.createControlledDocument(req);
        }

        @PostMapping("/documents/{id}/approve")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public ControlledDocumentEntity approveDoc(@PathVariable UUID id, @RequestParam String approverId) {
            return service.approveControlledDocument(id, approverId, LocalDate.now());
        }

        @GetMapping("/qc")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public List<QcCertificateEntity> listQc() {
            return service.listQcCertificates();
        }

        @PostMapping("/qc")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public QcCertificateEntity recordQc(@RequestBody RecordQcTestRequest req) {
            return service.recordQcTest(req);
        }

        @PostMapping("/qc/{id}/issue-coa")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public QcCertificateEntity issueCoa(@PathVariable UUID id, @RequestParam String qaApproverId) {
            return service.issueCertificateOfAnalysis(id, qaApproverId);
        }

        @GetMapping("/capa")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public List<DeviationCapaEntity> listCapas() {
            return service.listDeviationsAndCapas();
        }

        @PostMapping("/capa")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public DeviationCapaEntity openCapa(@RequestBody OpenDeviationCapaRequest req) {
            return service.openDeviationOrCapa(req);
        }

        @PostMapping("/capa/{id}/escalate-recall")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public DeviationCapaEntity escalateRecall(
                @PathVariable UUID id,
                @RequestParam String recallRecordNumber,
                @RequestParam String qaApproverId,
                @RequestParam String recallReason) {
            return service.escalateComplaintToRecall(id, recallRecordNumber, qaApproverId, recallReason);
        }

        @PostMapping("/capa/{id}/close")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public DeviationCapaEntity closeCapa(@PathVariable UUID id, @RequestBody CloseDeviationCapaRequest req) {
            return service.closeDeviationOrCapa(id, req);
        }

        @PostMapping("/equipment")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public EquipmentQualificationEntity registerEquipment(@RequestBody RegisterEquipmentRequest req) {
            return service.registerEquipmentQualification(req);
        }

        @GetMapping("/equipment/{assetCode}/verify-qualified")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public EquipmentQualificationEntity verifyEquipment(@PathVariable String assetCode) {
            return service.assertEquipmentQualifiedForProduction(assetCode);
        }

        @PostMapping("/stability")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public StabilitySampleEntity scheduleStability(@RequestBody ScheduleStabilitySampleRequest req) {
            return service.scheduleStabilitySample(req);
        }

        @PostMapping("/stability/{id}/result")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public StabilitySampleEntity recordStabilityResult(@PathVariable UUID id, @RequestBody RecordStabilityResultRequest req) {
            return service.recordStabilityPullResult(id, req);
        }

        @PostMapping("/suppliers/qualify")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public SupplierQualificationEntity qualifySupplier(@RequestBody QualifySupplierRequest req) {
            return service.qualifySupplier(req);
        }

        @GetMapping("/pqr/{productCode}")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public Map<String, Object> productQualityReview(@PathVariable String productCode) {
            return service.generateProductQualityReview(productCode);
        }
    }
}
