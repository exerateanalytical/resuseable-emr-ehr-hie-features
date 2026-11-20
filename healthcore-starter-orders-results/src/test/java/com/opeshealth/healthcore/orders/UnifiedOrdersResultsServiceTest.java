package com.opeshealth.healthcore.orders;

import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UnifiedOrdersResultsServiceTest {

    private OrderSetRepository orderSetRepo;
    private UnifiedOrderRepository orderRepo;
    private OrderSpecimenRepository specimenRepo;
    private ClinicalResultRepository resultRepo;
    private ReflexOrderRuleRepository reflexRuleRepo;
    private UnifiedOrdersResultsService service;

    @BeforeEach
    void setUp() {
        orderSetRepo = mock(OrderSetRepository.class);
        orderRepo = mock(UnifiedOrderRepository.class);
        specimenRepo = mock(OrderSpecimenRepository.class);
        resultRepo = mock(ClinicalResultRepository.class);
        reflexRuleRepo = mock(ReflexOrderRuleRepository.class);
        when(orderSetRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(orderRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(specimenRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(resultRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reflexRuleRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new UnifiedOrdersResultsService(orderSetRepo, orderRepo, specimenRepo, resultRepo, reflexRuleRepo,
                mock(ClinicalAuditService.class));
    }

    @Test
    void verifiesCriticalResultTriggersReflexOrderAndClosedLoopAck() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        UnifiedClinicalOrderEntity order = service.placeOrder(facId, new PlaceOrderRequest(
                patientId, null, OrderDomain.LABORATORY, "http://loinc.org", "2823-3", "Serum Potassium",
                OrderPriority.STAT, "Rule out hyperkalemia"), "dr.1");
        when(orderRepo.findByIdAndFacilityId(order.getId(), facId)).thenReturn(Optional.of(order));

        ClinicalResultEntity prelim = service.enterResult(facId, new EnterResultRequest(
                order.getId(), "2823-3", "Serum Potassium", new BigDecimal("6.8"), null, "mmol/L",
                "3.5-5.1", AbnormalFlag.CRITICAL_HIGH), "tech.1");
        assertEquals(ResultStatus.PRELIMINARY, prelim.getStatus());
        when(resultRepo.findByIdAndFacilityId(prelim.getId(), facId)).thenReturn(Optional.of(prelim));

        ReflexOrderRuleEntity rule = service.createReflexRule(facId, new CreateReflexRuleRequest(
                "2823-3", AbnormalFlag.CRITICAL_HIGH, OrderDomain.PROCEDURE, "93000", "STAT 12-Lead ECG", OrderPriority.STAT));
        when(reflexRuleRepo.findByFacilityIdAndTriggerResultCodeAndTriggerFlagAndActiveTrue(
                facId, "2823-3", AbnormalFlag.CRITICAL_HIGH)).thenReturn(List.of(rule));

        VerificationOutcome outcome = service.verifyResult(facId, prelim.getId(), "director.1");
        assertEquals(ResultStatus.FINAL, outcome.verifiedResult().getStatus());
        assertEquals(1, outcome.triggeredReflexOrders().size());
        assertEquals("93000", outcome.triggeredReflexOrders().get(0).getOrderCode());

        ClinicalResultEntity acked = service.acknowledgeCriticalResult(facId, prelim.getId(), "dr.attending",
                "Verbal read-back completed and calcium gluconate ordered");
        assertTrue(acked.isCriticalAcknowledged());
        assertNotNull(service.toFhirServiceRequest(order));
        assertNotNull(service.toFhirDiagnosticReport(acked));
    }
}
