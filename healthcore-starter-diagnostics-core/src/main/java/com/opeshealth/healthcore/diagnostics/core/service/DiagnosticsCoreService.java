/*
 * =============================================================================
 * Opes HealthCore SDK — Diagnostics Center Core Service (ISO 15189-Aligned)
 * =============================================================================
 */
package com.opeshealth.healthcore.diagnostics.core.service;

import com.opeshealth.healthcore.diagnostics.core.domain.DiagnosticsCoreEntities.*;
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
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class DiagnosticsCoreService {

    /**
     * Pluggable QC policy SPI (Westgard rules, modality QA calibration gates) evaluated before
     * releasing verified diagnostic reports.
     */
    public interface DiagnosticsQcPolicySpi {
        boolean isReleasePermitted(DiagnosticServiceOrderEntity order);
    }

    /**
     * Pluggable critical-value escalation SPI allowing facilities to plug in SMS/pager/FHIR Communication
     * routing rules on panic values.
     */
    public interface CriticalValueEscalationSpi {
        boolean isCritical(DiagnosticCatalogItemEntity catalogItem, BigDecimal numericValue);
    }

    public interface ReferringPartnerRepository extends JpaRepository<ReferringPartnerEntity, UUID> {
        List<ReferringPartnerEntity> findByFacilityIdOrderByPartnerNameAsc(UUID facilityId);
    }

    public interface DiagnosticCatalogRepository extends JpaRepository<DiagnosticCatalogItemEntity, UUID> {
        Optional<DiagnosticCatalogItemEntity> findByFacilityIdAndCatalogCode(UUID facilityId, String catalogCode);
    }

    public interface DiagnosticServiceOrderRepository extends JpaRepository<DiagnosticServiceOrderEntity, UUID> {
        Optional<DiagnosticServiceOrderEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<DiagnosticServiceOrderEntity> findByFacilityIdOrderByOrderedAtDesc(UUID facilityId);
    }

    public interface CriticalNotificationRepository extends JpaRepository<CriticalValueNotificationEntity, UUID> {
        Optional<CriticalValueNotificationEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface ResultAmendmentRepository extends JpaRepository<ResultAmendmentEntity, UUID> {
        List<ResultAmendmentEntity> findByFacilityIdAndOrderId(UUID facilityId, UUID orderId);
    }

    public interface QualityIncidentRepository extends JpaRepository<QualityIncidentEntity, UUID> {
        Optional<QualityIncidentEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public record RegisterReferringPartnerRequest(
            String partnerCode,
            String partnerName,
            String partnerType,
            String reportDeliveryChannel
    ) {}

    public record RegisterCatalogItemRequest(
            String catalogCode,
            String displayName,
            String modalityDomain,
            boolean packagePanel,
            String unitUcum,
            BigDecimal refLow,
            BigDecimal refHigh,
            BigDecimal criticalLow,
            BigDecimal criticalHigh,
            int tatSlaMinutes,
            String sendOutPartner
    ) {}

    public record CreateDiagnosticOrderRequest(
            String accessionNumber,
            String modalityDomain,
            String catalogCode,
            String subjectRef,
            String referringPartnerCode,
            int tatSlaMinutes
    ) {}

    public record EscalateCriticalResultRequest(
            UUID orderId,
            BigDecimal numericValue,
            String criticalValueSummary,
            String notifiedClinician
    ) {}

    public record AmendResultRequest(
            UUID orderId,
            String priorResultSummary,
            String amendedResultSummary,
            String amendmentReason,
            String amendedBy
    ) {}

    public record LogQualityIncidentRequest(
            String incidentCode,
            String phase,
            String accessionNumber,
            String description,
            String reportedBy
    ) {}

    private final ReferringPartnerRepository partnerRepo;
    private final DiagnosticCatalogRepository catalogRepo;
    private final DiagnosticServiceOrderRepository orderRepo;
    private final CriticalNotificationRepository criticalRepo;
    private final ResultAmendmentRepository amendmentRepo;
    private final QualityIncidentRepository incidentRepo;
    private final DiagnosticsQcPolicySpi qcPolicySpi;
    private final CriticalValueEscalationSpi criticalSpi;

    public DiagnosticsCoreService(
            ReferringPartnerRepository partnerRepo,
            DiagnosticCatalogRepository catalogRepo,
            DiagnosticServiceOrderRepository orderRepo,
            CriticalNotificationRepository criticalRepo,
            ResultAmendmentRepository amendmentRepo,
            QualityIncidentRepository incidentRepo,
            ObjectProvider<DiagnosticsQcPolicySpi> qcPolicyProvider,
            ObjectProvider<CriticalValueEscalationSpi> criticalSpiProvider) {
        this.partnerRepo = partnerRepo;
        this.catalogRepo = catalogRepo;
        this.orderRepo = orderRepo;
        this.criticalRepo = criticalRepo;
        this.amendmentRepo = amendmentRepo;
        this.incidentRepo = incidentRepo;
        this.qcPolicySpi = qcPolicyProvider != null
                ? qcPolicyProvider.getIfAvailable(() -> order -> !"REJECTED_RECOLLECT".equals(order.getStatus()))
                : order -> !"REJECTED_RECOLLECT".equals(order.getStatus());
        this.criticalSpi = criticalSpiProvider != null
                ? criticalSpiProvider.getIfAvailable(() -> DiagnosticsCoreService::defaultCriticalCheck)
                : DiagnosticsCoreService::defaultCriticalCheck;
    }

    private static boolean defaultCriticalCheck(DiagnosticCatalogItemEntity item, BigDecimal val) {
        // Unknown test or missing measurement cannot establish a critical threshold.
        if (item == null || val == null) return false;
        if (item.getCriticalLow() != null && val.compareTo(item.getCriticalLow()) <= 0) return true;
        return item.getCriticalHigh() != null && val.compareTo(item.getCriticalHigh()) >= 0;
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_PARTNER_REGISTER", resourceType = "ReferringPartner")
    public ReferringPartnerEntity registerReferringPartner(RegisterReferringPartnerRequest req) {
        UUID facId = requireFacility();
        ReferringPartnerEntity partner = new ReferringPartnerEntity();
        partner.setFacilityId(facId);
        partner.setPartnerCode(req.partnerCode().trim().toUpperCase(Locale.ROOT));
        partner.setPartnerName(req.partnerName().trim());
        partner.setPartnerType(req.partnerType().trim().toUpperCase(Locale.ROOT));
        partner.setReportDeliveryChannel(req.reportDeliveryChannel() == null ? "PORTAL" : req.reportDeliveryChannel().trim().toUpperCase(Locale.ROOT));
        return partnerRepo.save(partner);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_CATALOG_REGISTER", resourceType = "DiagnosticCatalogItem")
    public DiagnosticCatalogItemEntity registerCatalogItem(RegisterCatalogItemRequest req) {
        UUID facId = requireFacility();
        DiagnosticCatalogItemEntity item = new DiagnosticCatalogItemEntity();
        item.setFacilityId(facId);
        item.setCatalogCode(req.catalogCode().trim().toUpperCase(Locale.ROOT));
        item.setDisplayName(req.displayName().trim());
        item.setModalityDomain(req.modalityDomain().trim().toUpperCase(Locale.ROOT));
        item.setPackagePanel(req.packagePanel());
        item.setUnitUcum(req.unitUcum());
        item.setRefLow(req.refLow());
        item.setRefHigh(req.refHigh());
        item.setCriticalLow(req.criticalLow());
        item.setCriticalHigh(req.criticalHigh());
        item.setTatSlaMinutes(req.tatSlaMinutes() > 0 ? req.tatSlaMinutes() : 180);
        item.setSendOutPartner(req.sendOutPartner());
        return catalogRepo.save(item);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_ORDER_CREATE", resourceType = "DiagnosticServiceOrder")
    public DiagnosticServiceOrderEntity createOrder(CreateDiagnosticOrderRequest req) {
        UUID facId = requireFacility();
        DiagnosticServiceOrderEntity order = new DiagnosticServiceOrderEntity();
        order.setFacilityId(facId);
        order.setAccessionNumber(req.accessionNumber().trim().toUpperCase(Locale.ROOT));
        order.setModalityDomain(req.modalityDomain().trim().toUpperCase(Locale.ROOT));
        order.setCatalogCode(req.catalogCode().trim().toUpperCase(Locale.ROOT));
        order.setSubjectRef(req.subjectRef().trim());
        order.setReferringPartnerCode(req.referringPartnerCode());
        order.setTatSlaMinutes(req.tatSlaMinutes() > 0 ? req.tatSlaMinutes() : 180);
        order.setStatus("REGISTERED");
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_SPECIMEN_REJECT", resourceType = "DiagnosticServiceOrder")
    public DiagnosticServiceOrderEntity rejectAndRequestRecollection(UUID orderId, String reason) {
        UUID facId = requireFacility();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("ISO 15189 pre-analytical rule: rejection reason is mandatory.");
        }
        DiagnosticServiceOrderEntity order = orderRepo.findByIdAndFacilityId(orderId, facId)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic order not found: " + orderId));
        if ("VERIFIED_RELEASED".equals(order.getStatus()) || "AMENDED".equals(order.getStatus())) {
            throw new IllegalStateException("Released diagnostic reports require an audited amendment, not specimen rejection");
        }
        if ("REJECTED_RECOLLECT".equals(order.getStatus()) && reason.trim().equals(order.getRejectionReason())) {
            return order; // Repeat request does not overwrite rejection evidence.
        }
        order.setRejectionReason(reason.trim());
        order.setRecollectionRequested(true);
        order.setStatus("REJECTED_RECOLLECT");
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_CRITICAL_ESCALATE", resourceType = "CriticalValueNotification")
    public CriticalValueNotificationEntity escalateCriticalResult(EscalateCriticalResultRequest req) {
        UUID facId = requireFacility();
        DiagnosticServiceOrderEntity order = orderRepo.findByIdAndFacilityId(req.orderId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic order not found: " + req.orderId()));
        if (req.numericValue() == null || req.criticalValueSummary() == null
                || req.criticalValueSummary().isBlank() || req.notifiedClinician() == null
                || req.notifiedClinician().isBlank()) {
            throw new IllegalArgumentException("Measured critical value, summary, and responsible clinician are required");
        }
        if ("REJECTED_RECOLLECT".equals(order.getStatus())) {
            throw new IllegalStateException("Cannot escalate an invalid or rejected specimen result");
        }
        DiagnosticCatalogItemEntity item = catalogRepo.findByFacilityIdAndCatalogCode(facId, order.getCatalogCode())
                .orElseThrow(() -> new IllegalStateException("No authorized diagnostic catalog thresholds for order"));
        if (item.getCriticalLow() == null && item.getCriticalHigh() == null) {
            throw new IllegalStateException("Critical notification requires a configured low/high threshold");
        }
        if (!criticalSpi.isCritical(item, req.numericValue())) {
            throw new IllegalArgumentException("Numeric value " + req.numericValue() + " is outside configured critical threshold conditions for " + item.getCatalogCode());
        }
        CriticalValueNotificationEntity notif = new CriticalValueNotificationEntity();
        notif.setFacilityId(facId);
        notif.setOrderId(order.getId());
        notif.setAccessionNumber(order.getAccessionNumber());
        notif.setCriticalValueSummary(req.criticalValueSummary().trim());
        notif.setNotifiedClinician(req.notifiedClinician().trim());
        notif.setStatus("ESCALATED");
        return criticalRepo.save(notif);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_CRITICAL_READBACK_ACK", resourceType = "CriticalValueNotification")
    public CriticalValueNotificationEntity acknowledgeCriticalReadBack(UUID notificationId, String acknowledgedBy, boolean readBackVerified) {
        UUID facId = requireFacility();
        if (!readBackVerified) {
            throw new IllegalStateException("Clinician read-back verification is mandatory to close a critical value alert.");
        }
        if (notificationId == null || acknowledgedBy == null || acknowledgedBy.isBlank()) {
            throw new IllegalArgumentException("Critical notification ID and accountable clinician are required");
        }
        CriticalValueNotificationEntity notif = criticalRepo.findByIdAndFacilityId(notificationId, facId)
                .orElseThrow(() -> new NoSuchElementException("Critical notification not found: " + notificationId));
        String clinician = acknowledgedBy.trim();
        if ("ACKNOWLEDGED_READBACK".equals(notif.getStatus())) {
            if (notif.isReadBackVerified() && clinician.equals(notif.getAcknowledgedBy())
                    && notif.getAcknowledgedAt() != null) {
                return notif; // At-least-once callback is safe: preserve first acknowledgment evidence.
            }
            throw new IllegalStateException("Critical read-back has already been closed by another actor");
        }
        if (!"ESCALATED".equals(notif.getStatus())
                || !clinician.equals(notif.getNotifiedClinician())) {
            throw new IllegalStateException("Only the notified responsible clinician may close an active critical alert");
        }
        notif.setReadBackVerified(true);
        notif.setAcknowledgedBy(clinician);
        notif.setAcknowledgedAt(Instant.now());
        notif.setStatus("ACKNOWLEDGED_READBACK");
        return criticalRepo.save(notif);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_ORDER_VERIFY_RELEASE", resourceType = "DiagnosticServiceOrder")
    public DiagnosticServiceOrderEntity verifyAndReleaseReport(UUID orderId) {
        UUID facId = requireFacility();
        DiagnosticServiceOrderEntity order = orderRepo.findByIdAndFacilityId(orderId, facId)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic order not found: " + orderId));
        if ("VERIFIED_RELEASED".equals(order.getStatus())) {
            return order; // A retry cannot reset the original verification timestamp.
        }
        if (!("REGISTERED".equals(order.getStatus()) || "IN_PROGRESS".equals(order.getStatus()))
                || !qcPolicySpi.isReleasePermitted(order)) {
            throw new IllegalStateException("Diagnostics QC/state release gate blocked order " + order.getAccessionNumber()
                    + " (status=" + order.getStatus() + ").");
        }
        // This starter does not itself prove physical specimen QC or a signed patient report.
        // Production consumers MUST register a fail-closed QC policy backed by verified result evidence.
        Instant now = Instant.now();
        order.setVerifiedAt(now);
        long elapsedMinutes = Duration.between(order.getOrderedAt(), now).toMinutes();
        order.setTatBreached(elapsedMinutes > order.getTatSlaMinutes());
        order.setStatus("VERIFIED_RELEASED");
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_RESULT_AMEND", resourceType = "ResultAmendment")
    public ResultAmendmentEntity amendVerifiedResult(AmendResultRequest req) {
        UUID facId = requireFacility();
        if (req == null || req.orderId() == null || req.amendmentReason() == null
                || req.amendmentReason().isBlank() || req.priorResultSummary() == null
                || req.priorResultSummary().isBlank() || req.amendedResultSummary() == null
                || req.amendedResultSummary().isBlank() || req.amendedBy() == null
                || req.amendedBy().isBlank()) {
            throw new IllegalArgumentException("Signed result, previous and amended values, author, and amendment reason are required");
        }
        DiagnosticServiceOrderEntity order = orderRepo.findByIdAndFacilityId(req.orderId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic order not found: " + req.orderId()));
        if (order.getVerifiedAt() == null
                || !("VERIFIED_RELEASED".equals(order.getStatus()) || "AMENDED".equals(order.getStatus()))) {
            throw new IllegalStateException("Only a verified/released diagnostic report can be amended");
        }
        order.setStatus("AMENDED");
        orderRepo.save(order);

        ResultAmendmentEntity amend = new ResultAmendmentEntity();
        amend.setFacilityId(facId);
        amend.setOrderId(order.getId());
        amend.setPriorResultSummary(req.priorResultSummary().trim());
        amend.setAmendedResultSummary(req.amendedResultSummary().trim());
        amend.setAmendmentReason(req.amendmentReason().trim());
        amend.setAmendedBy(req.amendedBy().trim());
        return amendmentRepo.save(amend);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_ISO15189_INCIDENT", resourceType = "QualityIncident")
    public QualityIncidentEntity logQualityIncident(LogQualityIncidentRequest req) {
        UUID facId = requireFacility();
        QualityIncidentEntity inc = new QualityIncidentEntity();
        inc.setFacilityId(facId);
        inc.setIncidentCode(req.incidentCode().trim().toUpperCase(Locale.ROOT));
        inc.setPhase(req.phase().trim().toUpperCase(Locale.ROOT));
        inc.setAccessionNumber(req.accessionNumber());
        inc.setDescription(req.description().trim());
        inc.setReportedBy(req.reportedBy().trim());
        inc.setStatus("OPEN");
        return incidentRepo.save(inc);
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Diagnostics Core operations."));
    }

    @RestController
    @RequestMapping("/api/v1/diagnostics-core")
    public static class DiagnosticsCoreController {
        private final DiagnosticsCoreService service;

        public DiagnosticsCoreController(DiagnosticsCoreService service) {
            this.service = service;
        }

        @PostMapping("/referring-partners")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:order:create')")
        public ReferringPartnerEntity registerPartner(@RequestBody RegisterReferringPartnerRequest req) {
            return service.registerReferringPartner(req);
        }

        @PostMapping("/catalog")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public DiagnosticCatalogItemEntity registerCatalog(@RequestBody RegisterCatalogItemRequest req) {
            return service.registerCatalogItem(req);
        }

        @PostMapping("/orders")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:order:create')")
        public DiagnosticServiceOrderEntity createOrder(@RequestBody CreateDiagnosticOrderRequest req) {
            return service.createOrder(req);
        }

        @PostMapping("/orders/{id}/reject")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public DiagnosticServiceOrderEntity rejectOrder(@PathVariable UUID id, @RequestParam String reason) {
            return service.rejectAndRequestRecollection(id, reason);
        }

        @PostMapping("/critical-notifications")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public CriticalValueNotificationEntity escalateCritical(@RequestBody EscalateCriticalResultRequest req) {
            return service.escalateCriticalResult(req);
        }

        @PostMapping("/critical-notifications/{id}/acknowledge")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public CriticalValueNotificationEntity acknowledgeCritical(
                @PathVariable UUID id, @RequestParam String acknowledgedBy, @RequestParam boolean readBackVerified) {
            return service.acknowledgeCriticalReadBack(id, acknowledgedBy, readBackVerified);
        }

        @PostMapping("/orders/{id}/verify-release")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public DiagnosticServiceOrderEntity verifyRelease(@PathVariable UUID id) {
            return service.verifyAndReleaseReport(id);
        }

        @PostMapping("/amendments")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public ResultAmendmentEntity amend(@RequestBody AmendResultRequest req) {
            return service.amendVerifiedResult(req);
        }

        @PostMapping("/quality-incidents")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public QualityIncidentEntity logIncident(@RequestBody LogQualityIncidentRequest req) {
            return service.logQualityIncident(req);
        }
    }
}
