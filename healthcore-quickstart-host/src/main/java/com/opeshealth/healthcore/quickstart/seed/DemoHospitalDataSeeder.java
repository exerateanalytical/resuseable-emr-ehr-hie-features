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

import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RecordQualityMeasureRequest;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RequestCohortExportCommand;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.EdArrivalTriageRequest;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.News2RecordingRequest;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EncounterRepository;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantOrganizationEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.*;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.EnqueueMessageRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.UpsertPartnerRequest;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodUnitEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalScope;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.*;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.ScheduleExamRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.CreateCodingRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.EligibilityAuthRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.SubmitClaimRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.UpsertTariffRequest;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceCalendarEntity;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceKind;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ScheduledAppointmentEntity;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Idempotently seeds a complete demonstration hospital ("Douala Reference Hospital")
 * across all 17 clinical, specialty, interoperability, revenue-cycle, portal, and analytics
 * consoles strictly when the {@code dev-seed} Spring profile is active. Refuses execution if
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
    private final RadiologyRisService radiologyService;
    private final EmergencyNursingService emergencyNursingService;
    private final IntegrationOpsService integrationOpsService;
    private final RevenueCodingClaimsService revenueClaimsService;
    private final HospitalAnalyticsService analyticsService;
    private final BloodUnitRepository bloodUnitRepo;
    private final SurgicalScheduleRepository surgicalScheduleRepo;

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
        this(environment, governanceService, orgRepo, facilityRepo, mpiService, patientRepo,
                schedulingService, encounterRepo, ordersResultsService, pharmacyLisService,
                billingService, portalService, null, null, null, null, null, null, null);
    }

    @Autowired
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
            PatientPortalService portalService,
            @Autowired(required = false) RadiologyRisService radiologyService,
            @Autowired(required = false) EmergencyNursingService emergencyNursingService,
            @Autowired(required = false) IntegrationOpsService integrationOpsService,
            @Autowired(required = false) RevenueCodingClaimsService revenueClaimsService,
            @Autowired(required = false) HospitalAnalyticsService analyticsService,
            @Autowired(required = false) BloodUnitRepository bloodUnitRepo,
            @Autowired(required = false) SurgicalScheduleRepository surgicalScheduleRepo) {
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
        this.radiologyService = radiologyService;
        this.emergencyNursingService = emergencyNursingService;
        this.integrationOpsService = integrationOpsService;
        this.revenueClaimsService = revenueClaimsService;
        this.analyticsService = analyticsService;
        this.bloodUnitRepo = bloodUnitRepo;
        this.surgicalScheduleRepo = surgicalScheduleRepo;
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
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "ED-ACUTE", "Emergency & Trauma Unit", "EMERGENCY", 12));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "WARD-A", "Medical Ward A", "INPATIENT_WARD", 24));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "RAD-DIAG", "Diagnostic Radiology & PACS", "RADIOLOGY", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "LAB-CORE", "Central Clinical Laboratory", "LABORATORY", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "PHARM-MAIN", "Main Hospital Pharmacy", "PHARMACY", 0));
            governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "OT-SUITE", "Operating Theater & PACU", "SURGERY", 6));

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

            // 6. Pharmacy FEFO Stock Batches & Barcoded Lab Specimen
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

            LabSpecimenEntity specimen = new LabSpecimenEntity();
            specimen.setFacilityId(facilityId);
            specimen.setPatientId(p4.getId());
            specimen.setSpecimenType("SERUM_CHEMISTRY");
            specimen.setLoincPanelCode("24323-8");
            specimen.setAccessionBarcode("SPEC-DLA-2026-01");
            pharmacyLisService.accessionSpecimen(specimen);

            // 7. Double-Entry Patient Billing Charges
            billingService.postCharge(facilityId, p1.getId(), 15000L, "XAF", "INV-DLA-0001", "Outpatient consultation + CBC");
            billingService.postCharge(facilityId, p4.getId(), 48500L, "XAF", "INV-DLA-0002", "Inpatient Ward A bed day + STAT electrolytes");

            // 8. Patient Portal Accounts, Proxy Grant & Message Thread
            portalService.enrollAccount(facilityId, new EnrollPortalAccountRequest(
                    p1.getId(), "portal-" + p1.getId(), "fr", "amina.ndiaye@example.cm", "+237690110001"));
            portalService.grantProxyAccess(facilityId, "portal-" + p1.getId(), false, new GrantProxyRequest(
                    p1.getId(), "proxy.moussa", "SPOUSE",
                    Set.of(PortalScope.APPOINTMENTS, PortalScope.RESULTS), Instant.now().plus(Duration.ofDays(30))));
            portalService.openMessageThread(facilityId, "portal-" + p1.getId(), new OpenThreadRequest(
                    p1.getId(), "Question about lab fasting", "OPD-MED",
                    "Bonjour Docteur, should I fast before my follow-up blood draw next week?"));

            // 9. Radiology Modality Worklist (if starter bean present)
            if (radiologyService != null) {
                radiologyService.scheduleImagingExam(new ScheduleExamRequest(
                        p4.getId(), enc2.getId(), "CT", "71260", "CT Chest with Contrast",
                        "THORAX", "Rule out pulmonary embolism in hypertensive inpatient",
                        "CT_AE_01", "dr.ngono", true, Instant.now().plus(Duration.ofMinutes(30))));
                radiologyService.scheduleImagingExam(new ScheduleExamRequest(
                        p1.getId(), enc1.getId(), "CR", "71046", "Chest X-Ray PA & Lateral",
                        "CHEST", "Persistent fever workup",
                        "CR_AE_01", "dr.ekane", false, Instant.now().plus(Duration.ofHours(1))));
            }

            // 10. Emergency Department Triage & NEWS2 Escalation Board
            if (emergencyNursingService != null) {
                emergencyNursingService.registerEdTriage(new EdArrivalTriageRequest(
                        p2.getId(), null, "Acute chest tightness radiating to left arm", 2,
                        "AMBULANCE", "RESUS-02", "rn.triage"));
                emergencyNursingService.recordNews2Observation(new News2RecordingRequest(
                        p4.getId(), enc2.getId(), 23, 93, true, 96, 114, 38.4, "ALERT", "rn.ward"));
            }

            // 11. Blood Bank Inventory & Operating Theater Schedule
            if (bloodUnitRepo != null) {
                BloodUnitEntity unit1 = new BloodUnitEntity();
                unit1.setFacilityId(facilityId);
                unit1.setIsbtUnitCode("W000026001001");
                unit1.setComponentType("PRBC");
                unit1.setAboRhd("O-");
                unit1.setExpiryDate(LocalDate.now().plusDays(28));
                unit1.setStatus("AVAILABLE");
                bloodUnitRepo.save(unit1);

                BloodUnitEntity unit2 = new BloodUnitEntity();
                unit2.setFacilityId(facilityId);
                unit2.setIsbtUnitCode("W000026001002");
                unit2.setComponentType("FFP");
                unit2.setAboRhd("AB+");
                unit2.setExpiryDate(LocalDate.now().plusDays(180));
                unit2.setStatus("AVAILABLE");
                bloodUnitRepo.save(unit2);
            }

            if (surgicalScheduleRepo != null) {
                SurgicalScheduleEntity surg = new SurgicalScheduleEntity();
                surg.setFacilityId(facilityId);
                surg.setPatientId(p2.getId());
                surg.setTheaterRoom("OT-01");
                surg.setProcedureName("Laparoscopic Cholecystectomy");
                surg.setLeadSurgeonId("dr.mbock");
                surg.setWhoChecklistCompleted(true);
                surg.setStatus("SCHEDULED");
                surg.setScheduledAt(Instant.now().plus(Duration.ofHours(3)));
                surgicalScheduleRepo.save(surg);
            }

            // 12. Integration Operations Partner Profiles & Message Ledger
            if (integrationOpsService != null) {
                var natHie = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                        "CM-NAT-HIE", "Cameroon National HIE Hub", "FHIR_R4",
                        "https://hie.sante.cm/fhir", "MTLS", "Patient,Observation,Bundle", 180, true), "dev-seed");
                var labHl7 = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                        "REF-LAB-MLLP", "Central Reference Lab Interface", "HL7_V2",
                        "mllp://lis.douala-ref.local:2575", "MTLS", "ORM^O01,ORU^R01", 300, true), "dev-seed");
                integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                        natHie.getId(), "DLA-FHIR-IPS-001", "Bundle",
                        "{\"resourceType\":\"Bundle\",\"type\":\"document\",\"id\":\"demo-ips-1\"}", 5), "dev-seed");
                var failedMsg = integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                        labHl7.getId(), "DLA-HL7-ORM-001", "ORM^O01",
                        "MSH|^~\\&|HIS|DLA|LIS|REF|202611200800||ORM^O01|ORM001|P|2.5.1\r", 1), "dev-seed");
                integrationOpsService.recordDeliveryFailure(failedMsg.getId(), "Initial MLLP timeout during simulated night cutover");
            }

            // 13. Revenue Tariffs, Clinical Coding & Insurance Claims
            if (revenueClaimsService != null) {
                revenueClaimsService.upsertTariff(new UpsertTariffRequest(
                        "CNPS-UHC", "CONS-SPEC", "Specialist Cardiology Consultation", "CONSULTATION",
                        25000L, "XAF", LocalDate.now().minusMonths(1), null), "dev-seed");
                revenueClaimsService.upsertTariff(new UpsertTariffRequest(
                        "CNPS-UHC", "LAB-ELEC", "Serum Electrolytes Panel STAT", "LABORATORY",
                        12000L, "XAF", LocalDate.now().minusMonths(1), null), "dev-seed");
                var coding = revenueClaimsService.createCodingAssignment(new CreateCodingRequest(
                        p4.getId(), enc2.getId(), "ICD10", "I10", "E87.5",
                        "99222,80051", "DRG-304", "coder.ngono", 48500L));
                revenueClaimsService.approveClinicalCoding(coding.getId(), "dr.ngono");
                var auth = revenueClaimsService.verifyEligibilityAndRequestAuth(new EligibilityAuthRequest(
                        p4.getId(), "CNPS-UHC", "CM-CNPS-2026-4410", "INP-WARD-A", true, 3), "dev-seed");
                revenueClaimsService.submitPayerClaim(new SubmitClaimRequest(
                        "CLM-DLA-2026-0001", coding.getId(), auth.getId(), "CNPS-UHC",
                        "FHIR_CLAIM", 48500L, "XAF"), "dev-seed");
            }

            // 14. Hospital Analytics Quality Measures & Cohort Export
            if (analyticsService != null) {
                analyticsService.recordQualityMeasure(new RecordQualityMeasureRequest(
                        "CMS165v12", "Controlling High Blood Pressure", "2026-Q4",
                        84L, 100L, 4L, "hc_emr_encounters+hc_orders", "dev-seed"));
                analyticsService.requestCohortExport(new RequestCohortExportCommand(
                        "Hypertension Outcomes Q4", "{\"icd10\":\"I10\"}", "PUBLIC_HEALTH_QUALITY",
                        true, 10, 42L, "dr.ngono"));
            }

            log.info("Seeded demonstration hospital '{}' (facilityId={}) across all 17 clinical, specialty, interoperability, revenue, portal, and analytics consoles.",
                    DEMO_FACILITY_CODE, facilityId);
            return facilityId;
        } finally {
            FacilityContext.clear();
        }
    }
}
