/*
 * =============================================================================
 * Opes HealthCore SDK — Departmental Queue Management Unit & Concurrency Tests
 * =============================================================================
 */
package com.opeshealth.healthcore.queue;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.queue.domain.QueueManagementEntities.*;
import com.opeshealth.healthcore.queue.service.QueueManagementService;
import com.opeshealth.healthcore.queue.service.QueueManagementService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueueManagementServiceTest {

    private final Map<String, QueueCounterEntity> counters = new ConcurrentHashMap<>();
    private final Map<UUID, DepartmentQueueEntryEntity> entries = new ConcurrentHashMap<>();
    private final Map<String, QueuePauseControlEntity> pauseMap = new ConcurrentHashMap<>();
    private QueueManagementService service;

    @BeforeEach
    void setUp() {
        QueueCounterRepository counterRepo = mock(QueueCounterRepository.class);
        DepartmentQueueEntryRepository entryRepo = mock(DepartmentQueueEntryRepository.class);
        QueuePauseControlRepository pauseRepo = mock(QueuePauseControlRepository.class);
        OutboxRelayService.OutboxRepository outboxRepo = mock(OutboxRelayService.OutboxRepository.class);

        when(counterRepo.findForUpdate(any(), anyString(), any(LocalDate.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1) + "|" + inv.getArgument(2);
            return Optional.ofNullable(counters.get(key));
        });
        when(counterRepo.save(any(QueueCounterEntity.class))).thenAnswer(inv -> {
            QueueCounterEntity c = inv.getArgument(0);
            counters.put(c.getFacilityId() + "|" + c.getDepartmentCode() + "|" + c.getServiceDate(), c);
            return c;
        });

        when(entryRepo.findByFacilityIdAndIdempotencyKey(any(), anyString())).thenAnswer(inv -> {
            UUID fac = inv.getArgument(0);
            String key = inv.getArgument(1);
            return entries.values().stream()
                    .filter(e -> fac.equals(e.getFacilityId()) && key.equals(e.getIdempotencyKey()))
                    .findFirst();
        });
        when(entryRepo.save(any(DepartmentQueueEntryEntity.class))).thenAnswer(inv -> {
            DepartmentQueueEntryEntity e = inv.getArgument(0);
            entries.put(e.getId(), e);
            return e;
        });
        when(entryRepo.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(entries.get(inv.getArgument(0))));
        when(entryRepo.findByFacilityIdAndDepartmentCodeOrderByUrgencyScoreDescCheckInAtAsc(any(), anyString()))
                .thenAnswer(inv -> {
                    UUID fac = inv.getArgument(0);
                    String dept = inv.getArgument(1);
                    return entries.values().stream()
                            .filter(e -> fac.equals(e.getFacilityId()) && dept.equals(e.getDepartmentCode()))
                            .sorted(Comparator.comparingInt(DepartmentQueueEntryEntity::getUrgencyScore).reversed()
                                    .thenComparing(DepartmentQueueEntryEntity::getCheckInAt))
                            .toList();
                });

        when(pauseRepo.findByFacilityIdAndDepartmentCode(any(), anyString())).thenAnswer(inv ->
                Optional.ofNullable(pauseMap.get(inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(pauseRepo.save(any(QueuePauseControlEntity.class))).thenAnswer(inv -> {
            QueuePauseControlEntity c = inv.getArgument(0);
            pauseMap.put(c.getFacilityId() + "|" + c.getDepartmentCode(), c);
            return c;
        });

        service = new QueueManagementService(counterRepo, entryRepo, pauseRepo, outboxRepo, null, List.of());
    }

    @Test
    void allocatesStrictlyUniqueTicketsUnderConcurrentCheckInsAndPreventsDoubleClaim() throws Exception {
        UUID facId = UUID.randomUUID();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<DepartmentQueueEntryEntity>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                startGate.await();
                return service.enqueue(facId, new EnqueueRequest(
                        "DOCTOR_CONSULTATION", UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                        QueuePriority.ROUTINE, "PAID", null, 60, "CONC-" + idx), "reception");
            }));
        }
        startGate.countDown();

        Set<String> tickets = new HashSet<>();
        DepartmentQueueEntryEntity firstEntry = null;
        for (Future<DepartmentQueueEntryEntity> f : futures) {
            DepartmentQueueEntryEntity e = f.get(5, TimeUnit.SECONDS);
            tickets.add(e.getTicketNumber());
            if (firstEntry == null) firstEntry = e;
        }
        assertEquals(threads, tickets.size(), "Concurrent check-ins must allocate strictly unique tickets");

        // Atomic claiming: two clinicians attempt to claim the same task concurrently
        final UUID targetId = firstEntry.getId();
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        CountDownLatch claimGate = new CountDownLatch(1);
        Future<?> c1 = pool.submit(() -> {
            try {
                claimGate.await();
                service.claimTask(facId, targetId, "dr.alpha");
                wins.incrementAndGet();
            } catch (Exception ex) {
                rejections.incrementAndGet();
            }
        });
        Future<?> c2 = pool.submit(() -> {
            try {
                claimGate.await();
                service.claimTask(facId, targetId, "dr.beta");
                wins.incrementAndGet();
            } catch (Exception ex) {
                rejections.incrementAndGet();
            }
        });
        claimGate.countDown();
        c1.get(5, TimeUnit.SECONDS);
        c2.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, wins.get(), "Exactly one worker must win atomic claim");
        assertEquals(1, rejections.get(), "Conflicting concurrent claim must be rejected");
    }

    @Test
    void separatesFinancialHoldFromQueueStatusAndEnforcesIdempotentReplay() {
        UUID facId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        EnqueueRequest req = new EnqueueRequest(
                "LABORATORY", UUID.randomUUID(), UUID.randomUUID(), orderId, null, null,
                QueuePriority.ROUTINE, "PAYMENT_PENDING", null, 90, "ORD-" + orderId);

        DepartmentQueueEntryEntity first = service.enqueue(facId, req, "cpoe");
        DepartmentQueueEntryEntity replay = service.enqueue(facId, req, "cpoe");

        assertEquals(first.getId(), replay.getId(), "Event replay must never create duplicate queue entries");
        // Reusing the key for a different patient's order must not leak or rebind it.
        EnqueueRequest conflictingPatient = new EnqueueRequest(
                "LABORATORY", UUID.randomUUID(), req.encounterId(), orderId, null, null,
                QueuePriority.ROUTINE, "PAYMENT_PENDING", null, 90, "ORD-" + orderId);
        assertThrows(SecurityException.class,
                () -> service.enqueue(facId, conflictingPatient, "malicious-caller"));
        EnqueueRequest conflictingOrder = new EnqueueRequest(
                "LABORATORY", req.patientId(), req.encounterId(), UUID.randomUUID(), null, null,
                QueuePriority.ROUTINE, "PAYMENT_PENDING", null, 90, "ORD-" + orderId);
        assertThrows(SecurityException.class,
                () -> service.enqueue(facId, conflictingOrder, "malicious-caller"));
        assertEquals(1, entries.size());
        assertEquals(QueueStatus.WAITING, first.getQueueStatus());
        assertFalse(first.isEligibleForActivation());
        assertThrows(IllegalStateException.class, () -> service.claimTask(facId, first.getId(), "lab.tech"));

        DepartmentQueueEntryEntity cleared = service.updateFinancialClearance(facId, first.getId(), "PAID");
        assertTrue(cleared.isEligibleForActivation());
        assertEquals(QueueStatus.READY, cleared.getQueueStatus());

        DepartmentQueueEntryEntity claimed = service.claimTask(facId, first.getId(), "lab.tech");
        assertEquals(QueueStatus.IN_SERVICE, claimed.getQueueStatus());
        assertThrows(SecurityException.class, () -> service.claimTask(UUID.randomUUID(), first.getId(), "intruder"));
    }
    @Test
    void completionIsBoundToFinancialClearanceAndClaimedWorker() {
        UUID facility = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        DepartmentQueueEntryEntity entry = service.enqueue(facility,
                new EnqueueRequest("LAB_COLLECTION", UUID.randomUUID(), UUID.randomUUID(),
                        order, null, null, QueuePriority.ROUTINE,
                        "PAYMENT_PENDING", null, 60, "ORDER-" + order), "router");
        assertThrows(IllegalStateException.class,
                () -> service.completeEntry(facility, entry.getId(), "lab-tech"));
        assertThrows(IllegalArgumentException.class,
                () -> service.transitionOperationalStatus(facility, entry.getId(), QueueStatus.COMPLETED, "bypass"));

        service.updateFinancialClearance(facility, entry.getId(), "PAID");
        assertThrows(SecurityException.class,
                () -> service.completeEntry(facility, entry.getId(), "lab-tech"));
        service.claimTask(facility, entry.getId(), "lab-tech");
        assertThrows(SecurityException.class,
                () -> service.completeEntry(facility, entry.getId(), "other-tech"));

        DepartmentQueueEntryEntity completed = service.completeEntry(facility, entry.getId(), "lab-tech");
        assertEquals(QueueStatus.COMPLETED, completed.getQueueStatus());
        java.time.Instant originalCompletedAt = completed.getCompletedAt();
        assertNotNull(originalCompletedAt);
        assertSame(completed, service.completeEntry(facility, entry.getId(), "lab-tech"));
        assertEquals(originalCompletedAt, completed.getCompletedAt());
        assertThrows(SecurityException.class,
                () -> service.completeEntry(facility, entry.getId(), "other-tech"));
        assertThrows(IllegalStateException.class,
                () -> service.updateFinancialClearance(facility, entry.getId(), "PAID"));
    }

}
