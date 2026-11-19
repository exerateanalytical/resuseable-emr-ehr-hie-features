/*
 * =============================================================================
 * Opes HealthCore SDK — Unified Orders & Closed-Loop Results Service & FHIR API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.orders.service;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Unified clinical order engine supporting multi-domain ordering, reusable order sets,
 * specimen collection/accessioning, result entry/verification/immutable amendment chains,
 * mandatory critical-value closed-loop acknowledgment, non-recursive reflex triggers,
 * and FHIR R4 ServiceRequest / DiagnosticReport projections.
 */
public class UnifiedOrdersResultsService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public interface OrderSetRepository extends JpaRepository<ClinicalOrderSetEntity, UUID> {
        Optional<ClinicalOrderSetEntity> findByFacilityIdAndSetCodeAndActiveTrue(UUID facilityId, String setCode);
        List<ClinicalOrderSetEntity> findByFacilityIdAndActiveTrueOrderByNameAsc(UUID facilityId);
    }

    public interface UnifiedOrderRepository extends JpaRepository<UnifiedClinicalOrderEntity, UUID> {
        Optional<UnifiedClinicalOrderEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<UnifiedClinicalOrderEntity> findByFacilityIdOrderByOrderedAtDesc(UUID facilityId);
        List<UnifiedClinicalOrderEntity> findByFacilityIdAndPatientIdOrderByOrderedAtDesc(UUID facilityId, UUID patientId);
    }

    public interface OrderSpecimenRepository extends JpaRepository<OrderSpecimenEntity, UUID> {
        List<OrderSpecimenEntity> findByFacilityIdAndOrderIdOrderByCollectedAtDesc(UUID facilityId, UUID orderId);
    }

    public interface ClinicalResultRepository extends JpaRepository<ClinicalResultEntity, UUID> {
        Optional<ClinicalResultEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ClinicalResultEntity> findByFacilityIdAndOrderIdOrderByRevisionNumberAsc(UUID facilityId, UUID orderId);
        List<ClinicalResultEntity> findByFacilityIdAndPatientIdOrderByRecordedAtDesc(UUID facilityId, UUID patientId);
        List<ClinicalResultEntity> findByFacilityIdAndCriticalAckRequiredTrueAndCriticalAckAtIsNullOrderByRecordedAtAsc(
                UUID facilityId);
    }

    public interface ReflexOrderRuleRepository extends JpaRepository<ReflexOrderRuleEntity, UUID> {
        List<ReflexOrderRuleEntity> findByFacilityIdAndTriggerResultCodeAndTriggerFlagAndActiveTrue(
                UUID facilityId, String triggerResultCode, AbnormalFlag triggerFlag);
    }

    public record PlaceOrderRequest(
            UUID patientId,
            UUID encounterId,
            OrderDomain orderDomain,
            String codeSystem,
            String orderCode,
            String orderDisplay,
            OrderPriority priority,
            String clinicalNotes) {}

    public record CreateOrderSetRequest(String setCode, String name, String clinicalIndication, String itemsSpec) {}

    public record CollectSpecimenRequest(String accessionBarcode, String specimenType, String conditionNotes) {}

    public record EnterResultRequest(
            UUID orderId,
            String resultCode,
            String resultDisplay,
            BigDecimal numericValue,
            String textValue,
            String unit,
            String referenceRange,
            AbnormalFlag abnormalFlag) {}

    public record AmendResultRequest(
            BigDecimal numericValue,
            String textValue,
            AbnormalFlag abnormalFlag,
            String amendmentReason) {}

    public record CriticalAckRequest(String notes) {}

    public record CreateReflexRuleRequest(
            String triggerResultCode,
            AbnormalFlag triggerFlag,
            OrderDomain reflexDomain,
            String reflexOrderCode,
            String reflexOrderDisplay,
            OrderPriority reflexPriority) {}

    public record VerificationOutcome(
            ClinicalResultEntity verifiedResult,
            List<UnifiedClinicalOrderEntity> triggeredReflexOrders) {}

    private final OrderSetRepository orderSetRepo;
    private final UnifiedOrderRepository orderRepo;
    private final OrderSpecimenRepository specimenRepo;
    private final ClinicalResultRepository resultRepo;
    private final ReflexOrderRuleRepository reflexRuleRepo;
    private final ClinicalAuditService auditService;

    public UnifiedOrdersResultsService(
            OrderSetRepository orderSetRepo,
            UnifiedOrderRepository orderRepo,
            OrderSpecimenRepository specimenRepo,
            ClinicalResultRepository resultRepo,
            ReflexOrderRuleRepository reflexRuleRepo,
            ClinicalAuditService auditService) {
        this.orderSetRepo = orderSetRepo;
        this.orderRepo = orderRepo;
        this.specimenRepo = specimenRepo;
        this.resultRepo = resultRepo;
        this.reflexRuleRepo = reflexRuleRepo;
        this.auditService = auditService;
    }

    @Transactional
    public ClinicalOrderSetEntity createOrderSet(UUID facilityId, CreateOrderSetRequest req) {
        if (facilityId == null || req == null || isBlank(req.setCode()) || isBlank(req.name()) || isBlank(req.itemsSpec())) {
            throw new IllegalArgumentException("facilityId, setCode, name, and itemsSpec are required.");
        }
        ClinicalOrderSetEntity entity = new ClinicalOrderSetEntity();
        entity.setFacilityId(facilityId);
        entity.setSetCode(req.setCode().trim().toUpperCase(Locale.ROOT));
        entity.setName(req.name().trim());
        entity.setClinicalIndication(req.clinicalIndication());
        entity.setItemsSpec(req.itemsSpec().trim());
        return orderSetRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "ORDERS_PLACE_ORDER", resourceType = "ServiceRequest")
    public UnifiedClinicalOrderEntity placeOrder(UUID facilityId, PlaceOrderRequest req, String orderedBy) {
        if (facilityId == null || req == null || req.patientId() == null || req.orderDomain() == null
                || isBlank(req.orderCode()) || isBlank(req.orderDisplay())) {
            throw new IllegalArgumentException("facilityId, patientId, orderDomain, orderCode, and orderDisplay are required.");
        }
        UnifiedClinicalOrderEntity order = new UnifiedClinicalOrderEntity();
        order.setFacilityId(facilityId);
        order.setPatientId(req.patientId());
        order.setEncounterId(req.encounterId());
        order.setOrderDomain(req.orderDomain());
        order.setCodeSystem(isBlank(req.codeSystem()) ? "http://loinc.org" : req.codeSystem().trim());
        order.setOrderCode(req.orderCode().trim());
        order.setOrderDisplay(req.orderDisplay().trim());
        order.setPriority(req.priority() != null ? req.priority() : OrderPriority.ROUTINE);
        order.setStatus(OrderStatus.ORDERED);
        order.setClinicalNotes(req.clinicalNotes());
        order.setOrderedBy(orderedBy != null ? orderedBy : "SYSTEM");
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "ORDERS_APPLY_ORDER_SET", resourceType = "ServiceRequest")
    public List<UnifiedClinicalOrderEntity> applyOrderSet(
            UUID facilityId, String setCode, UUID patientId, UUID encounterId, String orderedBy) {
        ClinicalOrderSetEntity set = orderSetRepo.findByFacilityIdAndSetCodeAndActiveTrue(
                        facilityId, setCode.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("Active order set not found: " + setCode));
        List<UnifiedClinicalOrderEntity> placed = new ArrayList<>();
        for (String rawItem : set.getItemsSpec().split(";")) {
            String trimmed = rawItem.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split("\\|");
            if (parts.length < 3) {
                throw new IllegalStateException("Malformed order set item specification: " + trimmed);
            }
            OrderDomain domain = OrderDomain.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
            OrderPriority prio = parts.length >= 4
                    ? OrderPriority.valueOf(parts[3].trim().toUpperCase(Locale.ROOT))
                    : OrderPriority.ROUTINE;
            placed.add(placeOrder(facilityId, new PlaceOrderRequest(
                    patientId, encounterId, domain, "http://loinc.org",
                    parts[1].trim(), parts[2].trim(), prio, "From order set " + set.getSetCode()), orderedBy));
        }
        return placed;
    }

    @Transactional
    public UnifiedClinicalOrderEntity acknowledgeOrder(UUID facilityId, UUID orderId, String actorId) {
        UnifiedClinicalOrderEntity order = requireOrder(facilityId, orderId);
        if (order.getStatus() != OrderStatus.ORDERED) {
            throw new IllegalStateException("Only ORDERED items can be acknowledged; current status=" + order.getStatus());
        }
        order.setStatus(OrderStatus.ACKNOWLEDGED);
        order.setAcknowledgedBy(actorId != null ? actorId : "SYSTEM");
        order.setAcknowledgedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }

    @Transactional
    public OrderSpecimenEntity collectSpecimen(
            UUID facilityId, UUID orderId, CollectSpecimenRequest req, String collectorId) {
        UnifiedClinicalOrderEntity order = requireOrder(facilityId, orderId);
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.VERIFIED) {
            throw new IllegalStateException("Cannot collect specimen for order in status " + order.getStatus());
        }
        String barcode = (req != null && !isBlank(req.accessionBarcode()))
                ? req.accessionBarcode().trim()
                : "ACC-" + order.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        OrderSpecimenEntity specimen = new OrderSpecimenEntity();
        specimen.setFacilityId(facilityId);
        specimen.setOrderId(order.getId());
        specimen.setAccessionBarcode(barcode);
        specimen.setSpecimenType(req != null && !isBlank(req.specimenType()) ? req.specimenType().trim() : "WHOLE_BLOOD");
        specimen.setCollectedBy(collectorId != null ? collectorId : "SYSTEM");
        specimen.setConditionNotes(req != null ? req.conditionNotes() : null);

        order.setAccessionNumber(barcode);
        order.setStatus(OrderStatus.SPECIMEN_COLLECTED);
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);
        return specimenRepo.save(specimen);
    }

    @Transactional
    @ClinicalAudit(action = "ORDERS_CANCEL", resourceType = "ServiceRequest")
    public UnifiedClinicalOrderEntity cancelOrder(UUID facilityId, UUID orderId, String reason) {
        UnifiedClinicalOrderEntity order = requireOrder(facilityId, orderId);
        if (order.getStatus() == OrderStatus.VERIFIED) {
            throw new IllegalStateException("Verified orders cannot be cancelled; amend the clinical result instead.");
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        order.setUpdatedAt(Instant.now());
        return orderRepo.save(order);
    }

    @Transactional
    @ClinicalAudit(action = "RESULTS_ENTER_PRELIMINARY", resourceType = "DiagnosticReport")
    public ClinicalResultEntity enterResult(UUID facilityId, EnterResultRequest req, String enteredBy) {
        if (facilityId == null || req == null || req.orderId() == null || isBlank(req.resultCode()) || isBlank(req.resultDisplay())) {
            throw new IllegalArgumentException("facilityId, orderId, resultCode, and resultDisplay are required.");
        }
        UnifiedClinicalOrderEntity order = requireOrder(facilityId, req.orderId());
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Cannot enter a result for a cancelled order.");
        }
        AbnormalFlag flag = req.abnormalFlag() != null ? req.abnormalFlag() : AbnormalFlag.NORMAL;
        ClinicalResultEntity result = new ClinicalResultEntity();
        result.setFacilityId(facilityId);
        result.setOrderId(order.getId());
        result.setPatientId(order.getPatientId());
        result.setResultCode(req.resultCode().trim());
        result.setResultDisplay(req.resultDisplay().trim());
        result.setNumericValue(req.numericValue());
        result.setTextValue(req.textValue());
        result.setUnit(req.unit());
        result.setReferenceRange(req.referenceRange());
        result.setAbnormalFlag(flag);
        result.setStatus(ResultStatus.PRELIMINARY);
        result.setCriticalAckRequired(isCritical(flag));
        result.setEnteredBy(enteredBy != null ? enteredBy : "SYSTEM");

        order.setStatus(OrderStatus.RESULTED);
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);
        return resultRepo.save(result);
    }

    @Transactional
    @ClinicalAudit(action = "RESULTS_VERIFY_FINAL", resourceType = "DiagnosticReport")
    public VerificationOutcome verifyResult(UUID facilityId, UUID resultId, String verifierId) {
        ClinicalResultEntity result = requireResult(facilityId, resultId);
        if (result.getStatus() != ResultStatus.PRELIMINARY) {
            throw new IllegalStateException("Only PRELIMINARY results can be verified; current status=" + result.getStatus());
        }
        result.setStatus(ResultStatus.FINAL);
        result.setVerifiedBy(verifierId != null ? verifierId : "SYSTEM");
        result.setVerifiedAt(Instant.now());
        ClinicalResultEntity saved = resultRepo.save(result);

        UnifiedClinicalOrderEntity order = requireOrder(facilityId, result.getOrderId());
        order.setStatus(OrderStatus.VERIFIED);
        order.setUpdatedAt(Instant.now());
        orderRepo.save(order);

        // Evaluate non-recursive reflex rules only if source order is not itself a reflex order
        List<UnifiedClinicalOrderEntity> triggered = new ArrayList<>();
        if (order.getReflexSourceOrderId() == null) {
            List<ReflexOrderRuleEntity> rules = reflexRuleRepo
                    .findByFacilityIdAndTriggerResultCodeAndTriggerFlagAndActiveTrue(
                            facilityId, saved.getResultCode(), saved.getAbnormalFlag());
            for (ReflexOrderRuleEntity rule : rules) {
                UnifiedClinicalOrderEntity reflexOrder = new UnifiedClinicalOrderEntity();
                reflexOrder.setFacilityId(facilityId);
                reflexOrder.setPatientId(order.getPatientId());
                reflexOrder.setEncounterId(order.getEncounterId());
                reflexOrder.setOrderDomain(rule.getReflexDomain());
                reflexOrder.setCodeSystem("http://loinc.org");
                reflexOrder.setOrderCode(rule.getReflexOrderCode());
                reflexOrder.setOrderDisplay(rule.getReflexOrderDisplay());
                reflexOrder.setPriority(rule.getReflexPriority());
                reflexOrder.setStatus(OrderStatus.ORDERED);
                reflexOrder.setReflexSourceOrderId(order.getId());
                reflexOrder.setClinicalNotes("Reflex triggered from result " + saved.getId()
                        + " (" + saved.getResultCode() + "=" + saved.getAbnormalFlag() + ")");
                reflexOrder.setOrderedBy("REFLEX_ENGINE:" + result.getVerifiedBy());
                triggered.add(orderRepo.save(reflexOrder));
            }
        }
        return new VerificationOutcome(saved, triggered);
    }

    /**
     * Finalized results are immutable; corrections create a linked AMENDED revision with full provenance.
     */
    @Transactional
    @ClinicalAudit(action = "RESULTS_AMEND", resourceType = "DiagnosticReport")
    public ClinicalResultEntity amendResult(
            UUID facilityId, UUID originalResultId, AmendResultRequest req, String actorId) {
        if (req == null || isBlank(req.amendmentReason())) {
            throw new IllegalArgumentException("amendmentReason is mandatory when amending a verified clinical result.");
        }
        ClinicalResultEntity prior = requireResult(facilityId, originalResultId);
        if (prior.getStatus() != ResultStatus.FINAL && prior.getStatus() != ResultStatus.AMENDED) {
            throw new IllegalStateException("Only FINAL or AMENDED results can be amended.");
        }
        AbnormalFlag nextFlag = req.abnormalFlag() != null ? req.abnormalFlag() : prior.getAbnormalFlag();
        ClinicalResultEntity amended = new ClinicalResultEntity();
        amended.setFacilityId(facilityId);
        amended.setOrderId(prior.getOrderId());
        amended.setPatientId(prior.getPatientId());
        amended.setResultCode(prior.getResultCode());
        amended.setResultDisplay(prior.getResultDisplay());
        amended.setNumericValue(req.numericValue() != null ? req.numericValue() : prior.getNumericValue());
        amended.setTextValue(req.textValue() != null ? req.textValue() : prior.getTextValue());
        amended.setUnit(prior.getUnit());
        amended.setReferenceRange(prior.getReferenceRange());
        amended.setAbnormalFlag(nextFlag);
        amended.setStatus(ResultStatus.AMENDED);
        amended.setRevisionNumber(prior.getRevisionNumber() + 1);
        amended.setAmendedFromResultId(prior.getId());
        amended.setAmendmentReason(req.amendmentReason().trim());
        amended.setEnteredBy(actorId != null ? actorId : "SYSTEM");
        amended.setVerifiedBy(actorId != null ? actorId : "SYSTEM");
        amended.setVerifiedAt(Instant.now());
        amended.setCriticalAckRequired(isCritical(nextFlag));
        return resultRepo.save(amended);
    }

    /**
     * Records mandatory closed-loop clinician acknowledgment for CRITICAL_HIGH / CRITICAL_LOW results.
     */
    @Transactional
    public ClinicalResultEntity acknowledgeCriticalResult(
            UUID facilityId, UUID resultId, String clinicianId, String notes) {
        if (isBlank(clinicianId)) {
            throw new IllegalArgumentException("clinicianId is required for critical value acknowledgment.");
        }
        ClinicalResultEntity result = requireResult(facilityId, resultId);
        if (!result.isCriticalAckRequired()) {
            throw new IllegalStateException("Result does not require critical-value acknowledgment.");
        }
        result.setCriticalAckBy(clinicianId.trim());
        result.setCriticalAckAt(Instant.now());
        result.setCriticalAckNotes(notes);
        ClinicalResultEntity saved = resultRepo.save(result);
        if (auditService != null) {
            auditService.recordEvent(
                    facilityId.toString(),
                    clinicianId.trim(),
                    "CLINICIAN",
                    "CRITICAL_RESULT_ACKNOWLEDGED",
                    "DiagnosticReport",
                    result.getId().toString(),
                    result.getPatientId(),
                    "SUCCESS",
                    notes,
                    "internal");
        }
        return saved;
    }

    @Transactional
    public ReflexOrderRuleEntity createReflexRule(UUID facilityId, CreateReflexRuleRequest req) {
        if (facilityId == null || req == null || isBlank(req.triggerResultCode()) || req.triggerFlag() == null
                || isBlank(req.reflexOrderCode()) || isBlank(req.reflexOrderDisplay())) {
            throw new IllegalArgumentException("Valid reflex rule fields are required.");
        }
        ReflexOrderRuleEntity rule = new ReflexOrderRuleEntity();
        rule.setFacilityId(facilityId);
        rule.setTriggerResultCode(req.triggerResultCode().trim());
        rule.setTriggerFlag(req.triggerFlag());
        rule.setReflexDomain(req.reflexDomain() != null ? req.reflexDomain() : OrderDomain.LABORATORY);
        rule.setReflexOrderCode(req.reflexOrderCode().trim());
        rule.setReflexOrderDisplay(req.reflexOrderDisplay().trim());
        rule.setReflexPriority(req.reflexPriority() != null ? req.reflexPriority() : OrderPriority.URGENT);
        return reflexRuleRepo.save(rule);
    }

    @Transactional(readOnly = true)
    public List<UnifiedClinicalOrderEntity> listFacilityOrders(UUID facilityId) {
        return orderRepo.findByFacilityIdOrderByOrderedAtDesc(facilityId);
    }

    @Transactional(readOnly = true)
    public List<UnifiedClinicalOrderEntity> listPatientOrders(UUID facilityId, UUID patientId) {
        return orderRepo.findByFacilityIdAndPatientIdOrderByOrderedAtDesc(facilityId, patientId);
    }

    @Transactional(readOnly = true)
    public List<ClinicalResultEntity> listPatientResults(UUID facilityId, UUID patientId) {
        return resultRepo.findByFacilityIdAndPatientIdOrderByRecordedAtDesc(facilityId, patientId);
    }

    @Transactional(readOnly = true)
    public List<ClinicalResultEntity> listUnacknowledgedCriticalResults(UUID facilityId) {
        return resultRepo.findByFacilityIdAndCriticalAckRequiredTrueAndCriticalAckAtIsNullOrderByRecordedAtAsc(facilityId);
    }

    public ServiceRequest toFhirServiceRequest(UnifiedClinicalOrderEntity order) {
        ServiceRequest sr = new ServiceRequest();
        sr.setId(order.getId().toString());
        sr.setStatus(switch (order.getStatus()) {
            case DRAFT -> ServiceRequest.ServiceRequestStatus.DRAFT;
            case CANCELLED -> ServiceRequest.ServiceRequestStatus.REVOKED;
            case VERIFIED -> ServiceRequest.ServiceRequestStatus.COMPLETED;
            default -> ServiceRequest.ServiceRequestStatus.ACTIVE;
        });
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
        sr.setPriority(switch (order.getPriority()) {
            case STAT -> ServiceRequest.ServiceRequestPriority.STAT;
            case URGENT -> ServiceRequest.ServiceRequestPriority.URGENT;
            case ROUTINE -> ServiceRequest.ServiceRequestPriority.ROUTINE;
        });
        sr.setSubject(new Reference("Patient/" + order.getPatientId()));
        sr.setCode(new CodeableConcept().addCoding(new Coding(order.getCodeSystem(), order.getOrderCode(), order.getOrderDisplay())));
        sr.setAuthoredOn(Date.from(order.getOrderedAt()));
        return sr;
    }

    public DiagnosticReport toFhirDiagnosticReport(ClinicalResultEntity result) {
        DiagnosticReport report = new DiagnosticReport();
        report.setId(result.getId().toString());
        report.setStatus(switch (result.getStatus()) {
            case PRELIMINARY -> DiagnosticReport.DiagnosticReportStatus.PRELIMINARY;
            case FINAL -> DiagnosticReport.DiagnosticReportStatus.FINAL;
            case AMENDED -> DiagnosticReport.DiagnosticReportStatus.AMENDED;
            case CANCELLED -> DiagnosticReport.DiagnosticReportStatus.CANCELLED;
        });
        report.setSubject(new Reference("Patient/" + result.getPatientId()));
        report.setCode(new CodeableConcept().addCoding(new Coding("http://loinc.org", result.getResultCode(), result.getResultDisplay())));
        report.setIssued(Date.from(result.getVerifiedAt() != null ? result.getVerifiedAt() : result.getRecordedAt()));
        String summary = (result.getNumericValue() != null ? result.getNumericValue().toPlainString() + " " + Objects.toString(result.getUnit(), "") : Objects.toString(result.getTextValue(), ""))
                + " [" + result.getAbnormalFlag() + "]";
        report.setConclusion(summary.trim());
        return report;
    }

    private UnifiedClinicalOrderEntity requireOrder(UUID facilityId, UUID orderId) {
        return orderRepo.findByIdAndFacilityId(orderId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Order not found in active facility."));
    }

    private ClinicalResultEntity requireResult(UUID facilityId, UUID resultId) {
        return resultRepo.findByIdAndFacilityId(resultId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Result not found in active facility."));
    }

    private static boolean isCritical(AbnormalFlag flag) {
        return flag == AbnormalFlag.CRITICAL_HIGH || flag == AbnormalFlag.CRITICAL_LOW;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @RestController
    @RequestMapping("/api/v1/orders-results")
    public static class UnifiedOrdersResultsController {
        private final UnifiedOrdersResultsService service;

        public UnifiedOrdersResultsController(UnifiedOrdersResultsService service) {
            this.service = service;
        }

        @PostMapping("/order-sets")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_order:sign')")
        public ClinicalOrderSetEntity createOrderSet(@RequestBody CreateOrderSetRequest req) {
            return service.createOrderSet(requireFacility(), req);
        }

        @PostMapping("/orders")
        @PreAuthorize("hasAuthority('PERM_order:sign') or hasAuthority('PERM_rx:prescribe')")
        public UnifiedClinicalOrderEntity placeOrder(@RequestBody PlaceOrderRequest req, Authentication auth) {
            return service.placeOrder(requireFacility(), req, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/orders/from-set/{setCode}")
        @PreAuthorize("hasAuthority('PERM_order:sign')")
        public List<UnifiedClinicalOrderEntity> applyOrderSet(
                @PathVariable String setCode,
                @RequestParam UUID patientId,
                @RequestParam(required = false) UUID encounterId,
                Authentication auth) {
            return service.applyOrderSet(
                    requireFacility(), setCode, patientId, encounterId, auth != null ? auth.getName() : "SYSTEM");
        }

        @GetMapping("/orders")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public List<UnifiedClinicalOrderEntity> listOrders(@RequestParam(required = false) UUID patientId) {
            UUID fac = requireFacility();
            return patientId != null ? service.listPatientOrders(fac, patientId) : service.listFacilityOrders(fac);
        }

        @PostMapping("/orders/{orderId}/acknowledge")
        @PreAuthorize("hasAuthority('PERM_lab:accession') or hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_order:sign')")
        public UnifiedClinicalOrderEntity acknowledge(@PathVariable UUID orderId, Authentication auth) {
            return service.acknowledgeOrder(requireFacility(), orderId, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/orders/{orderId}/specimen")
        @PreAuthorize("hasAuthority('PERM_lab:accession') or hasAuthority('PERM_vitals:write')")
        public OrderSpecimenEntity collectSpecimen(
                @PathVariable UUID orderId, @RequestBody CollectSpecimenRequest req, Authentication auth) {
            return service.collectSpecimen(requireFacility(), orderId, req, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/orders/{orderId}/cancel")
        @PreAuthorize("hasAuthority('PERM_order:sign')")
        public UnifiedClinicalOrderEntity cancelOrder(@PathVariable UUID orderId, @RequestParam(required = false) String reason) {
            return service.cancelOrder(requireFacility(), orderId, reason);
        }

        @PostMapping("/results")
        @PreAuthorize("hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_imaging:report:sign')")
        public ClinicalResultEntity enterResult(@RequestBody EnterResultRequest req, Authentication auth) {
            return service.enterResult(requireFacility(), req, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/results/{resultId}/verify")
        @PreAuthorize("hasAuthority('PERM_lab:verify') or hasAuthority('PERM_imaging:report:sign')")
        public VerificationOutcome verifyResult(@PathVariable UUID resultId, Authentication auth) {
            return service.verifyResult(requireFacility(), resultId, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/results/{resultId}/amend")
        @PreAuthorize("hasAuthority('PERM_lab:verify') or hasAuthority('PERM_imaging:report:sign')")
        public ClinicalResultEntity amendResult(
                @PathVariable UUID resultId, @RequestBody AmendResultRequest req, Authentication auth) {
            return service.amendResult(requireFacility(), resultId, req, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/results/{resultId}/acknowledge-critical")
        @PreAuthorize("hasAuthority('PERM_order:sign') or hasAuthority('PERM_encounter:sign')")
        public ClinicalResultEntity acknowledgeCritical(
                @PathVariable UUID resultId, @RequestBody(required = false) CriticalAckRequest req, Authentication auth) {
            return service.acknowledgeCriticalResult(
                    requireFacility(),
                    resultId,
                    auth != null ? auth.getName() : "SYSTEM",
                    req != null ? req.notes() : null);
        }

        @GetMapping("/results/critical-unacknowledged")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public List<ClinicalResultEntity> criticalQueue() {
            return service.listUnacknowledgedCriticalResults(requireFacility());
        }

        @PostMapping("/reflex-rules")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public ReflexOrderRuleEntity createReflexRule(@RequestBody CreateReflexRuleRequest req) {
            return service.createReflexRule(requireFacility(), req);
        }

        @GetMapping(value = "/fhir/ServiceRequest", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public ResponseEntity<String> searchFhirServiceRequests(@RequestParam(required = false) UUID patientId) {
            UUID fac = requireFacility();
            List<UnifiedClinicalOrderEntity> rows = patientId != null
                    ? service.listPatientOrders(fac, patientId)
                    : service.listFacilityOrders(fac);
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(rows.size());
            rows.forEach(r -> bundle.addEntry().setResource(service.toFhirServiceRequest(r)));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body(FHIR_R4.newJsonParser().encodeResourceToString(bundle));
        }

        @GetMapping(value = "/fhir/DiagnosticReport", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public ResponseEntity<String> searchFhirDiagnosticReports(@RequestParam UUID patientId) {
            List<ClinicalResultEntity> rows = service.listPatientResults(requireFacility(), patientId);
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(rows.size());
            rows.forEach(r -> bundle.addEntry().setResource(service.toFhirDiagnosticReport(r)));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body(FHIR_R4.newJsonParser().encodeResourceToString(bundle));
        }

        private UUID requireFacility() {
            return FacilityContext.getFacilityId()
                    .orElseThrow(() -> new AccessDeniedException("Active facility tenant context is required."));
        }
    }
}
