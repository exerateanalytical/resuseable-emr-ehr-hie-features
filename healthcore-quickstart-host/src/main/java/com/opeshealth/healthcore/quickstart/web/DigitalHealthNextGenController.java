/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService;
import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.AntibioticStewardshipOrderRequest;
import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.AwareAntibioticOrderReview;
import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.CumulativeAntibiogramReport;
import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.MicrobiologyIsolateRequest;
import com.opeshealth.healthcore.quickstart.service.AntimicrobialStewardshipService.MicrobiologySusceptibilityRecord;
import com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService;
import com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BcmaAdministrationRecord;
import com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.BedsideBarcodeScanRequest;
import com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.EmarScheduledMedicationOrder;
import com.opeshealth.healthcore.quickstart.service.BcmaMedicationSafetyService.EmarScheduledOrderRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.BreakTheGlassSession;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ClinicalSegmentItem;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.ConsentDirectiveRequest;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.FhirConsentDirective;
import com.opeshealth.healthcore.quickstart.service.FhirConsentEnforcementService.SegmentedAccessEvaluationResult;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.FhirSubscriptionRegistration;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionNotificationDelivery;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionRegistrationRequest;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionTopicDefinition;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSeedClinicalSummaryRequest;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.IpsSignatureVerificationResult;
import com.opeshealth.healthcore.quickstart.service.InternationalPatientSummaryService.SignedIpsBundleResponse;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PatientPgxGeneProfile;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxAlleleRegistrationRequest;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxPrescribingAlert;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxPrescriptionCheckRequest;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.EligibilityCheckRequest;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.EligibilityVerificationResponse;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PatientCoveragePlan;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PatientCoveragePlanRequest;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PriorAuthSubmissionRequest;
import com.opeshealth.healthcore.quickstart.service.PriorAuthAndEligibilityService.PriorAuthorizationAdjudication;
import com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService;
import com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService.PromAssessmentResult;
import com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService.PromSubmissionRequest;
import com.opeshealth.healthcore.quickstart.service.PromQuestionnaireEngineService.QuestionnaireDefinitionSummary;
import com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService;
import com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.EarlyWarningAssessment;
import com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.RegisteredWearableDevice;
import com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.RpmVitalTelemetryPacket;
import com.opeshealth.healthcore.quickstart.service.RemotePatientMonitoringService.WearableDeviceRegistrationRequest;
import com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService;
import com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.ReferralStatusUpdateRequest;
import com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohCommunityReferral;
import com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohScreeningEvaluation;
import com.opeshealth.healthcore.quickstart.service.SdohGravityReferralService.SdohScreeningRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified HL7 FHIR R4 &amp; Clinical REST Controller for the 10 Next-Generation Digital Health Capabilities:
 * <ol>
 *   <li>Fine-Grained FHIR {@code Consent}, DS4P Segmentation &amp; Break-the-Glass ({@code BTG})</li>
 *   <li>Pharmacogenomics (PGx) &amp; CPIC Precision Medicine Decision Support</li>
 *   <li>Remote Patient Monitoring (RPM) &amp; Early Warning Scores ({@code NEWS2}, {@code MEWS}, {@code qSOFA})</li>
 *   <li>HL7 FHIR R5-Backport Topic-Based Subscriptions ({@code SubscriptionTopic} &amp; {@code Subscription})</li>
 *   <li>Closed-Loop Barcoded Medication Administration (BCMA 5-Rights &amp; High-Alert LASA Dual Sign-Off)</li>
 *   <li>Antimicrobial Stewardship (AMS — WHO AWaRe Governance, MDRO Surveillance &amp; Antibiogram)</li>
 *   <li>International Patient Summary ({@code Patient/{id}/$summary}) &amp; Verifiable JWS Digital Health Certificates</li>
 *   <li>Electronic Prior Authorization (Da Vinci PAS) &amp; Real-Time Coverage Eligibility</li>
 *   <li>Patient-Reported Outcome Measures (PROMs — {@code PHQ-9}, {@code GAD-7}, {@code AUDIT-C}, {@code WHO-5})</li>
 *   <li>Social Determinants of Health (SDoH — Gravity Project Screening &amp; Closed-Loop CHW Referrals)</li>
 * </ol>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@Tag(name = "FHIR R4 Next-Generation Digital Health Suite",
        description = "FHIR Consent/DS4P Break-the-Glass, CPIC Pharmacogenomics, RPM NEWS2/qSOFA, R5-Backport Subscriptions, Closed-Loop BCMA, WHO AWaRe Antimicrobial Stewardship, Signed IPS ($summary), Da Vinci PAS Prior Auth, PROMs, and Gravity SDoH")
public class DigitalHealthNextGenController {

    private static final String FHIR_JSON = "application/fhir+json";

    public record Ds4pEvaluationRequest(
            UUID patientId,
            String actorId,
            String purposeOfUse,
            String breakTheGlassToken,
            List<ClinicalSegmentItem> clinicalItems
    ) {}

    public record IpsJwsVerificationRequest(
            String jwsCompactSignature
    ) {}

    private final FhirConsentEnforcementService consentService;
    private final PharmacogenomicsCdsService pgxService;
    private final RemotePatientMonitoringService rpmService;
    private final FhirSubscriptionTopicService subscriptionService;
    private final BcmaMedicationSafetyService bcmaService;
    private final AntimicrobialStewardshipService amsService;
    private final InternationalPatientSummaryService ipsService;
    private final PriorAuthAndEligibilityService priorAuthService;
    private final PromQuestionnaireEngineService promService;
    private final SdohGravityReferralService sdohService;

    public DigitalHealthNextGenController(
            FhirConsentEnforcementService consentService,
            PharmacogenomicsCdsService pgxService,
            RemotePatientMonitoringService rpmService,
            FhirSubscriptionTopicService subscriptionService,
            BcmaMedicationSafetyService bcmaService,
            AntimicrobialStewardshipService amsService,
            InternationalPatientSummaryService ipsService,
            PriorAuthAndEligibilityService priorAuthService,
            PromQuestionnaireEngineService promService,
            SdohGravityReferralService sdohService) {
        this.consentService = Objects.requireNonNull(consentService, "FhirConsentEnforcementService is required");
        this.pgxService = Objects.requireNonNull(pgxService, "PharmacogenomicsCdsService is required");
        this.rpmService = Objects.requireNonNull(rpmService, "RemotePatientMonitoringService is required");
        this.subscriptionService = Objects.requireNonNull(subscriptionService, "FhirSubscriptionTopicService is required");
        this.bcmaService = Objects.requireNonNull(bcmaService, "BcmaMedicationSafetyService is required");
        this.amsService = Objects.requireNonNull(amsService, "AntimicrobialStewardshipService is required");
        this.ipsService = Objects.requireNonNull(ipsService, "InternationalPatientSummaryService is required");
        this.priorAuthService = Objects.requireNonNull(priorAuthService, "PriorAuthAndEligibilityService is required");
        this.promService = Objects.requireNonNull(promService, "PromQuestionnaireEngineService is required");
        this.sdohService = Objects.requireNonNull(sdohService, "SdohGravityReferralService is required");
    }

    // ========================================================================
    // 1. FHIR Consent, DS4P Privacy Segmentation & Break-the-Glass
    //    (HTTP routes served by dedicated ConsentController)
    // ========================================================================

    @Operation(summary = "Register an HL7 FHIR R4 Consent privacy directive with DS4P confidentiality categories")
    public ResponseEntity<FhirConsentDirective> registerConsentDirective(
            @RequestBody ConsentDirectiveRequest request) {
        return ResponseEntity.status(201).body(consentService.registerConsentDirective(request));
    }

    @Operation(summary = "List patient HL7 FHIR R4 Consent directives")
    public ResponseEntity<List<FhirConsentDirective>> listPatientConsents(
            @RequestParam(value = "patientId", required = false) UUID patientId) {
        return ResponseEntity.ok(consentService.listPatientConsents(patientId));
    }

    @Operation(summary = "Invoke a time-bound Break-the-Glass (BTG / ETREAT) emergency privacy override")
    public ResponseEntity<BreakTheGlassSession> invokeBreakTheGlass(
            @RequestBody BreakTheGlassRequest request) {
        return ResponseEntity.status(201).body(consentService.invokeBreakTheGlass(request));
    }

    @Operation(summary = "Revoke an active Break-the-Glass emergency token")
    public ResponseEntity<BreakTheGlassSession> revokeBreakTheGlass(
            @RequestParam("token") String token) {
        return ResponseEntity.ok(consentService.revokeBreakTheGlassToken(token));
    }

    @Operation(summary = "Evaluate clinical items under HL7 DS4P Data Segmentation for Privacy rules")
    public ResponseEntity<SegmentedAccessEvaluationResult> evaluateDs4pSegmentation(
            @RequestBody Ds4pEvaluationRequest request) {
        return ResponseEntity.ok(consentService.evaluateSegmentedAccess(
                request.patientId(),
                request.actorId(),
                request.purposeOfUse(),
                request.breakTheGlassToken(),
                request.clinicalItems()
        ));
    }

    // ========================================================================
    // 2. Pharmacogenomics (PGx) & CPIC Precision Medicine
    // ========================================================================

    @PostMapping(value = "/api/v1/pgx/genotypes", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a patient pharmacogenomic allele/diplotype and infer CPIC phenotype")
    public ResponseEntity<PatientPgxGeneProfile> registerPatientGenotype(
            @RequestBody PgxAlleleRegistrationRequest request) {
        return ResponseEntity.status(201).body(pgxService.registerPatientGenotype(request));
    }

    @GetMapping(value = "/api/v1/pgx/genotypes/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a patient's registered pharmacogenomic profiles")
    public ResponseEntity<List<PatientPgxGeneProfile>> getPatientGenotypes(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(pgxService.getPatientGenotypes(patientId));
    }

    @PostMapping(value = "/api/v1/pgx/evaluate-order", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate a candidate medication order against CPIC Level A pharmacogenomic guidelines")
    public ResponseEntity<PgxPrescribingAlert> evaluatePgxOrder(
            @RequestBody PgxPrescriptionCheckRequest request) {
        return ResponseEntity.ok(pgxService.evaluateMedicationOrder(request));
    }

    // ========================================================================
    // 3. Remote Patient Monitoring (RPM) & NEWS2 / MEWS / qSOFA Early Warning
    // ========================================================================

    @PostMapping(value = "/api/v1/rpm/devices", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a wearable or bedside IoMT monitoring device for a patient")
    public ResponseEntity<RegisteredWearableDevice> registerWearableDevice(
            @RequestBody WearableDeviceRegistrationRequest request) {
        return ResponseEntity.status(201).body(rpmService.registerWearableDevice(request));
    }

    @GetMapping(value = "/api/v1/rpm/devices/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List registered wearable IoMT devices for a patient")
    public ResponseEntity<List<RegisteredWearableDevice>> listPatientDevices(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(rpmService.listPatientDevices(patientId));
    }

    @PostMapping(value = "/api/v1/rpm/telemetry", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Ingest RPM vital-signs telemetry, persist FHIR Observations, and calculate NEWS2, MEWS, and qSOFA scores")
    public ResponseEntity<EarlyWarningAssessment> ingestRpmTelemetry(
            @RequestBody RpmVitalTelemetryPacket packet) {
        return ResponseEntity.ok(rpmService.ingestTelemetryAndEvaluateScores(packet));
    }

    @GetMapping(value = "/api/v1/rpm/assessments/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get historical NEWS2, MEWS, and qSOFA early warning assessments for a patient")
    public ResponseEntity<List<EarlyWarningAssessment>> getPatientRpmAssessments(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(rpmService.getPatientAssessments(patientId));
    }

    // ========================================================================
    // 4. HL7 FHIR R5-Backport Topic-Based Subscriptions
    // ========================================================================

    @GetMapping(value = "/api/v1/subscriptions/topics", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List canonical HL7 FHIR R5-Backport SubscriptionTopics")
    public ResponseEntity<List<SubscriptionTopicDefinition>> listSubscriptionTopics() {
        return ResponseEntity.ok(subscriptionService.listCanonicalTopics());
    }

    @PostMapping(value = "/api/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Create an HL7 FHIR R5-Backport topic-based Subscription (rest-hook, websocket, in-app)")
    public ResponseEntity<FhirSubscriptionRegistration> createSubscription(
            @RequestBody SubscriptionRegistrationRequest request) {
        return ResponseEntity.status(201).body(subscriptionService.createSubscription(request));
    }

    @GetMapping(value = "/api/v1/subscriptions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List active FHIR Subscriptions")
    public ResponseEntity<List<FhirSubscriptionRegistration>> listSubscriptions(
            @RequestParam(value = "facilityId", required = false) UUID facilityId,
            @RequestParam(value = "topicUrl", required = false) String topicUrl) {
        return ResponseEntity.ok(subscriptionService.listSubscriptions(facilityId, topicUrl));
    }

    @PostMapping(value = "/api/v1/subscriptions/dispatch", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Publish a clinical event to matching FHIR R5-Backport Subscriptions with HMAC-SHA256 signatures")
    public ResponseEntity<List<SubscriptionNotificationDelivery>> dispatchSubscriptionEvent(
            @RequestBody SubscriptionEventDispatchRequest request) {
        return ResponseEntity.ok(subscriptionService.publishClinicalEvent(request));
    }

    @GetMapping(value = "/api/v1/subscriptions/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List delivered FHIR Subscription notification bundles and HMAC-SHA256 signatures")
    public ResponseEntity<List<SubscriptionNotificationDelivery>> listSubscriptionDeliveries(
            @RequestParam(value = "subscriptionId", required = false) UUID subscriptionId,
            @RequestParam(value = "patientId", required = false) UUID patientId) {
        return ResponseEntity.ok(subscriptionService.listDeliveries(subscriptionId, patientId));
    }

    // ========================================================================
    // 5. Closed-Loop Barcoded Medication Administration (BCMA) & eMAR
    // ========================================================================

    @PostMapping(value = "/api/v1/bcma/orders", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Schedule an inpatient eMAR medication order with GS1 GTIN barcode and High-Alert LASA flags")
    public ResponseEntity<EmarScheduledMedicationOrder> scheduleEmarOrder(
            @RequestBody EmarScheduledOrderRequest request) {
        return ResponseEntity.status(201).body(bcmaService.scheduleEmarOrder(request));
    }

    @GetMapping(value = "/api/v1/bcma/orders/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List scheduled eMAR medication orders for a patient")
    public ResponseEntity<List<EmarScheduledMedicationOrder>> listPatientEmarOrders(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(bcmaService.listPatientScheduledOrders(patientId));
    }

    @PostMapping(value = "/api/v1/bcma/administer", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute bedside 5-Rights barcode scan, PGx check, and High-Alert LASA dual-nurse verification")
    public ResponseEntity<BcmaAdministrationRecord> verifyAndAdministerMedication(
            @RequestBody BedsideBarcodeScanRequest request) {
        return ResponseEntity.ok(bcmaService.verifyAndAdministerMedication(request));
    }

    @GetMapping(value = "/api/v1/bcma/administrations/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List BCMA medication administration records for a patient")
    public ResponseEntity<List<BcmaAdministrationRecord>> listPatientAdministrations(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(bcmaService.listPatientAdministrations(patientId));
    }

    // ========================================================================
    // 6. Antimicrobial Stewardship (AMS — WHO AWaRe & Cumulative Antibiogram)
    // ========================================================================

    @PostMapping(value = "/api/v1/ams/evaluate-order", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Classify an antibiotic order under WHO AWaRe (ACCESS, WATCH, RESERVE) and enforce stewardship rules")
    public ResponseEntity<AwareAntibioticOrderReview> evaluateAntibioticOrder(
            @RequestBody AntibioticStewardshipOrderRequest request) {
        return ResponseEntity.ok(amsService.evaluateAntibioticOrder(request));
    }

    @PostMapping(value = "/api/v1/ams/isolates", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record a microbiology isolate MIC susceptibility result and screen for MDRO (MRSA, ESBL, CRE, VRE)")
    public ResponseEntity<MicrobiologySusceptibilityRecord> recordIsolateSusceptibility(
            @RequestBody MicrobiologyIsolateRequest request) {
        return ResponseEntity.status(201).body(amsService.recordIsolateSusceptibility(request));
    }

    @GetMapping(value = "/api/v1/ams/antibiogram", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate a CLSI M39 facility Cumulative Antibiogram report")
    public ResponseEntity<CumulativeAntibiogramReport> generateCumulativeAntibiogram(
            @RequestParam(value = "facilityId", required = false) UUID facilityId) {
        return ResponseEntity.ok(amsService.generateCumulativeAntibiogram(facilityId));
    }

    // ========================================================================
    // 7. International Patient Summary (Delegated to InternationalPatientSummaryController)
    // ========================================================================

    public ResponseEntity<Map<String, Object>> seedPatientIpsData(
            IpsSeedClinicalSummaryRequest request) {
        ipsService.upsertPatientIpsClinicalData(request);
        return ResponseEntity.ok(Map.of("patientId", request.patientId(), "status", "IPS_CLINICAL_SUMMARY_UPDATED"));
    }

    public ResponseEntity<SignedIpsBundleResponse> generatePatientIpsSummary(
            UUID patientId,
            String actorHeader,
            String btgTokenHeader,
            String btgTokenParam,
            String jwsAlgorithm) {

        String resolvedBtg = (btgTokenParam != null && !btgTokenParam.isBlank()) ? btgTokenParam : btgTokenHeader;
        return ResponseEntity.ok(ipsService.generateSignedPatientSummary(
                patientId,
                actorHeader,
                resolvedBtg,
                jwsAlgorithm
        ));
    }

    public ResponseEntity<IpsSignatureVerificationResult> verifySignedIpsBundle(
            IpsJwsVerificationRequest request) {
        return ResponseEntity.ok(ipsService.verifySignedIpsBundle(
                request != null ? request.jwsCompactSignature() : null));
    }

    // ========================================================================
    // 8. Electronic Prior Authorization (Da Vinci PAS) & Coverage Eligibility
    // ========================================================================

    @PostMapping(value = "/api/v1/prior-auth/coverage", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a patient health insurance / UHC Coverage policy")
    public ResponseEntity<PatientCoveragePlan> registerPatientCoverage(
            @RequestBody PatientCoveragePlanRequest request) {
        return ResponseEntity.status(201).body(priorAuthService.registerPatientCoverage(request));
    }

    @PostMapping(value = "/api/v1/prior-auth/eligibility-check", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Execute a real-time HL7 FHIR R4 CoverageEligibilityRequest benefit check")
    public ResponseEntity<EligibilityVerificationResponse> verifyCoverageEligibility(
            @RequestBody EligibilityCheckRequest request) {
        return ResponseEntity.ok(priorAuthService.verifyCoverageEligibility(request));
    }

    @PostMapping(value = "/api/v1/prior-auth/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit an HL7 Da Vinci PAS Prior Authorization Claim and receive real-time adjudication")
    public ResponseEntity<PriorAuthorizationAdjudication> submitPriorAuthorization(
            @RequestBody PriorAuthSubmissionRequest request) {
        return ResponseEntity.ok(priorAuthService.submitPriorAuthorization(request));
    }

    @GetMapping(value = "/api/v1/prior-auth/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List Prior Authorization adjudications for a patient")
    public ResponseEntity<List<PriorAuthorizationAdjudication>> listPatientPriorAuthorizations(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(priorAuthService.listPatientPriorAuthorizations(patientId));
    }

    // ========================================================================
    // 9. Patient-Reported Outcome Measures (PROMs — PHQ-9, GAD-7, AUDIT-C, WHO-5)
    // ========================================================================

    @GetMapping(value = "/api/v1/proms/questionnaires", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List standardized HL7 FHIR R4 PROM Questionnaires (PHQ-9, GAD-7, AUDIT-C, WHO-5)")
    public ResponseEntity<List<QuestionnaireDefinitionSummary>> listPromQuestionnaires() {
        return ResponseEntity.ok(promService.listAvailableQuestionnaires());
    }

    @PostMapping(value = "/api/v1/proms/submit", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Submit and score a FHIR QuestionnaireResponse (PHQ-9, GAD-7, AUDIT-C, WHO-5) and generate a LOINC Observation")
    public ResponseEntity<PromAssessmentResult> submitPromQuestionnaire(
            @RequestBody PromSubmissionRequest request) {
        return ResponseEntity.ok(promService.submitAndScoreQuestionnaire(request));
    }

    @GetMapping(value = "/api/v1/proms/patient/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List scored PROM assessments for a patient")
    public ResponseEntity<List<PromAssessmentResult>> listPatientPromAssessments(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(promService.listPatientPromAssessments(patientId));
    }

    // ========================================================================
    // 10. Social Determinants of Health (SDoH — Gravity Project & CHW Referrals)
    // ========================================================================

    @PostMapping(value = "/api/v1/sdoh/screenings", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Evaluate a Gravity Project SDoH screening, generate ICD-10 Z-code Condition, and issue a closed-loop CHW referral")
    public ResponseEntity<SdohScreeningEvaluation> evaluateSdohScreening(
            @RequestBody SdohScreeningRequest request) {
        return ResponseEntity.status(201).body(sdohService.evaluateSdohScreening(request));
    }

    @GetMapping(value = "/api/v1/sdoh/screenings/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List SDoH screenings for a patient")
    public ResponseEntity<List<SdohScreeningEvaluation>> listPatientSdohScreenings(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(sdohService.listPatientScreenings(patientId));
    }

    @PostMapping(value = "/api/v1/sdoh/referrals/{referralId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update or close the loop on an SDoH community / CHW ServiceRequest referral")
    public ResponseEntity<SdohCommunityReferral> updateSdohReferralStatus(
            @PathVariable("referralId") UUID referralId,
            @RequestBody ReferralStatusUpdateRequest request) {
        return ResponseEntity.ok(sdohService.updateReferralStatus(referralId, request));
    }

    @GetMapping(value = "/api/v1/sdoh/referrals/{patientId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List closed-loop SDoH community referrals for a patient")
    public ResponseEntity<List<SdohCommunityReferral>> listPatientSdohReferrals(
            @PathVariable("patientId") UUID patientId) {
        return ResponseEntity.ok(sdohService.listPatientReferrals(patientId));
    }
}
