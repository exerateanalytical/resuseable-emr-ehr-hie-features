/*
 * =============================================================================
 * Opes HealthCore SDK — Double-Entry Ledger, UHC Claims & Mobile Money Adapters
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.uhc.service;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.persistence.sql.RetryableOnTransientSqlFailure;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
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
    }
    public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, UUID> {}
    public interface CbhiPolicyRepository extends JpaRepository<CbhiPolicyEntity, UUID> {
        Optional<CbhiPolicyEntity> findByPolicyNumberAndActiveTrue(String policyNumber);
    }
    public interface InsuranceClaimRepository extends JpaRepository<InsuranceClaimEntity, UUID> {}
    public interface PaymentTransactionRepository extends JpaRepository<PaymentTransactionEntity, UUID> {}

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

    private final PatientAccountRepository accountRepo;
    private final LedgerEntryRepository ledgerRepo;
    private final CbhiPolicyRepository cbhiRepo;
    private final InsuranceClaimRepository claimRepo;
    private final PaymentTransactionRepository txRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final Map<String, PaymentGatewaySpi> paymentAdapters = new HashMap<>();

    public UhcBillingService(
            PatientAccountRepository accountRepo,
            LedgerEntryRepository ledgerRepo,
            CbhiPolicyRepository cbhiRepo,
            InsuranceClaimRepository claimRepo,
            PaymentTransactionRepository txRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            List<PaymentGatewaySpi> adapters) {
        this.accountRepo = accountRepo;
        this.ledgerRepo = ledgerRepo;
        this.cbhiRepo = cbhiRepo;
        this.claimRepo = claimRepo;
        this.txRepo = txRepo;
        this.outboxRepo = outboxRepo;
        adapters.forEach(a -> paymentAdapters.put(a.providerCode(), a));
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

    private static boolean verifyHmacSha256(String payload, String signatureHex, String secret) {
        if (signatureHex == null || secret == null) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
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
    @RequestMapping("/api/v1/uhc")
    @Tag(name = "UHC / CBHI & Billing", description = "Double-entry ledger, CBHI Mutuelles de Santé claims, and MTN/Orange MoMo webhooks")
    public static class UhcBillingController {
        private final UhcBillingService service;
        private final CbhiPolicyRepository cbhiRepo;

        public UhcBillingController(UhcBillingService service, CbhiPolicyRepository cbhiRepo) {
            this.service = service;
            this.cbhiRepo = cbhiRepo;
        }

        @PostMapping("/policies")
        @Operation(summary = "Enroll patient or household into CBHI / UHC insurance scheme")
        public CbhiPolicyEntity enrollPolicy(@RequestBody CbhiPolicyEntity policy) {
            return cbhiRepo.save(policy);
        }

        @PostMapping("/charges")
        @Operation(summary = "Post immutable DEBIT charge to patient double-entry account")
        public LedgerEntryEntity postCharge(
                @RequestParam UUID facilityId, @RequestParam UUID patientId,
                @RequestParam long amountMinor, @RequestParam(defaultValue = "XAF") String currency,
                @RequestParam String ref, @RequestParam String description) {
            return service.postCharge(facilityId, patientId, amountMinor, currency, ref, description);
        }

        @PostMapping("/claims/adjudicate")
        @Operation(summary = "Adjudicate UHC/CBHI claim splitting insurer share and patient co-pay")
        public InsuranceClaimEntity adjudicate(
                @RequestParam String policyNumber,
                @RequestParam long totalBilledMinor,
                @RequestParam(defaultValue = "FEE_FOR_SERVICE") String model) {
            return service.adjudicateCbhiClaim(policyNumber, totalBilledMinor, model);
        }

        @PostMapping("/webhooks/{providerCode}")
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
