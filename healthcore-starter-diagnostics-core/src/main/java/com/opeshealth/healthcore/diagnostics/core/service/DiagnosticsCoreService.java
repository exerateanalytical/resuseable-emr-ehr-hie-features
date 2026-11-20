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

    public interface ReferringPartnerRepository extends JpaRepository<ReferringPartnerEntity, UUID> {
        List<ReferringPartnerEntity> findByFacilityIdOrderByPartnerNameAsc(UUID facilityId);
    }

    public interface DiagnosticServiceOrderRepository extends JpaRepository<DiagnosticServiceOrderEntity, UUID> {
        Optional<DiagnosticServiceOrderEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<DiagnosticServiceOrderEntity> findByFacilityIdOrderByOrderedAtDesc(UUID facilityId);
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

    public record CreateDiagnosticOrderRequest(
            String accessionNumber,
            String modalityDomain,
            String catalogCode,
            String subjectRef,
            String referringPartnerCode,
            int tatSlaMinutes
    ) {}

    public record LogQualityIncidentRequest(
            String incidentCode,
            String phase,
            String accessionNumber,
            String description,
            String reportedBy
    ) {}

    private final ReferringPartnerRepository partnerRepo;
    private final DiagnosticServiceOrderRepository orderRepo;
    private final QualityIncidentRepository incidentRepo;
    private final DiagnosticsQcPolicySpi qcPolicySpi;

    public DiagnosticsCoreService(
            ReferringPartnerRepository partnerRepo,
            DiagnosticServiceOrderRepository orderRepo,
            QualityIncidentRepository incidentRepo,
            ObjectProvider<DiagnosticsQcPolicySpi> qcPolicyProvider) {
        this.partnerRepo = partnerRepo;
        this.orderRepo = orderRepo;
        this.incidentRepo = incidentRepo;
        this.qcPolicySpi = qcPolicyProvider != null
                ? qcPolicyProvider.getIfAvailable(() -> order -> !"REJECTED_RECOLLECT".equals(order.getStatus()))
                : order -> !"REJECTED_RECOLLECT".equals(order.getStatus());
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
        order.setRejectionReason(reason.trim());
        order.setRecollectionRequested(true);
        order.setStatus("REJECTED_RECOLLECT");
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "DIAG_ORDER_VERIFY_RELEASE", resourceType = "DiagnosticServiceOrder")
    public DiagnosticServiceOrderEntity verifyAndReleaseReport(UUID orderId) {
        UUID facId = requireFacility();
        DiagnosticServiceOrderEntity order = orderRepo.findByIdAndFacilityId(orderId, facId)
                .orElseThrow(() -> new NoSuchElementException("Diagnostic order not found: " + orderId));
        if (!qcPolicySpi.isReleasePermitted(order)) {
            throw new IllegalStateException("Diagnostics QC release gate blocked order " + order.getAccessionNumber()
                    + " (status=" + order.getStatus() + ").");
        }
        Instant now = Instant.now();
        order.setVerifiedAt(now);
        long elapsedMinutes = Duration.between(order.getOrderedAt(), now).toMinutes();
        order.setTatBreached(elapsedMinutes > order.getTatSlaMinutes());
        order.setStatus("VERIFIED_RELEASED");
        return orderRepo.save(order);
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

        @PostMapping("/orders/{id}/verify-release")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public DiagnosticServiceOrderEntity verifyRelease(@PathVariable UUID id) {
            return service.verifyAndReleaseReport(id);
        }

        @PostMapping("/quality-incidents")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public QualityIncidentEntity logIncident(@RequestBody LogQualityIncidentRequest req) {
            return service.logQualityIncident(req);
        }
    }
}
