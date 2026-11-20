/*
 * =============================================================================
 * Opes HealthCore SDK — Revenue Coding, Tariffs & Payer Claims Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.revenueclaims.service;

import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

public class RevenueCodingClaimsService {

    private static final Pattern ICD10_PATTERN = Pattern.compile("^[A-TV-Z][0-9]{2}(\\.[0-9A-TV-Z]{1,4})?$");
    private static final Pattern ICD11_PATTERN = Pattern.compile("^[1-9A-HJ-NP-Z][A-HJ-NP-Z0-9]{3}(\\.[A-HJ-NP-Z0-9]{1,2})?$");
    private static final Set<String> ALLOWED_FORMATS = Set.of("X12_837P", "X12_837I", "FHIR_CLAIM");

    public interface FacilityTariffRepository extends JpaRepository<FacilityTariffEntity, UUID> {
        Optional<FacilityTariffEntity> findByFacilityIdAndPayerCodeAndServiceCode(UUID facilityId, String payerCode, String serviceCode);
        List<FacilityTariffEntity> findByFacilityIdOrderByPayerCodeAscServiceCodeAsc(UUID facilityId);
    }

    public interface ClinicalCodingRepository extends JpaRepository<ClinicalCodingRecordEntity, UUID> {
        Optional<ClinicalCodingRecordEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ClinicalCodingRecordEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
    }

    public interface InsuranceAuthorizationRepository extends JpaRepository<InsuranceAuthorizationEntity, UUID> {
        Optional<InsuranceAuthorizationEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<InsuranceAuthorizationEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
    }

    public interface PayerClaimRepository extends JpaRepository<PayerClaimEntity, UUID> {
        Optional<PayerClaimEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<PayerClaimEntity> findByFacilityIdAndClaimControlNumber(UUID facilityId, String claimControlNumber);
        List<PayerClaimEntity> findByFacilityIdOrderBySubmittedAtDesc(UUID facilityId);
    }

    public record UpsertTariffRequest(
            String payerCode,
            String serviceCode,
            String serviceName,
            String serviceCategory,
            long unitPriceMinor,
            String currencyCode,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {}

    public record CreateCodingRequest(
            UUID patientId,
            UUID encounterId,
            String codingSystem,
            String primaryIcdCode,
            String secondaryIcdCodes,
            String cptProcedureCodes,
            String drgCode,
            String coderId,
            long estimatedReimbursementMinor
    ) {}

    public record EligibilityAuthRequest(
            UUID patientId,
            String payerCode,
            String memberPolicyNumber,
            String serviceCode,
            boolean eligibilityVerified,
            int requestedUnits
    ) {}

    public record SubmitClaimRequest(
            String claimControlNumber,
            UUID codingRecordId,
            UUID authorizationId,
            String payerCode,
            String claimFormat,
            long billedAmountMinor,
            String currencyCode
    ) {}

    public record PostEraRemittanceRequest(
            String eraTraceNumber,
            long allowedAmountMinor,
            long paidAmountMinor,
            long patientResponsibilityMinor,
            String denialReasonCode
    ) {}

    public record ClaimsSummaryReport(
            long totalClaims,
            long submittedClaims,
            long paidClaims,
            long deniedClaims,
            long totalBilledMinor,
            long totalPaidMinor,
            long totalRefundedMinor
    ) {}

    private final FacilityTariffRepository tariffRepo;
    private final ClinicalCodingRepository codingRepo;
    private final InsuranceAuthorizationRepository authRepo;
    private final PayerClaimRepository claimRepo;
    private final ClinicalAuditService auditService;

    public RevenueCodingClaimsService(
            FacilityTariffRepository tariffRepo,
            ClinicalCodingRepository codingRepo,
            InsuranceAuthorizationRepository authRepo,
            PayerClaimRepository claimRepo,
            ClinicalAuditService auditService) {
        this.tariffRepo = tariffRepo;
        this.codingRepo = codingRepo;
        this.authRepo = authRepo;
        this.claimRepo = claimRepo;
        this.auditService = auditService;
    }

    private UUID requireFacilityId() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("Missing authenticated facility context for Revenue Coding & Claims."));
    }

    @Transactional
    public FacilityTariffEntity upsertTariff(UpsertTariffRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.payerCode() == null || req.payerCode().isBlank()
                || req.serviceCode() == null || req.serviceCode().isBlank()
                || req.serviceName() == null || req.serviceName().isBlank()) {
            throw new IllegalArgumentException("payerCode, serviceCode, and serviceName are required.");
        }
        if (req.unitPriceMinor() < 0) {
            throw new IllegalArgumentException("unitPriceMinor must be non-negative.");
        }
        String payer = req.payerCode().trim().toUpperCase(Locale.ROOT);
        String code = req.serviceCode().trim().toUpperCase(Locale.ROOT);
        FacilityTariffEntity tariff = tariffRepo
                .findByFacilityIdAndPayerCodeAndServiceCode(facilityId, payer, code)
                .orElseGet(FacilityTariffEntity::new);
        tariff.setFacilityId(facilityId);
        tariff.setPayerCode(payer);
        tariff.setServiceCode(code);
        tariff.setServiceName(req.serviceName().trim());
        tariff.setServiceCategory(req.serviceCategory() != null && !req.serviceCategory().isBlank()
                ? req.serviceCategory().trim().toUpperCase(Locale.ROOT) : "GENERAL");
        tariff.setUnitPriceMinor(req.unitPriceMinor());
        tariff.setCurrencyCode(req.currencyCode() != null && !req.currencyCode().isBlank()
                ? req.currencyCode().trim().toUpperCase(Locale.ROOT) : "XAF");
        tariff.setEffectiveFrom(req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now());
        tariff.setEffectiveTo(req.effectiveTo());
        tariff.setActive(true);
        FacilityTariffEntity saved = tariffRepo.save(tariff);
        audit(facilityId, null, actor, "REV_TARIFF_UPSERT", payer + ":" + code,
                "unitPriceMinor=" + saved.getUnitPriceMinor());
        return saved;
    }

    @Transactional(readOnly = true)
    public long quoteTariffPrice(String payerCode, String serviceCode) {
        UUID facilityId = requireFacilityId();
        String payer = payerCode == null || payerCode.isBlank() ? "SELF_PAY" : payerCode.trim().toUpperCase(Locale.ROOT);
        String code = serviceCode == null ? "" : serviceCode.trim().toUpperCase(Locale.ROOT);
        return tariffRepo.findByFacilityIdAndPayerCodeAndServiceCode(facilityId, payer, code)
                .or(() -> tariffRepo.findByFacilityIdAndPayerCodeAndServiceCode(facilityId, "SELF_PAY", code))
                .filter(FacilityTariffEntity::isActive)
                .map(FacilityTariffEntity::getUnitPriceMinor)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active tariff found for payer=" + payer + " service=" + code));
    }

    @Transactional
    public ClinicalCodingRecordEntity createCodingAssignment(CreateCodingRequest req) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.encounterId() == null
                || req.primaryIcdCode() == null || req.primaryIcdCode().isBlank()
                || req.coderId() == null || req.coderId().isBlank()) {
            throw new IllegalArgumentException("patientId, encounterId, primaryIcdCode, and coderId are required.");
        }
        String system = (req.codingSystem() == null || req.codingSystem().isBlank())
                ? "ICD10" : req.codingSystem().trim().toUpperCase(Locale.ROOT);
        String primaryCode = req.primaryIcdCode().trim().toUpperCase(Locale.ROOT);
        if ("ICD10".equals(system) && !ICD10_PATTERN.matcher(primaryCode).matches()) {
            throw new IllegalArgumentException("Invalid ICD-10 diagnosis code syntax: " + primaryCode);
        } else if ("ICD11".equals(system) && !ICD11_PATTERN.matcher(primaryCode).matches()) {
            throw new IllegalArgumentException("Invalid ICD-11 diagnosis code syntax: " + primaryCode);
        } else if (!Set.of("ICD10", "ICD11").contains(system)) {
            throw new IllegalArgumentException("Unsupported codingSystem: " + system);
        }

        ClinicalCodingRecordEntity rec = new ClinicalCodingRecordEntity();
        rec.setFacilityId(facilityId);
        rec.setPatientId(req.patientId());
        rec.setEncounterId(req.encounterId());
        rec.setCodingSystem(system);
        rec.setPrimaryIcdCode(primaryCode);
        rec.setSecondaryIcdCodes(req.secondaryIcdCodes());
        rec.setCptProcedureCodes(req.cptProcedureCodes());
        rec.setDrgCode(req.drgCode());
        rec.setCoderId(req.coderId().trim());
        rec.setReviewStatus("PENDING_CLINICAL_REVIEW");
        rec.setEstimatedReimbursementMinor(Math.max(0L, req.estimatedReimbursementMinor()));

        ClinicalCodingRecordEntity saved = codingRepo.save(rec);
        audit(facilityId, req.patientId(), req.coderId(), "REV_CODING_ASSIGNED",
                saved.getId().toString(), system + ":" + primaryCode);
        return saved;
    }

    @Transactional
    public ClinicalCodingRecordEntity approveClinicalCoding(UUID codingId, String reviewerId) {
        UUID facilityId = requireFacilityId();
        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("reviewerId is required for clinical coding sign-off.");
        }
        ClinicalCodingRecordEntity rec = codingRepo.findByIdAndFacilityId(codingId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinical coding record not found"));
        if (rec.getCoderId().equalsIgnoreCase(reviewerId.trim())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Separation of duties: clinical coding reviewer must differ from the original coder.");
        }
        rec.setReviewStatus("CLINICALLY_APPROVED");
        rec.setReviewedBy(reviewerId.trim());
        rec.setReviewedAt(Instant.now());
        ClinicalCodingRecordEntity saved = codingRepo.save(rec);
        audit(facilityId, saved.getPatientId(), reviewerId, "REV_CODING_APPROVED",
                saved.getId().toString(), "icd=" + saved.getPrimaryIcdCode());
        return saved;
    }

    @Transactional
    public InsuranceAuthorizationEntity verifyEligibilityAndRequestAuth(EligibilityAuthRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.patientId() == null || req.payerCode() == null || req.payerCode().isBlank()
                || req.memberPolicyNumber() == null || req.memberPolicyNumber().isBlank()
                || req.serviceCode() == null || req.serviceCode().isBlank()) {
            throw new IllegalArgumentException("patientId, payerCode, memberPolicyNumber, and serviceCode are required.");
        }
        InsuranceAuthorizationEntity auth = new InsuranceAuthorizationEntity();
        auth.setFacilityId(facilityId);
        auth.setPatientId(req.patientId());
        auth.setPayerCode(req.payerCode().trim().toUpperCase(Locale.ROOT));
        auth.setMemberPolicyNumber(req.memberPolicyNumber().trim());
        auth.setServiceCode(req.serviceCode().trim().toUpperCase(Locale.ROOT));
        auth.setEligibilityVerified(req.eligibilityVerified());
        auth.setAuthStatus(req.eligibilityVerified() ? "PENDING_AUTH" : "DENIED");
        auth.setApprovedUnits(0);

        InsuranceAuthorizationEntity saved = authRepo.save(auth);
        audit(facilityId, req.patientId(), actor, "REV_ELIGIBILITY_CHECK",
                saved.getId().toString(), "payer=" + saved.getPayerCode() + " eligible=" + req.eligibilityVerified());
        return saved;
    }

    @Transactional
    public InsuranceAuthorizationEntity decidePriorAuth(UUID authId, boolean approved, int approvedUnits, LocalDate validUntil, String actor) {
        UUID facilityId = requireFacilityId();
        InsuranceAuthorizationEntity auth = authRepo.findByIdAndFacilityId(authId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Insurance authorization record not found"));
        if (!auth.isEligibilityVerified() && approved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot approve prior authorization when eligibility is unverified.");
        }
        if (approved) {
            auth.setAuthStatus("APPROVED");
            auth.setPriorAuthNumber("PA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            auth.setApprovedUnits(Math.max(1, approvedUnits));
            auth.setValidUntil(validUntil != null ? validUntil : LocalDate.now().plusDays(90));
        } else {
            auth.setAuthStatus("DENIED");
            auth.setApprovedUnits(0);
        }
        InsuranceAuthorizationEntity saved = authRepo.save(auth);
        audit(facilityId, saved.getPatientId(), actor, "REV_PRIOR_AUTH_" + saved.getAuthStatus(),
                saved.getId().toString(), "pa=" + saved.getPriorAuthNumber());
        return saved;
    }

    @Transactional
    public PayerClaimEntity submitPayerClaim(SubmitClaimRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.claimControlNumber() == null || req.claimControlNumber().isBlank()
                || req.codingRecordId() == null || req.payerCode() == null || req.payerCode().isBlank()) {
            throw new IllegalArgumentException("claimControlNumber, codingRecordId, and payerCode are required.");
        }
        if (req.billedAmountMinor() <= 0) {
            throw new IllegalArgumentException("billedAmountMinor must be greater than zero.");
        }
        String controlNum = req.claimControlNumber().trim().toUpperCase(Locale.ROOT);
        Optional<PayerClaimEntity> existing = claimRepo.findByFacilityIdAndClaimControlNumber(facilityId, controlNum);
        if (existing.isPresent()) {
            return existing.get();
        }
        String format = (req.claimFormat() == null || req.claimFormat().isBlank())
                ? "X12_837P" : req.claimFormat().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_FORMATS.contains(format)) {
            throw new IllegalArgumentException("Unsupported claimFormat: " + format);
        }

        ClinicalCodingRecordEntity coding = codingRepo.findByIdAndFacilityId(req.codingRecordId(), facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clinical coding record not found"));
        if (!"CLINICALLY_APPROVED".equals(coding.getReviewStatus()) && !"LOCKED".equals(coding.getReviewStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Claim submission blocked: clinical coding must pass human review (CLINICALLY_APPROVED) before submission.");
        }

        if (req.authorizationId() != null) {
            InsuranceAuthorizationEntity auth = authRepo.findByIdAndFacilityId(req.authorizationId(), facilityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Prior authorization not found"));
            if (!"APPROVED".equals(auth.getAuthStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Claim submission blocked: referenced prior authorization is not APPROVED (status=" + auth.getAuthStatus() + ").");
            }
            if (auth.getValidUntil() != null && auth.getValidUntil().isBefore(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Prior authorization has expired.");
            }
        }

        coding.setReviewStatus("LOCKED");
        codingRepo.save(coding);

        PayerClaimEntity claim = new PayerClaimEntity();
        claim.setFacilityId(facilityId);
        claim.setClaimControlNumber(controlNum);
        claim.setPatientId(coding.getPatientId());
        claim.setEncounterId(coding.getEncounterId());
        claim.setCodingRecordId(coding.getId());
        claim.setAuthorizationId(req.authorizationId());
        claim.setPayerCode(req.payerCode().trim().toUpperCase(Locale.ROOT));
        claim.setClaimFormat(format);
        claim.setClaimStatus("SUBMITTED");
        claim.setBilledAmountMinor(req.billedAmountMinor());
        claim.setCurrencyCode(req.currencyCode() != null && !req.currencyCode().isBlank()
                ? req.currencyCode().trim().toUpperCase(Locale.ROOT) : "XAF");

        PayerClaimEntity saved = claimRepo.save(claim);
        audit(facilityId, saved.getPatientId(), actor, "REV_CLAIM_SUBMITTED",
                saved.getClaimControlNumber(), "format=" + format + " billed=" + saved.getBilledAmountMinor());
        return saved;
    }

    @Transactional
    public PayerClaimEntity postEraRemittance(UUID claimId, PostEraRemittanceRequest req, String actor) {
        UUID facilityId = requireFacilityId();
        if (req == null || req.eraTraceNumber() == null || req.eraTraceNumber().isBlank()) {
            throw new IllegalArgumentException("eraTraceNumber is required to post X12 835 / ERA remittance.");
        }
        if (req.allowedAmountMinor() < 0 || req.paidAmountMinor() < 0 || req.patientResponsibilityMinor() < 0) {
            throw new IllegalArgumentException("ERA monetary amounts must be non-negative.");
        }
        if (req.paidAmountMinor() > req.allowedAmountMinor()) {
            throw new IllegalArgumentException("paidAmountMinor cannot exceed allowedAmountMinor.");
        }
        PayerClaimEntity claim = claimRepo.findByIdAndFacilityId(claimId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payer claim not found"));

        claim.setEraTraceNumber(req.eraTraceNumber().trim());
        claim.setAllowedAmountMinor(req.allowedAmountMinor());
        claim.setPaidAmountMinor(req.paidAmountMinor());
        claim.setPatientResponsibilityMinor(req.patientResponsibilityMinor());
        claim.setDenialReasonCode(req.denialReasonCode());
        claim.setAdjudicatedAt(Instant.now());

        if (req.denialReasonCode() != null && !req.denialReasonCode().isBlank() && req.paidAmountMinor() == 0) {
            claim.setClaimStatus("DENIED");
        } else if (req.paidAmountMinor() >= claim.getBilledAmountMinor()
                || (req.paidAmountMinor() + req.patientResponsibilityMinor()) >= req.allowedAmountMinor()) {
            claim.setClaimStatus("PAID");
        } else {
            claim.setClaimStatus("PARTIAL_PAID");
        }

        PayerClaimEntity saved = claimRepo.save(claim);
        audit(facilityId, saved.getPatientId(), actor, "REV_ERA_POSTED_" + saved.getClaimStatus(),
                saved.getClaimControlNumber(), "era=" + saved.getEraTraceNumber() + " paid=" + saved.getPaidAmountMinor());
        return saved;
    }

    @Transactional
    public PayerClaimEntity appealDeniedClaim(UUID claimId, String appealNote, String actor) {
        UUID facilityId = requireFacilityId();
        if (appealNote == null || appealNote.isBlank()) {
            throw new IllegalArgumentException("appealNote is required to appeal a denied claim.");
        }
        PayerClaimEntity claim = claimRepo.findByIdAndFacilityId(claimId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payer claim not found"));
        if (!"DENIED".equals(claim.getClaimStatus()) && !"PARTIAL_PAID".equals(claim.getClaimStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only DENIED or PARTIAL_PAID claims can be appealed.");
        }
        claim.setClaimStatus("APPEALED");
        PayerClaimEntity saved = claimRepo.save(claim);
        audit(facilityId, saved.getPatientId(), actor, "REV_CLAIM_APPEALED",
                saved.getClaimControlNumber(), appealNote.trim());
        return saved;
    }

    @Transactional
    public PayerClaimEntity recordClaimRefund(UUID claimId, long refundMinor, String reason, String actor) {
        UUID facilityId = requireFacilityId();
        if (refundMinor <= 0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Positive refundMinor and reason are required.");
        }
        PayerClaimEntity claim = claimRepo.findByIdAndFacilityId(claimId, facilityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payer claim not found"));
        if (claim.getRefundAmountMinor() + refundMinor > claim.getPaidAmountMinor()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Total refunds cannot exceed paidAmountMinor (" + claim.getPaidAmountMinor() + ").");
        }
        claim.setRefundAmountMinor(claim.getRefundAmountMinor() + refundMinor);
        if (claim.getRefundAmountMinor() == claim.getPaidAmountMinor()) {
            claim.setClaimStatus("REFUNDED");
        }
        PayerClaimEntity saved = claimRepo.save(claim);
        audit(facilityId, saved.getPatientId(), actor, "REV_CLAIM_REFUND",
                saved.getClaimControlNumber(), "refund=" + refundMinor + " reason=" + reason.trim());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<FacilityTariffEntity> listTariffs() {
        return tariffRepo.findByFacilityIdOrderByPayerCodeAscServiceCodeAsc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public List<ClinicalCodingRecordEntity> listCodingRecords() {
        return codingRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public List<InsuranceAuthorizationEntity> listAuthorizations() {
        return authRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public List<PayerClaimEntity> listClaims() {
        return claimRepo.findByFacilityIdOrderBySubmittedAtDesc(requireFacilityId());
    }

    @Transactional(readOnly = true)
    public ClaimsSummaryReport getClaimsSummary() {
        List<PayerClaimEntity> claims = listClaims();
        long submitted = claims.stream().filter(c -> "SUBMITTED".equals(c.getClaimStatus())).count();
        long paid = claims.stream().filter(c -> "PAID".equals(c.getClaimStatus()) || "PARTIAL_PAID".equals(c.getClaimStatus())).count();
        long denied = claims.stream().filter(c -> "DENIED".equals(c.getClaimStatus())).count();
        long billed = claims.stream().mapToLong(PayerClaimEntity::getBilledAmountMinor).sum();
        long paidSum = claims.stream().mapToLong(PayerClaimEntity::getPaidAmountMinor).sum();
        long refundSum = claims.stream().mapToLong(PayerClaimEntity::getRefundAmountMinor).sum();
        return new ClaimsSummaryReport(claims.size(), submitted, paid, denied, billed, paidSum, refundSum);
    }

    private void audit(UUID facilityId, UUID patientId, String actor, String action, String resourceId, String detail) {
        if (auditService != null) {
            auditService.record(facilityId, patientId, actor != null && !actor.isBlank() ? actor : "REV_SYSTEM",
                    action, "RevenueCodingClaims", resourceId, "SUCCESS", detail);
        }
    }

    @RestController
    @RequestMapping("/api/v1/revenue-claims")
    @Tag(name = "Tariffs, Clinical Coding & Payer Claims", description = "Facility tariffs, human-reviewed ICD-10/ICD-11/CPT/DRG coding, eligibility/prior auth, X12 837/FHIR claims, and X12 835 ERA remittance")
    public static class RevenueCodingClaimsController {
        private final RevenueCodingClaimsService service;

        public RevenueCodingClaimsController(RevenueCodingClaimsService service) {
            this.service = service;
        }

        @PostMapping("/tariffs")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Upsert facility or payer-contract tariff price")
        public FacilityTariffEntity upsertTariff(@RequestBody UpsertTariffRequest request) {
            return service.upsertTariff(request, "api.billing");
        }

        @GetMapping("/tariffs")
        @PreAuthorize("hasAuthority('PERM_billing:read')")
        @Operation(summary = "List facility tariff schedules")
        public List<FacilityTariffEntity> listTariffs() {
            return service.listTariffs();
        }

        @PostMapping("/coding")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Create ICD-10/ICD-11/CPT/DRG clinical coding assignment awaiting clinician review")
        public ClinicalCodingRecordEntity createCoding(@RequestBody CreateCodingRequest request) {
            return service.createCodingAssignment(request);
        }

        @PostMapping("/coding/{codingId}/approve")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_billing:write')")
        @Operation(summary = "Record independent human clinical review approval for coding assignment")
        public ClinicalCodingRecordEntity approveCoding(@PathVariable UUID codingId, @RequestParam String reviewerId) {
            return service.approveClinicalCoding(codingId, reviewerId);
        }

        @PostMapping("/authorizations")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Verify insurance eligibility and initiate prior authorization")
        public InsuranceAuthorizationEntity createAuth(@RequestBody EligibilityAuthRequest request) {
            return service.verifyEligibilityAndRequestAuth(request, "api.billing");
        }

        @PostMapping("/authorizations/{authId}/decide")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Record payer prior authorization decision")
        public InsuranceAuthorizationEntity decideAuth(
                @PathVariable UUID authId,
                @RequestParam boolean approved,
                @RequestParam(defaultValue = "1") int units) {
            return service.decidePriorAuth(authId, approved, units, LocalDate.now().plusDays(90), "api.billing");
        }

        @PostMapping("/claims")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Submit X12 837 / FHIR payer claim after clinical coding approval")
        public PayerClaimEntity submitClaim(@RequestBody SubmitClaimRequest request) {
            return service.submitPayerClaim(request, "api.billing");
        }

        @PostMapping("/claims/{claimId}/era")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Auto-post X12 835 / ERA remittance advice and reconcile amounts")
        public PayerClaimEntity postEra(@PathVariable UUID claimId, @RequestBody PostEraRemittanceRequest request) {
            return service.postEraRemittance(claimId, request, "api.billing");
        }

        @PostMapping("/claims/{claimId}/appeal")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Appeal a denied or partially paid claim")
        public PayerClaimEntity appeal(@PathVariable UUID claimId, @RequestParam String note) {
            return service.appealDeniedClaim(claimId, note, "api.billing");
        }

        @PostMapping("/claims/{claimId}/refund")
        @PreAuthorize("hasAuthority('PERM_billing:write')")
        @Operation(summary = "Record audited refund against a paid claim")
        public PayerClaimEntity refund(@PathVariable UUID claimId, @RequestParam long amountMinor, @RequestParam String reason) {
            return service.recordClaimRefund(claimId, amountMinor, reason, "api.billing");
        }

        @GetMapping("/claims")
        @PreAuthorize("hasAuthority('PERM_billing:read')")
        @Operation(summary = "List facility payer claims")
        public List<PayerClaimEntity> listClaims() {
            return service.listClaims();
        }

        @GetMapping("/claims/summary")
        @PreAuthorize("hasAuthority('PERM_billing:read')")
        @Operation(summary = "Get facility payer claims reconciliation summary")
        public ClaimsSummaryReport summary() {
            return service.getClaimsSummary();
        }
    }
}
