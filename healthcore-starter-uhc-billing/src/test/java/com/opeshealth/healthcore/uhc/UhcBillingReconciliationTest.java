/*
 * =============================================================================
 * Opes HealthCore SDK — Double-Entry Billing & Reconciliation Tests (FIN-001)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.uhc;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.OutboxRepository;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.LedgerEntryEntity;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities.PatientAccountEntity;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UhcBillingReconciliationTest {

    private PatientAccountRepository accountRepo;
    private LedgerEntryRepository ledgerRepo;
    private UhcBillingService service;

    @BeforeEach
    void setUp() {
        accountRepo = mock(PatientAccountRepository.class);
        ledgerRepo = mock(LedgerEntryRepository.class);
        service = new UhcBillingService(
                accountRepo,
                ledgerRepo,
                mock(CbhiPolicyRepository.class),
                mock(InsuranceClaimRepository.class),
                mock(PaymentTransactionRepository.class),
                mock(UhcVoucherRepository.class),
                mock(OutboxRepository.class),
                List.of(new MtnMomoAdapter(), new OrangeMoneyAdapter()));
    }

    @Test
    void rejectsUnbalancedDoubleEntryJournalAndMixedCurrencies() {
        UUID acctId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                service.postBalancedJournal(List.of(
                        new JournalPostingLine(acctId, UhcBillingService.GL_PATIENT_AR, "DEBIT", 5000, "XAF", "INV-1", "Consult"),
                        new JournalPostingLine(acctId, UhcBillingService.GL_CLINICAL_REVENUE, "CREDIT", 4500, "XAF", "INV-1", "Short credit")
                )));

        assertThrows(IllegalArgumentException.class, () ->
                service.postBalancedJournal(List.of(
                        new JournalPostingLine(acctId, UhcBillingService.GL_PATIENT_AR, "DEBIT", 5000, "XAF", "INV-2", "Consult"),
                        new JournalPostingLine(acctId, UhcBillingService.GL_CLINICAL_REVENUE, "CREDIT", 5000, "USD", "INV-2", "Wrong currency")
                )));
    }

    @Test
    void postsBalancedChargeReversalAndGeneratesReconciliationReport() {
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        List<LedgerEntryEntity> savedEntries = new ArrayList<>();
        Map<UUID, PatientAccountEntity> accounts = new HashMap<>();

        when(accountRepo.findByFacilityIdAndPatientId(facilityId, patientId))
                .thenAnswer(inv -> accounts.values().stream().findFirst());
        when(accountRepo.findById(any())).thenAnswer(inv -> Optional.ofNullable(accounts.get(inv.getArgument(0))));
        when(accountRepo.save(any())).thenAnswer(inv -> {
            PatientAccountEntity a = inv.getArgument(0);
            accounts.put(a.getId(), a);
            return a;
        });
        when(ledgerRepo.save(any())).thenAnswer(inv -> {
            LedgerEntryEntity e = inv.getArgument(0);
            savedEntries.add(e);
            return e;
        });
        when(ledgerRepo.findByJournalBatchId(any())).thenAnswer(inv -> {
            UUID batchId = inv.getArgument(0);
            return savedEntries.stream().filter(e -> batchId.equals(e.getJournalBatchId())).toList();
        });
        when(ledgerRepo.findByAccountIdOrderByPostedAtAsc(any())).thenAnswer(inv -> savedEntries);

        LedgerEntryEntity charge = service.postCharge(facilityId, patientId, 15000L, "XAF", "INV-2026-01", "Lab panel");
        assertEquals(2, savedEntries.size());

        List<LedgerEntryEntity> reversals = service.reverseJournalBatch(charge.getJournalBatchId(), "Duplicate invoice");
        assertEquals(2, reversals.size());

        AccountReconciliationReport report = service.generateAccountReconciliationReport(facilityId, patientId);
        assertTrue(report.doubleEntryBalanced());
        assertEquals(30000L, report.totalDebitsMinor());
        assertEquals(30000L, report.totalCreditsMinor());
        assertEquals(0L, report.accountBalanceMinor());
        assertEquals(4, report.entryCount());
    }
}
