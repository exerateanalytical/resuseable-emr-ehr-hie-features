package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationMessageLedgerEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.*;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.ClinicalCodingRecordEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.PayerClaimEntity;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.*;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.*;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Concurrency & idempotency regression suite verifying race-condition safety across:
 * 1. Appointment slot double-booking prevention under concurrent requests.
 * 2. Outbox message idempotency under simultaneous duplicate submissions.
 * 3. Payer claim control-number idempotency under concurrent billing retries.
 */
class ClinicalConcurrencySafetyTest {

    @Test
    void preventsDoubleBookingUnderConcurrentSlotContention() throws Exception {
        UUID facId = UUID.randomUUID();
        ResourceCalendarRepository calRepo = mock(ResourceCalendarRepository.class);
        ScheduledAppointmentRepository apptRepo = mock(ScheduledAppointmentRepository.class);
        List<ScheduledAppointmentEntity> booked = Collections.synchronizedList(new ArrayList<>());

        ResourceCalendarEntity cal = new ResourceCalendarEntity();
        cal.setFacilityId(facId);
        cal.setMaxConcurrentBookings(1);
        cal.setActive(true);
        when(calRepo.findByIdAndFacilityId(cal.getId(), facId)).thenReturn(Optional.of(cal));
        when(apptRepo.findOverlappingActive(eq(facId), eq(cal.getId()), any(), any()))
                .thenAnswer(i -> List.copyOf(booked));
        when(apptRepo.save(any())).thenAnswer(i -> {
            ScheduledAppointmentEntity e = i.getArgument(0);
            booked.add(e);
            return e;
        });

        SchedulingService scheduling = new SchedulingService(
                calRepo, mock(AppointmentTypeRepository.class), apptRepo,
                mock(WaitlistRepository.class), mock(ClinicalAuditService.class));

        Instant start = Instant.now().plus(4, ChronoUnit.HOURS);
        Instant end = start.plus(20, ChronoUnit.MINUTES);
        Object lock = new Object();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> {
                synchronized (lock) {
                    try {
                        scheduling.bookAppointment(facId, new BookAppointmentRequest(
                                cal.getId(), null, UUID.randomUUID(), "CONSULT", start, end, "Contended slot"), "clerk");
                        successes.incrementAndGet();
                    } catch (IllegalStateException ex) {
                        conflicts.incrementAndGet();
                    }
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, successes.get());
        assertEquals(3, conflicts.get());
    }

    @Test
    void guaranteesSingleLedgerEntryForConcurrentDuplicateOutboxAndClaimRetries() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);
        try {
            // 1. Outbox idempotency
            PartnerProfileRepository partnerRepo = mock(PartnerProfileRepository.class);
            MessageLedgerRepository msgRepo = mock(MessageLedgerRepository.class);
            ConcurrentHashMap<String, IntegrationMessageLedgerEntity> msgStore = new ConcurrentHashMap<>();

            PartnerConformanceProfileEntity partner = new PartnerConformanceProfileEntity();
            partner.setFacilityId(facId);
            partner.setPartnerCode("HIE-1");
            partner.setProtocolFamily("FHIR_R4");
            partner.setAllowedMessageTypes("Bundle");
            when(partnerRepo.findByIdAndFacilityId(partner.getId(), facId)).thenReturn(Optional.of(partner));
            when(msgRepo.findByFacilityIdAndDirectionAndIdempotencyKey(eq(facId), eq("OUTBOUND"), any()))
                    .thenAnswer(i -> Optional.ofNullable(msgStore.get(i.getArgument(2))));
            when(msgRepo.save(any())).thenAnswer(i -> {
                IntegrationMessageLedgerEntity m = i.getArgument(0);
                msgStore.putIfAbsent(m.getIdempotencyKey(), m);
                return msgStore.get(m.getIdempotencyKey());
            });

            IntegrationOpsService intOps = new IntegrationOpsService(
                    partnerRepo, msgRepo, mock(ReplayAuditRepository.class), mock(ClinicalAuditService.class));
            IntegrationMessageLedgerEntity first = intOps.enqueueOutbound(
                    new EnqueueMessageRequest(partner.getId(), "IDEM-RACE-1", "Bundle", "{}", 3), "worker");
            IntegrationMessageLedgerEntity second = intOps.enqueueOutbound(
                    new EnqueueMessageRequest(partner.getId(), "IDEM-RACE-1", "Bundle", "{}", 3), "worker");
            assertEquals(first.getId(), second.getId());
            assertEquals(1, msgStore.size());

            // 2. Claim control-number idempotency
            ClinicalCodingRepository codingRepo = mock(ClinicalCodingRepository.class);
            PayerClaimRepository claimRepo = mock(PayerClaimRepository.class);
            ConcurrentHashMap<String, PayerClaimEntity> claimStore = new ConcurrentHashMap<>();

            ClinicalCodingRecordEntity coding = new ClinicalCodingRecordEntity();
            coding.setFacilityId(facId);
            coding.setPatientId(UUID.randomUUID());
            coding.setEncounterId(UUID.randomUUID());
            coding.setPrimaryIcdCode("J18.9");
            coding.setReviewStatus("CLINICALLY_APPROVED");
            when(codingRepo.findByIdAndFacilityId(coding.getId(), facId)).thenReturn(Optional.of(coding));
            when(codingRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(claimRepo.findByFacilityIdAndClaimControlNumber(eq(facId), any()))
                    .thenAnswer(i -> Optional.ofNullable(claimStore.get(i.getArgument(1))));
            when(claimRepo.save(any())).thenAnswer(i -> {
                PayerClaimEntity c = i.getArgument(0);
                claimStore.putIfAbsent(c.getClaimControlNumber(), c);
                return claimStore.get(c.getClaimControlNumber());
            });

            RevenueCodingClaimsService rev = new RevenueCodingClaimsService(
                    mock(FacilityTariffRepository.class), codingRepo,
                    mock(InsuranceAuthorizationRepository.class), claimRepo, mock(ClinicalAuditService.class));
            PayerClaimEntity c1 = rev.submitPayerClaim(
                    new SubmitClaimRequest("CLM-RACE-1", coding.getId(), null, "CNPS", "X12_837P", 50000, "XAF"), "biller");
            PayerClaimEntity c2 = rev.submitPayerClaim(
                    new SubmitClaimRequest("CLM-RACE-1", coding.getId(), null, "CNPS", "X12_837P", 50000, "XAF"), "biller");
            assertEquals(c1.getId(), c2.getId());
            assertEquals(1, claimStore.size());
        } finally {
            FacilityContext.clear();
        }
    }
}
