package com.opeshealth.healthcore.revenueclaims;

import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.*;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RevenueCodingClaimsServiceTest {

    private FacilityTariffRepository tariffRepo;
    private ClinicalCodingRepository codingRepo;
    private InsuranceAuthorizationRepository authRepo;
    private PayerClaimRepository claimRepo;
    private RevenueCodingClaimsService service;
    private UUID facilityId;

    @BeforeEach
    void setUp() {
        tariffRepo = mock(FacilityTariffRepository.class);
        codingRepo = mock(ClinicalCodingRepository.class);
        authRepo = mock(InsuranceAuthorizationRepository.class);
        claimRepo = mock(PayerClaimRepository.class);
        when(tariffRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(codingRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(authRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(claimRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new RevenueCodingClaimsService(tariffRepo, codingRepo, authRepo, claimRepo, mock(ClinicalAuditService.class));
        facilityId = UUID.randomUUID();
        FacilityContext.setFacilityId(facilityId);
    }

    @AfterEach
    void tearDown() {
        FacilityContext.clear();
    }

    @Test
    void enforcesHumanClinicalReviewSeparationAndPriorAuthBeforeClaimSubmission() {
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        // Reject invalid ICD-10 syntax
        assertThrows(IllegalArgumentException.class, () -> service.createCodingAssignment(
                new CreateCodingRequest(patientId, encounterId, "ICD10", "BADCODE", null, "99213", "DRG-101", "coder.1", 50000)));

        ClinicalCodingRecordEntity coding = service.createCodingAssignment(
                new CreateCodingRequest(patientId, encounterId, "ICD10", "J18.9", "I10", "99223", "DRG-194", "coder.1", 75000));
        assertEquals("PENDING_CLINICAL_REVIEW", coding.getReviewStatus());
        when(codingRepo.findByIdAndFacilityId(coding.getId(), facilityId)).thenReturn(Optional.of(coding));

        // Self-review blocked
        assertThrows(ResponseStatusException.class, () -> service.approveClinicalCoding(coding.getId(), "coder.1"));

        // Claim blocked prior to approval
        assertThrows(ResponseStatusException.class, () -> service.submitPayerClaim(
                new SubmitClaimRequest("CLM-1001", coding.getId(), null, "CNPS", "X12_837P", 75000, "XAF"), "biller.1"));

        // Independent reviewer approves coding
        service.approveClinicalCoding(coding.getId(), "dr.reviewer");
        assertEquals("CLINICALLY_APPROVED", coding.getReviewStatus());

        // Prior auth workflow
        InsuranceAuthorizationEntity auth = service.verifyEligibilityAndRequestAuth(
                new EligibilityAuthRequest(patientId, "CNPS", "POL-8899", "99223", true, 1), "biller.1");
        when(authRepo.findByIdAndFacilityId(auth.getId(), facilityId)).thenReturn(Optional.of(auth));

        // Pending auth blocks claim submission
        assertThrows(ResponseStatusException.class, () -> service.submitPayerClaim(
                new SubmitClaimRequest("CLM-1001", coding.getId(), auth.getId(), "CNPS", "X12_837P", 75000, "XAF"), "biller.1"));

        service.decidePriorAuth(auth.getId(), true, 1, LocalDate.now().plusDays(30), "payer.desk");
        PayerClaimEntity claim = service.submitPayerClaim(
                new SubmitClaimRequest("CLM-1001", coding.getId(), auth.getId(), "CNPS", "X12_837P", 75000, "XAF"), "biller.1");
        assertEquals("SUBMITTED", claim.getClaimStatus());
        assertEquals("LOCKED", coding.getReviewStatus());
    }

    @Test
    void reconcilesEraRemittanceDenialAppealAndRefundCap() {
        PayerClaimEntity claim = new PayerClaimEntity();
        claim.setFacilityId(facilityId);
        claim.setClaimControlNumber("CLM-2002");
        claim.setPatientId(UUID.randomUUID());
        claim.setEncounterId(UUID.randomUUID());
        claim.setCodingRecordId(UUID.randomUUID());
        claim.setPayerCode("ALLIANZ");
        claim.setBilledAmountMinor(100000);
        when(claimRepo.findByIdAndFacilityId(claim.getId(), facilityId)).thenReturn(Optional.of(claim));

        // Paid > allowed rejected
        assertThrows(IllegalArgumentException.class, () -> service.postEraRemittance(
                claim.getId(), new PostEraRemittanceRequest("ERA-1", 80000, 90000, 0, null), "era.bot"));

        // Denial -> Appeal
        service.postEraRemittance(claim.getId(), new PostEraRemittanceRequest("ERA-2", 0, 0, 0, "CO-197"), "era.bot");
        assertEquals("DENIED", claim.getClaimStatus());
        service.appealDeniedClaim(claim.getId(), "Attached signed operative note and approved PA", "biller.1");
        assertEquals("APPEALED", claim.getClaimStatus());

        // Paid via ERA -> Refund capped at paid amount
        service.postEraRemittance(claim.getId(), new PostEraRemittanceRequest("ERA-3", 95000, 85000, 10000, null), "era.bot");
        assertEquals("PAID", claim.getClaimStatus());
        assertThrows(ResponseStatusException.class, () -> service.recordClaimRefund(claim.getId(), 90000, "Over-refund", "biller.1"));
        service.recordClaimRefund(claim.getId(), 85000, "Duplicate payer wire reversal", "biller.1");
        assertEquals("REFUNDED", claim.getClaimStatus());
    }
}
