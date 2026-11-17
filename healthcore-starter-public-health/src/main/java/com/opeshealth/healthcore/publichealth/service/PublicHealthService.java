/*
 * =============================================================================
 * Opes HealthCore SDK — CHW, Home Care, EPI & IDSR Outbreak Watch + DHIS2 ADX
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.publichealth.service;

import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.publichealth.domain.PublicHealthEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class PublicHealthService {

    public interface ChwHouseholdRepository extends JpaRepository<ChwHouseholdEntity, UUID> {}
    public interface ChwVisitRepository extends JpaRepository<ChwVisitEntity, UUID> {}
    public interface HomeCarePlanRepository extends JpaRepository<HomeCarePlanEntity, UUID> {}
    public interface ImmunizationRepository extends JpaRepository<ImmunizationRecordEntity, UUID> {}
    public interface SurveillanceCaseRepository extends JpaRepository<SurveillanceCaseEntity, UUID> {
        long countByDistrictCodeAndDiseaseIcd10AndReportedAtAfter(String district, String icd10, Instant since);
        List<SurveillanceCaseEntity> findByDistrictCode(String district);
    }
    public interface ContactTracingRepository extends JpaRepository<ContactTracingLinkEntity, UUID> {}

    /** Epidemic alert thresholds over a rolling 7-day district window (WHO IDSR). */
    private static final Map<String, Long> WEEKLY_OUTBREAK_THRESHOLDS = Map.of(
            "A00", 1L,   // Cholera: single confirmed case triggers immediate response
            "A98", 1L,   // Viral Hemorrhagic Fever / Ebola / Marburg: single case
            "B50.9", 5L, // Malaria district surge alert
            "B20", 10L,  // HIV sentinel notification
            "B18.1", 5L, // Hepatitis B cluster watch
            "A15", 3L    // Active pulmonary TB cluster
    );

    private final ChwHouseholdRepository householdRepo;
    private final ChwVisitRepository visitRepo;
    private final HomeCarePlanRepository homeCareRepo;
    private final ImmunizationRepository immunizationRepo;
    private final SurveillanceCaseRepository surveillanceRepo;
    private final ContactTracingRepository contactRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;

    public PublicHealthService(
            ChwHouseholdRepository householdRepo,
            ChwVisitRepository visitRepo,
            HomeCarePlanRepository homeCareRepo,
            ImmunizationRepository immunizationRepo,
            SurveillanceCaseRepository surveillanceRepo,
            ContactTracingRepository contactRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.householdRepo = householdRepo;
        this.visitRepo = visitRepo;
        this.homeCareRepo = homeCareRepo;
        this.immunizationRepo = immunizationRepo;
        this.surveillanceRepo = surveillanceRepo;
        this.contactRepo = contactRepo;
        this.outboxRepo = outboxRepo;
    }

    @Transactional
    public ChwVisitEntity recordChwVisit(ChwVisitEntity visit) {
        // Trigger severe acute malnutrition (MUAC < 115 mm) or danger-sign QR referral
        if ((visit.getMuacMm() != null && visit.getMuacMm() < 115) || visit.isDangerSignsPresent()) {
            visit.setDangerSignsPresent(true);
            visit.setReferralQrToken("OPES-REF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        }
        return visitRepo.save(visit);
    }

    @Transactional
    public ImmunizationRecordEntity recordImmunization(ImmunizationRecordEntity rec) {
        if (rec.getNextDueDate() == null && rec.getDoseNumber() < 3) {
            // Standard WHO EPI 4-week minimum interval for multi-dose series
            rec.setNextDueDate(LocalDate.now().plusWeeks(4));
        }
        return immunizationRepo.save(rec);
    }

    @Transactional
    @ClinicalAudit(action = "IDSR_REPORT_CASE", resourceType = "SurveillanceCase")
    public Map<String, Object> reportSurveillanceCase(SurveillanceCaseEntity sc) {
        SurveillanceCaseEntity saved = surveillanceRepo.save(sc);
        long weeklyCount = surveillanceRepo.countByDistrictCodeAndDiseaseIcd10AndReportedAtAfter(
                sc.getDistrictCode(), sc.getDiseaseIcd10(), Instant.now().minus(7, ChronoUnit.DAYS));

        long threshold = WEEKLY_OUTBREAK_THRESHOLDS.getOrDefault(sc.getDiseaseIcd10(), 5L);
        boolean outbreakTriggered = weeklyCount >= threshold;
        if (outbreakTriggered) {
            TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
            evt.setIdempotencyKey("SurveillanceCase:OutbreakThresholdExceeded:" + saved.getId() + ":" + evt.getId());
            evt.setAggregateType("SurveillanceCase");
            evt.setAggregateId(saved.getId());
            evt.setEventType("OutbreakThresholdExceededEvent");
            evt.setPayloadJson("{\"district\":\"" + sc.getDistrictCode() + "\",\"icd10\":\""
                    + sc.getDiseaseIcd10() + "\",\"weeklyCount\":" + weeklyCount + "}");
            outboxRepo.save(evt);
        }
        return Map.of("caseRecord", saved, "weeklyCount", weeklyCount, "outbreakThresholdExceeded", outbreakTriggered);
    }

    /** Generates DHIS2 ADX (Aggregate Data Exchange) XML payload for Ministry of Health reporting. */
    @Transactional(readOnly = true)
    public String exportDhis2AdxXml(String districtCode, String periodIsoWeek) {
        List<SurveillanceCaseEntity> cases = surveillanceRepo.findByDistrictCode(districtCode);
        Map<String, Long> byDisease = new LinkedHashMap<>();
        for (SurveillanceCaseEntity c : cases) {
            byDisease.merge(c.getDiseaseIcd10(), 1L, Long::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<adx xmlns=\"urn:ihe:qrph:adx:2015\" exported=\"").append(Instant.now()).append("\">\n");
        sb.append("  <group orgUnit=\"").append(districtCode).append("\" period=\"").append(periodIsoWeek).append("\">\n");
        byDisease.forEach((icd, count) ->
                sb.append("    <dataValue dataElement=\"IDSR_").append(icd).append("\" value=\"").append(count).append("\"/>\n"));
        sb.append("  </group>\n</adx>");
        return sb.toString();
    }

    @RestController
    @RequestMapping("/api/v1/public-health")
    @Tag(name = "CHW, Home Care, EPI & Disease Watch", description = "Community Health Workers, Home Care, WHO EPI vaccines, IDSR Outbreak Watch & DHIS2 ADX")
    public static class PublicHealthController {
        private final PublicHealthService service;
        private final ChwHouseholdRepository householdRepo;
        private final HomeCarePlanRepository homeCareRepo;
        private final ContactTracingRepository contactRepo;

        public PublicHealthController(
                PublicHealthService service,
                ChwHouseholdRepository householdRepo,
                HomeCarePlanRepository homeCareRepo,
                ContactTracingRepository contactRepo) {
            this.service = service;
            this.householdRepo = householdRepo;
            this.homeCareRepo = homeCareRepo;
            this.contactRepo = contactRepo;
        }

        @PostMapping("/chw/households")
        @Operation(summary = "Register GPS-tagged CHW household")
        public ChwHouseholdEntity registerHousehold(@RequestBody ChwHouseholdEntity hh) {
            return householdRepo.save(hh);
        }

        @PostMapping("/chw/visits")
        @Operation(summary = "Log CHW field visit (ANC/iCCM/MUAC) with automatic emergency referral QR")
        public ChwVisitEntity logChwVisit(@RequestBody ChwVisitEntity visit) {
            return service.recordChwVisit(visit);
        }

        @PostMapping("/home-care")
        @Operation(summary = "Enroll patient in Home Care / Palliative / RPM track")
        public HomeCarePlanEntity createHomeCare(@RequestBody HomeCarePlanEntity plan) {
            return homeCareRepo.save(plan);
        }

        @PostMapping("/immunizations")
        @Operation(summary = "Record EPI vaccine dose and compute next due date")
        public ImmunizationRecordEntity recordVaccine(@RequestBody ImmunizationRecordEntity rec) {
            return service.recordImmunization(rec);
        }

        @PostMapping("/surveillance/cases")
        @Operation(summary = "Report IDSR disease case (Malaria, HIV, Hepatitis, TB, Cholera, VHF) and evaluate outbreak threshold")
        public Map<String, Object> reportCase(@RequestBody SurveillanceCaseEntity sc) {
            return service.reportSurveillanceCase(sc);
        }

        @PostMapping("/surveillance/contacts")
        @Operation(summary = "Register contact tracing link for reportable outbreak case")
        public ContactTracingLinkEntity addContact(@RequestBody ContactTracingLinkEntity link) {
            return contactRepo.save(link);
        }

        @GetMapping(value = "/surveillance/dhis2-adx", produces = MediaType.APPLICATION_XML_VALUE)
        @Operation(summary = "Export aggregated IDSR counts in DHIS2 ADX XML format")
        public String dhis2Adx(@RequestParam String districtCode, @RequestParam(defaultValue = "2026W46") String period) {
            return service.exportDhis2AdxXml(districtCode, period);
        }
    }
}
