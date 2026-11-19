/*
 * =============================================================================
 * Opes HealthCore SDK — Workforce Credential Gate & Shift Roster Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.PractitionerCredentialEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.WorkforceRosterEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.CredentialRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.WorkforceRosterRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * End-to-end Workforce Medical License Expiry Gate &amp; Safe Clinical Duty-Hour Roster
 * module enforcing valid medical council licensure prior to shift assignment, blocking
 * unsafe consecutive night-to-morning turnaround shifts, and auditing credential expiry
 * windows in PostgreSQL.
 */
@AutoConfiguration(after = HospitalOpsAutoConfiguration.class)
public class WorkforceRosterStarter {

    public record VerifiedShiftAssignmentRequest(
            UUID facilityId,
            String practitionerId,
            String department,
            LocalDate shiftDate,
            String shiftType,
            boolean onCallEscalation
    ) {}

    public record CredentialComplianceAudit(
            UUID facilityId,
            int totalPractitioners,
            int activeValidCredentials,
            int expiringWithin30Days,
            int expiredOrSuspendedCredentials,
            List<String> blockedPractitionerIds
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public WorkforceCredentialAndRosterService workforceCredentialAndRosterService(
            HospitalOpsService hospitalOpsService,
            CredentialRepository credentialRepository,
            WorkforceRosterRepository workforceRosterRepository) {
        return new WorkforceCredentialAndRosterService(
                hospitalOpsService, credentialRepository, workforceRosterRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkforceCredentialAndRosterController workforceCredentialAndRosterController(
            WorkforceCredentialAndRosterService rosterService) {
        return new WorkforceCredentialAndRosterController(rosterService);
    }

    public static class WorkforceCredentialAndRosterService {

        private final HospitalOpsService hospitalOpsService;
        private final CredentialRepository credentialRepository;
        private final WorkforceRosterRepository workforceRosterRepository;

        public WorkforceCredentialAndRosterService(
                HospitalOpsService hospitalOpsService,
                CredentialRepository credentialRepository,
                WorkforceRosterRepository workforceRosterRepository) {
            this.hospitalOpsService = Objects.requireNonNull(hospitalOpsService, "HospitalOpsService is required");
            this.credentialRepository = Objects.requireNonNull(credentialRepository, "CredentialRepository is required");
            this.workforceRosterRepository = Objects.requireNonNull(workforceRosterRepository, "WorkforceRosterRepository is required");
        }

        /**
         * Verifies that the practitioner holds an active, non-expired medical council license
         * on the date of the shift and checks fatigue rules (no NIGHT shift followed immediately
         * by a MORNING shift on the next calendar day) before persisting the roster assignment.
         */
        @Transactional
        @ClinicalAudit(action = "WORKFORCE_ASSIGN_VERIFIED_SHIFT", resourceType = "WorkforceRoster")
        public WorkforceRosterEntity assignVerifiedClinicalShift(VerifiedShiftAssignmentRequest req) {
            Objects.requireNonNull(req, "VerifiedShiftAssignmentRequest must not be null");
            if (req.facilityId() == null || req.practitionerId() == null || req.practitionerId().isBlank()) {
                throw new IllegalArgumentException("facilityId and practitionerId are required for shift assignment");
            }
            LocalDate shiftDate = req.shiftDate() != null ? req.shiftDate() : LocalDate.now();
            String normShift = req.shiftType() != null ? req.shiftType().trim().toUpperCase(Locale.ROOT) : "MORNING";

            PractitionerCredentialEntity cred = credentialRepository
                    .findByFacilityIdAndPractitionerId(req.facilityId(), req.practitionerId().trim())
                    .orElseThrow(() -> new SecurityException("Cannot roster practitioner '" + req.practitionerId()
                            + "': no verified medical council credential on file for facility " + req.facilityId()));

            if (cred.isSuspended() || cred.getLicenseExpiresOn() == null || cred.getLicenseExpiresOn().isBefore(shiftDate)) {
                throw new SecurityException("Clinical License Gate blocked shift assignment for '"
                        + req.practitionerId() + "': license expired on " + cred.getLicenseExpiresOn()
                        + " or suspended=" + cred.isSuspended());
            }

            // Safe Duty-Hour Fatigue Check: block MORNING shift if practitioner worked NIGHT shift the previous day
            if ("MORNING".equals(normShift)) {
                List<WorkforceRosterEntity> prevDayShifts = workforceRosterRepository
                        .findByFacilityIdAndShiftDate(req.facilityId(), shiftDate.minusDays(1));
                boolean workedPrevNight = prevDayShifts.stream().anyMatch(s ->
                        req.practitionerId().trim().equalsIgnoreCase(s.getPractitionerId())
                                && "NIGHT".equalsIgnoreCase(s.getShiftType()));
                if (workedPrevNight) {
                    throw new IllegalStateException("Clinical Fatigue Safety Gate: practitioner '"
                            + req.practitionerId() + "' worked NIGHT shift on " + shiftDate.minusDays(1)
                            + " and cannot be assigned an immediate MORNING turnaround shift on " + shiftDate);
                }
            }

            WorkforceRosterEntity roster = new WorkforceRosterEntity();
            roster.setFacilityId(req.facilityId());
            roster.setPractitionerId(req.practitionerId().trim());
            roster.setDepartment(req.department() != null && !req.department().isBlank() ? req.department().trim() : "GENERAL_MEDICINE");
            roster.setShiftDate(shiftDate);
            roster.setShiftType(normShift);
            roster.setOnCallEscalation(req.onCallEscalation());

            return workforceRosterRepository.save(roster);
        }

        /**
         * Audits medical council license validity across all facility staff in PostgreSQL.
         */
        @Transactional(readOnly = true)
        public CredentialComplianceAudit auditFacilityCredentials(UUID facilityId) {
            List<PractitionerCredentialEntity> all = credentialRepository.findAll().stream()
                    .filter(c -> facilityId == null || facilityId.equals(c.getFacilityId()))
                    .toList();

            LocalDate today = LocalDate.now();
            LocalDate thirtyDaysOut = today.plus(30, ChronoUnit.DAYS);
            int valid = 0;
            int expiringSoon = 0;
            int expiredOrSuspended = 0;
            List<String> blocked = new ArrayList<>();

            for (PractitionerCredentialEntity c : all) {
                if (c.isSuspended() || c.getLicenseExpiresOn() == null || c.getLicenseExpiresOn().isBefore(today)) {
                    expiredOrSuspended++;
                    blocked.add(c.getPractitionerId());
                } else {
                    valid++;
                    if (!c.getLicenseExpiresOn().isAfter(thirtyDaysOut)) {
                        expiringSoon++;
                    }
                }
            }

            return new CredentialComplianceAudit(
                    facilityId,
                    all.size(),
                    valid,
                    expiringSoon,
                    expiredOrSuspended,
                    List.copyOf(blocked)
            );
        }
    }

    @RestController
    @RequestMapping("/api/v1/workforce-roster")
    @Tag(name = "Workforce Credential Gate & Shift Roster", description = "Medical council license expiry enforcement and safe clinical duty-hour fatigue protection")
    public static class WorkforceCredentialAndRosterController {

        private final WorkforceCredentialAndRosterService rosterService;

        public WorkforceCredentialAndRosterController(WorkforceCredentialAndRosterService rosterService) {
            this.rosterService = rosterService;
        }

        @PostMapping("/assign-verified-shift")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_staff:onboard')")
        @Operation(summary = "Assign a clinical shift after verifying active medical license and duty-hour rest rules")
        public WorkforceRosterEntity assignVerifiedShift(@RequestBody VerifiedShiftAssignmentRequest request) {
            return rosterService.assignVerifiedClinicalShift(request);
        }

        @GetMapping("/credential-audit")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_staff:onboard')")
        @Operation(summary = "Audit medical council license validity and upcoming 30-day expirations")
        public CredentialComplianceAudit auditCredentials(@RequestParam(required = false) UUID facilityId) {
            return rosterService.auditFacilityCredentials(facilityId);
        }
    }
}
