package com.opeshealth.healthcore.portal;

import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.*;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.*;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PatientPortalServiceTest {

    private PortalAccountRepository accountRepo;
    private ProxyGrantRepository proxyRepo;
    private MessageThreadRepository threadRepo;
    private PortalMessageRepository messageRepo;
    private SchedulingService schedulingService;
    private UnifiedOrdersResultsService ordersResultsService;
    private PatientPortalService service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(PortalAccountRepository.class);
        proxyRepo = mock(ProxyGrantRepository.class);
        threadRepo = mock(MessageThreadRepository.class);
        messageRepo = mock(PortalMessageRepository.class);
        schedulingService = mock(SchedulingService.class);
        ordersResultsService = mock(UnifiedOrdersResultsService.class);
        when(accountRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(proxyRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(threadRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new PatientPortalService(accountRepo, proxyRepo, threadRepo, messageRepo,
                schedulingService, ordersResultsService, mock(ClinicalAuditService.class));
    }

    @Test
    void enforcesOwnershipAndOnlyReleasesFinalResults() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        PatientPortalAccountEntity acct = service.enrollAccount(facId, new EnrollPortalAccountRequest(
                patientId, "sub-patient-1", "en", "p@example.org", "+237600000001"));
        when(accountRepo.findByFacilityIdAndIdentitySubjectAndActiveTrue(facId, "sub-patient-1"))
                .thenReturn(Optional.of(acct));
        when(proxyRepo.findByFacilityIdAndPatientIdAndProxySubjectAndRevokedFalse(eq(facId), eq(patientId), any()))
                .thenReturn(List.of());

        // Unauthorized caller blocked
        assertThrows(AccessDeniedException.class, () -> service.getReleasedResults(facId, "intruder", patientId));

        // Only FINAL / AMENDED results returned; PRELIMINARY hidden
        ClinicalResultEntity prelim = new ClinicalResultEntity();
        prelim.setStatus(ResultStatus.PRELIMINARY);
        prelim.setResultCode("2823-3");
        prelim.setResultDisplay("Potassium");
        ClinicalResultEntity fin = new ClinicalResultEntity();
        fin.setStatus(ResultStatus.FINAL);
        fin.setResultCode("2345-7");
        fin.setResultDisplay("Glucose");
        fin.setNumericValue(new BigDecimal("5.1"));
        fin.setAbnormalFlag(AbnormalFlag.NORMAL);
        fin.setVerifiedAt(Instant.now());
        when(ordersResultsService.listPatientResults(facId, patientId)).thenReturn(List.of(prelim, fin));

        List<PortalReleasedResultView> released = service.getReleasedResults(facId, "sub-patient-1", patientId);
        assertEquals(1, released.size());
        assertEquals("2345-7", released.get(0).resultCode());

        // Scoped proxy grant succeeds for patient
        PatientProxyGrantEntity grant = service.grantProxyAccess(facId, "sub-patient-1", false,
                new GrantProxyRequest(patientId, "sub-parent", "MOTHER",
                        Set.of(PortalScope.RESULTS), Instant.now().plus(30, ChronoUnit.DAYS)));
        assertFalse(grant.isRevoked());
        when(proxyRepo.findByFacilityIdAndPatientIdAndProxySubjectAndRevokedFalse(
                facId, patientId, "sub-parent")).thenReturn(List.of(grant));
        assertEquals(1, service.getReleasedResults(facId, "sub-parent", patientId).size());
        assertThrows(AccessDeniedException.class,
                () -> service.getPatientAppointments(facId, "sub-parent", patientId));
        assertThrows(AccessDeniedException.class,
                () -> service.getReleasedResults(UUID.randomUUID(), "sub-parent", patientId));

    }

    @Test
    void rejectsFinalResultWithoutVerificationTimestampAndToleratesMissingAbnormalFlag() {
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        PatientPortalAccountEntity account = service.enrollAccount(facilityId,
                new EnrollPortalAccountRequest(patientId, "patient-subject", "en", null, null));
        when(accountRepo.findByFacilityIdAndIdentitySubjectAndActiveTrue(facilityId, "patient-subject"))
                .thenReturn(Optional.of(account));

        ClinicalResultEntity unverifiedFinal = new ClinicalResultEntity();
        unverifiedFinal.setStatus(ResultStatus.FINAL);
        unverifiedFinal.setResultCode("2823-3");

        ClinicalResultEntity verifiedFinal = new ClinicalResultEntity();
        verifiedFinal.setStatus(ResultStatus.FINAL);
        verifiedFinal.setResultCode("2345-7");
        verifiedFinal.setResultDisplay("Glucose");
        verifiedFinal.setVerifiedAt(Instant.now());
        when(ordersResultsService.listPatientResults(facilityId, patientId))
                .thenReturn(List.of(unverifiedFinal, verifiedFinal));

        List<PortalReleasedResultView> results =
                service.getReleasedResults(facilityId, "patient-subject", patientId);
        assertEquals(1, results.size());
        assertEquals("2345-7", results.get(0).resultCode());
        // The real entity defaults abnormalFlag to NORMAL; do not assert a fake
        // missing value from a fixture that never actually omitted the field.
        assertEquals("NORMAL", results.get(0).abnormalFlag());

        // Defensive view mapping must still tolerate legacy/partial result inputs
        // without an abnormal flag rather than returning an HTTP 500.
        verifiedFinal.setAbnormalFlag(null);
        List<PortalReleasedResultView> fallback =
                service.getReleasedResults(facilityId, "patient-subject", patientId);
        assertEquals(1, fallback.size());
        assertEquals("UNKNOWN", fallback.get(0).abnormalFlag());
    }

    @Test
    void missingCallerSubjectCannotGrantProxyOrReadPatientRecords() {
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        GrantProxyRequest grant = new GrantProxyRequest(
                patientId, "proxy-subject", "GUARDIAN", Set.of(PortalScope.RESULTS),
                Instant.now().plus(1, ChronoUnit.DAYS));
        assertThrows(AccessDeniedException.class,
                () -> service.grantProxyAccess(facilityId, null, false, grant));
        assertThrows(AccessDeniedException.class,
                () -> service.getReleasedResults(facilityId, null, patientId));
        verifyNoInteractions(ordersResultsService);
    }
}
