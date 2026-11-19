/*
 * =============================================================================
 * Opes HealthCore SDK — Development Demonstration Hospital Seeder (dev-seed)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.seed;

import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EncounterRepository;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantOrganizationEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.*;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalScope;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.*;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceCalendarEntity;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceKind;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ScheduledAppointmentEntity;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Idempotently seeds a complete demonstration hospital ("Douala Reference Hospital")
 * strictly when the {@code dev-seed} Spring profile is active. Refuses execution if
 * {@code prod} or {@code production} is also active.
 */
@Component
@Profile("dev-seed")
public class DemoHospitalDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoHospitalDataSeeder.class);

    public static final String DEMO_ORG_CODE = "CM-RHN-001";
    public static final String DEMO_FACILITY_CODE = "DLA-REF-001";

    private final Environment environment;
    private final TenantGovernanceService governanceService;
    private final TenantGovernanceService.OrganizationRepository orgRepo;
    private final TenantGovernanceService.FacilityRepository facilityRepo;
    private final MpiCommandService mpiService;
    private final PatientRepository patientRepo;
    private final SchedulingService schedulingService;
    private final EncounterRepository encounterRepo;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final PharmacyLisService pharmacyLisService;
    private final UhcBillingService billingService;
    private final PatientPortalService portalService;

    private final AtomicReference<UUID> seededFacilityId = new AtomicReference<>();

    public DemoHospitalDataSeeder(
            Environment environment,
            TenantGovernanceService governanceService,
            TenantGovernanceService.OrganizationRepository orgRepo,
            TenantGovernanceService.FacilityRepository facilityRepo,
            MpiCommandService mpiService,
            PatientRepository patientRepo,
            SchedulingService schedulingService,
            EncounterRepository encounterRepo,
            UnifiedOrdersResultsService ordersResultsService,
            PharmacyLisService pharmacyLisService,
            UhcBillingService billingService,
            PatientPortalService portalService) {
        this.environment = environment;
        this.governanceService = governanceService;
        this.orgRepo = orgRepo;
        this.facilityRepo = facilityRepo;
        this.mpiService = mpiService;
        this.patientRepo = patientRepo;
        this.schedulingService = schedulingService;
        this.encounterRepo = encounterRepo;
        this.ordersResultsService = ordersResultsService;
        this.pharmacyLisService = pharmacyLisService;
        this.billingService = billingService;
        this.portalService = portalService;
    }

    public UUID getSeededFacilityId() {
        UUID cached = seededFacilityId.get();
        if (cached != null) {
            return cached;
        }
        return facilityRepo.findByFacilityCode(DEMO_FACILITY_CODE)
                .map(TenantFacilityEntity::getId)
                .orElse(null);
    }

    @Override
    public void run(ApplicationArguments args) {
        seedDemoHospital();
    }

    public UUID seedDemoHospital() {
        boolean prodActive = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
        if (prodActive) {
            throw new IllegalStateException(
                    "DemoHospitalDataSeeder cannot execute while 'prod' or 'production' profile is active.");
        }

        TenantOrganizationEntity org = orgRepo.findByOrgCode(DEMO_ORG_CODE)
                .orElseGet(() -> governanceService.createOrganization(new CreateOrganizationRequest(
                        DEMO_ORG_CODE, "Cameroon Reference Health Network", "HOSPITAL_NETWORK", "CM")));

        TenantFacilityEntity facility = facilityRepo.findByFacilityCode(DEMO_FACILITY_CODE)
                .orElseGet(() -> governanceService.onboardFacility(new OnboardFacilityRequest(
                        org.getId(), DEMO_FACILITY_CODE, "Douala Reference Hospital",
                        "TERTIARY_HOSPITAL", "CM", "Africa/Douala", "XAF", "fr", true)));

        UUID facilityId = facility.getId();
        seededFacilityId.set(facilityId);

        // Idempotent guard: skip clinical fixture creation if already seeded
        if (!patientRepo.findByFacilityId(facilityId).isEmpty()) {
            log.info("Demo hospital '{}' (facilityId={}) already seeded; skipping duplicate fixtures.",
                    DEMO_FACILITY_CODE, facilityId);
            return facilityId;
        }

        try {
            FacilityContext.setFacilityId(facilityId);

            // 1. Departments & feature flags
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "OPD-MED", "Outpatient Internal Medicine", "OUTPATIENT", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "OPD-CARDIO", "Cardiology Clinic", "OUTPATIENT", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "WARD-A", "Medical Ward A", "INPATIENT_WARD", 24));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "LAB-CORE", "Central Clinical Laboratory", "LABORATORY", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "PHARM-MAIN", "Main Hospital Pharmacy", "PHARMACY", 0));

            governanceService.upsertFeatureFlag(facilityId, "scheduling.waitlist", true, false, "dev-seed");
            governanceService.upsertFeatureFlag(facilityId, "orders.reflex.engine", true, false, "dev-seed");
            governanceService.upsertFeatureFlag(facilityId, "portal.patient.messaging", true, false, "dev-seed");
            governanceService.upsertFeatureFlag(facilityId, "clinical.ai.coding", false, false, "dev-seed");

            // 2. Patients (MPI)
            PatientAggregateEntity p1 = mpiService.registerPatient(new RegisterPatientCommand(
                    facilityId, "DLA-001001", "Ndiaye", "Amina", LocalDate.of(1988, 4, 14),
                    "female", "O+", "+237690110001", "fr", "CM-CNI", "CM19880414001",
                    "Douala", "Moussa Ndiaye", "+237690110099"));
            PatientAggregateEntity p2 = mpiService.registerPatient(new RegisterPatientCommand(
                    facilityId, "DLA-001002", "Mbarga", "Paul", LocalDate.of(1972, 9, 3),
                    "male", "A+", "+237690110002", "fr", "CM-CNI", "CM19720903002",
                    "Douala", "Claire Mbarga", "+237690110098"));
            PatientAggregateEntity p3 = mpiService.registerPatient(new RegisterPatientCommand(
                    facilityId, "DLA-001003", "Tchoumi", "Grace", LocalDate.of(1995, 11, 21),
                    "female", "B+", "+237690110003", "en", "CM-CNI", "CM19951121003",
                    "Buea", "Samuel Tchoumi", "+237690110097"));
            PatientAggregateEntity p4 = mpiService.registerPatient(new RegisterPatientCommand(
                    facilityId, "DLA-001004", "Fotso", "Jean-Luc", LocalDate.of(1961, 2, 19),
                    "male", "AB+", "+237690110004", "fr", "CM-CNI", "CM19610219004",
                    "Douala", "Marie Fotso", "+237690110096"));

            // 3. Calendars, Appointment Types & Appointments
            ResourceCalendarEntity calMed = schedulingService.createCalendar(facilityId, new CreateCalendarRequest(
                    "OPD-MED", ResourceKind.PROVIDER, "dr.ekane", "Dr. Ekane — Internal Medicine", 20, 1));
            ResourceCalendarEntity calCardio = schedulingService.createCalendar(facilityId, new CreateCalendarRequest(
                    "OPD-CARDIO", ResourceKind.PROVIDER, "dr.ngono", "Dr. Ngono — Cardiology", 30, 1));
            schedulingService.createAppointmentType(facilityId, new CreateAppointmentTypeRequest(
                    "CONSULT", "General Consultation", "INTERNAL_MED", 20, 0));
            schedulingService.createAppointmentType(facilityId, new CreateAppointmentTypeRequest(
                    "CARDIO_FU", "Cardiology Follow-Up", "CARDIO", 30, 5));

            Instant base = Instant.now().plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            ScheduledAppointmentEntity appt1 = schedulingService.bookAppointment(facilityId, new BookAppointmentRequest(
                    calMed.getId(), null, p1.getId(), "CONSULT", base, base.plus(Duration.ofMinutes(20)),
                    "Persistent fever & fatigue"), "dev-seed");
            schedulingService.checkIn(facilityId, appt1.getId());
            schedulingService.bookAppointment(facilityId, new BookAppointmentRequest(
                    calCardio.getId(), null, p2.getId(), "CARDIO_FU", base.plus(Duration.ofHours(1)), base.plus(Duration.ofMinutes(90)),
                    "Hypertension & renal electrolyte review"), "dev-seed");
            schedulingService.bookAppointment(facilityId, new BookAppointmentRequest(
                    calMed.getId(), null, p3.getId(), "CONSULT", base.plus(Duration.ofHours(2)), base.plus(Duration.ofMinutes(140)),
                    "Antenatal routine review"), "dev-seed");

            // 4. Encounters
            EncounterEntity enc1 = new EncounterEntity();
            enc1.setFacilityId(facilityId);
            enc1.setPatientId(p1.getId());
            enc1.setEncounterClass("AMB");
            enc1.setStatus("IN_PROGRESS");
            enc1.setAttendingId("dr.ekane");
            enc1.setDepartmentCode("OPD-MED");
            enc1.setPrimaryIcd10("R50.9");
            encounterRepo.save(enc1);

            EncounterEntity enc2 = new EncounterEntity();
            enc2.setFacilityId(facilityId);
            enc2.setPatientId(p4.getId());
            enc2.setEncounterClass("IMP");
            enc2.setStatus("IN_PROGRESS");
            enc2.setAttendingId("dr.ngono");
            enc2.setDepartmentCode("WARD-A");
            enc2.setPrimaryIcd10("I10");
            encounterRepo.save(enc2);

            // 5. Order Set, Reflex Rule, Unified Orders & Critical Result
            ordersResultsService.createOrderSet(facilityId, new CreateOrderSetRequest(
                    "FEVER-WORKUP", "Acute Febrile Illness Panel", "Suspected malaria / bacterial infection",
                    "LABORATORY|58410-2|Complete Blood Count (CBC)|URGENT;LABORATORY|32700-7|Malaria Blood Smear|STAT"));
            ordersResultsService.createReflexRule(facilityId, new CreateReflexRuleRequest(
                    "2823-3", AbnormalFlag.CRITICAL_HIGH, OrderDomain.PROCEDURE,
                    "11524-6", "12-Lead Electrocardiogram STAT", OrderPriority.STAT));

            UnifiedClinicalOrderEntity cbcOrder = ordersResultsService.placeOrder(facilityId, new PlaceOrderRequest(
                    p1.getId(), enc1.getId(), OrderDomain.LABORATORY, "http://loinc.org",
                    "718-7", "Hemoglobin [Mass/volume] in Blood", OrderPriority.ROUTINE, "Baseline CBC"), "dr.ekane");
            ordersResultsService.collectSpecimen(facilityId, cbcOrder.getId(),
                    new CollectSpecimenRequest("ACC-DLA-1001", "BLOOD_EDTA", "Good sample"), "nurse.biya");
            ClinicalResultEntity hbRes = ordersResultsService.enterResult(facilityId, new EnterResultRequest(
                    cbcOrder.getId(), "718-7", "Hemoglobin", new BigDecimal("12.6"), null,
                    "g/dL", "12.0-15.5", AbnormalFlag.NORMAL), "lab.scientist1");
            ordersResultsService.verifyResult(facilityId, hbRes.getId(), "lab.director");

            UnifiedClinicalOrderEntity kOrder = ordersResultsService.placeOrder(facilityId, new PlaceOrderRequest(
                    p4.getId(), enc2.getId(), OrderDomain.LABORATORY, "http://loinc.org",
                    "2823-3", "Serum Potassium", OrderPriority.STAT, "Inpatient ACEi monitoring"), "dr.ngono");
            ordersResultsService.collectSpecimen(facilityId, kOrder.getId(),
                    new CollectSpecimenRequest("ACC-DLA-1002", "SERUM", "Non-hemolyzed"), "nurse.biya");
            ClinicalResultEntity kRes = ordersResultsService.enterResult(facilityId, new EnterResultRequest(
                    kOrder.getId(), "2823-3", "Serum Potassium", new BigDecimal("6.4"), null,
                    "mmol/L", "3.5-5.1", AbnormalFlag.CRITICAL_HIGH), "lab.scientist1");
            ordersResultsService.verifyResult(facilityId, kRes.getId(), "lab.director");

            // 6. Pharmacy FEFO Stock Batches
            MedicationBatchEntity amox = new MedicationBatchEntity();
            amox.setFacilityId(facilityId);
            amox.setDrugCode("RX-AMOX-500");
            amox.setDrugName("Amoxicillin 500mg Capsule");
            amox.setLotNumber("LOT-CM-2026-A");
            amox.setExpiryDate(LocalDate.now().plusMonths(10));
            amox.setQuantityOnHand(240);
            amox.setReorderThreshold(50);
            pharmacyLisService.receiveStockBatch(amox);

            MedicationBatchEntity amlo = new MedicationBatchEntity();
            amlo.setFacilityId(facilityId);
            amlo.setDrugCode("RX-AMLO-5");
            amlo.setDrugName("Amlodipine 5mg Tablet");
            amlo.setLotNumber("LOT-CM-2026-B");
            amlo.setExpiryDate(LocalDate.now().plusMonths(14));
            amlo.setQuantityOnHand(180);
            amlo.setReorderThreshold(40);
            pharmacyLisService.receiveStockBatch(amlo);

            // 7. Double-Entry Patient Billing Charges
            billingService.postCharge(facilityId, p1.getId(), 15000L, "XAF", "INV-DLA-0001", "Outpatient consultation + CBC");
            billingService.postCharge(facilityId, p4.getId(), 48500L, "XAF", "INV-DLA-0002", "Inpatient Ward A bed day + STAT electrolytes");

            // 8. Patient Portal Accounts, Proxy Grant & Message Thread
            portalService.enrollAccount(facilityId, new EnrollPortalAccountRequest(
                    p1.getId(), "patient.amina", "fr", "amina.ndiaye@example.cm", "+237690110001"));
            portalService.grantProxyAccess(facilityId, "patient.amina", false, new GrantProxyRequest(
                    p1.getId(), "proxy.moussa", "SPOUSE",
                    Set.of(PortalScope.APPOINTMENTS, PortalScope.RESULTS), Instant.now().plus(Duration.ofDays(30))));
            portalService.openMessageThread(facilityId, "patient.amina", new OpenThreadRequest(
                    p1.getId(), "Question about lab fasting", "OPD-MED",
                    "Bonjour Docteur, should I fast before my follow-up blood draw next week?"));

            log.info("Seeded demonstration hospital '{}' (facilityId={}) with patients, schedules, orders, stock, ledger, and portal fixtures.",
                    DEMO_FACILITY_CODE, facilityId);
            return facilityId;
        } finally {
            FacilityContext.clear();
        }
    }
}
