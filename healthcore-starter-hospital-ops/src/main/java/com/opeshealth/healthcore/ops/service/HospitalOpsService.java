/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Ops, Workforce Gate & HIPAA Safe-Harbor De-ID
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.ops.service;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.*;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

public class HospitalOpsService {

    public interface BloodUnitRepository extends JpaRepository<BloodComponentUnitEntity, UUID> {}
    public interface SurgicalScheduleRepository extends JpaRepository<SurgicalScheduleEntity, UUID> {}
    public interface TelemedicineRepository extends JpaRepository<TelemedicineSessionEntity, UUID> {}
    public interface ColdChainRepository extends JpaRepository<ColdChainTelemetryEntity, UUID> {}
    public interface CrvsRepository extends JpaRepository<CrvsBirthDeathEntity, UUID> {}
    public interface CredentialRepository extends JpaRepository<PractitionerCredentialEntity, UUID> {
        Optional<PractitionerCredentialEntity> findByFacilityIdAndPractitionerId(UUID facilityId, String practitionerId);
    }

    private final BloodUnitRepository bloodRepo;
    private final SurgicalScheduleRepository surgeryRepo;
    private final TelemedicineRepository telemedRepo;
    private final ColdChainRepository coldChainRepo;
    private final CrvsRepository crvsRepo;
    private final CredentialRepository credentialRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;

    public HospitalOpsService(
            BloodUnitRepository bloodRepo,
            SurgicalScheduleRepository surgeryRepo,
            TelemedicineRepository telemedRepo,
            ColdChainRepository coldChainRepo,
            CrvsRepository crvsRepo,
            CredentialRepository credentialRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.bloodRepo = bloodRepo;
        this.surgeryRepo = surgeryRepo;
        this.telemedRepo = telemedRepo;
        this.coldChainRepo = coldChainRepo;
        this.crvsRepo = crvsRepo;
        this.credentialRepo = credentialRepo;
        this.outboxRepo = outboxRepo;
    }

    /** Enforces 2-person bedside verification prior to releasing blood unit for transfusion. */
    @Transactional
    @ClinicalAudit(action = "BLOOD_BANK_TRANSFUSE", resourceType = "BloodComponentUnit")
    public BloodComponentUnitEntity verifyAndIssueBloodUnit(
            UUID unitId, UUID patientId, String verifier1, String verifier2) {
        if (Objects.equals(verifier1, verifier2) || verifier1 == null || verifier2 == null) {
            throw new SecurityException("Blood transfusion requires 2 distinct clinical verifiers at bedside");
        }
        BloodComponentUnitEntity unit = bloodRepo.findById(unitId)
                .orElseThrow(() -> new NoSuchElementException("Blood unit not found"));
        if (unit.getExpiryDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Blood unit expired on " + unit.getExpiryDate());
        }
        unit.setCrossmatchedPatientId(patientId);
        unit.setTransfusionVerifier1(verifier1);
        unit.setTransfusionVerifier2(verifier2);
        unit.setStatus("ISSUED");
        return bloodRepo.save(unit);
    }

    /** Gates Operating Theater incision on completed WHO Surgical Safety Checklist. */
    @Transactional
    public SurgicalScheduleEntity completeSurgeryWithWhoGate(UUID surgeryId, String implantUdi) {
        SurgicalScheduleEntity op = surgeryRepo.findById(surgeryId)
                .orElseThrow(() -> new NoSuchElementException("Surgical schedule not found"));
        if (!op.isWhoChecklistCompleted()) {
            throw new IllegalStateException("WHO Surgical Safety Checklist (Sign-In/Time-Out/Sign-Out) must be completed");
        }
        op.setImplantUdi(implantUdi);
        op.setStatus("COMPLETED");
        return surgeryRepo.save(op);
    }

    /** Records vaccine/blood fridge telemetry and triggers alert if outside WHO 2.0C - 8.0C range. */
    @Transactional
    public ColdChainTelemetryEntity ingestColdChainReading(ColdChainTelemetryEntity reading) {
        boolean excursion = reading.getTempCelsius().compareTo(new BigDecimal("2.0")) < 0
                || reading.getTempCelsius().compareTo(new BigDecimal("8.0")) > 0;
        reading.setExcursionAlert(excursion);
        ColdChainTelemetryEntity saved = coldChainRepo.save(reading);
        if (excursion) {
            TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
            evt.setIdempotencyKey("ColdChain:ColdChainExcursion:" + saved.getId() + ":" + evt.getId());
            evt.setAggregateType("ColdChain");
            evt.setAggregateId(saved.getId());
            evt.setEventType("ColdChainExcursionEvent");
            evt.setPayloadJson("{\"equipmentTag\":\"" + saved.getEquipmentTag() + "\",\"temp\":" + saved.getTempCelsius() + "}");
            outboxRepo.save(evt);
        }
        return saved;
    }

    /** Verifies whether practitioner holds an active, unexpired medical/nursing license. */
    @Transactional(readOnly = true)
    public boolean isPractitionerCredentialActive(UUID facilityId, String practitionerId) {
        return credentialRepo.findByFacilityIdAndPractitionerId(facilityId, practitionerId)
                .map(c -> !c.isSuspended() && !c.getExpiresOn().isBefore(LocalDate.now()))
                .orElse(false);
    }

    /**
     * HIPAA Safe-Harbor 18-identifier de-identification helper for research & ML dataset export:
     * removes names/phones/MRNs, retains only birth year (generalizes >89 to 90+), and deterministically
     * pseudonymizes patient UUID with SHA-256.
     */
    public Map<String, Object> deidentifySafeHarbor(UUID patientId, int birthYear, String gender, String icd10, String region) {
        int currentYear = LocalDate.now().getYear();
        int age = currentYear - birthYear;
        String safeAgeGroup = age > 89 ? "90+" : Integer.toString(age);
        return Map.of(
                "researchSubjectId", sha256Short(patientId.toString()),
                "ageGroup", safeAgeGroup,
                "gender", gender != null ? gender : "unknown",
                "icd10", icd10 != null ? icd10 : "",
                "regionPrefix", region != null && region.length() >= 3 ? region.substring(0, 3) : "XXX"
        );
    }

    private String sha256Short(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Hospital Ops, Blood Bank, OT, CRVS & De-ID", description = "Blood bank, WHO Surgical Checklist, Telemedicine, Cold-Chain IoT, CRVS & HIPAA Safe-Harbor")
    public static class HospitalOpsController {
        private final HospitalOpsService service;
        private final BloodUnitRepository bloodRepo;
        private final SurgicalScheduleRepository surgeryRepo;
        private final TelemedicineRepository telemedRepo;
        private final CrvsRepository crvsRepo;
        private final CredentialRepository credentialRepo;

        public HospitalOpsController(
                HospitalOpsService service,
                BloodUnitRepository bloodRepo,
                SurgicalScheduleRepository surgeryRepo,
                TelemedicineRepository telemedRepo,
                CrvsRepository crvsRepo,
                CredentialRepository credentialRepo) {
            this.service = service;
            this.bloodRepo = bloodRepo;
            this.surgeryRepo = surgeryRepo;
            this.telemedRepo = telemedRepo;
            this.crvsRepo = crvsRepo;
            this.credentialRepo = credentialRepo;
        }

        @PostMapping("/blood/units")
        @Operation(summary = "Register ISBT-128 blood component unit")
        public BloodComponentUnitEntity addBloodUnit(@RequestBody BloodComponentUnitEntity unit) {
            return bloodRepo.save(unit);
        }

        @PostMapping("/blood/units/{id}/transfuse")
        @Operation(summary = "2-person bedside verify and issue blood component")
        public BloodComponentUnitEntity issueBlood(
                @PathVariable UUID id, @RequestParam UUID patientId,
                @RequestParam String verifier1, @RequestParam String verifier2) {
            return service.verifyAndIssueBloodUnit(id, patientId, verifier1, verifier2);
        }

        @PostMapping("/ot/surgeries")
        @Operation(summary = "Schedule operating theater procedure")
        public SurgicalScheduleEntity scheduleSurgery(@RequestBody SurgicalScheduleEntity op) {
            return surgeryRepo.save(op);
        }

        @PostMapping("/ot/surgeries/{id}/complete")
        @Operation(summary = "Complete surgery gated by WHO Surgical Safety Checklist")
        public SurgicalScheduleEntity completeSurgery(@PathVariable UUID id, @RequestParam(required = false) String implantUdi) {
            return service.completeSurgeryWithWhoGate(id, implantUdi);
        }

        @PostMapping("/telemedicine/sessions")
        @Operation(summary = "Start WebRTC/3G-fallback telemedicine consultation session")
        public TelemedicineSessionEntity startTelemed(@RequestBody TelemedicineSessionEntity session) {
            return telemedRepo.save(session);
        }

        @PostMapping("/cold-chain/telemetry")
        @Operation(summary = "Ingest refrigerator/cold-chain temperature sensor reading (2C-8C alert)")
        public ColdChainTelemetryEntity ingestTemp(@RequestBody ColdChainTelemetryEntity reading) {
            return service.ingestColdChainReading(reading);
        }

        @PostMapping("/crvs/events")
        @Operation(summary = "Record Civil Registration & Vital Statistics (Birth / ICD-10 Cause of Death / Mortuary)")
        public CrvsBirthDeathEntity recordCrvs(@RequestBody CrvsBirthDeathEntity evt) {
            return crvsRepo.save(evt);
        }

        @PostMapping("/workforce/credentials")
        @Operation(summary = "Register clinician medical/nursing license expiry")
        public PractitionerCredentialEntity registerCredential(@RequestBody PractitionerCredentialEntity cred) {
            return credentialRepo.save(cred);
        }

        @GetMapping("/deidentify")
        @Operation(summary = "Apply HIPAA Safe-Harbor 18-identifier de-identification for research exports")
        public Map<String, Object> deidentify(
                @RequestParam UUID patientId, @RequestParam int birthYear,
                @RequestParam String gender, @RequestParam String icd10, @RequestParam String region) {
            return service.deidentifySafeHarbor(patientId, birthYear, gender, icd10, region);
        }
    }
}
