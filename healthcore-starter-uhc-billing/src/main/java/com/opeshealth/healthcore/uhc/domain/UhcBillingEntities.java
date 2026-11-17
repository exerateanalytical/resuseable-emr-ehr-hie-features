/*
 * =============================================================================
 * Opes HealthCore SDK — UHC/CBHI, Double-Entry Billing & Payment Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.uhc.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * All monetary figures are stored in integer minor currency units ({@code long})
 * to prevent IEEE-754 floating-point rounding discrepancies across databases.
 */
public final class UhcBillingEntities {

    private UhcBillingEntities() {}

    @Entity @Table(name = "hc_tariff_schedules")
    public static class TariffScheduleEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "service_code", nullable = false, length = 64) private String serviceCode;
        @Column(name = "service_name", nullable = false, length = 200) private String serviceName;
        @Column(name = "payer_tier", nullable = false, length = 32) private String payerTier; // CASH, CBHI_UHC, PRIVATE_INS
        @Column(name = "unit_price_minor", nullable = false) private long unitPriceMinor;
        @Column(name = "currency", nullable = false, length = 8) private String currency = "XAF";

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getServiceCode() { return serviceCode; }
        public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getPayerTier() { return payerTier; }
        public void setPayerTier(String payerTier) { this.payerTier = payerTier; }
        public long getUnitPriceMinor() { return unitPriceMinor; }
        public void setUnitPriceMinor(long unitPriceMinor) { this.unitPriceMinor = unitPriceMinor; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }

    @Entity @Table(name = "hc_patient_accounts")
    public static class PatientAccountEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "currency", nullable = false, length = 8) private String currency = "XAF";
        @Column(name = "balance_minor", nullable = false) private long balanceMinor = 0L;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public long getBalanceMinor() { return balanceMinor; }
        public void setBalanceMinor(long balanceMinor) { this.balanceMinor = balanceMinor; }
        public long getVersion() { return version; }
    }

    @Entity @Table(name = "hc_ledger_entries")
    public static class LedgerEntryEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "account_id", nullable = false) private UUID accountId;
        @Column(name = "entry_type", nullable = false, length = 16) private String entryType; // DEBIT or CREDIT
        @Column(name = "amount_minor", nullable = false) private long amountMinor;
        @Column(name = "currency", nullable = false, length = 8) private String currency;
        @Column(name = "reference_code", nullable = false, length = 100) private String referenceCode;
        @Column(name = "description", length = 300) private String description;
        @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getAccountId() { return accountId; }
        public void setAccountId(UUID accountId) { this.accountId = accountId; }
        public String getEntryType() { return entryType; }
        public void setEntryType(String entryType) { this.entryType = entryType; }
        public long getAmountMinor() { return amountMinor; }
        public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getReferenceCode() { return referenceCode; }
        public void setReferenceCode(String referenceCode) { this.referenceCode = referenceCode; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Instant getPostedAt() { return postedAt; }
    }

    /** Universal Health Care & Community-Based Health Insurance (CBHI / Mutuelles de Santé) policy. */
    @Entity @Table(name = "hc_cbhi_policies")
    public static class CbhiPolicyEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "scheme_name", nullable = false, length = 120) private String schemeName;
        @Column(name = "policy_number", nullable = false, length = 64) private String policyNumber;
        @Column(name = "household_id", length = 64) private String householdId;
        @Column(name = "coverage_percent", nullable = false) private int coveragePercent = 80;
        @Column(name = "valid_from", nullable = false) private LocalDate validFrom;
        @Column(name = "valid_until", nullable = false) private LocalDate validUntil;
        @Column(name = "active", nullable = false) private boolean active = true;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getSchemeName() { return schemeName; }
        public void setSchemeName(String schemeName) { this.schemeName = schemeName; }
        public String getPolicyNumber() { return policyNumber; }
        public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
        public String getHouseholdId() { return householdId; }
        public void setHouseholdId(String householdId) { this.householdId = householdId; }
        public int getCoveragePercent() { return coveragePercent; }
        public void setCoveragePercent(int coveragePercent) { this.coveragePercent = coveragePercent; }
        public LocalDate getValidFrom() { return validFrom; }
        public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
        public LocalDate getValidUntil() { return validUntil; }
        public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Entity @Table(name = "hc_insurance_claims")
    public static class InsuranceClaimEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "policy_id", nullable = false) private UUID policyId;
        @Column(name = "adjudication_model", nullable = false, length = 32) private String adjudicationModel; // FEE_FOR_SERVICE, CAPITATION, CASE_DRG
        @Column(name = "total_billed_minor", nullable = false) private long totalBilledMinor;
        @Column(name = "insurer_share_minor", nullable = false) private long insurerShareMinor;
        @Column(name = "patient_copay_minor", nullable = false) private long patientCopayMinor;
        @Column(name = "status", nullable = false, length = 24) private String status = "SUBMITTED";
        @Column(name = "submitted_at", nullable = false) private Instant submittedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getPolicyId() { return policyId; }
        public void setPolicyId(UUID policyId) { this.policyId = policyId; }
        public String getAdjudicationModel() { return adjudicationModel; }
        public void setAdjudicationModel(String adjudicationModel) { this.adjudicationModel = adjudicationModel; }
        public long getTotalBilledMinor() { return totalBilledMinor; }
        public void setTotalBilledMinor(long totalBilledMinor) { this.totalBilledMinor = totalBilledMinor; }
        public long getInsurerShareMinor() { return insurerShareMinor; }
        public void setInsurerShareMinor(long insurerShareMinor) { this.insurerShareMinor = insurerShareMinor; }
        public long getPatientCopayMinor() { return patientCopayMinor; }
        public void setPatientCopayMinor(long patientCopayMinor) { this.patientCopayMinor = patientCopayMinor; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getSubmittedAt() { return submittedAt; }
    }

    @Entity @Table(name = "hc_payment_transactions")
    public static class PaymentTransactionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "account_id", nullable = false) private UUID accountId;
        @Column(name = "provider_code", nullable = false, length = 32) private String providerCode; // MTN_MOMO, ORANGE_MONEY, CASH, CARD
        @Column(name = "external_tx_ref", nullable = false, length = 128) private String externalTxRef;
        @Column(name = "amount_minor", nullable = false) private long amountMinor;
        @Column(name = "currency", nullable = false, length = 8) private String currency;
        @Column(name = "status", nullable = false, length = 24) private String status = "SETTLED";
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getAccountId() { return accountId; }
        public void setAccountId(UUID accountId) { this.accountId = accountId; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String providerCode) { this.providerCode = providerCode; }
        public String getExternalTxRef() { return externalTxRef; }
        public void setExternalTxRef(String externalTxRef) { this.externalTxRef = externalTxRef; }
        public long getAmountMinor() { return amountMinor; }
        public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
