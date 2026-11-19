/*
 * =============================================================================
 * Opes HealthCore SDK — Pure Java Reference Hospital Workstation Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.ui;

import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.AbnormalFlag;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.OrderDomain;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.OrderPriority;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.EnterResultRequest;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.PlaceOrderRequest;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.MedicationBatchRepository;
import com.opeshealth.healthcore.quickstart.seed.DemoHospitalDataSeeder;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.BookAppointmentRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import com.opeshealth.healthcore.uhc.service.UhcBillingService.PatientAccountRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Server-rendered Spring MVC + Thymeleaf Reference Hospital Application controller
 * (zero TypeScript / zero Node.js) wired directly to real Opes HealthCore starter services.
 */
@Controller
@RequestMapping("/ui")
public class HospitalWorkstationController {

    private final MpiCommandService mpiService;
    private final PatientRepository patientRepo;
    private final SchedulingService schedulingService;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final PharmacyLisService pharmacyLisService;
    private final MedicationBatchRepository batchRepo;
    private final UhcBillingService billingService;
    private final PatientAccountRepository accountRepo;
    private final TenantGovernanceService governanceService;
    private final TenantGovernanceService.FacilityRepository facilityRepo;
    private final ClinicalAuditService auditService;
    private final ObjectProvider<DemoHospitalDataSeeder> demoSeederProvider;

    public HospitalWorkstationController(
            MpiCommandService mpiService,
            PatientRepository patientRepo,
            SchedulingService schedulingService,
            UnifiedOrdersResultsService ordersResultsService,
            PharmacyLisService pharmacyLisService,
            MedicationBatchRepository batchRepo,
            UhcBillingService billingService,
            PatientAccountRepository accountRepo,
            TenantGovernanceService governanceService,
            TenantGovernanceService.FacilityRepository facilityRepo,
            ClinicalAuditService auditService,
            ObjectProvider<DemoHospitalDataSeeder> demoSeederProvider) {
        this.mpiService = mpiService;
        this.patientRepo = patientRepo;
        this.schedulingService = schedulingService;
        this.ordersResultsService = ordersResultsService;
        this.pharmacyLisService = pharmacyLisService;
        this.batchRepo = batchRepo;
        this.billingService = billingService;
        this.accountRepo = accountRepo;
        this.governanceService = governanceService;
        this.facilityRepo = facilityRepo;
        this.auditService = auditService;
        this.demoSeederProvider = demoSeederProvider;
    }

    @GetMapping({"", "/", "/overview"})
    public String overview(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "overview", "Hospital Overview", facId);
        if (facId != null) {
            var patients = patientRepo.findByFacilityId(facId);
            var appointments = schedulingService.listFacilityAppointments(facId);
            var orders = ordersResultsService.listFacilityOrders(facId);
            var criticalUnacked = ordersResultsService.listUnacknowledgedCriticalResults(facId);
            var auditVerification = auditService.verifyChainIntegrity(facId.toString());

            model.addAttribute("patientCount", patients.size());
            model.addAttribute("appointmentCount", appointments.size());
            model.addAttribute("orderCount", orders.size());
            model.addAttribute("criticalCount", criticalUnacked.size());
            model.addAttribute("criticalQueue", criticalUnacked);
            model.addAttribute("recentAppointments", appointments.stream().limit(6).toList());
            model.addAttribute("auditVerification", auditVerification);
        } else {
            model.addAttribute("patientCount", 0);
            model.addAttribute("appointmentCount", 0);
            model.addAttribute("orderCount", 0);
            model.addAttribute("criticalCount", 0);
            model.addAttribute("criticalQueue", List.of());
            model.addAttribute("recentAppointments", List.of());
        }
        return "workstation/overview";
    }

    @GetMapping("/patients")
    public String patients(@RequestParam(required = false) String q, Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "patients", "Patients & MPI", facId);
        List<PatientAggregateEntity> rows = List.of();
        if (facId != null) {
            rows = (q != null && !q.isBlank())
                    ? patientRepo.searchActiveGoldenByNamePrefix(facId, q.trim())
                    : patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId);
        }
        model.addAttribute("query", q != null ? q : "");
        model.addAttribute("patients", rows);
        return "workstation/patients";
    }

    @PostMapping("/patients")
    public String registerPatient(
            @RequestParam String mrn,
            @RequestParam String familyName,
            @RequestParam String givenName,
            @RequestParam LocalDate birthDate,
            @RequestParam String gender,
            @RequestParam(required = false) String primaryPhone,
            @RequestParam(defaultValue = "en") String preferredLanguage,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        if (facId == null) {
            redirect.addFlashAttribute("error", "No active facility is configured. Run with profile 'dev-seed' or onboard a facility first.");
            return "redirect:/ui/patients";
        }
        try {
            FacilityContext.setFacilityId(facId);
            mpiService.registerPatient(new RegisterPatientCommand(
                    facId, mrn, familyName, givenName, birthDate, gender, null,
                    primaryPhone, preferredLanguage, null, null, null, null, null));
            redirect.addFlashAttribute("notice", "Registered patient " + givenName + " " + familyName + " (" + mrn + ").");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/patients";
    }

    @GetMapping("/scheduling")
    public String scheduling(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "scheduling", "Appointments & Queue", facId);
        if (facId != null) {
            model.addAttribute("calendars", schedulingService.listCalendars(facId));
            model.addAttribute("appointments", schedulingService.listFacilityAppointments(facId));
            model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
        } else {
            model.addAttribute("calendars", List.of());
            model.addAttribute("appointments", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/scheduling";
    }

    @PostMapping("/scheduling/book")
    public String bookAppointment(
            @RequestParam UUID calendarId,
            @RequestParam UUID patientId,
            @RequestParam(defaultValue = "CONSULT") String appointmentTypeCode,
            @RequestParam(defaultValue = "20") int durationMinutes,
            @RequestParam(required = false) String visitReason,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        if (facId == null) {
            redirect.addFlashAttribute("error", "No active facility configured.");
            return "redirect:/ui/scheduling";
        }
        try {
            FacilityContext.setFacilityId(facId);
            Instant start = Instant.now().plus(Duration.ofMinutes(30)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
            Instant end = start.plus(Duration.ofMinutes(Math.max(10, durationMinutes)));
            schedulingService.bookAppointment(facId, new BookAppointmentRequest(
                    calendarId, null, patientId, appointmentTypeCode, start, end, visitReason), "ui.clerk");
            redirect.addFlashAttribute("notice", "Appointment booked cleanly.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/scheduling";
    }

    @PostMapping("/scheduling/{id}/check-in")
    public String checkIn(@PathVariable UUID id, RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            var appt = schedulingService.checkIn(facId, id);
            redirect.addFlashAttribute("notice", "Checked in — Queue Token #" + appt.getQueueTokenNumber());
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/scheduling";
    }

    @PostMapping("/scheduling/{id}/cancel")
    public String cancelAppointment(@PathVariable UUID id, RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            schedulingService.cancelAppointment(facId, id, "Cancelled from workstation", "ui.clerk");
            redirect.addFlashAttribute("notice", "Appointment cancelled.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/scheduling";
    }

    @GetMapping("/orders")
    public String orders(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "orders", "Unified Orders & Results", facId);
        if (facId != null) {
            model.addAttribute("orders", ordersResultsService.listFacilityOrders(facId));
            model.addAttribute("criticalResults", ordersResultsService.listUnacknowledgedCriticalResults(facId));
            model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
        } else {
            model.addAttribute("orders", List.of());
            model.addAttribute("criticalResults", List.of());
            model.addAttribute("patients", List.of());
        }
        model.addAttribute("domains", OrderDomain.values());
        model.addAttribute("priorities", OrderPriority.values());
        model.addAttribute("abnormalFlags", AbnormalFlag.values());
        return "workstation/orders";
    }

    @PostMapping("/orders")
    public String placeOrder(
            @RequestParam UUID patientId,
            @RequestParam OrderDomain orderDomain,
            @RequestParam String orderCode,
            @RequestParam String orderDisplay,
            @RequestParam(defaultValue = "ROUTINE") OrderPriority priority,
            @RequestParam(required = false) String clinicalNotes,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            ordersResultsService.placeOrder(facId, new PlaceOrderRequest(
                    patientId, null, orderDomain, "http://loinc.org", orderCode, orderDisplay, priority, clinicalNotes),
                    "ui.clinician");
            redirect.addFlashAttribute("notice", "Placed " + orderDomain + " order: " + orderDisplay);
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/orders";
    }

    @PostMapping("/orders/results")
    public String enterAndVerifyResult(
            @RequestParam UUID orderId,
            @RequestParam String resultCode,
            @RequestParam String resultDisplay,
            @RequestParam(required = false) BigDecimal numericValue,
            @RequestParam(required = false) String unit,
            @RequestParam(defaultValue = "NORMAL") AbnormalFlag abnormalFlag,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            var prelim = ordersResultsService.enterResult(facId, new EnterResultRequest(
                    orderId, resultCode, resultDisplay, numericValue, null, unit, null, abnormalFlag), "ui.labtech");
            var outcome = ordersResultsService.verifyResult(facId, prelim.getId(), "ui.labdirector");
            String msg = "Result verified as FINAL.";
            if (!outcome.triggeredReflexOrders().isEmpty()) {
                msg += " Reflex engine triggered " + outcome.triggeredReflexOrders().size() + " follow-up order(s).";
            }
            redirect.addFlashAttribute("notice", msg);
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/orders";
    }

    @PostMapping("/orders/results/{resultId}/ack-critical")
    public String ackCritical(
            @PathVariable UUID resultId,
            @RequestParam(defaultValue = "Reviewed and escalated with attending physician") String notes,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            ordersResultsService.acknowledgeCriticalResult(facId, resultId, "ui.attending", notes);
            redirect.addFlashAttribute("notice", "Critical result closed-loop acknowledgment recorded.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/orders";
    }

    @GetMapping("/pharmacy")
    public String pharmacy(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "pharmacy", "Pharmacy & FEFO Inventory", facId);
        if (facId != null) {
            model.addAttribute("batches", batchRepo.findAll().stream()
                    .filter(b -> facId.equals(b.getFacilityId()))
                    .toList());
            model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
        } else {
            model.addAttribute("batches", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/pharmacy";
    }

    @PostMapping("/pharmacy/dispense")
    public String dispenseFefo(
            @RequestParam UUID patientId,
            @RequestParam String drugCode,
            @RequestParam int quantity,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            var dispensed = pharmacyLisService.dispenseFefo(
                    facId, patientId, null, drugCode, drugCode, quantity, false, "ui.pharmacist", null);
            redirect.addFlashAttribute("notice",
                    "Dispensed " + dispensed.getQuantityDispensed() + " unit(s) of " + dispensed.getDispensedDrugCode() + " via atomic FEFO.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/pharmacy";
    }

    @GetMapping("/billing")
    public String billing(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "billing", "Billing & Double-Entry Ledger", facId);
        if (facId != null) {
            model.addAttribute("accounts", accountRepo.findAll().stream()
                    .filter(a -> facId.equals(a.getFacilityId()))
                    .toList());
            model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
        } else {
            model.addAttribute("accounts", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/billing";
    }

    @PostMapping("/billing/charge")
    public String postCharge(
            @RequestParam UUID patientId,
            @RequestParam long amountMinor,
            @RequestParam(defaultValue = "XAF") String currency,
            @RequestParam String referenceCode,
            @RequestParam String description,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            FacilityContext.setFacilityId(facId);
            billingService.postCharge(facId, patientId, amountMinor, currency, referenceCode, description);
            redirect.addFlashAttribute("notice", "Posted balanced double-entry charge (" + amountMinor + " " + currency + ").");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:/ui/billing";
    }

    @GetMapping("/governance")
    public String governance(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "governance", "Tenant Governance & Flags", facId);
        if (facId != null) {
            model.addAttribute("departments", governanceService.listDepartments(facId));
            model.addAttribute("flags", governanceService.listFeatureFlags(facId));
            model.addAttribute("grants", governanceService.listGrantsForSourceFacility(facId));
        } else {
            model.addAttribute("departments", List.of());
            model.addAttribute("flags", List.of());
            model.addAttribute("grants", List.of());
        }
        return "workstation/governance";
    }

    @PostMapping("/governance/flags")
    public String updateFlag(
            @RequestParam String flagKey,
            @RequestParam(defaultValue = "false") boolean enabled,
            @RequestParam(defaultValue = "false") boolean killSwitch,
            RedirectAttributes redirect) {
        UUID facId = resolveUiFacilityId();
        try {
            governanceService.upsertFeatureFlag(facId, flagKey, enabled, killSwitch, "ui.admin");
            redirect.addFlashAttribute("notice", "Updated feature flag '" + flagKey + "'.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/ui/governance";
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "audit", "Tamper-Evident Audit Explorer", facId);
        if (facId != null) {
            model.addAttribute("verification", auditService.verifyChainIntegrity(facId.toString()));
            var logs = new ArrayList<>(auditService.findFacilityLogs(facId.toString()));
            Collections.reverse(logs);
            model.addAttribute("auditLogs", logs.stream().limit(50).toList());
        } else {
            model.addAttribute("auditLogs", List.of());
        }
        return "workstation/audit";
    }

    private UUID resolveUiFacilityId() {
        Optional<UUID> active = FacilityContext.getFacilityId();
        if (active.isPresent()) {
            return active.get();
        }
        DemoHospitalDataSeeder seeder = demoSeederProvider.getIfAvailable();
        if (seeder != null && seeder.getSeededFacilityId() != null) {
            return seeder.getSeededFacilityId();
        }
        return facilityRepo.findAll().stream()
                .map(TenantFacilityEntity::getId)
                .findFirst()
                .orElse(null);
    }

    private void populateChrome(Model model, String activeNav, String pageTitle, UUID facId) {
        model.addAttribute("activeNav", activeNav);
        model.addAttribute("pageTitle", pageTitle);
        model.addAttribute("facilityId", facId);
        if (facId != null) {
            governanceService.findFacility(facId).ifPresent(f -> model.addAttribute("facility", f));
        }
    }
}
