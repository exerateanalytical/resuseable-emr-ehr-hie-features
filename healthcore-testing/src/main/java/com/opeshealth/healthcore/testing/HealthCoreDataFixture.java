/*
 * =============================================================================
 * Opes HealthCore SDK — Centralized ISO/HL7 Data Fixture for Isolated Testing
 * =============================================================================
 */
package com.opeshealth.healthcore.testing;

import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.TokenUsage;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.ClinicalAiDraftRecord;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RecordQualityMeasureRequest;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RequestCohortExportCommand;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.DischargeReadinessRequest;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.EdArrivalTriageRequest;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.News2RecordingRequest;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.emr.service.EmrClinicalWorkflowService.EncounterRepository;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantOrganizationEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.CreateDepartmentRequest;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.CreateOrganizationRequest;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService.OnboardFacilityRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.EnqueueMessageRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.RecordFailureRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.ReplayRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.UpsertPartnerRequest;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BloodComponentUnitEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.SurgicalScheduleEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.AbnormalFlag;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.ClinicalResultEntity;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.OrderDomain;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.OrderPriority;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.UnifiedClinicalOrderEntity;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.CollectSpecimenRequest;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.CreateReflexRuleRequest;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.EnterResultRequest;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService.PlaceOrderRequest;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalScope;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.EnrollPortalAccountRequest;
import com.opeshealth.healthcore.portal.service.PatientPortalService.GrantProxyRequest;
import com.opeshealth.healthcore.portal.service.PatientPortalService.OpenThreadRequest;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.AcknowledgeCriticalRequest;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.MppsUpdateRequest;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.ScheduleExamRequest;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService.SignRadiologyReportRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.CreateCodingRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.EligibilityAuthRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.PostEraRemittanceRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.SubmitClaimRequest;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService.UpsertTariffRequest;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.starters.BloodBankStarter;
import com.opeshealth.healthcore.starters.SurgeryOtStarter;
import com.opeshealth.healthcore.testing.data.IsoHl7StandardSampleData;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Centralized, ISO/HL7-compliant database fixture engine supporting both:
 * <ol>
 *   <li><b>Strict Isolated Seeding</b> — build with only a single starter or console
 *       (via {@link #builder()}) to verify its documented workflows without pulling or
 *       initializing unrelated hospital modules.</li>
 *   <li><b>Full Reference Hospital Seeding</b> — invoke {@link #seedAllReferenceConsolesAndStarters()}
 *       to seed all 8 Reference Hospital consoles and both new backend starters
 *       ({@code healthcore-starter-integration-ops} and {@code healthcore-starter-revenue-coding-claims})
 *       with deterministic, zero-PII ISO/HL7 sample records.</li>
 * </ol>
 */
public class HealthCoreDataFixture {

    private static final String FIXTURE_ACTOR = "healthcore-data-fixture";

    private final TenantGovernanceService governanceService;
    private final TenantGovernanceService.OrganizationRepository orgRepo;
    private final TenantGovernanceService.FacilityRepository facilityRepo;
    private final MpiCommandService mpiService;
    private final EncounterRepository encounterRepo;
    private final RadiologyRisService radiologyService;
    private final EmergencyNursingService emergencyNursingService;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final PharmacyLisService pharmacyLisService;
    private final BloodBankStarter.BloodBankHemovigilanceService bloodBankService;
    private final BloodUnitRepository bloodUnitRepo;
    private final SurgeryOtStarter.SurgicalPerioperativeService surgeryService;
    private final SurgicalScheduleRepository surgicalScheduleRepo;
    private final PatientPortalService portalService;
    private final IntegrationOpsService integrationOpsService;
    private final ClinicalAiGovernanceService aiGovernanceService;
    private final HospitalAnalyticsService analyticsService;
    private final RevenueCodingClaimsService revenueClaimsService;

    private HealthCoreDataFixture(Builder b) {
        this.governanceService = b.governanceService;
        this.orgRepo = b.orgRepo;
        this.facilityRepo = b.facilityRepo;
        this.mpiService = b.mpiService;
        this.encounterRepo = b.encounterRepo;
        this.radiologyService = b.radiologyService;
        this.emergencyNursingService = b.emergencyNursingService;
        this.ordersResultsService = b.ordersResultsService;
        this.pharmacyLisService = b.pharmacyLisService;
        this.bloodBankService = b.bloodBankService;
        this.bloodUnitRepo = b.bloodUnitRepo;
        this.surgeryService = b.surgeryService;
        this.surgicalScheduleRepo = b.surgicalScheduleRepo;
        this.portalService = b.portalService;
        this.integrationOpsService = b.integrationOpsService;
        this.aiGovernanceService = b.aiGovernanceService;
        this.analyticsService = b.analyticsService;
        this.revenueClaimsService = b.revenueClaimsService;
    }

    public static Builder builder() {
        return new Builder();
    }

    // =========================================================================
    // 1. ISOLATED CONSOLE & STARTER SEEDING METHODS
    // =========================================================================

    /**
     * Verifies and initializes an isolated ISO/HL7-compliant tenant context for any of the
     * 8 OPES HealthCore Industry Capability Packs without requiring unrelated hospital starters
     * on the runtime classpath.
     */
    public ConsoleSeedSummary initializeIsolatedPackContext(String packId, UUID facilityId) {
        Objects.requireNonNull(packId, "packId is required");
        Objects.requireNonNull(facilityId, "facilityId is required");
        return withinFacility(facilityId, () -> {
            UUID active = FacilityContext.requireFacilityId();
            if (!facilityId.equals(active)) {
                throw new IllegalStateException("FacilityContext mismatch for isolated pack " + packId);
            }
            if (!"CM".equals(IsoHl7StandardSampleData.ISO_COUNTRY_CM)
                    || !"XAF".equals(IsoHl7StandardSampleData.ISO_CURRENCY_XAF)
                    || !IsoHl7StandardSampleData.FHIR_R4_IPS_BUNDLE_JSON.contains("\"resourceType\":\"Bundle\"")
                    || !IsoHl7StandardSampleData.HL7_V251_ORM_O01_MESSAGE.startsWith("MSH|")) {
                throw new IllegalStateException("ISO/HL7 sample data invariants failed for pack " + packId);
            }
            return new ConsoleSeedSummary(packId, List.of(facilityId), 1);
        });
    }

    /**
     * Console 1 — Radiology &amp; PACS Modality Worklist (DICOM CT + MPPS + Critical Report + Read-back).
     */
    public ConsoleSeedSummary seedRadiologyConsole(UUID facilityId, UUID patientId, UUID encounterId) {
        Objects.requireNonNull(radiologyService, "radiologyService is required for Radiology console seeding");
        return withinFacility(facilityId, () -> RadiologySeeder.seed(radiologyService, patientId, encounterId));
    }

    /**
     * Console 2 — Emergency Triage (ESI-2), Inpatient NEWS2 Escalation &amp; Discharge Readiness Gate.
     */
    public ConsoleSeedSummary seedEmergencyNursingConsole(UUID facilityId, UUID patientId, UUID encounterId) {
        Objects.requireNonNull(emergencyNursingService, "emergencyNursingService is required for Emergency/Nursing seeding");
        return withinFacility(facilityId, () -> EmergencyNursingSeeder.seed(emergencyNursingService, patientId, encounterId));
    }

    /**
     * Console 3 — Laboratory Accession, Reflex Rule &amp; LOINC Critical-Value Verification.
     */
    public ConsoleSeedSummary seedLaboratoryConsole(UUID facilityId, UUID patientId, UUID encounterId) {
        Objects.requireNonNull(ordersResultsService, "ordersResultsService is required for Laboratory console seeding");
        Objects.requireNonNull(pharmacyLisService, "pharmacyLisService is required for Laboratory specimen accessioning");
        return withinFacility(facilityId, () -> LaboratorySeeder.seed(ordersResultsService, pharmacyLisService, facilityId, patientId, encounterId));
    }

    /**
     * Console 4 — Blood Bank Hemovigilance (ISBT-128) &amp; Operating Theater / PACU Aldrete Assessment.
     */
    public ConsoleSeedSummary seedBloodBankAndOtConsole(UUID facilityId, UUID patientId) {
        Objects.requireNonNull(bloodUnitRepo, "bloodUnitRepo is required for Blood Bank/OT console seeding");
        Objects.requireNonNull(surgicalScheduleRepo, "surgicalScheduleRepo is required for Blood Bank/OT console seeding");
        return withinFacility(facilityId, () -> BloodBankOtSeeder.seed(bloodBankService, bloodUnitRepo, surgeryService, surgicalScheduleRepo, facilityId, patientId));
    }

    /**
     * Console 5 — Patient Portal Enrollment (ISO 639-1), Scoped Proxy Grant &amp; Care-Team Thread.
     */
    public ConsoleSeedSummary seedPatientPortalConsole(UUID facilityId, UUID patientId) {
        Objects.requireNonNull(portalService, "portalService is required for Patient Portal console seeding");
        return withinFacility(facilityId, () -> PatientPortalSeeder.seed(portalService, facilityId, patientId));
    }

    /**
     * Console 6 / New Backend Starter #1 — Integration Operations &amp; DLQ Replay
     * ({@code healthcore-starter-integration-ops}) using HL7 FHIR R4 &amp; HL7 v2.5.1 payloads.
     */
    public ConsoleSeedSummary seedIntegrationOpsStarter(UUID facilityId) {
        Objects.requireNonNull(integrationOpsService, "integrationOpsService is required for Integration Ops seeding");
        return withinFacility(facilityId, () -> IntegrationOpsSeeder.seed(integrationOpsService));
    }

    /**
     * Console 7 — Governed Clinical AI Workbench (Tenant Policy + SHA-256 Draft + Clinician Attestation).
     */
    public ConsoleSeedSummary seedClinicalAiConsole(UUID facilityId, UUID patientId) {
        Objects.requireNonNull(aiGovernanceService, "aiGovernanceService is required for Clinical AI console seeding");
        return withinFacility(facilityId, () -> ClinicalAiSeeder.seed(aiGovernanceService, facilityId, patientId));
    }

    /**
     * Console 8 — Hospital Analytics, CMS eCQM Quality Measures &amp; k-Anonymized Cohort Export.
     */
    public ConsoleSeedSummary seedAnalyticsConsole(UUID facilityId) {
        Objects.requireNonNull(analyticsService, "analyticsService is required for Analytics console seeding");
        return withinFacility(facilityId, () -> AnalyticsSeeder.seed(analyticsService));
    }

    /**
     * Console 9 / New Backend Starter #2 — Tariffs, Clinical Coding &amp; Payer Claims
     * ({@code healthcore-starter-revenue-coding-claims}) using ISO 4217 {@code XAF},
     * ICD-10/CPT/DRG, X12 837P / FHIR Claim &amp; X12 835 ERA remittance.
     */
    public ConsoleSeedSummary seedRevenueCodingClaimsStarter(UUID facilityId, UUID patientId, UUID encounterId) {
        Objects.requireNonNull(revenueClaimsService, "revenueClaimsService is required for Revenue Coding/Claims seeding");
        return withinFacility(facilityId, () -> RevenueCodingClaimsSeeder.seed(revenueClaimsService, patientId, encounterId));
    }

    // =========================================================================
    // 2. FULL REFERENCE HOSPITAL SUITE SEEDING (ALL 8 CONSOLES + 2 STARTERS)
    // =========================================================================

    /**
     * Seeds tenant governance, HL7 FHIR R4 patients, EMR encounters, all 8 Reference
     * Hospital consoles, and both new backend starters in topological order.
     */
    public SeededHospitalFixtureResult seedAllReferenceConsolesAndStarters() {
        Objects.requireNonNull(governanceService, "governanceService is required for full hospital seeding");
        Objects.requireNonNull(orgRepo, "orgRepo is required for full hospital seeding");
        Objects.requireNonNull(facilityRepo, "facilityRepo is required for full hospital seeding");
        Objects.requireNonNull(mpiService, "mpiService is required for full hospital seeding");
        Objects.requireNonNull(encounterRepo, "encounterRepo is required for full hospital seeding");
        return FullHospitalSeeder.seed(this);
    }

    public <T> T withinFacility(UUID facilityId, Supplier<T> action) {
        UUID previous = FacilityContext.getFacilityId().orElse(null);
        try {
            FacilityContext.setFacilityId(facilityId);
            return action.get();
        } finally {
            if (previous != null) {
                FacilityContext.setFacilityId(previous);
            } else {
                FacilityContext.clear();
            }
        }
    }

    // =========================================================================
    // 3. LAZILY-LINKED PER-DOMAIN SEEDERS (PREVENT NoClassDefFoundError IN ISOLATED PACKS)
    // =========================================================================

    private static final class RadiologySeeder {
        static ConsoleSeedSummary seed(RadiologyRisService radiologyService, UUID patientId, UUID encounterId) {
            var exam = radiologyService.scheduleImagingExam(new ScheduleExamRequest(
                    patientId, encounterId, "CT", IsoHl7StandardSampleData.CPT_CT_CHEST_CONTRAST,
                    "CT Chest with Contrast", "THORAX",
                    "Rule out acute pulmonary embolism (ISO/DICOM fixture)",
                    "SYN_CT_01", "dr.iso.ngono", true, IsoHl7StandardSampleData.DICOM_STUDY_INSTANCE_UID));
            radiologyService.updateMpps(exam.getId(), new MppsUpdateRequest("COMPLETED", "tech.iso.rad", 3, 128));
            var report = radiologyService.signReport(new SignRadiologyReportRequest(
                    exam.getId(),
                    "Filling defect identified in right main pulmonary artery.",
                    "Acute right pulmonary embolism — urgent clinical correlation.",
                    "dr.iso.radiologist", true, "ACUTE_PE", null));
            radiologyService.acknowledgeCriticalFinding(report.getId(),
                    new AcknowledgeCriticalRequest("dr.iso.ngono", "Verbal read-back documented per ISO/ACR closed-loop policy"));
            return new ConsoleSeedSummary("radiology", List.of(exam.getId(), report.getId()), 2);
        }
    }

    private static final class EmergencyNursingSeeder {
        static ConsoleSeedSummary seed(EmergencyNursingService emergencyNursingService, UUID patientId, UUID encounterId) {
            var triage = emergencyNursingService.registerEdTriage(new EdArrivalTriageRequest(
                    patientId, encounterId, "Acute substernal chest tightness", 2,
                    "AMBULANCE", "RESUS-02", "rn.iso.triage"));
            var news2 = emergencyNursingService.recordNews2Observation(new News2RecordingRequest(
                    patientId, encounterId, 23, 93, true, 96, 114, 38.4, "ALERT", "rn.iso.ward"));
            var gate = emergencyNursingService.updateDischargeReadiness(new DischargeReadinessRequest(
                    patientId, encounterId, true, true, true, true, true, "rn.iso.charge"));
            return new ConsoleSeedSummary("emergency-nursing", List.of(triage.getId(), news2.getId(), gate.getId()), 3);
        }
    }

    private static final class LaboratorySeeder {
        static ConsoleSeedSummary seed(UnifiedOrdersResultsService ordersResultsService, PharmacyLisService pharmacyLisService,
                                       UUID facilityId, UUID patientId, UUID encounterId) {
            ordersResultsService.createReflexRule(facilityId, new CreateReflexRuleRequest(
                    IsoHl7StandardSampleData.LOINC_POTASSIUM, AbnormalFlag.CRITICAL_HIGH,
                    OrderDomain.PROCEDURE, IsoHl7StandardSampleData.LOINC_ECG_12_LEAD,
                    "12-Lead Electrocardiogram STAT", OrderPriority.STAT));
            UnifiedClinicalOrderEntity order = ordersResultsService.placeOrder(facilityId, new PlaceOrderRequest(
                    patientId, encounterId, OrderDomain.LABORATORY, IsoHl7StandardSampleData.LOINC_SYSTEM,
                    IsoHl7StandardSampleData.LOINC_POTASSIUM, "Serum Potassium", OrderPriority.STAT,
                    "ISO/LOINC critical-value workflow verification"), "dr.iso.ngono");
            ordersResultsService.collectSpecimen(facilityId, order.getId(),
                    new CollectSpecimenRequest("ISO-ACC-2823", "SERUM", "Non-hemolyzed serum"), "rn.iso.phleb");
            ClinicalResultEntity result = ordersResultsService.enterResult(facilityId, new EnterResultRequest(
                    order.getId(), IsoHl7StandardSampleData.LOINC_POTASSIUM, "Serum Potassium",
                    IsoHl7StandardSampleData.CRITICAL_POTASSIUM_VALUE, null,
                    IsoHl7StandardSampleData.UCUM_MMOL_L, IsoHl7StandardSampleData.POTASSIUM_REF_RANGE,
                    AbnormalFlag.CRITICAL_HIGH), "lab.iso.tech");
            ordersResultsService.verifyResult(facilityId, result.getId(), "lab.iso.director");

            LabSpecimenEntity spec = new LabSpecimenEntity();
            spec.setFacilityId(facilityId);
            spec.setPatientId(patientId);
            spec.setLabOrderId(order.getId());
            spec.setSpecimenType("SERUM_CHEMISTRY");
            spec.setAccessionBarcode("ISO-SPEC-24323");
            LabSpecimenEntity savedSpec = pharmacyLisService.accessionSpecimen(spec);
            return new ConsoleSeedSummary("laboratory", List.of(order.getId(), result.getId(), savedSpec.getId()), 3);
        }
    }

    private static final class BloodBankOtSeeder {
        static ConsoleSeedSummary seed(BloodBankStarter.BloodBankHemovigilanceService bloodBankService,
                                       BloodUnitRepository bloodUnitRepo,
                                       SurgeryOtStarter.SurgicalPerioperativeService surgeryService,
                                       SurgicalScheduleRepository surgicalScheduleRepo,
                                       UUID facilityId, UUID patientId) {
            BloodComponentUnitEntity unit = new BloodComponentUnitEntity();
            unit.setFacilityId(facilityId);
            unit.setIsbtUnitCode(IsoHl7StandardSampleData.ISBT_128_PRBC_UNIT);
            unit.setComponentType("PRBC");
            unit.setAboRhd("O-");
            unit.setExpiryDate(LocalDate.now().plusDays(28));
            unit.setStatus("AVAILABLE");
            BloodComponentUnitEntity savedUnit = bloodUnitRepo.save(unit);

            if (bloodBankService != null) {
                bloodBankService.evaluateCompatibility("O-", "AB+", "PRBC");
            }

            SurgicalScheduleEntity surgery = new SurgicalScheduleEntity();
            surgery.setFacilityId(facilityId);
            surgery.setPatientId(patientId);
            surgery.setTheaterRoom("OT-01");
            surgery.setProcedureName("Laparoscopic Cholecystectomy");
            surgery.setLeadSurgeonId("dr.iso.mbock");
            surgery.setWhoChecklistCompleted(true);
            surgery.setStatus("SCHEDULED");
            surgery.setScheduledAt(Instant.now().plus(Duration.ofHours(2)));
            SurgicalScheduleEntity savedSurgery = surgicalScheduleRepo.save(surgery);

            if (surgeryService != null) {
                surgeryService.evaluateAndRecordPacuAldreteScore(
                        new SurgeryOtStarter.PacuAldreteAssessmentRequest(
                                savedSurgery.getId(), 2, 2, 2, 2, 2, 75, "ASA_II"));
            }
            return new ConsoleSeedSummary("blood-ot", List.of(savedUnit.getId(), savedSurgery.getId()), 2);
        }
    }

    private static final class PatientPortalSeeder {
        static ConsoleSeedSummary seed(PatientPortalService portalService, UUID facilityId, UUID patientId) {
            String handle = "portal-" + patientId;
            portalService.enrollAccount(facilityId, new EnrollPortalAccountRequest(
                    patientId, handle, IsoHl7StandardSampleData.ISO_LANG_FR,
                    "iso.patient@example.invalid", "+237600001001"));
            portalService.grantProxyAccess(facilityId, handle, false, new GrantProxyRequest(
                    patientId, "proxy.iso.spouse", "SPOUSE",
                    Set.of(PortalScope.APPOINTMENTS, PortalScope.RESULTS),
                    Instant.now().plus(Duration.ofDays(90))));
            portalService.openMessageThread(facilityId, handle, new OpenThreadRequest(
                    patientId, "Pre-visit fasting verification", "OPD-MED",
                    "ISO/HL7 fixture message verifying secure patient portal messaging."));
            return new ConsoleSeedSummary("portal", List.of(patientId), 3);
        }
    }

    private static final class IntegrationOpsSeeder {
        static ConsoleSeedSummary seed(IntegrationOpsService integrationOpsService) {
            var fhirPartner = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                    "ISO-NAT-HIE-FHIR", "Synthetic National HIE (FHIR R4)", "FHIR_R4",
                    "https://hie.example.invalid/fhir", "MTLS", "Patient,Observation,Bundle", 180, true), FIXTURE_ACTOR);
            var hl7Partner = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                    "ISO-REF-LAB-HL7", "Synthetic Reference Lab (HL7 v2.5.1)", "HL7_V2",
                    "mllp://lis.example.invalid:2575", "MTLS", "ORM^O01,ORU^R01", 300, true), FIXTURE_ACTOR);

            var fhirMsg = integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                    fhirPartner.getId(), "ISO-FHIR-IPS-0001", "Bundle",
                    IsoHl7StandardSampleData.FHIR_R4_IPS_BUNDLE_JSON, 5), FIXTURE_ACTOR);
            var hl7Msg = integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                    hl7Partner.getId(), "ISO-HL7-ORM-0001", "ORM^O01",
                    IsoHl7StandardSampleData.HL7_V251_ORM_O01_MESSAGE, 1), FIXTURE_ACTOR);

            integrationOpsService.recordDeliveryFailure(
                    hl7Msg.getId(),
                    new RecordFailureRequest(504, "MLLP_TIMEOUT", "Simulated MLLP timeout to exercise DLQ workflow"),
                    FIXTURE_ACTOR);
            integrationOpsService.replayMessage(hl7Msg.getId(),
                    new ReplayRequest(FIXTURE_ACTOR, "Verified partner endpoint recovery in isolated fixture"));

            return new ConsoleSeedSummary("integration-ops",
                    List.of(fhirPartner.getId(), hl7Partner.getId(), fhirMsg.getId(), hl7Msg.getId()), 4);
        }
    }

    private static final class ClinicalAiSeeder {
        static ConsoleSeedSummary seed(ClinicalAiGovernanceService aiGovernanceService, UUID facilityId, UUID patientId) {
            aiGovernanceService.setTenantAiEnabled(facilityId, true);
            ClinicalAiDraftRecord draft = aiGovernanceService.registerDraft(
                    facilityId, patientId, AiCapabilityType.AUTOMATED_MEDICAL_CODING,
                    "clinical-coding-model-v1", IsoHl7StandardSampleData.SYNTHETIC_PROMPT_SHA256,
                    Map.of("PATIENT_NAME", 1, "PHONE", 1),
                    IsoHl7StandardSampleData.SYNTHETIC_AI_DRAFT_JSON,
                    new TokenUsage(320, 96, 416), 145L, FIXTURE_ACTOR);
            aiGovernanceService.attestDraft(draft.draftId(), new AttestationCommand(
                    "dr.iso.ngono", DraftAttestationStatus.APPROVED, null,
                    "Verified ICD-10 coding draft against inpatient clinical record"));
            return new ConsoleSeedSummary("clinical-ai", List.of(draft.draftId()), 2);
        }
    }

    private static final class AnalyticsSeeder {
        static ConsoleSeedSummary seed(HospitalAnalyticsService analyticsService) {
            var measure = analyticsService.recordQualityMeasure(new RecordQualityMeasureRequest(
                    IsoHl7StandardSampleData.ECQM_CMS165, "Controlling High Blood Pressure", "2026-Q4",
                    88L, 100L, 4L, "hc_emr_encounters+hc_orders", FIXTURE_ACTOR));
            var exportReq = analyticsService.requestCohortExport(new RequestCohortExportCommand(
                    "ISO Hypertension Outcomes Q4", "{\"icd10\":\"I10\"}", "PUBLIC_HEALTH_QUALITY",
                    true, 10, 48L, "dr.iso.ngono"));
            analyticsService.approveCohortExport(exportReq.getId(), "dpo.iso.reviewer");
            return new ConsoleSeedSummary("analytics", List.of(measure.getId(), exportReq.getId()), 2);
        }
    }

    private static final class RevenueCodingClaimsSeeder {
        static ConsoleSeedSummary seed(RevenueCodingClaimsService revenueClaimsService, UUID patientId, UUID encounterId) {
            var tariff = revenueClaimsService.upsertTariff(new UpsertTariffRequest(
                    IsoHl7StandardSampleData.PAYER_CNPS_UHC, "CONS-CARDIO", "Cardiology Specialist Consultation",
                    "CONSULTATION", 25000L, IsoHl7StandardSampleData.ISO_CURRENCY_XAF,
                    LocalDate.now().minusMonths(1), null), FIXTURE_ACTOR);
            var coding = revenueClaimsService.createCodingAssignment(new CreateCodingRequest(
                    patientId, encounterId, "ICD10", IsoHl7StandardSampleData.ICD10_HYPERTENSION,
                    IsoHl7StandardSampleData.ICD10_HYPERKALEMIA, IsoHl7StandardSampleData.CPT_INPATIENT_AND_PANEL,
                    IsoHl7StandardSampleData.DRG_HYPERTENSION, "coder.iso.01", 48500L));
            revenueClaimsService.approveClinicalCoding(coding.getId(), "dr.iso.ngono");
            var auth = revenueClaimsService.verifyEligibilityAndRequestAuth(new EligibilityAuthRequest(
                    patientId, IsoHl7StandardSampleData.PAYER_CNPS_UHC, "CM-CNPS-ISO-4410",
                    "INP-WARD-A", true, 3), FIXTURE_ACTOR);
            var claim = revenueClaimsService.submitPayerClaim(new SubmitClaimRequest(
                    IsoHl7StandardSampleData.CLAIM_CONTROL_NUMBER, coding.getId(), auth.getId(),
                    IsoHl7StandardSampleData.PAYER_CNPS_UHC, "X12_837P",
                    48500L, IsoHl7StandardSampleData.ISO_CURRENCY_XAF), FIXTURE_ACTOR);
            revenueClaimsService.postEraRemittance(claim.getId(), new PostEraRemittanceRequest(
                    IsoHl7StandardSampleData.ERA_TRACE_NUMBER, 48500L, 43500L, 5000L, null), FIXTURE_ACTOR);
            return new ConsoleSeedSummary("revenue-coding-claims",
                    List.of(tariff.getId(), coding.getId(), auth.getId(), claim.getId()), 5);
        }
    }

    private static final class FullHospitalSeeder {
        static SeededHospitalFixtureResult seed(HealthCoreDataFixture f) {
            TenantOrganizationEntity org = f.orgRepo.findByOrgCode(IsoHl7StandardSampleData.ORG_CODE)
                    .orElseGet(() -> f.governanceService.createOrganization(new CreateOrganizationRequest(
                            IsoHl7StandardSampleData.ORG_CODE, IsoHl7StandardSampleData.ORG_NAME,
                            "HOSPITAL_NETWORK", IsoHl7StandardSampleData.ISO_COUNTRY_CM)));

            TenantFacilityEntity facility = f.facilityRepo.findByFacilityCode(IsoHl7StandardSampleData.FACILITY_CODE)
                    .orElseGet(() -> f.governanceService.onboardFacility(new OnboardFacilityRequest(
                            org.getId(), IsoHl7StandardSampleData.FACILITY_CODE, IsoHl7StandardSampleData.FACILITY_NAME,
                            "TERTIARY_HOSPITAL", IsoHl7StandardSampleData.ISO_COUNTRY_CM,
                            IsoHl7StandardSampleData.IANA_TZ_DOUALA, IsoHl7StandardSampleData.ISO_CURRENCY_XAF,
                            IsoHl7StandardSampleData.ISO_LANG_FR, true)));

            UUID facilityId = facility.getId();
            Map<String, ConsoleSeedSummary> summaries = new LinkedHashMap<>();

            return f.withinFacility(facilityId, () -> {
                f.governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "OPD-MED", "Outpatient Medicine", "OUTPATIENT", 0));
                f.governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "WARD-A", "Inpatient Ward A", "INPATIENT_WARD", 24));
                f.governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "RAD-DIAG", "Radiology & PACS", "RADIOLOGY", 0));
                f.governanceService.createDepartment(facilityId, new CreateDepartmentRequest(null, "ED-ACUTE", "Emergency & Trauma", "EMERGENCY", 12));

                PatientAggregateEntity ambPatient = f.mpiService.registerPatient(new RegisterPatientCommand(
                        facilityId, IsoHl7StandardSampleData.PATIENT_AMB_MRN, "Synthetique", "Amina",
                        IsoHl7StandardSampleData.PATIENT_AMB_DOB, "female", "O+", "+237600001001",
                        IsoHl7StandardSampleData.ISO_LANG_FR, "CM-CNI", "SYN19880414001",
                        "Douala", "Moussa Synthetique", "+237600009001"));

                PatientAggregateEntity impPatient = f.mpiService.registerPatient(new RegisterPatientCommand(
                        facilityId, IsoHl7StandardSampleData.PATIENT_IMP_MRN, "Synthetique", "Jean-Luc",
                        IsoHl7StandardSampleData.PATIENT_IMP_DOB, "male", "AB+", "+237600001002",
                        IsoHl7StandardSampleData.ISO_LANG_FR, "CM-CNI", "SYN19610219002",
                        "Douala", "Marie Synthetique", "+237600009002"));

                EncounterEntity impEncounter = new EncounterEntity();
                impEncounter.setFacilityId(facilityId);
                impEncounter.setPatientId(impPatient.getId());
                impEncounter.setEncounterClass("IMP");
                impEncounter.setStatus("IN_PROGRESS");
                impEncounter.setAttendingId("dr.iso.ngono");
                impEncounter.setDepartmentCode("WARD-A");
                impEncounter.setPrimaryIcd10(IsoHl7StandardSampleData.ICD10_HYPERTENSION);
                EncounterEntity savedEncounter = f.encounterRepo.save(impEncounter);

                if (f.radiologyService != null) {
                    summaries.put("radiology", f.seedRadiologyConsole(facilityId, impPatient.getId(), savedEncounter.getId()));
                }
                if (f.emergencyNursingService != null) {
                    summaries.put("emergency-nursing", f.seedEmergencyNursingConsole(facilityId, impPatient.getId(), savedEncounter.getId()));
                }
                if (f.ordersResultsService != null && f.pharmacyLisService != null) {
                    summaries.put("laboratory", f.seedLaboratoryConsole(facilityId, impPatient.getId(), savedEncounter.getId()));
                }
                if (f.bloodUnitRepo != null && f.surgicalScheduleRepo != null) {
                    summaries.put("blood-ot", f.seedBloodBankAndOtConsole(facilityId, impPatient.getId()));
                }
                if (f.portalService != null) {
                    summaries.put("portal", f.seedPatientPortalConsole(facilityId, ambPatient.getId()));
                }
                if (f.integrationOpsService != null) {
                    summaries.put("integration-ops", f.seedIntegrationOpsStarter(facilityId));
                }
                if (f.aiGovernanceService != null) {
                    summaries.put("clinical-ai", f.seedClinicalAiConsole(facilityId, impPatient.getId()));
                }
                if (f.analyticsService != null) {
                    summaries.put("analytics", f.seedAnalyticsConsole(facilityId));
                }
                if (f.revenueClaimsService != null) {
                    summaries.put("revenue-coding-claims", f.seedRevenueCodingClaimsStarter(facilityId, impPatient.getId(), savedEncounter.getId()));
                }

                return new SeededHospitalFixtureResult(
                        org.getId(), facilityId, ambPatient.getId(), impPatient.getId(),
                        savedEncounter.getId(), Collections.unmodifiableMap(summaries));
            });
        }
    }

    public record ConsoleSeedSummary(String consoleKey, List<UUID> createdIds, int operationsExecuted) {}

    public record SeededHospitalFixtureResult(
            UUID organizationId,
            UUID facilityId,
            UUID ambulatoryPatientId,
            UUID inpatientPatientId,
            UUID inpatientEncounterId,
            Map<String, ConsoleSeedSummary> consoleSummaries
    ) {
        public void assertAllConsolesAndStartersSeeded() {
            List<String> expected = List.of(
                    "radiology", "emergency-nursing", "laboratory", "blood-ot",
                    "portal", "integration-ops", "clinical-ai", "analytics", "revenue-coding-claims"
            );
            for (String key : expected) {
                ConsoleSeedSummary s = consoleSummaries.get(key);
                if (s == null || s.operationsExecuted() <= 0) {
                    throw new IllegalStateException("Expected seeded ISO/HL7 records for console/starter '" + key + "'");
                }
            }
        }
    }

    public static final class Builder {
        private TenantGovernanceService governanceService;
        private TenantGovernanceService.OrganizationRepository orgRepo;
        private TenantGovernanceService.FacilityRepository facilityRepo;
        private MpiCommandService mpiService;
        private EncounterRepository encounterRepo;
        private RadiologyRisService radiologyService;
        private EmergencyNursingService emergencyNursingService;
        private UnifiedOrdersResultsService ordersResultsService;
        private PharmacyLisService pharmacyLisService;
        private BloodBankStarter.BloodBankHemovigilanceService bloodBankService;
        private BloodUnitRepository bloodUnitRepo;
        private SurgeryOtStarter.SurgicalPerioperativeService surgeryService;
        private SurgicalScheduleRepository surgicalScheduleRepo;
        private PatientPortalService portalService;
        private IntegrationOpsService integrationOpsService;
        private ClinicalAiGovernanceService aiGovernanceService;
        private HospitalAnalyticsService analyticsService;
        private RevenueCodingClaimsService revenueClaimsService;

        public Builder governance(TenantGovernanceService service,
                                  TenantGovernanceService.OrganizationRepository orgRepo,
                                  TenantGovernanceService.FacilityRepository facilityRepo) {
            this.governanceService = service;
            this.orgRepo = orgRepo;
            this.facilityRepo = facilityRepo;
            return this;
        }

        public Builder mpiService(MpiCommandService mpiService) { this.mpiService = mpiService; return this; }
        public Builder encounterRepo(EncounterRepository encounterRepo) { this.encounterRepo = encounterRepo; return this; }
        public Builder radiologyService(RadiologyRisService radiologyService) { this.radiologyService = radiologyService; return this; }
        public Builder emergencyNursingService(EmergencyNursingService emergencyNursingService) { this.emergencyNursingService = emergencyNursingService; return this; }
        public Builder ordersResultsService(UnifiedOrdersResultsService ordersResultsService) { this.ordersResultsService = ordersResultsService; return this; }
        public Builder pharmacyLisService(PharmacyLisService pharmacyLisService) { this.pharmacyLisService = pharmacyLisService; return this; }
        public Builder bloodBankService(BloodBankStarter.BloodBankHemovigilanceService bloodBankService) { this.bloodBankService = bloodBankService; return this; }
        public Builder bloodUnitRepo(BloodUnitRepository bloodUnitRepo) { this.bloodUnitRepo = bloodUnitRepo; return this; }
        public Builder surgeryService(SurgeryOtStarter.SurgicalPerioperativeService surgeryService) { this.surgeryService = surgeryService; return this; }
        public Builder surgicalScheduleRepo(SurgicalScheduleRepository surgicalScheduleRepo) { this.surgicalScheduleRepo = surgicalScheduleRepo; return this; }
        public Builder portalService(PatientPortalService portalService) { this.portalService = portalService; return this; }
        public Builder integrationOpsService(IntegrationOpsService integrationOpsService) { this.integrationOpsService = integrationOpsService; return this; }
        public Builder aiGovernanceService(ClinicalAiGovernanceService aiGovernanceService) { this.aiGovernanceService = aiGovernanceService; return this; }
        public Builder analyticsService(HospitalAnalyticsService analyticsService) { this.analyticsService = analyticsService; return this; }
        public Builder revenueClaimsService(RevenueCodingClaimsService revenueClaimsService) { this.revenueClaimsService = revenueClaimsService; return this; }

        public HealthCoreDataFixture build() {
            return new HealthCoreDataFixture(this);
        }
    }
}
