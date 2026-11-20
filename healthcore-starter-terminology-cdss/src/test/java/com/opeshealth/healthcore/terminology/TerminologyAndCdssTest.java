package com.opeshealth.healthcore.terminology;

import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine.*;
import com.opeshealth.healthcore.terminology.domain.TerminologyEntities.DdiRuleEntity;
import com.opeshealth.healthcore.terminology.service.TerminologyService;
import com.opeshealth.healthcore.terminology.service.TerminologyService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TerminologyAndCdssTest {

    private TerminologyService terminologyService;
    private DdiRuleRepository ddiRepo;
    private CdsHooksEngine cdsEngine;

    @BeforeEach
    void setUp() {
        CodeConceptRepository conceptRepo = mock(CodeConceptRepository.class);
        ConceptClosureRepository closureRepo = mock(ConceptClosureRepository.class);
        ConceptMapRepository mapRepo = mock(ConceptMapRepository.class);
        ddiRepo = mock(DdiRuleRepository.class);
        terminologyService = new TerminologyService(conceptRepo, closureRepo, mapRepo, ddiRepo);
        cdsEngine = new CdsHooksEngine(terminologyService, ddiRepo);
    }

    @Test
    void validatesLoincSnomedIcd10AndUcumConversions() {
        assertEquals("http://loinc.org", terminologyService.normalizeSystemUri("LOINC"));
        assertTrue(terminologyService.isValidLoincSyntax("2823-3"));
        assertFalse(terminologyService.isValidLoincSyntax("2823-9"));
        assertTrue(terminologyService.isValidSnomedSyntax("44054006"));
        assertTrue(terminologyService.isValidIcd10Syntax("J18.9"));
        assertFalse(terminologyService.isValidIcd10Syntax("999.9"));

        Map<String, Object> conv = terminologyService.convertUcum(BigDecimal.valueOf(1000), "mg", "g");
        assertEquals(1.0, ((Number) conv.get("convertedValue")).doubleValue(), 0.001);
    }

    @Test
    void detectsCriticalDrugInteractionAndLabPanicAlerts() {
        DdiRuleEntity rule = new DdiRuleEntity();
        rule.setDrugACode("WARFARIN");
        rule.setDrugBOrConditionCode("ASPIRIN");
        rule.setSeverity("CRITICAL");
        rule.setClinicalMessage("Major bleeding risk; avoid concurrent therapy.");
        when(ddiRepo.findByDrugACodeIn(any())).thenReturn(List.of(rule));

        List<CdsCard> cards = cdsEngine.evaluateOrderSafety(new OrderSafetyRequest(
                UUID.randomUUID(), new BigDecimal("65.0"), new BigDecimal("85.0"), false,
                List.of("ASPIRIN"), List.of(), List.of(), "WARFARIN", new BigDecimal("5.0")));
        assertFalse(cards.isEmpty());
        assertEquals("critical", cards.get(0).indicator());

        List<CdsCard> panicCards = cdsEngine.evaluateVitalsAndLabPanic(new VitalsPanicRequest(
                new BigDecimal("39.8"), 128, 28, 82, 88, new BigDecimal("6.9"), new BigDecimal("42.0")));
        assertFalse(panicCards.isEmpty());
    }
}
