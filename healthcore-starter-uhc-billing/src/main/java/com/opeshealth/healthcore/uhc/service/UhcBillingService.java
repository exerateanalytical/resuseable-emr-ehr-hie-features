/*
 * =============================================================================
 * Opes HealthCore SDK — Double-Entry Ledger, UHC/OpenIMIS, Vouchers & Mobile Money
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.uhc.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.persistence.sql.RetryableOnTransientSqlFailure;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

public class UhcBillingService {

    public interface TariffRepository extends JpaRepository<TariffScheduleEntity, UUID> {}
    public interface PatientAccountRepository extends JpaRepository<PatientAccountEntity, UUID> {
        Optional<PatientAccountEntity> findByFacilityIdAndPatientId(UUID facilityId, UUID patientId);
        @Modifying
        @Query("UPDATE PatientAccountEntity a SET a.patientId = :survivorId WHERE a.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {}
    public interface CbhiPolicyRepository extends JpaRepository<CbhiPolicyEntity, UUID> {
        Optional<CbhiPolicyEntity> findByPolicyNumberAndActiveTrue(String policyNumber);
        List<CbhiPolicyEntity> findByPatientIdAndActiveTrue(UUID patientId);
        @Modifying
        @Query("UPDATE CbhiPolicyEntity p SET p.patientId = :survivorId WHERE p.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaimEntity, UUID> {
        @Modifying
        @Query("UPDATE InsuranceClaimEntity c SET c.patientId = :survivorId WHERE c.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {}
    public interface UhcVoucherRepository extends JpaRepository<UhcVoucherEntity, UUID> {
        Optional<UhcVoucherEntity> findByVoucherCode(String voucherCode);
        @Modifying
        @Query("UPDATE UhcVoucherEntity v SET v.patientId = :survivorId WHERE v.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }

    /** Pluggable Mobile Money / Payer Gateway SPI (MTN MoMo, Orange Money, Stripe, Bank). */
    public interface PaymentGatewaySpi {
        String providerCode();
        boolean verifyWebhookSignature(String rawPayload, String signatureHeader, String secret);
    }

    public static class MtnMomoAdapter implements PaymentGatewaySpi {
        @Override public String providerCode() { return "MTN_MOMO"; }
        @Override public boolean verifyWebhookSignature(String payload, String signature, String secret) {
            return verifyHmacSha256(payload, signature, secret);
        }
    }

    public static class OrangeMoneyAdapter implements PaymentGatewaySpi {
        @Override public String providerCode() { return "ORANGE_MONEY"; }
        @Override public boolean verifyWebhookSignature(String payload, String signature, String secret) {
            return verifyHmacSha256(payload, signature, secret);
        }
    }

    private static final String OFFLINE_QR_SIGNING_SECRET = "opes-uhc-offline-qr-key-v1";

    private final PatientAccountRepository accountRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final CbhiPolicyRepository cbhiRepo;
    private final InsuranceClaimRepository claimRepo;
    private final PaymentTransactionRepository txRepo;
    private final UhcVoucherRepository voucherRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final Map<String, PaymentGatewaySpi> paymentAdapters = new HashMap<>();

    public UhcBillingService(
            PatientAccountRepository accountRepo,
            LedgerEntryRepository ledgerRepo,
            CbhiPolicyRepository cbhiRepo,
            InsuranceClaimRepository claimRepo,
            PaymentTransactionRepository txRepo,
            UhcVoucherRepository voucherRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            List<PaymentGatewaySpi> adapters) {
        this.accountRepo = accountRepo;
        this.ledgerRepo = ledgerRepo;
        this.cbhiRepo = cbhiRepo;
        this.claimRepo = claimRepo;
        this.txRepo = txRepo;
        this.voucherRepo = voucherRepo;
        this.outboxRepo = outboxRepo;
        adapters.forEach(a -> paymentAdapters.put(a.providerCode(), a));
    }

    private static final ObjectMapper MERGE_MAPPER = new ObjectMapper();

    /** Cascades MPI Golden Record merges emitted via Outbox into billing accounts, CBHI policies, claims & vouchers. */
    @EventListener
    @Transactional
    public void onOutboxDomainEvent(HealthCoreDomainEvent event) {
        if (event == null || !"PatientMergedEvent".equals(event.eventType()) || event.payloadJson() == null) {
            return;
        }
        try {
            JsonNode root = MERGE_MAPPER.readTree(event.payloadJson());
            String src = root.path("sourceId").asText("");
            String surv = root.path("survivingId").asText("");
            if (!src.isBlank() && !surv.isBlank()) {
                UUID sourceId = UUID.fromString(src);
                UUID survivorId = UUID.fromString(surv);
                accountRepo.relinkPatient(sourceId, survivorId);
                cbhiRepo.relinkPatient(sourceId, survivorId);
                claimRepo.relinkPatient(sourceId, survivorId);
                voucherRepo.relinkPatient(sourceId, survivorId);
            }
        } catch (Exception ignored) {
            // Malformed payload does not break other event consumers
        }
    }

    /** Posts an immutable DEBIT charge and updates account balance under optimistic version control. */
    @Transactional
    @RetryableOnTransientSqlFailure
    @ClinicalAudit(action = "BILLING_POST_CHARGE", resourceType = "PatientAccount")
    public LedgerEntryEntity postCharge(UUID facilityId, UUID patientId, long amountMinor, String currency, String ref, String desc) {
        PatientAccountEntity acct = accountRepo.findByFacilityIdAndPatientId(facilityId, patientId)
                .orElseGet(() -> {
                    PatientAccountEntity created = new PatientAccountEntity();
                    created.setFacilityId(facilityId);
                    created.setPatientId(patientId);
                    created.setCurrency(currency);
                    return accountRepo.save(created);
                });
        acct.setBalanceMinor(acct.getBalanceMinor() + amountMinor);
        accountRepo.save(acct);

        LedgerEntryEntity entry = new LedgerEntryEntity();
        entry.setAccountId(acct.getId());
        entry.setEntryType("DEBIT");
        entry.setAmountMinor(amountMinor);
        entry.setCurrency(currency);
        entry.setReferenceCode(ref);
        entry.setDescription(desc);
        return ledgerRepo.save(entry);
    }

    /** Adjudicates CBHI / UHC claim splitting insurer share vs. patient co-pay. */
    @Transactional
    @ClinicalAudit(action = "UHC_ADJUDICATE_CLAIM", resourceType = "InsuranceClaim")
    public InsuranceClaimEntity adjudicateCbhiClaim(String policyNumber, long totalBilledMinor, String adjudicationModel) {
        CbhiPolicyEntity policy = cbhiRepo.findByPolicyNumberAndActiveTrue(policyNumber)
                .orElseThrow(() -> new NoSuchElementException("Active CBHI/UHC policy not found: " + policyNumber));

        LocalDate today = LocalDate.now();
        if (today.isBefore(policy.getValidFrom()) || today.isAfter(policy.getValidUntil())) {
            throw new IllegalStateException("CBHI policy expired or not yet active: " + policyNumber);
        }

        long insurerShare = (totalBilledMinor * policy.getCoveragePercent()) / 100L;
        long copay = totalBilledMinor - insurerShare;

        InsuranceClaimEntity claim = new InsuranceClaimEntity();
        claim.setFacilityId(policy.getFacilityId());
        claim.setPatientId(policy.getPatientId());
        claim.setPolicyId(policy.getId());
        claim.setAdjudicationModel(adjudicationModel);
        claim.setTotalBilledMinor(totalBilledMinor);
        claim.setInsurerShareMinor(insurerShare);
        claim.setPatientCopayMinor(copay);
        claim.setStatus("ADJUDICATED");
        claim = claimRepo.save(claim);

        postCharge(policy.getFacilityId(), policy.getPatientId(), copay, "XAF",
                "CLAIM:" + claim.getId(), "Patient co-pay for UHC/CBHI policy " + policyNumber);

        emitOutbox("InsuranceClaim", claim.getId(), "ClaimAdjudicatedEvent",
                "{\"claimId\":\"" + claim.getId() + "\",\"insurerShareMinor\":" + insurerShare + "}");
        return claim;
    }

    /** Redeems a Maternity/Child UHC health voucher against a facility charge. */
    @Transactional
    @ClinicalAudit(action = "UHC_REDEEM_VOUCHER", resourceType = "UhcVoucher")
    public UhcVoucherEntity redeemVoucher(String voucherCode, long requestedAmountMinor) {
        UhcVoucherEntity voucher = voucherRepo.findByVoucherCode(voucherCode)
                .orElseThrow(() -> new NoSuchElementException("UHC Voucher not found: " + voucherCode));
        if (!"ACTIVE".equals(voucher.getStatus()) || voucher.getExpiresOn().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Voucher is inactive or expired: " + voucherCode);
        }
        if (requestedAmountMinor <= 0 || requestedAmountMinor > voucher.getRemainingMinor()) {
            throw new IllegalArgumentException("Insufficient voucher remaining balance: " + voucher.getRemainingMinor());
        }
        voucher.setRemainingMinor(voucher.getRemainingMinor() - requestedAmountMinor);
        if (voucher.getRemainingMinor() == 0) {
            voucher.setStatus("REDEEMED");
        }
        return voucherRepo.save(voucher);
    }

    /** Issues an HMAC-SHA256 signed offline UHC eligibility QR token verifiable without internet. */
    @Transactional(readOnly = true)
    public Map<String, Object> issueOfflineEligibilityQr(String policyNumber) {
        CbhiPolicyEntity policy = cbhiRepo.findByPolicyNumberAndActiveTrue(policyNumber)
                .orElseThrow(() -> new NoSuchElementException("Active policy not found: " + policyNumber));
        String claimsPayload = policy.getPolicyNumber() + "|" + policy.getPatientId() + "|"
                + policy.getCoveragePercent() + "|" + policy.getValidUntil();
        String sig = computeHmacSha256(claimsPayload, OFFLINE_QR_SIGNING_SECRET);
        String qrToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((claimsPayload + "|" + sig).getBytes(StandardCharsets.UTF_8));
        return Map.of(
                "policyNumber", policy.getPolicyNumber(),
                "schemeName", policy.getSchemeName(),
                "coveragePercent", policy.getCoveragePercent(),
                "validUntil", policy.getValidUntil().toString(),
                "signedQrToken", qrToken
        );
    }

    /** Verifies a scanned offline UHC eligibility QR token deterministically. */
    public Map<String, Object> verifyOfflineEligibilityQr(String qrToken) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(qrToken), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 5) {
                return Map.of("valid", false, "reason", "Malformed UHC offline QR token");
            }
            String claimsPayload = parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[3];
            boolean sigValid = verifyHmacSha256(claimsPayload, parts[4], OFFLINE_QR_SIGNING_SECRET);
            boolean unexpired = !LocalDate.parse(parts[3]).isBefore(LocalDate.now());
            return Map.of(
                    "valid", sigValid && unexpired,
                    "policyNumber", parts[0],
                    "patientId", parts[1],
                    "coveragePercent", Integer.parseInt(parts[2]),
                    "validUntil", parts[3]
            );
        } catch (Exception ex) {
            return Map.of("valid", false, "reason", ex.getMessage());
        }
    }

    /** Credits patient account after verifying Mobile Money HMAC webhook signature. */
    @Transactional
    @RetryableOnTransientSqlFailure
    public PaymentTransactionEntity settleMobileMoneyWebhook(
            String providerCode, UUID accountId, String externalRef,
            long amountMinor, String currency, String rawPayload, String signatureHeader, String webhookSecret) {
        PaymentGatewaySpi adapter = paymentAdapters.get(providerCode.toUpperCase(Locale.ROOT));
        if (adapter == null || !adapter.verifyWebhookSignature(rawPayload, signatureHeader, webhookSecret)) {
            throw new SecurityException("Invalid Mobile Money webhook signature for provider " + providerCode);
        }

        PatientAccountEntity acct = accountRepo.findById(accountId)
                .orElseThrow(() -> new NoSuchElementException("PatientAccount not found: " + accountId));
        acct.setBalanceMinor(acct.getBalanceMinor() - amountMinor);
        accountRepo.save(acct);

        LedgerEntryEntity credit = new LedgerEntryEntity();
        credit.setAccountId(accountId);
        credit.setEntryType("CREDIT");
        credit.setAmountMinor(amountMinor);
        credit.setCurrency(currency);
        credit.setReferenceCode(providerCode + ":" + externalRef);
        credit.setDescription("Mobile Money settlement");
        ledgerRepo.save(credit);

        PaymentTransactionEntity tx = new PaymentTransactionEntity();
        tx.setAccountId(accountId);
        tx.setProviderCode(providerCode);
        tx.setExternalTxRef(externalRef);
        tx.setAmountMinor(amountMinor);
        tx.setCurrency(currency);
        return txRepo.save(tx);
    }

    private static String computeHmacSha256(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean verifyHmacSha256(String payload, String signatureHex, String secret) {
        if (signatureHex == null || secret == null) return false;
        try {
            String expected = computeHmacSha256(payload, secret);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signatureHex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return false;
        }
    }

    private void emitOutbox(String type, UUID id, String evtType, String json) {
        TransactionalOutboxEntity e = new TransactionalOutboxEntity();
        e.setIdempotencyKey(type + ":" + evtType + ":" + id + ":" + e.getId());
        e.setAggregateType(type);
        e.setAggregateId(id);
        e.setEventType(evtType);
        e.setPayloadJson(json);
        outboxRepo.save(e);
    }

    @RestController
    @Tag(name = "UHC / CBHI, OpenIMIS & Billing", description = "Double-entry ledger, CBHI Mutuelles de Santé, OpenIMIS FHIR Coverage/Claim, Vouchers, Offline QR & MoMo")
    public static class UhcBillingController {
        private final UhcBillingService service;
        private final CbhiPolicyRepository cbhiRepo;
        private final UhcVoucherRepository voucherRepo;

        public UhcBillingController(
                UhcBillingService service,
                CbhiPolicyRepository cbhiRepo,
                UhcVoucherRepository voucherRepo) {
            this.service = service;
            this.cbhiRepo = cbhiRepo;
            this.voucherRepo = voucherRepo;
        }

        @PostMapping("/api/v1/uhc/policies")
        @PreAuthorize("hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:invoice')")
        @Operation(summary = "Enroll patient or household into CBHI / UHC insurance scheme")
        public CbhiPolicyEntity enrollPolicy(@RequestBody CbhiPolicyEntity policy) {
            return cbhiRepo.save(policy);
        }

        @GetMapping("/api/v1/uhc/policies/{policyNumber}/offline-qr")
        @PreAuthorize("hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:invoice')")
        @Operation(summary = "Generate HMAC-signed offline UHC eligibility verification QR token")
        public Map<String, Object> offlineQr(@PathVariable String policyNumber) {
            return service.issueOfflineEligibilityQr(policyNumber);
        }

        @PostMapping("/api/v1/uhc/policies/verify-offline-qr")
        @Operation(summary = "Verify offline UHC eligibility QR token without central internet")
        public Map<String, Object> verifyOfflineQr(@RequestParam String token) {
            return service.verifyOfflineEligibilityQr(token);
        }

        @PostMapping("/api/v1/uhc/vouchers")
        @PreAuthorize("hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:invoice')")
        @Operation(summary = "Issue UHC Maternity / Child subsidy voucher")
        public UhcVoucherEntity issueVoucher(@RequestBody UhcVoucherEntity voucher) {
            return voucherRepo.save(voucher);
        }

        @PostMapping("/api/v1/uhc/vouchers/{voucherCode}/redeem")
        @PreAuthorize("hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_billing:collect')")
        @Operation(summary = "Redeem UHC health voucher balance")
        public UhcVoucherEntity redeemVoucher(@PathVariable String voucherCode, @RequestParam long amountMinor) {
            return service.redeemVoucher(voucherCode, amountMinor);
        }

        @PostMapping("/api/v1/uhc/charges")
        @PreAuthorize("hasAuthority('PERM_billing:invoice')")
        @Operation(summary = "Post immutable DEBIT charge to patient double-entry account")
        public LedgerEntryEntity postCharge(
                @RequestParam UUID facilityId, @RequestParam UUID patientId,
                @RequestParam long amountMinor, @RequestParam(defaultValue = "XAF") String currency,
                @RequestParam String ref, @RequestParam String description) {
            return service.postCharge(facilityId, patientId, amountMinor, currency, ref, description);
        }

        @PostMapping("/api/v1/uhc/claims/adjudicate")
        @PreAuthorize("hasAuthority('PERM_claim:submit') or hasAuthority('PERM_uhc:verify')")
        @Operation(summary = "Adjudicate UHC/CBHI claim splitting insurer share and patient co-pay")
        public InsuranceClaimEntity adjudicate(
                @RequestParam String policyNumber,
                @RequestParam long totalBilledMinor,
                @RequestParam(defaultValue = "FEE_FOR_SERVICE") String model) {
            return service.adjudicateCbhiClaim(policyNumber, totalBilledMinor, model);
        }

        /** OpenIMIS FHIR R4 Coverage Search Endpoint. */
        @GetMapping(value = "/fhir/r4/Coverage", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_uhc:verify') or hasAuthority('PERM_patient:read')")
        @Operation(summary = "OpenIMIS-compatible FHIR R4 Coverage eligibility lookup")
        public Map<String, Object> fhirCoverage(@RequestParam UUID beneficiary) {
            List<Map<String, Object>> entries = cbhiRepo.findByPatientIdAndActiveTrue(beneficiary).stream()
                    .map(p -> Map.<String, Object>of("resource", Map.of(
                            "resourceType", "Coverage",
                            "id", p.getId().toString(),
                            "status", "active",
                            "subscriberId", p.getPolicyNumber(),
                            "beneficiary", Map.of("reference", "Patient/" + p.getPatientId()),
                            "payor", List.of(Map.of("display", p.getSchemeName())),
                            "period", Map.of("start", p.getValidFrom().toString(), "end", p.getValidUntil().toString()))))
                    .toList();
            return Map.of("resourceType", "Bundle", "type", "searchset", "total", entries.size(), "entry", entries);
        }

        /** OpenIMIS FHIR R4 Claim -> ClaimResponse Submission Endpoint. */
        @PostMapping(value = "/fhir/r4/Claim", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_claim:submit')")
        @Operation(summary = "Submit OpenIMIS-compatible FHIR R4 Claim and return ClaimResponse")
        public Map<String, Object> fhirClaim(
                @RequestParam String policyNumber,
                @RequestParam long totalBilledMinor) {
            InsuranceClaimEntity claim = service.adjudicateCbhiClaim(policyNumber, totalBilledMinor, "CASE_DRG");
            return Map.of(
                    "resourceType", "ClaimResponse",
                    "id", claim.getId().toString(),
                    "status", "active",
                    "outcome", "complete",
                    "disposition", "Adjudicated by Opes HealthCore OpenIMIS Adapter",
                    "total", List.of(
                            Map.of("category", Map.of("text", "submitted"), "amount", Map.of("value", claim.getTotalBilledMinor(), "currency", "XAF")),
                            Map.of("category", Map.of("text", "benefit"), "amount", Map.of("value", claim.getInsurerShareMinor(), "currency", "XAF"))
                    )
            );
        }

        @PostMapping("/api/v1/uhc/webhooks/{providerCode}")
        @Operation(summary = "HMAC-verified Mobile Money payment callback (MTN_MOMO / ORANGE_MONEY)")
        public PaymentTransactionEntity webhook(
                @PathVariable String providerCode,
                @RequestParam UUID accountId,
                @RequestParam String externalRef,
                @RequestParam long amountMinor,
                @RequestParam(defaultValue = "XAF") String currency,
                @RequestHeader("X-Webhook-Signature") String signature,
                @RequestBody String rawPayload) {
            return service.settleMobileMoneyWebhook(
                    providerCode, accountId, externalRef, amountMinor, currency, rawPayload, signature, "opes-momo-dev-secret");
        }
    }
}
