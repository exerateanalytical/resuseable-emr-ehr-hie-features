/*
 * =============================================================================
 * Opes HealthCore SDK — Extended Clinical, Specialty, Portal & Ops UI Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.ui;

import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AttestationCommand;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.DraftAttestationStatus;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RecordQualityMeasureRequest;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService.RequestCohortExportCommand;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.DischargeReadinessRequest;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.EdArrivalTriageRequest;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService.News2RecordingRequest;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.EnqueueMessageRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.ReplayRequest;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService.UpsertPartnerRequest;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.LabSpecimenEntity;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.LabSpecimenRepository;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.PortalScope;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.portal.service.PatientPortalService.EnrollPortalAccountRequest;
import com.opeshealth.healthcore.portal.service.PatientPortalService.GrantProxyRequest;
import com.opeshealth.healthcore.portal.service.PatientPortalService.OpenThreadRequest;
import com.opeshealth.healthcore.quickstart.seed.DemoHospitalDataSeeder;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pure Java Spring MVC + Thymeleaf controller powering the remaining 8+ clinical,
 * specialty, revenue-cycle, patient portal, integration operations, governed AI,
 * and analytics hospital workstation consoles.
 */
@Controller
@RequestMapping("/ui")
public class ExtendedHospitalWorkstationController {

    private final TenantGovernanceService governanceService;
    private final TenantGovernanceService.FacilityRepository facilityRepo;
    private final PatientRepository patientRepo;
    private final RadiologyRisService radiologyService;
    private final EmergencyNursingService emergencyNursingService;
    private final PharmacyLisService pharmacyLisService;
    private final LabSpecimenRepository specimenRepo;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final BloodBankStarter.BloodBankHemovigilanceService bloodBankService;
    private final BloodUnitRepository bloodUnitRepo;
    private final SurgeryOtStarter.SurgicalPerioperativeService surgeryService;
    private final SurgicalScheduleRepository surgicalScheduleRepo;
    private final PatientPortalService portalService;
    private final IntegrationOpsService integrationOpsService;
    private final RevenueCodingClaimsService revenueClaimsService;
    private final ClinicalAiGovernanceService aiGovernanceService;
    private final HospitalAnalyticsService analyticsService;
    private final ObjectProvider<DemoHospitalDataSeeder> demoSeederProvider;

    public ExtendedHospitalWorkstationController(
            TenantGovernanceService governanceService,
            TenantGovernanceService.FacilityRepository facilityRepo,
            PatientRepository patientRepo,
            RadiologyRisService radiologyService,
            EmergencyNursingService emergencyNursingService,
            PharmacyLisService pharmacyLisService,
            LabSpecimenRepository specimenRepo,
            UnifiedOrdersResultsService ordersResultsService,
            BloodBankStarter.BloodBankHemovigilanceService bloodBankService,
            BloodUnitRepository bloodUnitRepo,
            SurgeryOtStarter.SurgicalPerioperativeService surgeryService,
            SurgicalScheduleRepository surgicalScheduleRepo,
            PatientPortalService portalService,
            IntegrationOpsService integrationOpsService,
            RevenueCodingClaimsService revenueClaimsService,
            ClinicalAiGovernanceService aiGovernanceService,
            HospitalAnalyticsService analyticsService,
            ObjectProvider<DemoHospitalDataSeeder> demoSeederProvider) {
        this.governanceService = governanceService;
        this.facilityRepo = facilityRepo;
        this.patientRepo = patientRepo;
        this.radiologyService = radiologyService;
        this.emergencyNursingService = emergencyNursingService;
        this.pharmacyLisService = pharmacyLisService;
        this.specimenRepo = specimenRepo;
        this.ordersResultsService = ordersResultsService;
        this.bloodBankService = bloodBankService;
        this.bloodUnitRepo = bloodUnitRepo;
        this.surgeryService = surgeryService;
        this.surgicalScheduleRepo = surgicalScheduleRepo;
        this.portalService = portalService;
        this.integrationOpsService = integrationOpsService;
        this.revenueClaimsService = revenueClaimsService;
        this.aiGovernanceService = aiGovernanceService;
        this.analyticsService = analyticsService;
        this.demoSeederProvider = demoSeederProvider;
    }

    // -------------------------------------------------------------------------
    // 1. RADIOLOGY & PACS MODALITY WORKLIST
    // -------------------------------------------------------------------------
    @GetMapping("/radiology")
    public String radiology(@RequestParam(required = false) String status, Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "radiology", "Radiology & PACS Modality Worklist", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("worklist", radiologyService.listWorklist(status));
                model.addAttribute("criticalFindings", radiologyService.listUnacknowledgedCriticalFindings());
                model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
            });
        } else {
            model.addAttribute("worklist", List.of());
            model.addAttribute("criticalFindings", List.of());
            model.addAttribute("patients", List.of());
        }
        model.addAttribute("selectedStatus", status != null ? status : "");
        return "workstation/radiology";
    }

    @PostMapping("/radiology/schedule")
    public String scheduleRadiologyExam(
            @RequestParam UUID patientId,
            @RequestParam String modality,
            @RequestParam String procedureCode,
            @RequestParam String procedureDescription,
            @RequestParam(required = false) String bodySite,
            @RequestParam(required = false) String clinicalIndication,
            @RequestParam(defaultValue = "false") boolean statUrgent,
            RedirectAttributes redirect) {
        return handlePost("/ui/radiology", redirect, facId -> {
            var item = radiologyService.scheduleImagingExam(new ScheduleExamRequest(
                    patientId, null, modality, procedureCode, procedureDescription,
                    bodySite, clinicalIndication, modality + "_AE_01", "ui.clinician", statUrgent, null));
            return "Scheduled " + item.getModality() + " exam (" + item.getAccessionNumber() + ").";
        });
    }

    @PostMapping("/radiology/{id}/mpps")
    public String updateMpps(
            @PathVariable UUID id,
            @RequestParam String status,
            @RequestParam(defaultValue = "2") int seriesCount,
            @RequestParam(defaultValue = "48") int instanceCount,
            RedirectAttributes redirect) {
        return handlePost("/ui/radiology", redirect, facId -> {
            var item = radiologyService.updateMpps(id, new MppsUpdateRequest(status, "ui.radtech", seriesCount, instanceCount));
            return "Updated MPPS for " + item.getAccessionNumber() + " to " + item.getMppsStatus() + ".";
        });
    }

    @PostMapping("/radiology/reports")
    public String signRadiologyReport(
            @RequestParam UUID worklistItemId,
            @RequestParam String findingsText,
            @RequestParam String impressionText,
            @RequestParam(defaultValue = "false") boolean criticalFinding,
            @RequestParam(required = false) String criticalCategory,
            RedirectAttributes redirect) {
        return handlePost("/ui/radiology", redirect, facId -> {
            var rep = radiologyService.signReport(new SignRadiologyReportRequest(
                    worklistItemId, findingsText, impressionText, "dr.radiologist",
                    criticalFinding, criticalCategory, null));
            return "Signed " + rep.getReportStatus() + " radiology report for " + rep.getAccessionNumber() + ".";
        });
    }

    @PostMapping("/radiology/reports/{id}/ack-critical")
    public String acknowledgeRadiologyCritical(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Verbal read-back confirmed with attending clinician") String readbackNotes,
            RedirectAttributes redirect) {
        return handlePost("/ui/radiology", redirect, facId -> {
            radiologyService.acknowledgeCriticalFinding(id, new AcknowledgeCriticalRequest("dr.attending", readbackNotes));
            return "Closed-loop critical imaging finding acknowledged.";
        });
    }

    // -------------------------------------------------------------------------
    // 2. EMERGENCY TRIAGE & INPATIENT NURSING / NEWS2 / DISCHARGE READINESS
    // -------------------------------------------------------------------------
    @GetMapping("/emergency-nursing")
    public String emergencyNursing(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "emergency-nursing", "Emergency Triage & Inpatient Nursing", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("edBoard", emergencyNursingService.getActiveEdBoard());
                model.addAttribute("escalations", emergencyNursingService.listActiveEscalations());
                model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
            });
        } else {
            model.addAttribute("edBoard", List.of());
            model.addAttribute("escalations", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/emergency-nursing";
    }

    @PostMapping("/emergency-nursing/triage")
    public String registerTriage(
            @RequestParam UUID patientId,
            @RequestParam String chiefComplaint,
            @RequestParam int esiLevel,
            @RequestParam(defaultValue = "WALK_IN") String arrivalMode,
            @RequestParam(defaultValue = "BAY-01") String assignedBay,
            RedirectAttributes redirect) {
        return handlePost("/ui/emergency-nursing", redirect, facId -> {
            var visit = emergencyNursingService.registerEdTriage(new EdArrivalTriageRequest(
                    patientId, null, chiefComplaint, esiLevel, arrivalMode, assignedBay, "rn.triage"));
            return "Triaged patient into " + visit.getAssignedBay() + " at ESI-" + visit.getEsiLevel() + ".";
        });
    }

    @PostMapping("/emergency-nursing/news2")
    public String recordNews2(
            @RequestParam UUID patientId,
            @RequestParam int respiratoryRate,
            @RequestParam int spo2Percent,
            @RequestParam(defaultValue = "false") boolean supplementalO2,
            @RequestParam int systolicBp,
            @RequestParam int heartRate,
            @RequestParam double temperatureC,
            @RequestParam(defaultValue = "ALERT") String consciousnessAcvpu,
            RedirectAttributes redirect) {
        return handlePost("/ui/emergency-nursing", redirect, facId -> {
            var obs = emergencyNursingService.recordNews2Observation(new News2RecordingRequest(
                    patientId, null, respiratoryRate, spo2Percent, supplementalO2,
                    systolicBp, heartRate, temperatureC, consciousnessAcvpu, "rn.ward"));
            return "Recorded NEWS2=" + obs.getNews2TotalScore() + " (" + obs.getClinicalRiskBand() + ").";
        });
    }

    @PostMapping("/emergency-nursing/discharge-readiness")
    public String updateDischargeReadiness(
            @RequestParam UUID patientId,
            @RequestParam(defaultValue = "false") boolean medReconciliationComplete,
            @RequestParam(defaultValue = "false") boolean dischargeSummarySigned,
            @RequestParam(defaultValue = "false") boolean patientEducationDocumented,
            @RequestParam(defaultValue = "false") boolean followUpScheduled,
            @RequestParam(defaultValue = "false") boolean billingCleared,
            RedirectAttributes redirect) {
        return handlePost("/ui/emergency-nursing", redirect, facId -> {
            var gate = emergencyNursingService.updateDischargeReadiness(new DischargeReadinessRequest(
                    patientId, UUID.randomUUID(), medReconciliationComplete, dischargeSummarySigned,
                    patientEducationDocumented, followUpScheduled, billingCleared, "rn.charge"));
            return "Discharge readiness updated: " + (gate.isReadyForDischarge() ? "READY" : "BLOCKERS PENDING") + ".";
        });
    }

    // -------------------------------------------------------------------------
    // 3. LABORATORY ACCESSION, WESTGARD QC & PANIC-VALUE READ-BACK
    // -------------------------------------------------------------------------
    @GetMapping("/laboratory")
    public String laboratory(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "laboratory", "Laboratory Accession & Panic-Value QC", facId);
        if (facId != null) {
            model.addAttribute("specimens", specimenRepo.findAll().stream()
                    .filter(s -> facId.equals(s.getFacilityId())).toList());
            model.addAttribute("criticalResults", ordersResultsService.listUnacknowledgedCriticalResults(facId));
            model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
        } else {
            model.addAttribute("specimens", List.of());
            model.addAttribute("criticalResults", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/laboratory";
    }

    @PostMapping("/laboratory/accession")
    public String accessionSpecimen(
            @RequestParam UUID patientId,
            @RequestParam String specimenType,
            @RequestParam String loincPanelCode,
            @RequestParam(required = false) String barcode,
            RedirectAttributes redirect) {
        return handlePost("/ui/laboratory", redirect, facId -> {
            LabSpecimenEntity specimen = new LabSpecimenEntity();
            specimen.setFacilityId(facId);
            specimen.setPatientId(patientId);
            specimen.setSpecimenType(specimenType);
            specimen.setLoincPanelCode(loincPanelCode);
            specimen.setAccessionBarcode(barcode != null && !barcode.isBlank()
                    ? barcode.trim()
                    : "SPEC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
            var saved = pharmacyLisService.accessionSpecimen(specimen);
            return "Accessioned barcoded specimen " + saved.getAccessionBarcode() + ".";
        });
    }

    // -------------------------------------------------------------------------
    // 4. BLOOD BANK HEMOVIGILANCE & OPERATING THEATER / PACU CHECKLIST
    // -------------------------------------------------------------------------
    @GetMapping("/blood-ot")
    public String bloodAndOt(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "blood-ot", "Blood Bank & Operating Theater / PACU", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("bloodSummary", bloodBankService.getFacilityInventorySummary(facId));
                model.addAttribute("bloodUnits", bloodUnitRepo.findAll().stream()
                        .filter(u -> facId.equals(u.getFacilityId())).toList());
                model.addAttribute("surgeries", surgicalScheduleRepo.findAll().stream()
                        .filter(s -> facId.equals(s.getFacilityId())).toList());
                model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
            });
        } else {
            model.addAttribute("bloodUnits", List.of());
            model.addAttribute("surgeries", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/blood-ot";
    }

    @PostMapping("/blood-ot/compatibility")
    public String checkBloodCompatibility(
            @RequestParam String donorAboRh,
            @RequestParam String recipientAboRh,
            @RequestParam(defaultValue = "PRBC") String componentType,
            RedirectAttributes redirect) {
        var eval = bloodBankService.evaluateCompatibility(donorAboRh, recipientAboRh, componentType);
        redirect.addFlashAttribute(eval.compatible() ? "notice" : "error",
                "Cross-match " + eval.donorAboRh() + " -> " + eval.recipientAboRh() + " (" + eval.componentType() + "): "
                        + (eval.compatible() ? "COMPATIBLE — " : "INCOMPATIBLE — ") + eval.clinicalGuidance());
        return "redirect:/ui/blood-ot";
    }

    @PostMapping("/blood-ot/pacu-aldrete")
    public String evaluatePacuAldrete(
            @RequestParam UUID surgicalScheduleId,
            @RequestParam int activityScore,
            @RequestParam int respirationScore,
            @RequestParam int circulationScore,
            @RequestParam int consciousnessScore,
            @RequestParam int oxygenSaturationScore,
            @RequestParam(defaultValue = "100") int estimatedBloodLossMl,
            @RequestParam(defaultValue = "ASA_II") String asaClass,
            RedirectAttributes redirect) {
        return handlePost("/ui/blood-ot", redirect, facId -> {
            var decision = surgeryService.evaluateAndRecordPacuAldreteScore(
                    new SurgeryOtStarter.PacuAldreteAssessmentRequest(
                            surgicalScheduleId, activityScore, respirationScore, circulationScore,
                            consciousnessScore, oxygenSaturationScore, estimatedBloodLossMl, asaClass));
            return "Modified Aldrete Score=" + decision.totalAldreteScore() + "/10 ("
                    + decision.dispositionStatus() + "): " + decision.clinicalRecommendation();
        });
    }

    // -------------------------------------------------------------------------
    // 5. PATIENT PORTAL WORKSPACE
    // -------------------------------------------------------------------------
    @GetMapping("/portal")
    public String patientPortal(@RequestParam(required = false) UUID patientId, Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "portal", "Patient Portal & Proxy Access", facId);
        if (facId != null) {
            var patients = patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId);
            model.addAttribute("patients", patients);
            UUID activePatientId = patientId != null ? patientId : (patients.isEmpty() ? null : patients.get(0).getId());
            model.addAttribute("selectedPatientId", activePatientId);
            if (activePatientId != null) {
                String subject = "portal-" + activePatientId;
                runInFacility(facId, () -> {
                    try {
                        model.addAttribute("portalAppointments", portalService.getPatientAppointments(facId, subject, activePatientId));
                        model.addAttribute("portalResults", portalService.getReleasedResults(facId, subject, activePatientId));
                        model.addAttribute("portalThreads", portalService.listPatientThreads(facId, subject, activePatientId));
                    } catch (Exception ex) {
                        model.addAttribute("portalAppointments", List.of());
                        model.addAttribute("portalResults", List.of());
                        model.addAttribute("portalThreads", List.of());
                    }
                });
            }
        } else {
            model.addAttribute("patients", List.of());
        }
        return "workstation/portal";
    }

    @PostMapping("/portal/enroll")
    public String enrollPortalAccount(
            @RequestParam UUID patientId,
            @RequestParam(defaultValue = "en") String preferredLocale,
            @RequestParam(required = false) String contactEmail,
            @RequestParam(required = false) String contactPhone,
            RedirectAttributes redirect) {
        return handlePost("/ui/portal?patientId=" + patientId, redirect, facId -> {
            String subject = "portal-" + patientId;
            portalService.enrollAccount(facId, new EnrollPortalAccountRequest(
                    patientId, subject, preferredLocale, contactEmail, contactPhone));
            return "Enrolled patient portal account for subject " + subject + ".";
        });
    }

    @PostMapping("/portal/proxy")
    public String grantPortalProxy(
            @RequestParam UUID patientId,
            @RequestParam String proxySubject,
            @RequestParam String relationship,
            RedirectAttributes redirect) {
        return handlePost("/ui/portal?patientId=" + patientId, redirect, facId -> {
            portalService.grantProxyAccess(facId, "ui.admin", true, new GrantProxyRequest(
                    patientId, proxySubject, relationship,
                    Set.of(PortalScope.VIEW_APPOINTMENTS, PortalScope.VIEW_RESULTS),
                    Instant.now().plus(180, ChronoUnit.DAYS)));
            return "Granted scoped proxy access to " + proxySubject + " (" + relationship + ").";
        });
    }

    @PostMapping("/portal/threads")
    public String openPortalThread(
            @RequestParam UUID patientId,
            @RequestParam String subject,
            @RequestParam(defaultValue = "OUTPATIENT") String careTeamDepartment,
            @RequestParam String initialMessage,
            RedirectAttributes redirect) {
        return handlePost("/ui/portal?patientId=" + patientId, redirect, facId -> {
            portalService.openMessageThread(facId, "portal-" + patientId,
                    new OpenThreadRequest(patientId, subject, careTeamDepartment, initialMessage));
            return "Opened secure care-team message thread.";
        });
    }

    // -------------------------------------------------------------------------
    // 6. INTEGRATION OPERATIONS & DLQ REPLAY CONSOLE
    // -------------------------------------------------------------------------
    @GetMapping("/integration-ops")
    public String integrationOps(@RequestParam(required = false) String status, Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "integration-ops", "Integration Operations & DLQ Replay", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("partners", integrationOpsService.listPartners());
                model.addAttribute("partnerHealth", integrationOpsService.getPartnerHealthSummaries());
                model.addAttribute("messages", integrationOpsService.listMessages(status));
                model.addAttribute("dlqMessages", integrationOpsService.listMessages("DEAD_LETTERED"));
            });
        } else {
            model.addAttribute("partners", List.of());
            model.addAttribute("partnerHealth", List.of());
            model.addAttribute("messages", List.of());
            model.addAttribute("dlqMessages", List.of());
        }
        return "workstation/integration-ops";
    }

    @PostMapping("/integration-ops/partners")
    public String upsertPartner(
            @RequestParam String partnerCode,
            @RequestParam String partnerName,
            @RequestParam String protocolFamily,
            @RequestParam String endpointUrl,
            @RequestParam(defaultValue = "MTLS") String authMode,
            @RequestParam String allowedMessageTypes,
            RedirectAttributes redirect) {
        return handlePost("/ui/integration-ops", redirect, facId -> {
            var p = integrationOpsService.upsertPartnerProfile(new UpsertPartnerRequest(
                    partnerCode, partnerName, protocolFamily, endpointUrl, authMode, allowedMessageTypes, 120, true), "ui.intops");
            return "Saved trading partner conformance profile " + p.getPartnerCode() + ".";
        });
    }

    @PostMapping("/integration-ops/outbox")
    public String enqueueOutbound(
            @RequestParam UUID partnerId,
            @RequestParam String idempotencyKey,
            @RequestParam String messageType,
            @RequestParam String payloadBody,
            RedirectAttributes redirect) {
        return handlePost("/ui/integration-ops", redirect, facId -> {
            var msg = integrationOpsService.enqueueOutbound(new EnqueueMessageRequest(
                    partnerId, idempotencyKey, messageType, payloadBody, 5), "ui.intops");
            return "Queued outbound message " + msg.getIdempotencyKey() + " (" + msg.getDeliveryStatus() + ").";
        });
    }

    @PostMapping("/integration-ops/messages/{id}/replay")
    public String replayMessage(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "Operator verified partner recovery") String justification,
            RedirectAttributes redirect) {
        return handlePost("/ui/integration-ops", redirect, facId -> {
            var replayed = integrationOpsService.replayMessage(id, new ReplayRequest("ui.intops", justification));
            return "Replayed message " + replayed.getIdempotencyKey() + " with audit record.";
        });
    }

    @PostMapping("/integration-ops/partners/{id}/circuit")
    public String updateCircuit(@PathVariable UUID id, @RequestParam String state, RedirectAttributes redirect) {
        return handlePost("/ui/integration-ops", redirect, facId -> {
            var p = integrationOpsService.updateCircuitState(id, state, "ui.intops");
            return "Updated circuit breaker for " + p.getPartnerCode() + " to " + p.getCircuitState() + ".";
        });
    }

    // -------------------------------------------------------------------------
    // 7. GOVERNED CLINICAL AI WORKBENCH
    // -------------------------------------------------------------------------
    @GetMapping("/clinical-ai")
    public String clinicalAi(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "clinical-ai", "Governed Clinical AI Workbench", facId);
        if (facId != null) {
            model.addAttribute("aiEnabled", aiGovernanceService.isTenantAiEnabled(facId));
            model.addAttribute("aiMetrics", aiGovernanceService.getTenantMetrics(facId));
            model.addAttribute("drafts", aiGovernanceService.listDrafts(facId, null));
            model.addAttribute("capabilities", AiCapabilityType.values());
        } else {
            model.addAttribute("aiEnabled", false);
            model.addAttribute("drafts", List.of());
            model.addAttribute("capabilities", AiCapabilityType.values());
        }
        return "workstation/clinical-ai";
    }

    @PostMapping("/clinical-ai/policy")
    public String toggleAiPolicy(@RequestParam(defaultValue = "false") boolean enabled, RedirectAttributes redirect) {
        return handlePost("/ui/clinical-ai", redirect, facId -> {
            boolean state = aiGovernanceService.setTenantAiEnabled(facId, enabled);
            return "Durable tenant Clinical AI policy set to " + (state ? "ENABLED" : "DISABLED") + ".";
        });
    }

    @PostMapping("/clinical-ai/drafts/{draftId}/attest")
    public String attestAiDraft(
            @PathVariable UUID draftId,
            @RequestParam DraftAttestationStatus decision,
            @RequestParam(defaultValue = "Reviewed clinical accuracy against chart") String attestationReason,
            RedirectAttributes redirect) {
        return handlePost("/ui/clinical-ai", redirect, facId -> {
            aiGovernanceService.attestDraft(draftId, new AttestationCommand("dr.attending", decision, null, attestationReason));
            return "Recorded clinician attestation decision: " + decision + ".";
        });
    }

    // -------------------------------------------------------------------------
    // 8. HOSPITAL ANALYTICS, QUALITY MEASURES & COHORT EXPORT
    // -------------------------------------------------------------------------
    @GetMapping("/analytics")
    public String analytics(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "analytics", "Hospital Analytics & Quality Measures", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("kpis", analyticsService.computeOperationalKpis());
                model.addAttribute("measures", analyticsService.listQualityMeasures());
                model.addAttribute("exports", analyticsService.listCohortExports());
                model.addAttribute("claimsSummary", revenueClaimsService.getClaimsSummary());
            });
        } else {
            model.addAttribute("measures", List.of());
            model.addAttribute("exports", List.of());
        }
        return "workstation/analytics";
    }

    @PostMapping("/analytics/measures")
    public String recordQualityMeasure(
            @RequestParam String measureCode,
            @RequestParam String measureTitle,
            @RequestParam String reportingPeriod,
            @RequestParam long numeratorCount,
            @RequestParam long denominatorCount,
            @RequestParam(defaultValue = "0") long exclusionCount,
            RedirectAttributes redirect) {
        return handlePost("/ui/analytics", redirect, facId -> {
            var m = analyticsService.recordQualityMeasure(new RecordQualityMeasureRequest(
                    measureCode, measureTitle, reportingPeriod, numeratorCount, denominatorCount,
                    exclusionCount, "hc_emr_encounters+hc_orders", "ui.quality"));
            return "Recorded quality measure " + m.getMeasureCode() + " (" + m.getPerformanceRatePct() + "%).";
        });
    }

    @PostMapping("/analytics/exports")
    public String requestCohortExport(
            @RequestParam String cohortName,
            @RequestParam String exportPurpose,
            @RequestParam(defaultValue = "10") int minimumCellSuppression,
            @RequestParam long matchedPatientCount,
            RedirectAttributes redirect) {
        return handlePost("/ui/analytics", redirect, facId -> {
            var exp = analyticsService.requestCohortExport(new RequestCohortExportCommand(
                    cohortName, "{\"scope\":\"facility\"}", exportPurpose, true,
                    minimumCellSuppression, matchedPatientCount, "ui.researcher"));
            return "Submitted de-identified cohort export request (" + exp.getExportStatus() + ").";
        });
    }

    @PostMapping("/analytics/exports/{id}/approve")
    public String approveCohortExport(@PathVariable UUID id, RedirectAttributes redirect) {
        return handlePost("/ui/analytics", redirect, facId -> {
            var exp = analyticsService.approveCohortExport(id, "ui.dpo");
            return "Approved cohort export '" + exp.getCohortName() + "' (" + exp.getExportStatus() + ").";
        });
    }

    // -------------------------------------------------------------------------
    // 9. TARIFFS, CLINICAL CODING & INSURANCE CLAIMS CONSOLE
    // -------------------------------------------------------------------------
    @GetMapping("/revenue-claims")
    public String revenueClaims(Model model) {
        UUID facId = resolveUiFacilityId();
        populateChrome(model, "revenue-claims", "Tariffs, Clinical Coding & Payer Claims", facId);
        if (facId != null) {
            runInFacility(facId, () -> {
                model.addAttribute("tariffs", revenueClaimsService.listTariffs());
                model.addAttribute("codingRecords", revenueClaimsService.listCodingRecords());
                model.addAttribute("authorizations", revenueClaimsService.listAuthorizations());
                model.addAttribute("claims", revenueClaimsService.listClaims());
                model.addAttribute("claimsSummary", revenueClaimsService.getClaimsSummary());
                model.addAttribute("patients", patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(facId));
            });
        } else {
            model.addAttribute("tariffs", List.of());
            model.addAttribute("codingRecords", List.of());
            model.addAttribute("authorizations", List.of());
            model.addAttribute("claims", List.of());
            model.addAttribute("patients", List.of());
        }
        return "workstation/revenue-claims";
    }

    @PostMapping("/revenue-claims/tariffs")
    public String upsertTariff(
            @RequestParam String payerCode,
            @RequestParam String serviceCode,
            @RequestParam String serviceName,
            @RequestParam(defaultValue = "CONSULTATION") String serviceCategory,
            @RequestParam long unitPriceMinor,
            @RequestParam(defaultValue = "XAF") String currencyCode,
            RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var t = revenueClaimsService.upsertTariff(new UpsertTariffRequest(
                    payerCode, serviceCode, serviceName, serviceCategory, unitPriceMinor, currencyCode, LocalDate.now(), null), "ui.biller");
            return "Saved tariff " + t.getPayerCode() + ":" + t.getServiceCode() + ".";
        });
    }

    @PostMapping("/revenue-claims/coding")
    public String createCoding(
            @RequestParam UUID patientId,
            @RequestParam(defaultValue = "ICD10") String codingSystem,
            @RequestParam String primaryIcdCode,
            @RequestParam(required = false) String cptProcedureCodes,
            @RequestParam(required = false) String drgCode,
            @RequestParam(defaultValue = "50000") long estimatedReimbursementMinor,
            RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var c = revenueClaimsService.createCodingAssignment(new CreateCodingRequest(
                    patientId, UUID.randomUUID(), codingSystem, primaryIcdCode, null,
                    cptProcedureCodes, drgCode, "ui.coder", estimatedReimbursementMinor));
            return "Created clinical coding " + c.getPrimaryIcdCode() + " awaiting clinician review.";
        });
    }

    @PostMapping("/revenue-claims/coding/{id}/approve")
    public String approveCoding(@PathVariable UUID id, RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var c = revenueClaimsService.approveClinicalCoding(id, "dr.coding-reviewer");
            return "Clinically approved coding assignment " + c.getPrimaryIcdCode() + ".";
        });
    }

    @PostMapping("/revenue-claims/claims")
    public String submitClaim(
            @RequestParam String claimControlNumber,
            @RequestParam UUID codingRecordId,
            @RequestParam String payerCode,
            @RequestParam(defaultValue = "X12_837P") String claimFormat,
            @RequestParam long billedAmountMinor,
            RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var claim = revenueClaimsService.submitPayerClaim(new SubmitClaimRequest(
                    claimControlNumber, codingRecordId, null, payerCode, claimFormat, billedAmountMinor, "XAF"), "ui.biller");
            return "Submitted claim " + claim.getClaimControlNumber() + " (" + claim.getClaimFormat() + ").";
        });
    }

    @PostMapping("/revenue-claims/claims/{id}/era")
    public String postEra(
            @PathVariable UUID id,
            @RequestParam String eraTraceNumber,
            @RequestParam long allowedAmountMinor,
            @RequestParam long paidAmountMinor,
            @RequestParam(defaultValue = "0") long patientResponsibilityMinor,
            @RequestParam(required = false) String denialReasonCode,
            RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var claim = revenueClaimsService.postEraRemittance(id, new PostEraRemittanceRequest(
                    eraTraceNumber, allowedAmountMinor, paidAmountMinor, patientResponsibilityMinor, denialReasonCode), "ui.era");
            return "Posted ERA " + eraTraceNumber + " -> claim status " + claim.getClaimStatus() + ".";
        });
    }

    @PostMapping("/revenue-claims/auth")
    public String requestAuth(
            @RequestParam UUID patientId,
            @RequestParam String payerCode,
            @RequestParam String memberPolicyNumber,
            @RequestParam String serviceCode,
            RedirectAttributes redirect) {
        return handlePost("/ui/revenue-claims", redirect, facId -> {
            var a = revenueClaimsService.verifyEligibilityAndRequestAuth(new EligibilityAuthRequest(
                    patientId, payerCode, memberPolicyNumber, serviceCode, true, 1), "ui.biller");
            return "Verified eligibility and created prior auth request (" + a.getAuthStatus() + ").";
        });
    }

    // -------------------------------------------------------------------------
    // SHARED FACILITY HELPERS
    // -------------------------------------------------------------------------
    @FunctionalInterface
    private interface FacilityAction {
        String run(UUID facilityId);
    }

    private String handlePost(String redirectTarget, RedirectAttributes redirect, FacilityAction action) {
        UUID facId = resolveUiFacilityId();
        if (facId == null) {
            redirect.addFlashAttribute("error", "No active facility is configured.");
            return "redirect:" + redirectTarget;
        }
        try {
            FacilityContext.setFacilityId(facId);
            String msg = action.run(facId);
            redirect.addFlashAttribute("notice", msg);
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        } finally {
            FacilityContext.clear();
        }
        return "redirect:" + redirectTarget;
    }

    private void runInFacility(UUID facId, Runnable task) {
        try {
            FacilityContext.setFacilityId(facId);
            task.run();
        } finally {
            FacilityContext.clear();
        }
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
