/*
 * =============================================================================
 * Opes HealthCore SDK — UHC/CBHI & Billing Auto-Configuration
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.uhc.autoconfigure;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.uhc.domain.UhcBillingEntities;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;

@AutoConfiguration
@EntityScan(basePackageClasses = UhcBillingEntities.class)
@EnableJpaRepositories(basePackageClasses = UhcBillingService.class, considerNestedRepositories = true)
public class UhcBillingAutoConfiguration {

    @Bean @ConditionalOnMissingBean public MtnMomoAdapter mtnMomoAdapter() { return new MtnMomoAdapter(); }
    @Bean @ConditionalOnMissingBean public OrangeMoneyAdapter orangeMoneyAdapter() { return new OrangeMoneyAdapter(); }

    @Bean
    @ConditionalOnMissingBean
    public UhcBillingService uhcBillingService(
            PatientAccountRepository accountRepo,
            LedgerEntryRepository ledgerRepo,
            CbhiPolicyRepository cbhiRepo,
            InsuranceClaimRepository claimRepo,
            PaymentTransactionRepository txRepo,
            UhcVoucherRepository voucherRepo,
            OutboxRelayService.OutboxRepository outboxRepo,
            List<PaymentGatewaySpi> adapters) {
        return new UhcBillingService(accountRepo, ledgerRepo, cbhiRepo, claimRepo, txRepo, voucherRepo, outboxRepo, adapters);
    }

    @Bean
    @ConditionalOnMissingBean
    public UhcBillingController uhcBillingController(
            UhcBillingService service,
            CbhiPolicyRepository cbhiRepo,
            UhcVoucherRepository voucherRepo) {
        return new UhcBillingController(service, cbhiRepo, voucherRepo);
    }
}
