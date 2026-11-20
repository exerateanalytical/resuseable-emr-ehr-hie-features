/*
 * =============================================================================
 * Opes HealthCore SDK — Public Health IDSR Surveillance & Cascade Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.*;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService;
import com.opeshealth.healthcore.publichealth.service.PublicHealthService.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PublicHealthService} covering WHO IDSR epidemic threshold
 * escalation, CHW MUAC severe malnutrition emergency referral QR generation, EPI
 * immunization scheduling, HIV ART viral-load suppression cascade, and DHIS2 ADX XML export.
 */
class PublicHealthSurveillanceTest {

    private ChwHouseholdRepository householdRepo;
    private ChwVisitRepository visitRepo;
    private HomeCarePlanRepository homeCareRepo;
    private ImmunizationRepository immunizationRepo;
    private SurveillanceCaseRepository surveillanceRepo;
    private ContactTracingRepository contactRepo;
    private CaseInvestigationRepository cifRepo;
    private ChronicCascadeRepository cascadeRepo;
    private OutboxRelayService.OutboxRepository outboxRepo;
    private PublicHealthService service;

    @BeforeEach
    void setUp() {
        householdRepo = mock(ChwHouseholdRepository.class);
        visitRepo = mock(ChwVisitRepository.class);
        homeCareRepo = mock(HomeCarePlanRepository.class);
        immunizationRepo = mock(ImmunizationRepository.class);
        surveillanceRepo = mock(SurveillanceCaseRepository.class);
        contactRepo = mock(ContactTracingRepository.class);
        cifRepo = mock(CaseInvestigationRepository.class);
        cascadeRepo = mock(ChronicCascadeRepository.class);
        outboxRepo = mock(OutboxRelayService.OutboxRepository.class);
        service = new PublicHealthService(
                householdRepo, visitRepo, homeCareRepo, immunizationRepo,
                surveillanceRepo, contactRepo, cifRepo, cascadeRepo, outboxRepo);
    }

    @Test
    void choleraSingleCaseTriggersImmediateOutbreakOutboxEvent() {
        SurveillanceCaseEntity sc = new SurveillanceCaseEntity();
        sc.setFacilityId(UUID.randomUUID());
        sc.setDistrictCode("CM-LT-DLA");
        sc.setDiseaseIcd10("A00");
        sc.setDiseaseName("Cholera");
        sc.setCaseClassification("CONFIRMED");

        when(surveillanceRepo.save(any(SurveillanceCaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(surveillanceRepo.countByDistrictCodeAndDiseaseIcd10AndReportedAtAfter(
                eq("CM-LT-DLA"), eq("A00"), any(Instant.class))).thenReturn(1L);

        Map<String, Object> result = service.reportSurveillanceCase(sc);

        assertEquals(Boolean.TRUE, result.get("outbreakThresholdExceeded"));
        assertEquals(1L, result.get("weeklyCount"));
        verify(outboxRepo).save(any(TransactionalOutboxEntity.class));
    }

    @Test
    void malariaBelowWeeklyThresholdDoesNotTriggerOutbreakAlert() {
        SurveillanceCaseEntity sc = new SurveillanceCaseEntity();
        sc.setFacilityId(UUID.randomUUID());
        sc.setDistrictCode("CM-LT-DLA");
        sc.setDiseaseIcd10("B50.9");
        sc.setDiseaseName("Malaria");

        when(surveillanceRepo.save(any(SurveillanceCaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(surveillanceRepo.countByDistrictCodeAndDiseaseIcd10AndReportedAtAfter(
                eq("CM-LT-DLA"), eq("B50.9"), any(Instant.class))).thenReturn(2L);

        Map<String, Object> result = service.reportSurveillanceCase(sc);

        assertEquals(Boolean.FALSE, result.get("outbreakThresholdExceeded"));
        verify(outboxRepo, never()).save(any());
    }

    @Test
    void chwVisitFlagsSevereAcuteMalnutritionAndGeneratesReferralQr() {
        when(visitRepo.save(any(ChwVisitEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ChwVisitEntity visit = new ChwVisitEntity();
        visit.setHouseholdId(UUID.randomUUID());
        visit.setChwId("chw-douala-04");
        visit.setProgramType("MUAC");
        visit.setMuacMm(110); // < 115mm SAM threshold

        ChwVisitEntity saved = service.recordChwVisit(visit);

        assertTrue(saved.isDangerSignsPresent());
        assertNotNull(saved.getReferralQrToken());
        assertTrue(saved.getReferralQrToken().startsWith("OPES-REF-"));
    }

    @Test
    void immunizationComputesNextDueDateForEarlySeriesDose() {
        when(immunizationRepo.save(any(ImmunizationRecordEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ImmunizationRecordEntity rec = new ImmunizationRecordEntity();
        rec.setFacilityId(UUID.randomUUID());
        rec.setPatientId(UUID.randomUUID());
        rec.setCvxCode("10");
        rec.setVaccineName("IPV Polio");
        rec.setDoseNumber(1);

        ImmunizationRecordEntity saved = service.recordImmunization(rec);

        assertEquals(LocalDate.now().plusWeeks(4), saved.getNextDueDate());
    }

    @Test
    void hivArtCascadeMarksSuppressedWhenViralLoadBelow1000() {
        when(cascadeRepo.save(any(ChronicCascadeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        ChronicCascadeEntity cascade = new ChronicCascadeEntity();
        cascade.setFacilityId(UUID.randomUUID());
        cascade.setPatientId(UUID.randomUUID());
        cascade.setProgramCode("HIV_ART");
        cascade.setViralLoadCopiesMl(40);
        cascade.setOutcomeStatus("ACTIVE");

        ChronicCascadeEntity saved = service.upsertChronicCascade(cascade);

        assertEquals("SUPPRESSED", saved.getOutcomeStatus());
    }

    @Test
    void exportDhis2AdxXmlAggregatesDistrictDiseaseCounts() {
        SurveillanceCaseEntity c1 = new SurveillanceCaseEntity();
        c1.setDistrictCode("CM-LT-DLA");
        c1.setDiseaseIcd10("B50.9");
        SurveillanceCaseEntity c2 = new SurveillanceCaseEntity();
        c2.setDistrictCode("CM-LT-DLA");
        c2.setDiseaseIcd10("B50.9");
        SurveillanceCaseEntity c3 = new SurveillanceCaseEntity();
        c3.setDistrictCode("CM-LT-DLA");
        c3.setDiseaseIcd10("A00");

        when(surveillanceRepo.findByDistrictCode("CM-LT-DLA")).thenReturn(List.of(c1, c2, c3));

        String xml = service.exportDhis2AdxXml("CM-LT-DLA", "2026W46");

        assertTrue(xml.contains("xmlns=\"urn:ihe:qrph:adx:2015\""));
        assertTrue(xml.contains("orgUnit=\"CM-LT-DLA\""));
        assertTrue(xml.contains("period=\"2026W46\""));
        assertTrue(xml.contains("dataElement=\"IDSR_B50.9\" value=\"2\""));
        assertTrue(xml.contains("dataElement=\"IDSR_A00\" value=\"1\""));
    }
}
