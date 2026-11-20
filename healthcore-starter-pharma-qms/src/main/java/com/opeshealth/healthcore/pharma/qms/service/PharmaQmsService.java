/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical QMS, QC Lab, CAPA & CoA Service & API
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
        Optional<QcCertificateEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface DeviationCapaRepository extends JpaRepository<DeviationCapaEntity, UUID> {
        List<DeviationCapaEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
        Optional<DeviationCapaEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
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

    private final ControlledDocumentRepository docRepo;
    private final QcCertificateRepository qcRepo;
    private final DeviationCapaRepository capaRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public PharmaQmsService(
            ControlledDocumentRepository docRepo,
            QcCertificateRepository qcRepo,
            DeviationCapaRepository capaRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.docRepo = docRepo;
        this.qcRepo = qcRepo;
        this.capaRepo = capaRepo;
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

        @PostMapping("/capa/{id}/close")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public DeviationCapaEntity closeCapa(@PathVariable UUID id, @RequestBody CloseDeviationCapaRequest req) {
            return service.closeDeviationOrCapa(id, req);
        }
    }
}
