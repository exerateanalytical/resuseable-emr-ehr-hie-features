/*
 * =============================================================================
 * Opes HealthCore SDK — Revenue Coding, Tariffs & Payer Claims Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.revenueclaims.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class RevenueCodingClaimsEntities {
    private RevenueCodingClaimsEntities() {}

    @Entity
    @Table(name = "hc_rev_facility_tariffs")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class FacilityTariffEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "payer_code", nullable = false, length = 64) private String payerCode;
        @Column(name = "service_code", nullable = false, length = 64) private String serviceCode;
        @Column(name = "service_name", nullable = false, length = 220) private String serviceName;
        @Column(name = "service_category", nullable = false, length = 48) private String serviceCategory;
        @Column(name = "unit_price_minor", nullable = false) private long unitPriceMinor;
        @Column(name = "currency_code", nullable = false, length = 8) private String currencyCode = "XAF";
        @Column(name = "effective_from", nullable = false) private LocalDate effectiveFrom = LocalDate.now();
        @Column(name = "effective_to") private LocalDate effectiveTo;
        @Column(name = "active", nullable = false) private boolean active = true;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getPayerCode() { return payerCode; }
        public void setPayerCode(String payerCode) { this.payerCode = payerCode; }
        public String getServiceCode() { return serviceCode; }
        public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
        public String getServiceName() { return serviceName; }
        public void setServiceName(String serviceName) { this.serviceName = serviceName; }
        public String getServiceCategory() { return serviceCategory; }
        public void setServiceCategory(String serviceCategory) { this.serviceCategory = serviceCategory; }
        public long getUnitPriceMinor() { return unitPriceMinor; }
        public void setUnitPriceMinor(long unitPriceMinor) { this.unitPriceMinor = unitPriceMinor; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public LocalDate getEffectiveFrom() { return effectiveFrom; }
        public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
        public LocalDate getEffectiveTo() { return effectiveTo; }
        public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_rev_clinical_coding")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ClinicalCodingRecordEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "coding_system", nullable = false, length = 16) private String codingSystem = "ICD10";
        @Column(name = "primary_icd_code", nullable = false, length = 32) private String primaryIcdCode;
        @Column(name = "secondary_icd_codes", length = 400) private String secondaryIcdCodes;
        @Column(name = "cpt_procedure_codes", length = 400) private String cptProcedureCodes;
        @Column(name = "drg_code", length = 32) private String drgCode;
        @Column(name = "coder_id", nullable = false, length = 120) private String coderId;
        @Column(name = "review_status", nullable = false, length = 32) private String reviewStatus = "PENDING_CLINICAL_REVIEW";
        @Column(name = "reviewed_by", length = 120) private String reviewedBy;
        @Column(name = "reviewed_at") private Instant reviewedAt;
        @Column(name = "estimated_reimbursement_minor", nullable = false) private long estimatedReimbursementMinor;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public String getCodingSystem() { return codingSystem; }
        public void setCodingSystem(String codingSystem) { this.codingSystem = codingSystem; }
        public String getPrimaryIcdCode() { return primaryIcdCode; }
        public void setPrimaryIcdCode(String primaryIcdCode) { this.primaryIcdCode = primaryIcdCode; }
        public String getSecondaryIcdCodes() { return secondaryIcdCodes; }
        public void setSecondaryIcdCodes(String secondaryIcdCodes) { this.secondaryIcdCodes = secondaryIcdCodes; }
        public String getCptProcedureCodes() { return cptProcedureCodes; }
        public void setCptProcedureCodes(String cptProcedureCodes) { this.cptProcedureCodes = cptProcedureCodes; }
        public String getDrgCode() { return drgCode; }
        public void setDrgCode(String drgCode) { this.drgCode = drgCode; }
        public String getCoderId() { return coderId; }
        public void setCoderId(String coderId) { this.coderId = coderId; }
        public String getReviewStatus() { return reviewStatus; }
        public void setReviewStatus(String reviewStatus) { this.reviewStatus = reviewStatus; }
        public String getReviewedBy() { return reviewedBy; }
        public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
        public Instant getReviewedAt() { return reviewedAt; }
        public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
        public long getEstimatedReimbursementMinor() { return estimatedReimbursementMinor; }
        public void setEstimatedReimbursementMinor(long estimatedReimbursementMinor) { this.estimatedReimbursementMinor = estimatedReimbursementMinor; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_rev_insurance_auth")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class InsuranceAuthorizationEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "payer_code", nullable = false, length = 64) private String payerCode;
        @Column(name = "member_policy_number", nullable = false, length = 96) private String memberPolicyNumber;
        @Column(name = "service_code", nullable = false, length = 64) private String serviceCode;
        @Column(name = "eligibility_verified", nullable = false) private boolean eligibilityVerified;
        @Column(name = "prior_auth_number", length = 64) private String priorAuthNumber;
        @Column(name = "auth_status", nullable = false, length = 32) private String authStatus = "PENDING_AUTH";
        @Column(name = "approved_units", nullable = false) private int approvedUnits;
        @Column(name = "valid_until") private LocalDate validUntil;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getPayerCode() { return payerCode; }
        public void setPayerCode(String payerCode) { this.payerCode = payerCode; }
        public String getMemberPolicyNumber() { return memberPolicyNumber; }
        public void setMemberPolicyNumber(String memberPolicyNumber) { this.memberPolicyNumber = memberPolicyNumber; }
        public String getServiceCode() { return serviceCode; }
        public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
        public boolean isEligibilityVerified() { return eligibilityVerified; }
        public void setEligibilityVerified(boolean eligibilityVerified) { this.eligibilityVerified = eligibilityVerified; }
        public String getPriorAuthNumber() { return priorAuthNumber; }
        public void setPriorAuthNumber(String priorAuthNumber) { this.priorAuthNumber = priorAuthNumber; }
        public String getAuthStatus() { return authStatus; }
        public void setAuthStatus(String authStatus) { this.authStatus = authStatus; }
        public int getApprovedUnits() { return approvedUnits; }
        public void setApprovedUnits(int approvedUnits) { this.approvedUnits = approvedUnits; }
        public LocalDate getValidUntil() { return validUntil; }
        public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_rev_payer_claims")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PayerClaimEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "claim_control_number", nullable = false, length = 64) private String claimControlNumber;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "encounter_id", nullable = false) private UUID encounterId;
        @Column(name = "coding_record_id", nullable = false) private UUID codingRecordId;
        @Column(name = "authorization_id") private UUID authorizationId;
        @Column(name = "payer_code", nullable = false, length = 64) private String payerCode;
        @Column(name = "claim_format", nullable = false, length = 24) private String claimFormat = "X12_837P";
        @Column(name = "claim_status", nullable = false, length = 24) private String claimStatus = "SUBMITTED";
        @Column(name = "currency_code", nullable = false, length = 8) private String currencyCode = "XAF";
        @Column(name = "billed_amount_minor", nullable = false) private long billedAmountMinor;
        @Column(name = "allowed_amount_minor", nullable = false) private long allowedAmountMinor;
        @Column(name = "paid_amount_minor", nullable = false) private long paidAmountMinor;
        @Column(name = "patient_responsibility_minor", nullable = false) private long patientResponsibilityMinor;
        @Column(name = "refund_amount_minor", nullable = false) private long refundAmountMinor;
        @Column(name = "denial_reason_code", length = 64) private String denialReasonCode;
        @Column(name = "era_trace_number", length = 64) private String eraTraceNumber;
        @Column(name = "submitted_at", nullable = false) private Instant submittedAt = Instant.now();
        @Column(name = "adjudicated_at") private Instant adjudicatedAt;
        @Version @Column(name = "version", nullable = false) private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getClaimControlNumber() { return claimControlNumber; }
        public void setClaimControlNumber(String claimControlNumber) { this.claimControlNumber = claimControlNumber; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public UUID getEncounterId() { return encounterId; }
        public void setEncounterId(UUID encounterId) { this.encounterId = encounterId; }
        public UUID getCodingRecordId() { return codingRecordId; }
        public void setCodingRecordId(UUID codingRecordId) { this.codingRecordId = codingRecordId; }
        public UUID getAuthorizationId() { return authorizationId; }
        public void setAuthorizationId(UUID authorizationId) { this.authorizationId = authorizationId; }
        public String getPayerCode() { return payerCode; }
        public void setPayerCode(String payerCode) { this.payerCode = payerCode; }
        public String getClaimFormat() { return claimFormat; }
        public void setClaimFormat(String claimFormat) { this.claimFormat = claimFormat; }
        public String getClaimStatus() { return claimStatus; }
        public void setClaimStatus(String claimStatus) { this.claimStatus = claimStatus; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public long getBilledAmountMinor() { return billedAmountMinor; }
        public void setBilledAmountMinor(long billedAmountMinor) { this.billedAmountMinor = billedAmountMinor; }
        public long getAllowedAmountMinor() { return allowedAmountMinor; }
        public void setAllowedAmountMinor(long allowedAmountMinor) { this.allowedAmountMinor = allowedAmountMinor; }
        public long getPaidAmountMinor() { return paidAmountMinor; }
        public void setPaidAmountMinor(long paidAmountMinor) { this.paidAmountMinor = paidAmountMinor; }
        public long getPatientResponsibilityMinor() { return patientResponsibilityMinor; }
        public void setPatientResponsibilityMinor(long patientResponsibilityMinor) { this.patientResponsibilityMinor = patientResponsibilityMinor; }
        public long getRefundAmountMinor() { return refundAmountMinor; }
        public void setRefundAmountMinor(long refundAmountMinor) { this.refundAmountMinor = refundAmountMinor; }
        public String getDenialReasonCode() { return denialReasonCode; }
        public void setDenialReasonCode(String denialReasonCode) { this.denialReasonCode = denialReasonCode; }
        public String getEraTraceNumber() { return eraTraceNumber; }
        public void setEraTraceNumber(String eraTraceNumber) { this.eraTraceNumber = eraTraceNumber; }
        public Instant getSubmittedAt() { return submittedAt; }
        public Instant getAdjudicatedAt() { return adjudicatedAt; }
        public void setAdjudicatedAt(Instant adjudicatedAt) { this.adjudicatedAt = adjudicatedAt; }
        public long getVersion() { return version; }
    }
}
