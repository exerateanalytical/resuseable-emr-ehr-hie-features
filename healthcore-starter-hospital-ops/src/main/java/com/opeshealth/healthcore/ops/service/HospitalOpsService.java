/*
 * =============================================================================
 * Opes HealthCore SDK — Hospital Ops, Blood Bank, OT, Biomed, CRVS, Roster & De-ID
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
import com.opeshealth.healthcore.security.rbac.WorkforceCredentialSpi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

public class HospitalOpsService implements WorkforceCredentialSpi {

    public interface BloodUnitRepository extends JpaRepository<BloodComponentUnitEntity, UUID> {
        List<BloodComponentUnitEntity> findByFacilityId(UUID facilityId);
    }
    public interface BloodDonorRepository extends JpaRepository<BloodDonorEntity, UUID> {}
    public interface TransfusionReactionRepository extends JpaRepository<TransfusionReactionEntity, UUID> {}
    public interface SurgicalScheduleRepository extends JpaRepository<SurgicalScheduleEntity, UUID> {
        Optional<SurgicalScheduleEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }
    public interface AnesthesiaPacuRepository extends JpaRepository<AnesthesiaPacuLogEntity, UUID> {
        Optional<AnesthesiaPacuLogEntity> findTopByFacilityIdAndSurgeryIdOrderByRecordedAtDesc(UUID facilityId, UUID surgeryId);
    }
    public interface TelemedicineRepository extends JpaRepository<TelemedicineSessionEntity, UUID> {}
    public interface ColdChainRepository extends JpaRepository<ColdChainTelemetryEntity, UUID> {
        List<ColdChainTelemetryEntity> findByFacilityIdAndEquipmentTagOrderByRecordedAtAsc(UUID facilityId, String equipmentTag);
    }
    public interface BiomedMaintenanceRepository extends JpaRepository<BiomedMaintenanceEntity, UUID> {
        List<BiomedMaintenanceEntity> findByFacilityId(UUID facilityId);
    }
    public interface CrvsRepository extends JpaRepository<CrvsBirthDeathEntity, UUID> {
        Optional<CrvsBirthDeathEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }
    public interface CredentialRepository extends JpaRepository<PractitionerCredentialEntity, UUID> {
        Optional<PractitionerCredentialEntity> findByFacilityIdAndPractitionerId(UUID facilityId, String practitionerId);
    }
    public interface WorkforceRosterRepository extends JpaRepository<WorkforceRosterEntity, UUID> {
        List<WorkforceRosterEntity> findByFacilityIdAndShiftDate(UUID facilityId, LocalDate shiftDate);
    }

    private final BloodUnitRepository bloodRepo;
    private final BloodDonorRepository donorRepo;
    private final TransfusionReactionRepository reactionRepo;
    private final SurgicalScheduleRepository surgeryRepo;
    private final AnesthesiaPacuRepository anesthesiaRepo;
    private final TelemedicineRepository telemedRepo;
    private final ColdChainRepository coldChainRepo;
    private final BiomedMaintenanceRepository biomedRepo;
    private final CrvsRepository crvsRepo;
    private final CredentialRepository credentialRepo;
    private final WorkforceRosterRepository rosterRepo;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private volatile boolean allowUnregisteredPractitionersInDev = false;

    public HospitalOpsService(
            BloodUnitRepository bloodRepo,
            BloodDonorRepository donorRepo,
            TransfusionReactionRepository reactionRepo,
            SurgicalScheduleRepository surgeryRepo,
            AnesthesiaPacuRepository anesthesiaRepo,
            TelemedicineRepository telemedRepo,
            ColdChainRepository coldChainRepo,
            BiomedMaintenanceRepository biomedRepo,
            CrvsRepository crvsRepo,
            CredentialRepository credentialRepo,
            WorkforceRosterRepository rosterRepo,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.bloodRepo = bloodRepo;
        this.donorRepo = donorRepo;
        this.reactionRepo = reactionRepo;
        this.surgeryRepo = surgeryRepo;
        this.anesthesiaRepo = anesthesiaRepo;
        this.telemedRepo = telemedRepo;
        this.coldChainRepo = coldChainRepo;
        this.biomedRepo = biomedRepo;
        this.crvsRepo = crvsRepo;
        this.credentialRepo = credentialRepo;
        this.rosterRepo = rosterRepo;
        this.outboxRepo = outboxRepo;
    }

    /** Screens blood donor hemoglobin (>= 12.5 g/dL) and enforces temporary deferral if below threshold. */
    @Transactional
    public BloodDonorEntity registerDonorWithScreening(BloodDonorEntity donor) {
        if (donor.getHemoglobinGDl().compareTo(new BigDecimal("12.5")) < 0) {
            donor.setEligible(false);
            donor.setDeferralReason("Low Hemoglobin (" + donor.getHemoglobinGDl() + " g/dL < 12.5 g/dL)");
            donor.setDeferralUntil(LocalDate.now().plusDays(56));
        }
        return donorRepo.save(donor);
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

    /** Logs hemovigilance adverse transfusion reaction and quarantines implicated blood unit. */
    @Transactional
    @ClinicalAudit(action = "BLOOD_BANK_HEMOVIGILANCE", resourceType = "TransfusionReaction")
    public TransfusionReactionEntity reportTransfusionReaction(TransfusionReactionEntity reaction) {
        bloodRepo.findById(reaction.getBloodUnitId()).ifPresent(unit -> {
            unit.setStatus("QUARANTINED_REACTION");
            bloodRepo.save(unit);
        });
        TransfusionReactionEntity saved = reactionRepo.save(reaction);
        emitOutbox("TransfusionReaction", saved.getId(), "HemovigilanceAlertEvent",
                "{\"reactionType\":\"" + saved.getReactionType() + "\",\"severity\":\"" + saved.getSeverity() + "\"}");
        return saved;
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

    @Transactional
    public AnesthesiaPacuLogEntity recordAnesthesiaAndPacu(AnesthesiaPacuLogEntity log) {
        if (log.getPacuAldreteScore() != null && (log.getPacuAldreteScore() < 0 || log.getPacuAldreteScore() > 10)) {
            throw new IllegalArgumentException("PACU Aldrete score must be between 0 and 10");
        }
        return anesthesiaRepo.save(log);
    }

    /** Records vaccine/blood fridge telemetry and triggers alert if outside WHO 2.0C - 8.0C range. */
    @Transactional
    public ColdChainTelemetryEntity ingestColdChainReading(ColdChainTelemetryEntity reading) {
        boolean excursion = reading.getTempCelsius().compareTo(new BigDecimal("2.0")) < 0
                || reading.getTempCelsius().compareTo(new BigDecimal("8.0")) > 0;
        reading.setExcursionAlert(excursion);
        ColdChainTelemetryEntity saved = coldChainRepo.save(reading);
        if (excursion) {
            emitOutbox("ColdChain", saved.getId(), "ColdChainExcursionEvent",
                    "{\"equipmentTag\":\"" + saved.getEquipmentTag() + "\",\"temp\":" + saved.getTempCelsius() + "}");
        }
        return saved;
    }

    /** Releases a mortuary body cleanly to Next-of-Kin under CRVS custody tracking. */
    @Transactional
    @ClinicalAudit(action = "CRVS_MORTUARY_RELEASE", resourceType = "CrvsBirthDeath")
    public CrvsBirthDeathEntity releaseMortuaryBody(UUID crvsEventId, String nextOfKinName) {
        CrvsBirthDeathEntity evt = crvsRepo.findById(crvsEventId)
                .orElseThrow(() -> new NoSuchElementException("CRVS event not found: " + crvsEventId));
        evt.setMortuaryCustodyStatus("RELEASED");
        evt.setReleasedToNextOfKin(nextOfKinName);
        return crvsRepo.save(evt);
    }

    /**
     * Enables permissive unregistered-practitioner behavior strictly for local non-production development.
     * Throws {@link IllegalStateException} if attempted when a production profile is active.
     */
    public void configurePermissiveDevMode(boolean allowUnregistered, boolean productionProfileActive) {
        if (allowUnregistered && productionProfileActive) {
            throw new IllegalStateException(
                    "Fail-open practitioner credential mode cannot be enabled when a production profile is active.");
        }
        this.allowUnregisteredPractitionersInDev = allowUnregistered;
    }

    /**
     * Verifies whether practitioner holds an active, unexpired, unsuspended medical/nursing license.
     *
     * <p><strong>Fail-closed policy:</strong> Returns {@code false} when the practitioner is
     * unregistered, missing, expired, or suspended. Only returns {@code true} for unregistered
     * records if explicit non-production dev mode has been opted into via
     * {@link #configurePermissiveDevMode(boolean, boolean)}.</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isPractitionerActive(UUID facilityId, String practitionerId) {
        if (facilityId == null || practitionerId == null || practitionerId.isBlank() || credentialRepo == null) {
            return false;
        }
        return credentialRepo.findByFacilityIdAndPractitionerId(facilityId, practitionerId)
                .map(c -> !c.isSuspended() && c.getExpiresOn() != null && !c.getExpiresOn().isBefore(LocalDate.now()))
                .orElse(allowUnregisteredPractitionersInDev);
    }

    /** Strict query always failing closed if practitioner is unregistered, suspended, or expired. */
    @Transactional(readOnly = true)
    public boolean isPractitionerCredentialActive(UUID facilityId, String practitionerId) {
        if (facilityId == null || practitionerId == null || practitionerId.isBlank() || credentialRepo == null) {
            return false;
        }
        return credentialRepo.findByFacilityIdAndPractitionerId(facilityId, practitionerId)
                .map(c -> !c.isSuspended() && c.getExpiresOn() != null && !c.getExpiresOn().isBefore(LocalDate.now()))
                .orElse(false);
    }

    /** HIPAA Safe-Harbor 18-identifier de-identification helper for research & ML dataset export. */
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

    private void emitOutbox(String type, UUID id, String eventType, String payload) {
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(type + ":" + eventType + ":" + id + ":" + evt.getId());
        evt.setAggregateType(type);
        evt.setAggregateId(id);
        evt.setEventType(eventType);
        evt.setPayloadJson(payload);
        outboxRepo.save(evt);
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
    @Tag(name = "Blood Bank & Hemovigilance", description = "Donor Hb screening, ISBT-128 inventory, 2-person bedside transfusion verification & reactions")
    public static class BloodBankController {
        private final HospitalOpsService service;
        private final BloodUnitRepository bloodRepo;

        public BloodBankController(HospitalOpsService service, BloodUnitRepository bloodRepo) {
            this.service = service;
            this.bloodRepo = bloodRepo;
        }

        @PostMapping("/blood/donors")
        @PreAuthorize("hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Register and screen blood donor (automatic deferral if Hb < 12.5 g/dL)")
        public BloodDonorEntity registerDonor(@RequestBody BloodDonorEntity donor) {
            return service.registerDonorWithScreening(donor);
        }

        @PostMapping("/blood/units")
        @PreAuthorize("hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Register ISBT-128 blood component unit")
        public BloodComponentUnitEntity addBloodUnit(@RequestBody BloodComponentUnitEntity unit) {
            return bloodRepo.save(unit);
        }

        @PostMapping("/blood/units/{id}/transfuse")
        @PreAuthorize("hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_emar:administer')")
        @Operation(summary = "2-person bedside verify and issue blood component")
        public BloodComponentUnitEntity issueBlood(
                @PathVariable UUID id, @RequestParam UUID patientId,
                @RequestParam String verifier1, @RequestParam String verifier2) {
            return service.verifyAndIssueBloodUnit(id, patientId, verifier1, verifier2);
        }

        @PostMapping("/blood/reactions")
        @PreAuthorize("hasAuthority('PERM_bloodbank:crossmatch') or hasAuthority('PERM_emar:administer')")
        @Operation(summary = "Report adverse blood transfusion reaction (hemovigilance)")
        public TransfusionReactionEntity reportReaction(@RequestBody TransfusionReactionEntity reaction) {
            return service.reportTransfusionReaction(reaction);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Surgery & Operating Theater (OT)", description = "OT scheduling, WHO Surgical Safety Checklist gate, Anesthesia & PACU Aldrete logs")
    public static class SurgeryOtController {
        private final HospitalOpsService service;
        private final SurgicalScheduleRepository surgeryRepo;

        public SurgeryOtController(HospitalOpsService service, SurgicalScheduleRepository surgeryRepo) {
            this.service = service;
            this.surgeryRepo = surgeryRepo;
        }

        @PostMapping("/ot/surgeries")
        @PreAuthorize("hasAuthority('PERM_ot:schedule')")
        @Operation(summary = "Schedule operating theater procedure")
        public SurgicalScheduleEntity scheduleSurgery(@RequestBody SurgicalScheduleEntity op) {
            return surgeryRepo.save(op);
        }

        @PostMapping("/ot/surgeries/{id}/complete")
        @PreAuthorize("hasAuthority('PERM_ot:schedule') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Complete surgery gated by WHO Surgical Safety Checklist")
        public SurgicalScheduleEntity completeSurgery(@PathVariable UUID id, @RequestParam(required = false) String implantUdi) {
            return service.completeSurgeryWithWhoGate(id, implantUdi);
        }

        @PostMapping("/ot/anesthesia-pacu")
        @PreAuthorize("hasAuthority('PERM_ot:schedule') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Log intra-operative anesthesia & PACU Aldrete recovery score")
        public AnesthesiaPacuLogEntity logAnesthesia(@RequestBody AnesthesiaPacuLogEntity log) {
            return service.recordAnesthesiaAndPacu(log);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Telemedicine Virtual Care", description = "WebRTC & low-bandwidth 3G-fallback virtual consultation sessions")
    public static class TelemedicineController {
        private final TelemedicineRepository telemedRepo;

        public TelemedicineController(TelemedicineRepository telemedRepo) {
            this.telemedRepo = telemedRepo;
        }

        @PostMapping("/telemedicine/sessions")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_self:appointment:request')")
        @Operation(summary = "Start WebRTC/3G-fallback telemedicine consultation session")
        public TelemedicineSessionEntity startTelemed(@RequestBody TelemedicineSessionEntity session) {
            return telemedRepo.save(session);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Biomedical & Cold-Chain IoT", description = "2°C-8°C vaccine/blood cold-chain telemetry, MQTT bridge & biomedical PM/calibration")
    public static class BiomedicalColdChainController {
        private final HospitalOpsService service;
        private final BiomedMaintenanceRepository biomedRepo;

        public BiomedicalColdChainController(HospitalOpsService service, BiomedMaintenanceRepository biomedRepo) {
            this.service = service;
            this.biomedRepo = biomedRepo;
        }

        @PostMapping("/cold-chain/telemetry")
        @Operation(summary = "Ingest refrigerator/cold-chain temperature sensor reading (2C-8C alert)")
        public ColdChainTelemetryEntity ingestTemp(@RequestBody ColdChainTelemetryEntity reading) {
            return service.ingestColdChainReading(reading);
        }

        @PostMapping("/cold-chain/mqtt-webhook")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Ingest MQTT bridge JSON cold-chain sensor payload")
        public ColdChainTelemetryEntity ingestMqttBridge(@RequestBody ColdChainTelemetryEntity reading) {
            return service.ingestColdChainReading(reading);
        }

        @PostMapping("/biomed/maintenance")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Register biomedical equipment calibration and preventive maintenance schedule")
        public BiomedMaintenanceEntity saveBiomed(@RequestBody BiomedMaintenanceEntity asset) {
            return biomedRepo.save(asset);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "CRVS & Mortuary Chain-of-Custody", description = "Birth notifications, WHO MCCD cause-of-death certificates & mortuary release")
    public static class CrvsMortuaryController {
        private final HospitalOpsService service;
        private final CrvsRepository crvsRepo;

        public CrvsMortuaryController(HospitalOpsService service, CrvsRepository crvsRepo) {
            this.service = service;
            this.crvsRepo = crvsRepo;
        }

        @PostMapping("/crvs/events")
        @PreAuthorize("hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Record Civil Registration & Vital Statistics (Birth / WHO MCCD Cause of Death / Mortuary)")
        public CrvsBirthDeathEntity recordCrvs(@RequestBody CrvsBirthDeathEntity evt) {
            return crvsRepo.save(evt);
        }

        @PostMapping("/crvs/events/{id}/mortuary-release")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Log mortuary body custody release to Next-of-Kin")
        public CrvsBirthDeathEntity releaseMortuary(@PathVariable UUID id, @RequestParam String nextOfKin) {
            return service.releaseMortuaryBody(id, nextOfKin);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Workforce Licensing & Rosters", description = "Practitioner license expiry enforcement & department shift rosters")
    public static class WorkforceRosterController {
        private final CredentialRepository credentialRepo;
        private final WorkforceRosterRepository rosterRepo;

        public WorkforceRosterController(CredentialRepository credentialRepo, WorkforceRosterRepository rosterRepo) {
            this.credentialRepo = credentialRepo;
            this.rosterRepo = rosterRepo;
        }

        @PostMapping("/workforce/credentials")
        @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Register clinician medical/nursing license expiry")
        public PractitionerCredentialEntity registerCredential(@RequestBody PractitionerCredentialEntity cred) {
            return credentialRepo.save(cred);
        }

        @PostMapping("/workforce/rosters")
        @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Assign department shift or on-call roster entry")
        public WorkforceRosterEntity assignRoster(@RequestBody WorkforceRosterEntity entry) {
            return rosterRepo.save(entry);
        }

        @GetMapping("/workforce/rosters")
        @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "List facility staff shift & on-call roster by date")
        public List<WorkforceRosterEntity> getRosters(@RequestParam UUID facilityId, @RequestParam LocalDate shiftDate) {
            UUID activeFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.getFacilityId().orElse(null);
            if (activeFacility != null && !activeFacility.equals(facilityId)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Cross-tenant roster read denied for facility " + facilityId);
            }
            return rosterRepo.findByFacilityIdAndShiftDate(facilityId, shiftDate);
        }
    }

    @RestController
    @RequestMapping("/api/v1/ops")
    @Tag(name = "Research De-Identification", description = "HIPAA Safe-Harbor 18-identifier de-identification & cohort anonymizer")
    public static class DeidentificationResearchController {
        private final HospitalOpsService service;

        public DeidentificationResearchController(HospitalOpsService service) {
            this.service = service;
        }

        @GetMapping("/deidentify")
        @PreAuthorize("hasAuthority('PERM_audit:export') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Apply HIPAA Safe-Harbor 18-identifier de-identification for research exports")
        public Map<String, Object> deidentify(
                @RequestParam UUID patientId, @RequestParam int birthYear,
                @RequestParam String gender, @RequestParam String icd10, @RequestParam String region) {
            return service.deidentifySafeHarbor(patientId, birthYear, gender, icd10, region);
        }
    }
}
