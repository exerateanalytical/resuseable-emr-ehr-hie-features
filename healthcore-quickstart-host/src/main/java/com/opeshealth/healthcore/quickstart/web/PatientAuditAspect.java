/*
 * =============================================================================
 * Opes HealthCore SDK — HIPAA Audit Trail AOP Aspect for PatientController
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.persistence.observability.CorrelationAndPhiGuard;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlController;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlTypes.GqlEncounter;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlTypes.GqlObservation;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlTypes.GqlPatient;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookRequest;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.audit.PhiRedactionConverter;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aspect-oriented HIPAA &amp; GDPR audit interceptor capturing timestamp, authenticated user ID,
 * action, resource identifier, facility scope, correlation ID, source IP, and outcome
 * for every incoming API invocation targeting FHIR REST, Bundle, CDS Hooks 2.0, and GraphQL endpoints.
 *
 * <p>Delegates to {@link FhirAuditLoggingService} (and {@link ClinicalAuditService}) to persist
 * tamper-evident SHA-256 hash-chained entries to the database and scrubs any accidental PHI
 * from diagnostic failure details via {@link PhiRedactionConverter}.</p>
 */
@Aspect
@Component
public class PatientAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(PatientAuditAspect.class);
    private static final Pattern PATIENT_REF_UUID_PATTERN = Pattern.compile(
            "\"reference\"\\s*:\\s*\"Patient/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

    public record HipaaPatientAuditRecord(
            Instant timestamp,
            String userId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            UUID patientId,
            String facilityKey,
            String httpMethod,
            String requestUri,
            String sourceIp,
            String correlationId,
            String outcome,
            long durationMs,
            String detail
    ) {}

    private final ClinicalAuditService clinicalAuditService;
    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final List<Consumer<HipaaPatientAuditRecord>> auditListeners = new CopyOnWriteArrayList<>();

    public PatientAuditAspect(ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider) {
        this(
                clinicalAuditServiceProvider != null ? clinicalAuditServiceProvider.getIfAvailable() : null,
                null
        );
    }

    @Autowired
    public PatientAuditAspect(
            ObjectProvider<ClinicalAuditService> clinicalAuditServiceProvider,
            ObjectProvider<FhirAuditLoggingService> fhirAuditLoggingServiceProvider) {
        this(
                clinicalAuditServiceProvider != null ? clinicalAuditServiceProvider.getIfAvailable() : null,
                fhirAuditLoggingServiceProvider != null ? fhirAuditLoggingServiceProvider.getIfAvailable() : null
        );
    }

    public PatientAuditAspect(ClinicalAuditService clinicalAuditService) {
        this(clinicalAuditService, new FhirAuditLoggingService(clinicalAuditService));
    }

    public PatientAuditAspect(
            ClinicalAuditService clinicalAuditService,
            FhirAuditLoggingService fhirAuditLoggingService) {
        this.clinicalAuditService = clinicalAuditService;
        this.fhirAuditLoggingService = fhirAuditLoggingService != null
                ? fhirAuditLoggingService
                : (clinicalAuditService != null ? new FhirAuditLoggingService(clinicalAuditService) : null);
    }

    public void registerListener(Consumer<HipaaPatientAuditRecord> listener) {
        if (listener != null) {
            auditListeners.add(listener);
        }
    }

    public FhirAuditLoggingService getFhirAuditLoggingService() {
        return fhirAuditLoggingService;
    }

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.PatientController) && execution(public * *(..))")
    public void patientControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.PractitionerController) && execution(public * *(..))")
    public void practitionerControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.ObservationController) && execution(public * *(..))")
    public void observationControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.EncounterController) && execution(public * *(..))")
    public void encounterControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.BundleController) && execution(public * *(..))")
    public void bundleControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.CdsHooksV2Controller) && execution(public * *(..))")
    public void cdsHooksControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlController) && execution(public * *(..))")
    public void graphqlControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.FhirBulkDataExportController) && execution(public * *(..))")
    public void bulkExportControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.IdentityMatchingController) && execution(public * *(..))")
    public void identityMatchingControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.DigitalHealthNextGenController) && execution(public * *(..))")
    public void digitalHealthNextGenControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.CertificateController) && execution(public * *(..))")
    public void certificateControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.InternationalPatientSummaryController) && execution(public * *(..))")
    public void internationalPatientSummaryControllerEndpoints() {}

    @Pointcut("within(com.opeshealth.healthcore.quickstart.web.ConsentController) && execution(public * *(..))")
    public void consentControllerEndpoints() {}

    @Pointcut("(within(com.opeshealth.healthcore.quickstart.web.SmartHealthLinkController) || within(com.opeshealth.healthcore.quickstart.web.FhirChartPortabilityController) || within(com.opeshealth.healthcore.quickstart.web.FederatedHieExchangeController) || within(com.opeshealth.healthcore.quickstart.web.ClinicalQualityMeasureController) || within(com.opeshealth.healthcore.quickstart.web.PatientMessagingController) || within(com.opeshealth.healthcore.quickstart.web.CarePlanPathwayController) || within(com.opeshealth.healthcore.quickstart.web.RadiologyWorklistController) || within(com.opeshealth.healthcore.quickstart.web.ClinicalResearchOmopController) || within(com.opeshealth.healthcore.quickstart.web.OfflineMediaSyncController) || within(com.opeshealth.healthcore.quickstart.web.PayerClearinghouseController) || within(com.opeshealth.healthcore.quickstart.web.IntegrationDlqReplayController) || within(com.opeshealth.healthcore.quickstart.web.TenantGovernanceController)) && execution(public * *(..))")
    public void enterpriseDigitalHealthExpansionEndpoints() {}

    @Pointcut("(within(com.opeshealth.healthcore.quickstart.web.AmbientClinicalScribeController) || within(com.opeshealth.healthcore.quickstart.web.MedicalCodingAiController) || within(com.opeshealth.healthcore.quickstart.web.ChartSummarizationAiController) || within(com.opeshealth.healthcore.quickstart.web.MultimodalImageTriageController) || within(com.opeshealth.healthcore.quickstart.web.ClinicalDecisionSupportAiController) || within(com.opeshealth.healthcore.quickstart.web.RevenueCycleAiController) || within(com.opeshealth.healthcore.quickstart.web.CalibratedClinicalDecisionController) || within(com.opeshealth.healthcore.quickstart.web.PatientDischargeCoachAiController) || within(com.opeshealth.healthcore.quickstart.web.SemanticClinicalSearchAiController) || within(com.opeshealth.healthcore.quickstart.web.PredictiveAnalyticsAiController) || within(com.opeshealth.healthcore.quickstart.web.ClinicalAiGovernanceController)) && execution(public * *(..))")
    public void clinicalAiSuiteEndpoints() {}

    @Pointcut("(patientControllerEndpoints() || practitionerControllerEndpoints() || observationControllerEndpoints() || encounterControllerEndpoints() || bundleControllerEndpoints() || cdsHooksControllerEndpoints() || graphqlControllerEndpoints() || bulkExportControllerEndpoints() || identityMatchingControllerEndpoints() || digitalHealthNextGenControllerEndpoints() || certificateControllerEndpoints() || internationalPatientSummaryControllerEndpoints() || consentControllerEndpoints() || enterpriseDigitalHealthExpansionEndpoints() || clinicalAiSuiteEndpoints())")
    public void auditedFhirControllerEndpoints() {}

    @Around("auditedFhirControllerEndpoints()")
    public Object interceptPatientApiRequest(ProceedingJoinPoint pjp) throws Throwable {
        Instant timestamp = Instant.now();
        long startNanos = System.nanoTime();

        HttpServletRequest req = currentRequest();
        String methodName = pjp.getSignature().getName();
        String resourceType = resolveResourceType(pjp);
        String action = resolveHipaaAction(resourceType, methodName, req);
        String userId = resolveUserId(req);
        String actorRole = resolveActorRole();
        String facilityKey = resolveFacilityKey(req);
        String httpMethod = req != null ? req.getMethod() : resolveHttpMethodFallback(resourceType, methodName);
        String fallbackUri = switch (resourceType) {
            case "Practitioner" -> "/api/v1/practitioners";
            case "Observation" -> "/api/v1/observations";
            case "Encounter" -> "/api/v1/encounters";
            case "Bundle" -> "/api/v1/bundles";
            case "BulkDataExport" -> "/api/v1/fhir/Patient/$export";
            case "IdentityMatching" -> "/api/v1/fhir/Patient/$match";
            case "DigitalHealthSuite" -> "/api/v1/fhir/Patient/$summary";
            case "DigitalHealthCertificate" -> "/api/v1/certificates";
            case "CDSHooks" -> "/api/v1/cds-services";
            case "GraphQL" -> "/graphql";
            default -> "/api/v1/patients";
        };
        String requestUri = req != null ? req.getRequestURI() : fallbackUri;
        String sourceIp = resolveSourceIp(req);
        String correlationId = resolveCorrelationId(req);
        UUID pathResourceId = extractUuidArgument(pjp.getArgs());

        String outcome = "SUCCESS";
        String detail = null;
        UUID resolvedResourceId = pathResourceId;
        Object result = null;
        try {
            result = pjp.proceed();
            if (resolvedResourceId == null) {
                resolvedResourceId = extractPatientIdFromResponse(result);
            }
            return result;
        } catch (Throwable ex) {
            outcome = "FAILURE";
            String rawReason = ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
            detail = PhiRedactionConverter.redact(rawReason);
            throw ex;
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String resourceId = resolvedResourceId != null
                    ? resolvedResourceId.toString()
                    : extractStringResourceId(resourceType, pjp.getArgs(), result);
            UUID patientId = resolvePatientId(resourceType, resolvedResourceId, pjp.getArgs(), result);

            HipaaPatientAuditRecord record = new HipaaPatientAuditRecord(
                    timestamp,
                    userId,
                    actorRole,
                    action,
                    resourceType,
                    resourceId,
                    patientId,
                    facilityKey,
                    httpMethod,
                    requestUri,
                    sourceIp,
                    correlationId,
                    outcome,
                    durationMs,
                    detail
            );
            emitHipaaAudit(record);
        }
    }

    private void emitHipaaAudit(HipaaPatientAuditRecord record) {
        if ("FAILURE".equals(record.outcome())) {
            log.warn("HIPAA_AUDIT timestamp={} userId={} role={} action={} resourceType={} resourceId={} facilityId={} method={} uri={} ip={} correlationId={} outcome={} durationMs={} detail={}",
                    record.timestamp(), record.userId(), record.actorRole(), record.action(),
                    record.resourceType(), record.resourceId(), record.facilityKey(),
                    record.httpMethod(), record.requestUri(), record.sourceIp(),
                    record.correlationId(), record.outcome(), record.durationMs(), record.detail());
        } else {
            log.info("HIPAA_AUDIT timestamp={} userId={} role={} action={} resourceType={} resourceId={} facilityId={} method={} uri={} ip={} correlationId={} outcome={} durationMs={}",
                    record.timestamp(), record.userId(), record.actorRole(), record.action(),
                    record.resourceType(), record.resourceId(), record.facilityKey(),
                    record.httpMethod(), record.requestUri(), record.sourceIp(),
                    record.correlationId(), record.outcome(), record.durationMs());
        }

        for (Consumer<HipaaPatientAuditRecord> listener : auditListeners) {
            try {
                listener.accept(record);
            } catch (Exception ex) {
                log.debug("Audit listener error ignored: {}", ex.getMessage());
            }
        }

        if (fhirAuditLoggingService != null) {
            try {
                fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                        record.facilityKey(),
                        record.userId(),
                        record.actorRole(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.patientId(),
                        record.httpMethod(),
                        record.requestUri(),
                        record.sourceIp(),
                        record.correlationId(),
                        record.outcome(),
                        record.durationMs(),
                        record.detail()
                ));
                return;
            } catch (Exception ex) {
                log.warn("Unable to persist FhirAuditLoggingService entry for action={} correlationId={}: {}",
                        record.action(), record.correlationId(), ex.getMessage());
            }
        }

        if (clinicalAuditService != null) {
            try {
                clinicalAuditService.recordEventWithCorrelation(
                        record.facilityKey(),
                        record.userId(),
                        record.actorRole(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.patientId(),
                        record.outcome(),
                        record.detail(),
                        record.sourceIp(),
                        record.correlationId()
                );
            } catch (Exception ex) {
                log.warn("Unable to persist ClinicalAuditService entry for action={} correlationId={}: {}",
                        record.action(), record.correlationId(), ex.getMessage());
            }
        }
    }

    private static String resolveResourceType(ProceedingJoinPoint pjp) {
        Object target = pjp.getTarget();
        Class<?> declaring = pjp.getSignature() != null ? pjp.getSignature().getDeclaringType() : null;
        if (target instanceof PractitionerController
                || (declaring != null && PractitionerController.class.isAssignableFrom(declaring))) {
            return "Practitioner";
        }
        if (target instanceof ObservationController
                || (declaring != null && ObservationController.class.isAssignableFrom(declaring))) {
            return "Observation";
        }
        if (target instanceof EncounterController
                || (declaring != null && EncounterController.class.isAssignableFrom(declaring))) {
            return "Encounter";
        }
        if (target instanceof BundleController
                || (declaring != null && BundleController.class.isAssignableFrom(declaring))) {
            return "Bundle";
        }
        if (target instanceof CdsHooksV2Controller
                || (declaring != null && CdsHooksV2Controller.class.isAssignableFrom(declaring))) {
            return "CDSHooks";
        }
        if (target instanceof FhirGraphQlController
                || (declaring != null && FhirGraphQlController.class.isAssignableFrom(declaring))) {
            return "GraphQL";
        }
        if (target instanceof FhirBulkDataExportController
                || (declaring != null && FhirBulkDataExportController.class.isAssignableFrom(declaring))) {
            return "BulkDataExport";
        }
        if (target instanceof IdentityMatchingController
                || (declaring != null && IdentityMatchingController.class.isAssignableFrom(declaring))) {
            return "IdentityMatching";
        }
        return "Patient";
    }

    private static String extractStringResourceId(String resourceType, Object[] args, Object result) {
        if ("GraphQL".equals(resourceType)) {
            if (result instanceof GqlPatient pat && pat.id() != null) return pat.id();
            if (result instanceof GqlEncounter enc && enc.id() != null) return enc.id();
            if (result instanceof GqlObservation obs && obs.id() != null) return obs.id();
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof GqlPatient pat && pat.id() != null) return pat.id();
                    if (arg instanceof GqlEncounter enc && enc.id() != null) return enc.id();
                    if (arg instanceof GqlObservation obs && obs.id() != null) return obs.id();
                }
            }
        }
        if ("CDSHooks".equals(resourceType) && args != null) {
            for (Object arg : args) {
                if (arg instanceof String s && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }

    private static UUID resolvePatientId(String resourceType, UUID resolvedResourceId, Object[] args, Object result) {
        if ("Patient".equals(resourceType)) {
            return resolvedResourceId;
        }
        if ("GraphQL".equals(resourceType)) {
            if (result instanceof GqlPatient pat && pat.id() != null) {
                return parseUuidQuietly(pat.id());
            }
            if (result instanceof GqlEncounter enc && enc.subject() != null) {
                return parseRefUuidQuietly(enc.subject().reference());
            }
            if (result instanceof GqlObservation obs && obs.subject() != null) {
                return parseRefUuidQuietly(obs.subject().reference());
            }
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof GqlPatient pat && pat.id() != null) {
                        return parseUuidQuietly(pat.id());
                    }
                    if (arg instanceof GqlEncounter enc && enc.subject() != null) {
                        return parseRefUuidQuietly(enc.subject().reference());
                    }
                    if (arg instanceof GqlObservation obs && obs.subject() != null) {
                        return parseRefUuidQuietly(obs.subject().reference());
                    }
                }
            }
            return null;
        }
        if ("CDSHooks".equals(resourceType) && args != null) {
            for (Object arg : args) {
                if (arg instanceof CdsHookRequest hookReq && hookReq.context() != null) {
                    Object patIdObj = hookReq.context().get("patientId");
                    if (patIdObj != null) {
                        return parseRefUuidQuietly(patIdObj.toString());
                    }
                }
            }
        }
        if (("Observation".equals(resourceType) || "Encounter".equals(resourceType) || "Bundle".equals(resourceType))
                && result instanceof ResponseEntity<?> response
                && response.getBody() instanceof String body) {
            Matcher matcher = PATIENT_REF_UUID_PATTERN.matcher(body);
            if (matcher.find()) {
                return parseUuidQuietly(matcher.group(1));
            }
        }
        return null;
    }

    private static String resolveHipaaAction(String resourceType, String methodName, HttpServletRequest req) {
        String prefix = switch (resourceType) {
            case "Practitioner" -> "HIPAA_PRACTITIONER_";
            case "Observation" -> "HIPAA_OBSERVATION_";
            case "Encounter" -> "HIPAA_ENCOUNTER_";
            case "Bundle" -> "HIPAA_BUNDLE_";
            case "BulkDataExport" -> "HIPAA_BULK_EXPORT_";
            case "CDSHooks" -> "HIPAA_CDS_HOOKS_";
            case "GraphQL" -> "HIPAA_GRAPHQL_";
            default -> "HIPAA_PATIENT_";
        };
        if ("GraphQL".equals(resourceType) || "CDSHooks".equals(resourceType)) {
            return prefix + methodName.toUpperCase(java.util.Locale.ROOT);
        }
        if ("BulkDataExport".equals(resourceType)) {
            return switch (methodName) {
                case "kickOffPatientCohortExport" -> prefix + "PATIENT_COHORT";
                case "kickOffPractitionerCohortExport" -> prefix + "PRACTITIONER_COHORT";
                case "createCustomCohortExport" -> prefix + "CUSTOM_COHORT";
                case "getExportJobStatus" -> prefix + "STATUS";
                case "downloadExportNdjsonFile" -> prefix + "DOWNLOAD_NDJSON";
                case "downloadExportAsBundle" -> prefix + "DOWNLOAD_BUNDLE";
                case "deleteExportJob" -> prefix + "DELETE";
                default -> prefix + methodName.toUpperCase(java.util.Locale.ROOT);
            };
        }
        return switch (methodName) {
            case "searchPatients", "searchPractitioners", "searchObservations", "searchEncounters" -> prefix + "SEARCH";
            case "getPatientById", "getPractitionerById", "getObservationById", "getEncounterById" -> prefix + "READ";
            case "createPatient", "createPractitioner", "createObservation", "createEncounter" -> prefix + "CREATE";
            case "updatePatient", "updatePractitioner", "updateObservation", "updateEncounter" -> prefix + "UPDATE";
            case "deactivatePatient", "deactivatePractitioner", "deactivateObservation", "deactivateEncounter" -> prefix + "DEACTIVATE";
            case "processBundle" -> prefix + "PROCESS";
            default -> prefix + (req != null ? req.getMethod() : methodName.toUpperCase(java.util.Locale.ROOT));
        };
    }

    private static String resolveHttpMethodFallback(String resourceType, String methodName) {
        if ("GraphQL".equals(resourceType)) {
            return "POST";
        }
        if ("BulkDataExport".equals(resourceType)) {
            return switch (methodName) {
                case "createCustomCohortExport" -> "POST";
                case "deleteExportJob" -> "DELETE";
                default -> "GET";
            };
        }
        if ("CDSHooks".equals(resourceType)) {
            return "discoverServices".equals(methodName) ? "GET" : "POST";
        }
        return switch (methodName) {
            case "searchPatients", "getPatientById",
                 "searchPractitioners", "getPractitionerById",
                 "searchObservations", "getObservationById",
                 "searchEncounters", "getEncounterById" -> "GET";
            case "createPatient", "createPractitioner", "createObservation", "createEncounter", "processBundle" -> "POST";
            case "updatePatient", "updatePractitioner", "updateObservation", "updateEncounter" -> "PUT";
            case "deactivatePatient", "deactivatePractitioner", "deactivateObservation", "deactivateEncounter" -> "DELETE";
            default -> "HTTP";
        };
    }

    private static String resolveUserId(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !auth.getName().isBlank()) {
            return auth.getName();
        }
        if (req != null) {
            String actorHeader = req.getHeader("X-Actor-Id");
            if (actorHeader != null && !actorHeader.isBlank()) {
                return actorHeader.trim();
            }
        }
        return "ANONYMOUS";
    }

    private static String resolveActorRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            return auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a != null && a.startsWith("ROLE_"))
                    .findFirst()
                    .orElseGet(() -> auth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .findFirst()
                            .orElse("UNKNOWN"));
        }
        return "UNKNOWN";
    }

    private static String resolveFacilityKey(HttpServletRequest req) {
        var ctxFac = FacilityContext.getFacilityId();
        if (ctxFac.isPresent()) {
            return ctxFac.get().toString();
        }
        if (req != null) {
            String header = req.getHeader("X-Facility-Id");
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        return "GLOBAL";
    }

    private static String resolveSourceIp(HttpServletRequest req) {
        if (req == null) {
            return "internal";
        }
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return req.getRemoteAddr() != null ? req.getRemoteAddr() : "unknown";
    }

    private static String resolveCorrelationId(HttpServletRequest req) {
        String mdc = MDC.get(CorrelationAndPhiGuard.MDC_CORRELATION_ID);
        if (mdc != null && !mdc.isBlank()) {
            return mdc;
        }
        if (req != null) {
            String header = req.getHeader("X-Correlation-Id");
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }
        return UUID.randomUUID().toString();
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servletAttrs ? servletAttrs.getRequest() : null;
    }

    private static UUID extractUuidArgument(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }

    private static UUID extractPatientIdFromResponse(Object result) {
        if (result instanceof ResponseEntity<?> response) {
            URI location = response.getHeaders().getLocation();
            if (location != null) {
                String path = location.getPath();
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < path.length()) {
                    return parseUuidQuietly(path.substring(lastSlash + 1));
                }
            }
            List<String> contentLocations = response.getHeaders().get("Content-Location");
            if (contentLocations != null && !contentLocations.isEmpty()) {
                String cl = contentLocations.get(0);
                int lastSlash = cl.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash + 1 < cl.length()) {
                    return parseUuidQuietly(cl.substring(lastSlash + 1));
                }
            }
        }
        return null;
    }

    private static UUID parseUuidQuietly(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static UUID parseRefUuidQuietly(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String trimmed = ref.trim();
        int slash = trimmed.lastIndexOf('/');
        String candidate = slash >= 0 && slash + 1 < trimmed.length() ? trimmed.substring(slash + 1) : trimmed;
        return parseUuidQuietly(candidate);
    }
}
