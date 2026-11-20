/*
 * =============================================================================
 * Opes HealthCore SDK — Data-Driven Multi-Module Workflow Test Fixture
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.fixture;

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
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodComponentUnitEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.*;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.*;
import com.opeshealth.healthcore.persistence.fixture.DataDrivenWorkflowFixtureLoader;
import com.opeshealth.healthcore.persistence.fixture.StandardWorkflowFixtureDataset;
import com.opeshealth.healthcore.persistence.fixture.StandardWorkflowFixtureDataset.*;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.MedicationBatchEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalScope;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.*;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.ScheduleExamRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.*;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceCalendarEntity;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ResourceKind;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ScheduledAppointmentEntity;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.uhc.service.UhcBillingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reusable, data-driven test fixture that automatically populates OPES HealthCore
 * core modules in topological workflow order from standardized JSON datasets
 * ({@link StandardWorkflowFixtureDataset}) and returns a {@link PopulatedWorkflowContext}
 * for end-to-end workflow assertions.
 */
@TestComponent
public class CoreModulesDataDrivenTestFixture {

    private static final String FIXTURE_ACTOR = "workflow-test-fixture";

    private final TenantGovernanceService governanceService;
    private final TenantGovernanceService.OrganizationRepository orgRepo;
    private final TenantGovernanceService.FacilityRepository facilityRepo;
    private final MpiCommandService mpiService;
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

    @Autowired
    public CoreModulesDataDrivenTestFixture(
            TenantGovernanceService governanceService,
            TenantGovernanceService.OrganizationRepository orgRepo,
            TenantGovernanceService.FacilityRepository facilityRepo,
            MpiCommandService mpiService,
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
        this.governanceService = governanceService;
        this.orgRepo = orgRepo;
        this.facilityRepo = facilityRepo;
        this.mpiService = mpiService;
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

    /**
     * Populates all wired core modules using the canonical standardized dataset at
     * {@code classpath:fixtures/healthcore-standard-workflows-dataset.json}.
     */
    public PopulatedWorkflowContext populateStandardDataset() {
        return populate(DataDrivenWorkflowFixtureLoader.loadDefault());
    }

    /**
     * Populates all wired core modules from a custom classpath JSON dataset.
     */
    public PopulatedWorkflowContext populateFromClasspath(String resourcePath) {
        return populate(DataDrivenWorkflowFixtureLoader.loadFromClasspath(resourcePath));
    }

    /**
     * Populates all wired core modules from an already-parsed {@link StandardWorkflowFixtureDataset}.
     * Automatically binds and clears {@link FacilityContext} around tenant-scoped writes.
     */
    public PopulatedWorkflowContext populate(StandardWorkflowFixtureDataset dataset) {
        Objects.requireNonNull(dataset, "dataset must not be null");
        dataset.validate();

        TenantFixture tf = dataset.tenant();
        TenantOrganizationEntity org = orgRepo.findByOrgCode(tf.orgCode())
                .orElseGet(() -> governanceService.createOrganization(new CreateOrganizationRequest(
                        tf.orgCode(), tf.orgName(), tf.orgType(), tf.countryCode())));

        TenantFacilityEntity facility = facilityRepo.findByFacilityCode(tf.facilityCode())
                .orElseGet(() -> governanceService.onboardFacility(new OnboardFacilityRequest(
                        org.getId(), tf.facilityCode(), tf.facilityName(), tf.facilityType(),
                        tf.countryCode(), tf.timezone(), tf.currency(), tf.language(), tf.active())));

        UUID facilityId = facility.getId();
        Map<String, UUID> patientIdsByMrn = new LinkedHashMap<>();
        Map<String, UUID> calendarIdsByDept = new LinkedHashMap<>();
        Map<String, UUID> encounterIdsByKey = new LinkedHashMap<>();
        Map<String, UUID> orderIdsByKey = new LinkedHashMap<>();
        Map<String, UUID> resultIdsByKey = new LinkedHashMap<>();
        Map<String, UUID> partnerIdsByCode = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        try {
            FacilityContext.setFacilityId(facilityId);

            // 1. Tenant Governance: departments & feature flags
            int deptCount = 0;
            if (tf.departments() != null) {
                for (DepartmentFixture d : tf.departments()) {
                    governanceService.createDepartment(facilityId, new CreateDepartmentRequest(
                            null, d.code(), d.name(), d.kind(), d.bedCapacity()));
                    deptCount++;
                }
            }
            counts.put("departments", deptCount);

            int flagCount = 0;
            if (tf.featureFlags() != null) {
                for (FeatureFlagFixture f : tf.featureFlags()) {
                    governanceService.upsertFeatureFlag(facilityId, f.key(), f.enabled(), f.locked(), FIXTURE_ACTOR);
                    flagCount++;
                }
            }
            counts.put("featureFlags", flagCount);

            // 2. MPI: standardized synthetic patients
            if (dataset.patients() != null) {
                for (PatientFixture p : dataset.patients()) {
                    PatientAggregateEntity saved = mpiService.registerPatient(new RegisterPatientCommand(
                            facilityId, p.mrn(), p.familyName(), p.givenName(), p.birthDate(),
                            p.sex(), p.bloodGroup(), p.phone(), p.language(),
                            p.nationalIdType(), p.nationalIdNumber(), p.city(),
                            p.emergencyContactName(), p.emergencyContactPhone()));
                    patientIdsByMrn.put(p.mrn(), saved.getId());
                }
            }
            counts.put("patients", patientIdsByMrn.size());

            // 3. Scheduling: calendars, appointment types & appointments
            int apptCount = 0;
            if (dataset.scheduling() != null) {
                if (dataset.scheduling().calendars() != null) {
                    for (CalendarFixture c : dataset.scheduling().calendars()) {
                        ResourceCalendarEntity cal = schedulingService.createCalendar(facilityId,
                                new CreateCalendarRequest(c.departmentCode(), ResourceKind.valueOf(c.resourceKind()),
                                        c.resourceCode(), c.displayName(), c.slotDurationMinutes(), c.concurrency()));
                        calendarIdsByDept.put(c.departmentCode(), cal.getId());
                    }
                }
                if (dataset.scheduling().appointmentTypes() != null) {
                    for (AppointmentTypeFixture at : dataset.scheduling().appointmentTypes()) {
                        schedulingService.createAppointmentType(facilityId, new CreateAppointmentTypeRequest(
                                at.code(), at.name(), at.specialty(), at.durationMinutes(), at.bufferMinutes()));
                    }
                }
                if (dataset.scheduling().appointments() != null) {
                    Instant base = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.HOURS);
                    for (AppointmentFixture a : dataset.scheduling().appointments()) {
                        UUID calId = calendarIdsByDept.get(a.departmentCode());
                        UUID patientId = patientIdsByMrn.get(a.patientMrn());
                        Instant start = base.plus(Duration.ofMinutes(a.offsetMinutesFromBase()));
                        ScheduledAppointmentEntity appt = schedulingService.bookAppointment(facilityId,
                                new BookAppointmentRequest(calId, null, patientId, a.appointmentTypeCode(),
                                        start, start.plus(Duration.ofMinutes(a.durationMinutes())), a.reason()),
                                FIXTURE_ACTOR);
                        if (a.checkIn()) {
                            schedulingService.checkIn(facilityId, appt.getId());
                        }
                        apptCount++;
                    }
                }
            }
            counts.put("appointments", apptCount);

            // 4. EMR Clinical: ambulatory & inpatient encounters
            if (dataset.encounters() != null) {
                for (EncounterFixture e : dataset.encounters()) {
                    EncounterEntity enc = new EncounterEntity();
                    enc.setFacilityId(facilityId);
                    enc.setPatientId(patientIdsByMrn.get(e.patientMrn()));
                    enc.setEncounterClass(e.encounterClass());
                    enc.setStatus(e.status());
                    enc.setAttendingId(e.attendingId());
                    enc.setDepartmentCode(e.departmentCode());
                    enc.setPrimaryIcd10(e.primaryIcd10());
                    EncounterEntity saved = encounterRepo.save(enc);
                    encounterIdsByKey.put(e.encounterKey(), saved.getId());
                }
            }
            counts.put("encounters", encounterIdsByKey.size());

            // 5. Unified Orders & Results: order sets, reflex rules, orders, specimens, verified results
            if (dataset.ordersAndResults() != null) {
                if (dataset.ordersAndResults().orderSets() != null) {
                    for (OrderSetFixture os : dataset.ordersAndResults().orderSets()) {
                        ordersResultsService.createOrderSet(facilityId, new CreateOrderSetRequest(
                                os.code(), os.name(), os.indication(), os.itemsSpec()));
                    }
                }
                if (dataset.ordersAndResults().reflexRules() != null) {
                    for (ReflexRuleFixture rr : dataset.ordersAndResults().reflexRules()) {
                        ordersResultsService.createReflexRule(facilityId, new CreateReflexRuleRequest(
                                rr.triggerLoinc(), AbnormalFlag.valueOf(rr.triggerFlag()),
                                OrderDomain.valueOf(rr.reflexDomain()), rr.reflexCode(),
                                rr.reflexName(), OrderPriority.valueOf(rr.reflexPriority())));
                    }
                }
                if (dataset.ordersAndResults().orders() != null) {
                    for (ClinicalOrderFixture o : dataset.ordersAndResults().orders()) {
                        UUID patientId = patientIdsByMrn.get(o.patientMrn());
                        UUID encounterId = encounterIdsByKey.get(o.encounterKey());
                        UnifiedClinicalOrderEntity order = ordersResultsService.placeOrder(facilityId,
                                new PlaceOrderRequest(patientId, encounterId, OrderDomain.valueOf(o.domain()),
                                        o.codeSystem(), o.code(), o.displayName(),
                                        OrderPriority.valueOf(o.priority()), o.clinicalNotes()),
                                o.orderingProvider());
                        orderIdsByKey.put(o.orderKey(), order.getId());

                        ordersResultsService.collectSpecimen(facilityId, order.getId(),
                                new CollectSpecimenRequest(o.accessionNumber(), o.specimenType(), "Standardized fixture sample"),
                                o.collectedBy());
                        ClinicalResultEntity res = ordersResultsService.enterResult(facilityId,
                                new EnterResultRequest(order.getId(), o.code(), o.displayName(),
                                        o.numericValue(), null, o.unit(), o.referenceRange(),
                                        AbnormalFlag.valueOf(o.abnormalFlag())),
                                o.enteredBy());
                        ordersResultsService.verifyResult(facilityId, res.getId(), o.verifiedBy());
                        resultIdsByKey.put(o.orderKey(), res.getId());
                    }
                }
            }
            counts.put("orders", orderIdsByKey.size());
            counts.put("verifiedResults", resultIdsByKey.size());

            // 6. Pharmacy FEFO Batches & Lab Specimens
            int batchCount = 0;
            int specCount = 0;
            if (dataset.pharmacyAndLab() != null) {
                if (dataset.pharmacyAndLab().medicationBatches() != null) {
                    for (MedicationBatchFixture mb : dataset.pharmacyAndLab().medicationBatches()) {
                        MedicationBatchEntity entity = new MedicationBatchEntity();
                        entity.setFacilityId(facilityId);
                        entity.setDrugCode(mb.drugCode());
                        entity.setDrugName(mb.drugName());
                        entity.setLotNumber(mb.lotNumber());
                        entity.setExpiryDate(LocalDate.now().plusDays(mb.expiryDaysFromNow()));
                        entity.setQuantityOnHand(mb.quantityOnHand());
                        entity.setReorderThreshold(mb.reorderThreshold());
                        pharmacyLisService.receiveStockBatch(entity);
                        batchCount++;
                    }
                }
                if (dataset.pharmacyAndLab().labSpecimens() != null) {
                    for (LabSpecimenFixture ls : dataset.pharmacyAndLab().labSpecimens()) {
                        LabSpecimenEntity entity = new LabSpecimenEntity();
                        entity.setFacilityId(facilityId);
                        entity.setPatientId(patientIdsByMrn.get(ls.patientMrn()));
                        entity.setSpecimenType(ls.specimenType());
                        entity.setLoincPanelCode(ls.loincPanelCode());
                        entity.setAccessionBarcode(ls.accessionBarcode());
                        pharmacyLisService.accessionSpecimen(entity);
                        specCount++;
                    }
                }
            }
            counts.put("medicationBatches", batchCount);
            counts.put("labSpecimens", specCount);

            // 7. Double-Entry Billing Charges
            int chargeCount = 0;
            if (dataset.billingCharges() != null) {
                for (BillingChargeFixture bc : dataset.billingCharges()) {
                    billingService.postCharge(facilityId, patientIdsByMrn.get(bc.patientMrn()),
                            bc.amountMinorUnits(), bc.currency(), bc.invoiceRef(), bc.description());
                    chargeCount++;
                }
            }
            counts.put("billingCharges", chargeCount);

            // 8. Patient Portal
            int portalCount = 0;
            if (dataset.patientPortal() != null && dataset.patientPortal().enrollments() != null) {
                for (PortalEnrollmentFixture pe : dataset.patientPortal().enrollments()) {
                    UUID patientId = patientIdsByMrn.get(pe.patientMrn());
                    String handle = "portal-" + patientId;
                    portalService.enrollAccount(facilityId, new EnrollPortalAccountRequest(
                            patientId, handle, pe.language(), pe.email(), pe.phone()));
                    if (pe.proxyUsername() != null && !pe.proxyUsername().isBlank()) {
                        Set<PortalScope> scopes = pe.proxyScopes().stream()
                                .map(PortalScope::valueOf)
                                .collect(Collectors.toSet());
                        portalService.grantProxyAccess(facilityId, handle, false, new GrantProxyRequest(
                                patientId, pe.proxyUsername(), pe.proxyRelationship(),
                                scopes, Instant.now().plus(Duration.ofDays(30))));
                    }
                    if (pe.threadSubject() != null && !pe.threadSubject().isBlank()) {
                        portalService.openMessageThread(facilityId, handle, new OpenThreadRequest(
                                patientId, pe.threadSubject(), pe.threadDepartment(), pe.threadBody()));
                    }
                    portalCount++;
                }
            }
            counts.put("portalEnrollments", portalCount);

            // 9. Radiology RIS (optional)
            int radCount = 0;
            if (radiologyService != null && dataset.radiologyExams() != null) {
                for (RadiologyExamFixture r : dataset.radiologyExams()) {
                    radiologyService.scheduleImagingExam(new ScheduleExamRequest(
                            patientIdsByMrn.get(r.patientMrn()), encounterIdsByKey.get(r.encounterKey()),
                            r.modality(), r.procedureCode(), r.procedureName(), r.bodyPart(),
                            r.clinicalIndication(), r.aeTitle(), r.orderingProvider(),
                            r.contrastRequired(), Instant.now().plus(Duration.ofMinutes(30))));
                    radCount++;
                }
            }
            counts.put("radiologyExams", radCount);

            // 10. Emergency & Nursing (optional)
            int edCount = 0;
            int news2Count = 0;
            if (emergencyNursingService != null && dataset.emergencyAndNursing() != null) {
                if (dataset.emergencyAndNursing().edTriages() != null) {
                    for (EdTriageFixture t : dataset.emergencyAndNursing().edTriages()) {
                        emergencyNursingService.registerEdTriage(new EdArrivalTriageRequest(
                                patientIdsByMrn.get(t.patientMrn()), null, t.chiefComplaint(),
                                t.esiLevel(), t.arrivalMode(), t.bayCode(), t.triageNurse()));
                        edCount++;
                    }
                }
                if (dataset.emergencyAndNursing().news2Observations() != null) {
                    for (News2ObservationFixture n : dataset.emergencyAndNursing().news2Observations()) {
                        emergencyNursingService.recordNews2Observation(new News2RecordingRequest(
                                patientIdsByMrn.get(n.patientMrn()), encounterIdsByKey.get(n.encounterKey()),
                                n.respiratoryRate(), n.spo2(), n.supplementalOxygen(),
                                n.systolicBp(), n.heartRate(), n.temperatureCelsius(),
                                n.consciousness(), n.recordedBy()));
                        news2Count++;
                    }
                }
            }
            counts.put("edTriages", edCount);
            counts.put("news2Observations", news2Count);

            // 11. Hospital Ops: Blood Bank & OT (optional)
            int bloodCount = 0;
            int otCount = 0;
            if (dataset.hospitalOps() != null) {
                if (bloodUnitRepo != null && dataset.hospitalOps().bloodUnits() != null) {
                    for (BloodUnitFixture bu : dataset.hospitalOps().bloodUnits()) {
                        BloodComponentUnitEntity unit = new BloodComponentUnitEntity();
                        unit.setFacilityId(facilityId);
                        unit.setIsbtUnitCode(bu.isbtUnitCode());
                        unit.setComponentType(bu.componentType());
                        unit.setAboRhd(bu.aboRhd());
                        unit.setExpiryDate(LocalDate.now().plusDays(bu.expiryDaysFromNow()));
                        unit.setStatus(bu.status());
                        bloodUnitRepo.save(unit);
                        bloodCount++;
                    }
                }
                if (surgicalScheduleRepo != null && dataset.hospitalOps().surgicalSchedules() != null) {
                    for (SurgicalScheduleFixture ss : dataset.hospitalOps().surgicalSchedules()) {
                        SurgicalScheduleEntity surg = new SurgicalScheduleEntity();
                        surg.setFacilityId(facilityId);
                        surg.setPatientId(patientIdsByMrn.get(ss.patientMrn()));
                        surg.setTheaterRoom(ss.theaterRoom());
                        surg.setProcedureName(ss.procedureName());
                        surg.setLeadSurgeonId(ss.leadSurgeonId());
                        surg.setWhoChecklistCompleted(ss.whoChecklistCompleted());
                        surg.setStatus(ss.status());
                        surg.setScheduledAt(Instant.now().plus(Duration.ofHours(3)));
                        surgicalScheduleRepo.save(surg);
                        otCount++;
                    }
                }
            }
            counts.put("bloodUnits", bloodCount);
            counts.put("surgicalSchedules", otCount);

            // 12. Integration Ops (optional)
            int msgCount = 0;
            if (integrationOpsService != null && dataset.integrationOps() != null) {
                if (dataset.integrationOps().partners() != null) {
                    for (IntegrationPartnerFixture ip : dataset.integrationOps().partners()) {
                        var saved = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                                ip.code(), ip.name(), ip.protocol(), ip.endpointUrl(), ip.authMode(),
                                ip.resourceTypes(), ip.timeoutSeconds(), ip.enabled()), FIXTURE_ACTOR);
                        partnerIdsByCode.put(ip.code(), saved.getId());
                    }
                }
                if (dataset.integrationOps().outboundMessages() != null) {
                    for (OutboundMessageFixture om : dataset.integrationOps().outboundMessages()) {
                        integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                                partnerIdsByCode.get(om.partnerCode()), om.correlationId(),
                                om.messageType(), om.payload(), om.maxRetries()), FIXTURE_ACTOR);
                        msgCount++;
                    }
                }
            }
            counts.put("integrationPartners", partnerIdsByCode.size());
            counts.put("integrationMessages", msgCount);

            // 13. Revenue Coding & Claims (optional)
            int claimCount = 0;
            if (revenueClaimsService != null && dataset.revenueAndClaims() != null) {
                if (dataset.revenueAndClaims().tariffs() != null) {
                    for (TariffFixture t : dataset.revenueAndClaims().tariffs()) {
                        revenueClaimsService.upsertTariff(new UpsertTariffRequest(
                                t.payerCode(), t.serviceCode(), t.description(), t.category(),
                                t.amountMinorUnits(), t.currency(), LocalDate.now().minusMonths(1), null),
                                FIXTURE_ACTOR);
                    }
                }
                if (dataset.revenueAndClaims().claims() != null) {
                    for (ClaimWorkflowFixture c : dataset.revenueAndClaims().claims()) {
                        UUID patientId = patientIdsByMrn.get(c.patientMrn());
                        UUID encounterId = encounterIdsByKey.get(c.encounterKey());
                        var coding = revenueClaimsService.createCodingAssignment(new CreateCodingRequest(
                                patientId, encounterId, c.codeSystem(), c.primaryCode(), c.secondaryCodes(),
                                c.procedureCodes(), c.drgCode(), c.coderId(), c.billedAmountMinorUnits()));
                        revenueClaimsService.approveClinicalCoding(coding.getId(), c.approverId());
                        var auth = revenueClaimsService.verifyEligibilityAndRequestAuth(new EligibilityAuthRequest(
                                patientId, c.payerCode(), c.memberNumber(), c.serviceCode(),
                                c.inNetwork(), c.approvedDays()), FIXTURE_ACTOR);
                        revenueClaimsService.submitPayerClaim(new SubmitClaimRequest(
                                c.claimNumber(), coding.getId(), auth.getId(), c.payerCode(),
                                c.claimFormat(), c.billedAmountMinorUnits(), c.currency()), FIXTURE_ACTOR);
                        claimCount++;
                    }
                }
            }
            counts.put("claims", claimCount);

            // 14. Analytics (optional)
            int measureCount = 0;
            int cohortCount = 0;
            if (analyticsService != null && dataset.analytics() != null) {
                if (dataset.analytics().qualityMeasures() != null) {
                    for (QualityMeasureFixture qm : dataset.analytics().qualityMeasures()) {
                        analyticsService.recordQualityMeasure(new RecordQualityMeasureRequest(
                                qm.measureCode(), qm.title(), qm.reportingPeriod(),
                                qm.numerator(), qm.denominator(), qm.exclusions(),
                                qm.sourceTables(), FIXTURE_ACTOR));
                        measureCount++;
                    }
                }
                if (dataset.analytics().cohortExports() != null) {
                    for (CohortExportFixture ce : dataset.analytics().cohortExports()) {
                        analyticsService.requestCohortExport(new RequestCohortExportCommand(
                                ce.cohortName(), ce.filterJson(), ce.purpose(),
                                ce.deidentified(), ce.kAnonymityThreshold(),
                                ce.estimatedRecordCount(), ce.requestedBy()));
                        cohortCount++;
                    }
                }
            }
            counts.put("qualityMeasures", measureCount);
            counts.put("cohortExports", cohortCount);

            return new PopulatedWorkflowContext(
                    dataset.datasetId(),
                    dataset.version(),
                    org.getId(),
                    facilityId,
                    Collections.unmodifiableMap(patientIdsByMrn),
                    Collections.unmodifiableMap(calendarIdsByDept),
                    Collections.unmodifiableMap(encounterIdsByKey),
                    Collections.unmodifiableMap(orderIdsByKey),
                    Collections.unmodifiableMap(resultIdsByKey),
                    Collections.unmodifiableMap(partnerIdsByCode),
                    Collections.unmodifiableMap(counts)
            );
        } finally {
            FacilityContext.clear();
        }
    }

    public record PopulatedWorkflowContext(
            String datasetId,
            String datasetVersion,
            UUID organizationId,
            UUID facilityId,
            Map<String, UUID> patientIdsByMrn,
            Map<String, UUID> calendarIdsByDept,
            Map<String, UUID> encounterIdsByKey,
            Map<String, UUID> orderIdsByKey,
            Map<String, UUID> resultIdsByKey,
            Map<String, UUID> partnerIdsByCode,
            Map<String, Integer> populatedCounts
    ) {
        /**
         * Asserts that every core documented workflow section populated at least one record.
         */
        public void assertCoreWorkflowsPopulated() {
            Objects.requireNonNull(organizationId, "organizationId must be populated");
            Objects.requireNonNull(facilityId, "facilityId must be populated");
            for (String required : List.of("departments", "patients", "appointments", "encounters",
                    "orders", "verifiedResults", "medicationBatches", "billingCharges", "portalEnrollments")) {
                int count = populatedCounts.getOrDefault(required, 0);
                if (count <= 0) {
                    throw new IllegalStateException("Expected populated workflow records for '" + required + "', found 0");
                }
            }
        }
    }
}
