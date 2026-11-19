/*
 * =============================================================================
 * Opes HealthCore SDK — FHIR R4 Bundle Batch & Transaction Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService.CdsHookResponse;
import com.opeshealth.healthcore.quickstart.service.EncounterService.EncounterSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.PractitionerService.PractitionerSearchCriteria;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.service.PatientService.PatientSearchCriteria;
import com.opeshealth.healthcore.service.PatientService.StringSearchFilter;
import jakarta.persistence.EntityNotFoundException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Processes HL7 FHIR R4 {@code Bundle} resources of type {@code transaction}
 * (atomic all-or-nothing execution with {@code urn:uuid:*} placeholder reference
 * resolution across {@code Practitioner}, {@code Patient}, {@code Encounter}, and
 * {@code Observation}) and {@code batch} (isolated per-entry execution returning
 * individual HTTP statuses and {@link OperationOutcome} errors).
 */
@Service
public class BundleService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);

    /**
     * HAPI FHIR R4 structural &amp; schema validator embedded within {@link BundleService}.
     */
    public static final class FhirBundleValidatorService {

        private final FhirContext fhirContext;
        private final FhirValidator schemaValidator;

        public FhirBundleValidatorService() {
            this(FHIR_R4);
        }

        public FhirBundleValidatorService(FhirContext fhirContext) {
            this.fhirContext = Objects.requireNonNull(fhirContext, "FhirContext is required");
            this.schemaValidator = this.fhirContext.newValidator();
            this.schemaValidator.setValidateAgainstStandardSchema(true);
            this.schemaValidator.setValidateAgainstStandardSchematron(false);
        }

        public Bundle parseStrict(String bundleJson) {
            if (isBlank(bundleJson)) {
                throw new IllegalArgumentException("FHIR Bundle JSON payload must not be empty");
            }
            try {
                IParser strictParser = fhirContext.newJsonParser()
                        .setParserErrorHandler(new StrictErrorHandler());
                Bundle parsed = strictParser.parseResource(Bundle.class, bundleJson);
                if (parsed == null || !"Bundle".equals(parsed.fhirType())) {
                    throw new IllegalArgumentException("FHIR payload resourceType must be 'Bundle'");
                }
                return parsed;
            } catch (IllegalArgumentException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IllegalArgumentException("HL7 FHIR R4 strict Bundle parse failure: " + ex.getMessage(), ex);
            }
        }

        public ValidationResult validateWithResult(Bundle bundle) {
            if (bundle == null) {
                throw new IllegalArgumentException("FHIR R4 Bundle resource must not be null");
            }
            return schemaValidator.validateWithResult(bundle);
        }

        public void validateAgainstSchema(Bundle bundle) {
            ValidationResult result = validateWithResult(bundle);
            if (!result.isSuccessful()) {
                List<String> diagnostics = result.getMessages().stream()
                        .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
                        .map(SingleValidationMessage::toString)
                        .toList();
                String summary = diagnostics.isEmpty()
                        ? result.getMessages().stream().map(SingleValidationMessage::toString).reduce((a, b) -> a + "; " + b).orElse("Unknown FHIR R4 Bundle schema violation")
                        : String.join("; ", diagnostics);
                throw new IllegalArgumentException("HL7 FHIR R4 Bundle schema validation failed: " + summary);
            }
        }
    }

    public record BundleExecutionResult(
            Bundle responseBundle,
            int cdsAlertCount,
            String maxCdsIndicator
    ) {}

    private record IndexedEntry(int index, BundleEntryComponent entry) {}

    private static final class PendingCdsEvaluations {
        final List<Patient> patients = new ArrayList<>();
        final List<Encounter> encounters = new ArrayList<>();
        final List<Observation> observations = new ArrayList<>();
    }

    private final PatientService patientService;
    private final PractitionerService practitionerService;
    private final EncounterService encounterService;
    private final ObservationService observationService;
    private final PlatformTransactionManager transactionManager;
    private final CdsHooksService cdsHooksService;
    private final OutboxRelayService.OutboxRepository outboxRepo;
    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final ConsentService consentService;
    private final FhirBundleValidatorService validatorService;

    public BundleService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService) {
        this(patientService, practitionerService, encounterService, observationService, null, null, null, null, null);
    }

    public BundleService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            PlatformTransactionManager transactionManager,
            CdsHooksService cdsHooksService,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this(patientService, practitionerService, encounterService, observationService,
                transactionManager, cdsHooksService, outboxRepo, null, null);
    }

    @Autowired
    public BundleService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            ObjectProvider<PlatformTransactionManager> txManagerProvider,
            ObjectProvider<CdsHooksService> cdsHooksServiceProvider,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider,
            ObjectProvider<FhirAuditLoggingService> auditLoggingServiceProvider,
            ObjectProvider<ConsentService> consentServiceProvider) {
        this(
                patientService,
                practitionerService,
                encounterService,
                observationService,
                txManagerProvider != null ? txManagerProvider.getIfAvailable() : null,
                cdsHooksServiceProvider != null ? cdsHooksServiceProvider.getIfAvailable() : null,
                outboxRepoProvider != null ? outboxRepoProvider.getIfAvailable() : null,
                auditLoggingServiceProvider != null ? auditLoggingServiceProvider.getIfAvailable() : null,
                consentServiceProvider != null ? consentServiceProvider.getIfAvailable() : null
        );
    }

    public BundleService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            PlatformTransactionManager transactionManager,
            CdsHooksService cdsHooksService,
            OutboxRelayService.OutboxRepository outboxRepo,
            FhirAuditLoggingService fhirAuditLoggingService) {
        this(patientService, practitionerService, encounterService, observationService,
                transactionManager, cdsHooksService, outboxRepo, fhirAuditLoggingService, null);
    }

    public BundleService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            PlatformTransactionManager transactionManager,
            CdsHooksService cdsHooksService,
            OutboxRelayService.OutboxRepository outboxRepo,
            FhirAuditLoggingService fhirAuditLoggingService,
            ConsentService consentService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.practitionerService = Objects.requireNonNull(practitionerService, "PractitionerService is required");
        this.encounterService = Objects.requireNonNull(encounterService, "EncounterService is required");
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
        this.transactionManager = transactionManager;
        this.cdsHooksService = cdsHooksService;
        this.outboxRepo = outboxRepo;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        this.consentService = consentService;
        this.validatorService = new FhirBundleValidatorService(FHIR_R4);
    }

    public FhirBundleValidatorService getValidatorService() {
        return validatorService;
    }

    public String encodeFhirResource(IBaseResource resource) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(resource);
    }

    public Bundle parseStrictBundle(String bundleJson) {
        return validatorService.parseStrict(bundleJson);
    }

    /**
     * Validates and processes an HL7 FHIR R4 {@code transaction} or {@code batch} Bundle.
     */
    @ClinicalAudit(action = "BUNDLE_PROCESS", resourceType = "Bundle")
    public Bundle processBundle(UUID facilityId, String bundleJson) {
        return processBundleWithSummary(facilityId, bundleJson).responseBundle();
    }

    /**
     * Validates and processes an HL7 FHIR R4 {@code transaction} or {@code batch} Bundle,
     * returning the response {@link Bundle} plus aggregated CDS Hooks 2.0 alert metadata.
     */
    @ClinicalAudit(action = "BUNDLE_PROCESS", resourceType = "Bundle")
    public BundleExecutionResult processBundleWithSummary(UUID facilityId, String bundleJson) {
        UUID scopedFacility = facilityId != null ? facilityId : DEFAULT_FACILITY_ID;
        Bundle inbound = validatorService.parseStrict(bundleJson);
        validateBundleEnvelope(inbound);

        PendingCdsEvaluations cdsQueue = new PendingCdsEvaluations();
        Bundle responseBundle = inbound.getType() == BundleType.TRANSACTION
                ? executeTransaction(scopedFacility, inbound, cdsQueue)
                : executeBatch(scopedFacility, inbound, cdsQueue);

        int totalAlerts = 0;
        String maxIndicator = "none";
        if (cdsHooksService != null) {
            for (Patient pat : cdsQueue.patients) {
                CdsHookResponse resp = cdsHooksService.triggerPatientInteraction(pat, scopedFacility);
                totalAlerts += resp.cards().size();
                maxIndicator = maxSeverity(maxIndicator, resp.highestIndicator());
            }
            for (Encounter enc : cdsQueue.encounters) {
                CdsHookResponse resp = cdsHooksService.triggerEncounterInteraction(enc, scopedFacility);
                totalAlerts += resp.cards().size();
                maxIndicator = maxSeverity(maxIndicator, resp.highestIndicator());
            }
            for (Observation obs : cdsQueue.observations) {
                CdsHookResponse resp = cdsHooksService.triggerObservationInteraction(obs, scopedFacility);
                totalAlerts += resp.cards().size();
                maxIndicator = maxSeverity(maxIndicator, resp.highestIndicator());
            }
        }

        emitOutboxEvent(
                "Bundle",
                UUID.nameUUIDFromBytes(responseBundle.getId().getBytes(StandardCharsets.UTF_8)),
                inbound.getType() == BundleType.TRANSACTION ? "BundleTransactionProcessedEvent" : "BundleBatchProcessedEvent",
                "{\"bundleId\":\"" + responseBundle.getId() + "\",\"type\":\"" + responseBundle.getType().toCode()
                        + "\",\"entryCount\":" + responseBundle.getEntry().size() + "}"
        );

        return new BundleExecutionResult(responseBundle, totalAlerts, maxIndicator);
    }

    private void validateBundleEnvelope(Bundle inbound) {
        validatorService.validateAgainstSchema(inbound);
        if (inbound.getType() != BundleType.TRANSACTION && inbound.getType() != BundleType.BATCH) {
            throw new IllegalArgumentException(
                    "FHIR Bundle.type must be 'transaction' or 'batch'; found: "
                            + (inbound.getType() != null ? inbound.getType().toCode() : "null"));
        }
        if (inbound.getEntry().isEmpty()) {
            throw new IllegalArgumentException("FHIR Bundle.entry must contain at least one entry");
        }
        for (int i = 0; i < inbound.getEntry().size(); i++) {
            BundleEntryComponent entry = inbound.getEntry().get(i);
            if (!entry.hasRequest() || entry.getRequest().getMethod() == null || isBlank(entry.getRequest().getUrl())) {
                throw new IllegalArgumentException("Bundle entry[" + i + "] must include request.method and request.url");
            }
            HTTPVerb verb = entry.getRequest().getMethod();
            if ((verb == HTTPVerb.POST || verb == HTTPVerb.PUT) && !entry.hasResource()) {
                throw new IllegalArgumentException("Bundle entry[" + i + "] with " + verb.toCode() + " must contain a resource");
            }
        }
        if (consentService != null) {
            consentService.assertFhirResourceAiConsentValid(inbound);
        }
    }

    /**
     * Validates an HL7 FHIR R4 {@link Bundle} against schema and patient AI-Assisted Processing
     * consent flags for the requested automated clinical AI scopes.
     */
    public ConsentService.AiConsentValidationOutcome validateBundleForAiProcessing(
            Bundle inbound,
            java.util.Set<ConsentService.AiProcessingScope> requestedScopes) {
        validatorService.validateAgainstSchema(inbound);
        if (consentService == null) {
            throw new IllegalStateException("ConsentService is required for AI-Assisted Processing consent validation");
        }
        ConsentService.AiConsentValidationOutcome outcome =
                consentService.validateFhirResourceAiConsent(inbound, requestedScopes);
        if (!outcome.allowed()) {
            consentService.assertFhirResourceAiConsentValid(inbound, requestedScopes);
        }
        return outcome;
    }

    /**
     * Executes all entries atomically within a single transaction boundary, resolving
     * placeholder {@code urn:uuid:*} references in HL7 FHIR R4 dependency order.
     */
    private Bundle executeTransaction(UUID facilityId, Bundle inbound, PendingCdsEvaluations cdsQueue) {
        if (transactionManager != null) {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            Bundle txResult = template.execute(status -> executeTransactionEntries(facilityId, inbound, cdsQueue));
            if (txResult == null) {
                throw new IllegalStateException("FHIR Transaction returned null Bundle");
            }
            return txResult;
        }
        return executeTransactionEntries(facilityId, inbound, cdsQueue);
    }

    private Bundle executeTransactionEntries(UUID facilityId, Bundle inbound, PendingCdsEvaluations cdsQueue) {
        int count = inbound.getEntry().size();
        BundleEntryComponent[] orderedOutputs = new BundleEntryComponent[count];
        Map<String, String> urnMap = new LinkedHashMap<>();

        List<IndexedEntry> deletes = new ArrayList<>();
        List<IndexedEntry> posts = new ArrayList<>();
        List<IndexedEntry> puts = new ArrayList<>();
        List<IndexedEntry> gets = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            BundleEntryComponent entry = inbound.getEntry().get(i);
            HTTPVerb verb = entry.getRequest().getMethod();
            switch (verb) {
                case DELETE -> deletes.add(new IndexedEntry(i, entry));
                case POST -> posts.add(new IndexedEntry(i, entry));
                case PUT -> puts.add(new IndexedEntry(i, entry));
                case GET -> gets.add(new IndexedEntry(i, entry));
                default -> throw new IllegalArgumentException("Unsupported Bundle request.method in transaction: " + verb.toCode());
            }
        }

        // Order POST creates so Practitioner -> Patient -> Encounter -> Observation resolve dependencies
        posts.sort(Comparator.comparingInt(ie -> resourceDependencyRank(ie.entry())));

        for (IndexedEntry item : deletes) {
            orderedOutputs[item.index()] = executeSingleEntry(facilityId, item.entry(), urnMap, true, cdsQueue);
        }
        for (IndexedEntry item : posts) {
            BundleEntryComponent resp = executeSingleEntry(facilityId, item.entry(), urnMap, true, cdsQueue);
            String fullUrl = item.entry().getFullUrl();
            if (!isBlank(fullUrl) && resp.hasFullUrl()) {
                urnMap.put(fullUrl.trim(), resp.getFullUrl());
            }
            orderedOutputs[item.index()] = resp;
        }
        for (IndexedEntry item : puts) {
            BundleEntryComponent resp = executeSingleEntry(facilityId, item.entry(), urnMap, true, cdsQueue);
            String fullUrl = item.entry().getFullUrl();
            if (!isBlank(fullUrl) && resp.hasFullUrl()) {
                urnMap.put(fullUrl.trim(), resp.getFullUrl());
            }
            orderedOutputs[item.index()] = resp;
        }
        for (IndexedEntry item : gets) {
            orderedOutputs[item.index()] = executeSingleEntry(facilityId, item.entry(), urnMap, true, cdsQueue);
        }

        Bundle response = new Bundle();
        response.setId(UUID.randomUUID().toString());
        response.setType(BundleType.TRANSACTIONRESPONSE);
        response.setTimestamp(new Date());
        for (BundleEntryComponent out : orderedOutputs) {
            response.addEntry(out);
        }
        return response;
    }

    /**
     * Executes each batch entry independently in isolated REQUIRES_NEW transactions so failures
     * on one entry emit per-entry OperationOutcome errors without affecting sibling entries.
     */
    private Bundle executeBatch(UUID facilityId, Bundle inbound, PendingCdsEvaluations cdsQueue) {
        Bundle response = new Bundle();
        response.setId(UUID.randomUUID().toString());
        response.setType(BundleType.BATCHRESPONSE);
        response.setTimestamp(new Date());

        TransactionTemplate perEntryTx = null;
        if (transactionManager != null) {
            perEntryTx = new TransactionTemplate(transactionManager);
            perEntryTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        for (BundleEntryComponent entry : inbound.getEntry()) {
            PendingCdsEvaluations entryCds = new PendingCdsEvaluations();
            try {
                BundleEntryComponent out;
                if (perEntryTx != null) {
                    out = perEntryTx.execute(status -> executeSingleEntry(facilityId, entry, Map.of(), false, entryCds));
                } else {
                    out = executeSingleEntry(facilityId, entry, Map.of(), false, entryCds);
                }
                response.addEntry(out);
                cdsQueue.patients.addAll(entryCds.patients);
                cdsQueue.encounters.addAll(entryCds.encounters);
                cdsQueue.observations.addAll(entryCds.observations);
            } catch (Exception ex) {
                response.addEntry(buildBatchErrorEntry(ex));
            }
        }
        return response;
    }

    private BundleEntryComponent executeSingleEntry(
            UUID facilityId,
            BundleEntryComponent entry,
            Map<String, String> urnMap,
            boolean resolvePlaceholders,
            PendingCdsEvaluations cdsQueue) {
        BundleEntryRequestComponent req = entry.getRequest();
        HTTPVerb verb = req.getMethod();
        String rawUrl = req.getUrl().trim();
        if (resolvePlaceholders && !urnMap.isEmpty()) {
            rawUrl = replaceUrnPlaceholdersInUrl(rawUrl, urnMap);
        }
        ParsedRequestUrl parsedUrl = parseRequestUrl(rawUrl);
        String entryAction = "HIPAA_BUNDLE_ENTRY_" + verb.toCode();

        try {
            BundleEntryComponent resp = switch (verb) {
                case POST -> handlePost(facilityId, parsedUrl, entry.getResource(), req, urnMap, resolvePlaceholders, cdsQueue);
                case PUT -> handlePut(parsedUrl, entry.getResource(), req, urnMap, resolvePlaceholders, cdsQueue);
                case GET -> handleGet(facilityId, parsedUrl);
                case DELETE -> handleDelete(parsedUrl);
                default -> throw new IllegalArgumentException("Unsupported Bundle request.method: " + verb.toCode());
            };
            recordBundleEntryAudit(facilityId, entryAction, parsedUrl, resp, "SUCCESS", null);
            return resp;
        } catch (RuntimeException ex) {
            recordBundleEntryAudit(facilityId, entryAction, parsedUrl, null, "FAILURE",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
            throw ex;
        }
    }

    private void recordBundleEntryAudit(
            UUID facilityId,
            String action,
            ParsedRequestUrl parsedUrl,
            BundleEntryComponent resp,
            String outcome,
            String reason) {
        if (fhirAuditLoggingService == null) {
            return;
        }
        try {
            String resolvedId = parsedUrl.resourceId() != null ? parsedUrl.resourceId().toString() : null;
            UUID patientId = null;
            if (resp != null && resp.hasResource()) {
                Resource res = resp.getResource();
                if (resolvedId == null && res.hasIdElement() && !isBlank(res.getIdElement().getIdPart()) && !(res instanceof Bundle)) {
                    resolvedId = res.getIdElement().getIdPart();
                }
                if (res instanceof Patient pat && pat.hasIdElement()) {
                    patientId = tryParseUuid(pat.getIdElement().getIdPart());
                } else if (res instanceof Encounter enc && enc.hasSubject()) {
                    patientId = extractReferenceUuid(enc.getSubject().getReference());
                } else if (res instanceof Observation obs && obs.hasSubject()) {
                    patientId = extractReferenceUuid(obs.getSubject().getReference());
                }
            }
            if (patientId == null && "Patient".equals(parsedUrl.resourceType()) && parsedUrl.resourceId() != null) {
                patientId = parsedUrl.resourceId();
            }
            fhirAuditLoggingService.recordFhirResourceAccess(
                    action,
                    parsedUrl.resourceType(),
                    resolvedId,
                    patientId,
                    outcome,
                    reason
            );
        } catch (Exception ignored) {
            // Do not disrupt clinical bundle transaction if secondary entry audit callback fails
        }
    }

    private static UUID extractReferenceUuid(String ref) {
        if (isBlank(ref)) return null;
        int slash = ref.lastIndexOf('/');
        String idPart = slash >= 0 && slash + 1 < ref.length() ? ref.substring(slash + 1) : ref;
        return tryParseUuid(idPart);
    }

    private static UUID tryParseUuid(String raw) {
        if (isBlank(raw)) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private BundleEntryComponent handlePost(
            UUID facilityId,
            ParsedRequestUrl url,
            Resource resource,
            BundleEntryRequestComponent req,
            Map<String, String> urnMap,
            boolean resolvePlaceholders,
            PendingCdsEvaluations cdsQueue) {
        if (url.resourceId() != null) {
            throw new IllegalArgumentException("POST URL must target a resource type collection without an ID: " + url.resourceType());
        }
        if (resource == null || !Objects.equals(url.resourceType(), resource.fhirType())) {
            throw new IllegalArgumentException("Bundle entry request URL '" + url.resourceType()
                    + "' does not match resourceType '" + (resource != null ? resource.fhirType() : "null") + "'");
        }
        Resource working = resource.copy();
        if (resolvePlaceholders) {
            resolveResourceReferences(working, urnMap);
        }

        // Optional FHIR R4 Conditional Create (If-None-Exist)
        if (!isBlank(req.getIfNoneExist())) {
            Optional<Resource> existing = tryFindExistingByIfNoneExist(facilityId, url.resourceType(), req.getIfNoneExist());
            if (existing.isPresent()) {
                return buildSuccessResponseEntry("200 OK", existing.get());
            }
        }

        String json = encodeFhirResource(working);
        Resource saved = switch (url.resourceType()) {
            case "Patient" -> {
                Patient p = patientService.createFromFhirJson(facilityId, json);
                cdsQueue.patients.add(p);
                yield p;
            }
            case "Practitioner" -> practitionerService.createFromFhirJson(facilityId, json);
            case "Encounter" -> {
                Encounter enc = encounterService.createFromFhirJson(facilityId, json);
                cdsQueue.encounters.add(enc);
                yield enc;
            }
            case "Observation" -> {
                Observation obs = observationService.createFromFhirJson(facilityId, json);
                cdsQueue.observations.add(obs);
                yield obs;
            }
            default -> throw new IllegalArgumentException("Unsupported FHIR resource type in Bundle POST: " + url.resourceType());
        };
        return buildSuccessResponseEntry("201 Created", saved);
    }

    private BundleEntryComponent handlePut(
            ParsedRequestUrl url,
            Resource resource,
            BundleEntryRequestComponent req,
            Map<String, String> urnMap,
            boolean resolvePlaceholders,
            PendingCdsEvaluations cdsQueue) {
        if (url.resourceId() == null) {
            throw new IllegalArgumentException("PUT URL must specify target UUID (<ResourceType>/<uuid>): " + url.resourceType());
        }
        if (resource == null || !Objects.equals(url.resourceType(), resource.fhirType())) {
            throw new IllegalArgumentException("Bundle entry PUT URL '" + url.resourceType()
                    + "' does not match resourceType '" + (resource != null ? resource.fhirType() : "null") + "'");
        }
        Resource working = resource.copy();
        if (resolvePlaceholders) {
            resolveResourceReferences(working, urnMap);
        }

        String json = encodeFhirResource(working);
        String ifMatch = req.getIfMatch();
        Resource updated = switch (url.resourceType()) {
            case "Patient" -> {
                Patient p = patientService.updateFromFhirJson(url.resourceId(), json, ifMatch);
                cdsQueue.patients.add(p);
                yield p;
            }
            case "Practitioner" -> practitionerService.updateFromFhirJson(url.resourceId(), json, ifMatch);
            case "Encounter" -> {
                Encounter enc = encounterService.updateFromFhirJson(url.resourceId(), json, ifMatch);
                cdsQueue.encounters.add(enc);
                yield enc;
            }
            case "Observation" -> {
                Observation obs = observationService.updateFromFhirJson(url.resourceId(), json, ifMatch);
                cdsQueue.observations.add(obs);
                yield obs;
            }
            default -> throw new IllegalArgumentException("Unsupported FHIR resource type in Bundle PUT: " + url.resourceType());
        };
        return buildSuccessResponseEntry("200 OK", updated);
    }

    private BundleEntryComponent handleGet(UUID facilityId, ParsedRequestUrl url) {
        if (url.resourceId() != null) {
            Resource found = switch (url.resourceType()) {
                case "Patient" -> patientService.getFhirPatientById(url.resourceId());
                case "Practitioner" -> practitionerService.getFhirPractitionerById(url.resourceId());
                case "Encounter" -> encounterService.getFhirEncounterById(url.resourceId());
                case "Observation" -> observationService.getFhirObservationById(url.resourceId());
                default -> throw new IllegalArgumentException("Unsupported FHIR resource type in Bundle GET: " + url.resourceType());
            };
            return buildSuccessResponseEntry("200 OK", found);
        }

        Map<String, List<String>> q = url.queryParams();
        Bundle searchBundle = switch (url.resourceType()) {
            case "Patient" -> patientService.searchFhirBundle(buildPatientCriteria(facilityId, q));
            case "Practitioner" -> practitionerService.searchFhirBundle(buildPractitionerCriteria(facilityId, q));
            case "Encounter" -> encounterService.searchFhirBundle(buildEncounterCriteria(facilityId, q));
            case "Observation" -> observationService.searchFhirBundle(buildObservationCriteria(facilityId, q));
            default -> throw new IllegalArgumentException("Unsupported FHIR resource type in Bundle search GET: " + url.resourceType());
        };
        BundleEntryComponent entry = new BundleEntryComponent();
        entry.setResource(searchBundle);
        entry.getResponse().setStatus("200 OK");
        entry.getResponse().setLastModified(new Date());
        return entry;
    }

    private BundleEntryComponent handleDelete(ParsedRequestUrl url) {
        if (url.resourceId() == null) {
            throw new IllegalArgumentException("DELETE URL must specify target UUID (<ResourceType>/<uuid>): " + url.resourceType());
        }
        switch (url.resourceType()) {
            case "Patient" -> patientService.deactivatePatient(url.resourceId());
            case "Practitioner" -> practitionerService.deactivatePractitioner(url.resourceId());
            case "Encounter" -> encounterService.cancelEncounter(url.resourceId());
            case "Observation" -> observationService.markEnteredInError(url.resourceId());
            default -> throw new IllegalArgumentException("Unsupported FHIR resource type in Bundle DELETE: " + url.resourceType());
        }
        BundleEntryComponent entry = new BundleEntryComponent();
        entry.getResponse().setStatus("204 No Content");
        entry.getResponse().setLastModified(new Date());
        return entry;
    }

    private Optional<Resource> tryFindExistingByIfNoneExist(UUID facilityId, String resourceType, String ifNoneExist) {
        Map<String, List<String>> q = parseQueryString(ifNoneExist.startsWith("?") ? ifNoneExist.substring(1) : ifNoneExist);
        Bundle found = switch (resourceType) {
            case "Patient" -> patientService.searchFhirBundle(buildPatientCriteria(facilityId, q));
            case "Practitioner" -> practitionerService.searchFhirBundle(buildPractitionerCriteria(facilityId, q));
            case "Observation" -> observationService.searchFhirBundle(buildObservationCriteria(facilityId, q));
            case "Encounter" -> encounterService.searchFhirBundle(buildEncounterCriteria(facilityId, q));
            default -> new Bundle();
        };
        if (found.getTotal() == 1 && !found.getEntry().isEmpty()) {
            return Optional.ofNullable(found.getEntryFirstRep().getResource());
        }
        if (found.getTotal() > 1) {
            throw new IllegalStateException("Conditional create If-None-Exist matched multiple (" + found.getTotal() + ") " + resourceType + " resources");
        }
        return Optional.empty();
    }

    private static void resolveResourceReferences(Resource resource, Map<String, String> urnMap) {
        if (resource instanceof Patient pat) {
            for (Reference gp : pat.getGeneralPractitioner()) {
                rewriteReference(gp, urnMap);
            }
            if (pat.hasManagingOrganization()) {
                rewriteReference(pat.getManagingOrganization(), urnMap);
            }
        } else if (resource instanceof Encounter enc) {
            if (enc.hasSubject()) {
                rewriteReference(enc.getSubject(), urnMap);
            }
            for (Encounter.EncounterParticipantComponent participant : enc.getParticipant()) {
                if (participant.hasIndividual()) {
                    rewriteReference(participant.getIndividual(), urnMap);
                }
            }
            if (enc.hasServiceProvider()) {
                rewriteReference(enc.getServiceProvider(), urnMap);
            }
            if (enc.hasPartOf()) {
                rewriteReference(enc.getPartOf(), urnMap);
            }
        } else if (resource instanceof Observation obs) {
            if (obs.hasSubject()) {
                rewriteReference(obs.getSubject(), urnMap);
            }
            if (obs.hasEncounter()) {
                rewriteReference(obs.getEncounter(), urnMap);
            }
            for (Reference perf : obs.getPerformer()) {
                rewriteReference(perf, urnMap);
            }
            for (Reference member : obs.getHasMember()) {
                rewriteReference(member, urnMap);
            }
            for (Reference derived : obs.getDerivedFrom()) {
                rewriteReference(derived, urnMap);
            }
        }
    }

    private static void rewriteReference(Reference ref, Map<String, String> urnMap) {
        if (ref == null || !ref.hasReference()) return;
        String raw = ref.getReference().trim();
        if (urnMap.containsKey(raw)) {
            ref.setReference(urnMap.get(raw));
        } else if (raw.startsWith("urn:uuid:")) {
            throw new IllegalArgumentException("Unresolved transaction placeholder reference: " + raw);
        }
    }

    private static String replaceUrnPlaceholdersInUrl(String rawUrl, Map<String, String> urnMap) {
        String updated = rawUrl;
        for (Map.Entry<String, String> e : urnMap.entrySet()) {
            updated = updated.replace(e.getKey(), e.getValue());
        }
        return updated;
    }

    private static int resourceDependencyRank(BundleEntryComponent entry) {
        if (entry == null || !entry.hasResource()) return 4;
        return switch (entry.getResource().fhirType()) {
            case "Practitioner" -> 0;
            case "Patient" -> 1;
            case "Encounter" -> 2;
            case "Observation" -> 3;
            default -> 4;
        };
    }

    private static BundleEntryComponent buildSuccessResponseEntry(String httpStatus, Resource resource) {
        String type = resource.fhirType();
        String id = resource.hasIdElement() ? resource.getIdElement().getIdPart() : "";
        String version = resource.hasMeta() && resource.getMeta().hasVersionId()
                ? resource.getMeta().getVersionId()
                : "0";
        String fullUrl = !id.isBlank() ? type + "/" + id : null;

        BundleEntryComponent respEntry = new BundleEntryComponent();
        if (fullUrl != null) {
            respEntry.setFullUrl(fullUrl);
        }
        respEntry.setResource(resource);

        BundleEntryResponseComponent resp = respEntry.getResponse();
        resp.setStatus(httpStatus);
        if (fullUrl != null) {
            resp.setLocation(fullUrl + "/_history/" + version);
        }
        resp.setEtag("W/\"" + version + "\"");
        resp.setLastModified(resource.hasMeta() && resource.getMeta().hasLastUpdated()
                ? resource.getMeta().getLastUpdated()
                : new Date());
        return respEntry;
    }

    private static BundleEntryComponent buildBatchErrorEntry(Exception ex) {
        String status;
        IssueType issueType;
        if (ex instanceof IllegalArgumentException) {
            status = "400 Bad Request";
            issueType = IssueType.INVALID;
        } else if (ex instanceof NoSuchElementException || ex instanceof EntityNotFoundException) {
            status = "404 Not Found";
            issueType = IssueType.NOTFOUND;
        } else if (ex instanceof OptimisticLockingFailureException
                || ex instanceof IllegalStateException
                || ex instanceof DataIntegrityViolationException) {
            status = "409 Conflict";
            issueType = IssueType.CONFLICT;
        } else {
            status = "500 Internal Server Error";
            issueType = IssueType.EXCEPTION;
        }

        OperationOutcome oo = new OperationOutcome();
        oo.setId("oo-" + UUID.randomUUID());
        oo.addIssue()
                .setSeverity(IssueSeverity.ERROR)
                .setCode(issueType)
                .setDiagnostics(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());

        BundleEntryComponent entry = new BundleEntryComponent();
        entry.getResponse().setStatus(status);
        entry.getResponse().setOutcome(oo);
        entry.getResponse().setLastModified(new Date());
        return entry;
    }

    private record ParsedRequestUrl(String resourceType, UUID resourceId, Map<String, List<String>> queryParams) {}

    private static ParsedRequestUrl parseRequestUrl(String rawUrl) {
        String cleaned = rawUrl.trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        String pathPart = cleaned;
        String queryPart = "";
        int qIdx = cleaned.indexOf('?');
        if (qIdx >= 0) {
            pathPart = cleaned.substring(0, qIdx);
            queryPart = cleaned.substring(qIdx + 1);
        }
        String[] segments = pathPart.split("/");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw new IllegalArgumentException("Invalid FHIR Bundle request.url: " + rawUrl);
        }
        String resourceType = segments[0].trim();
        UUID resourceId = null;
        if (segments.length >= 2 && !segments[1].isBlank()) {
            try {
                resourceId = UUID.fromString(segments[1].trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid UUID in Bundle request.url '" + rawUrl + "': " + segments[1], ex);
            }
        }
        return new ParsedRequestUrl(resourceType, resourceId, parseQueryString(queryPart));
    }

    private static Map<String, List<String>> parseQueryString(String queryPart) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        if (isBlank(queryPart)) {
            return params;
        }
        for (String pair : queryPart.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            String val = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(val);
        }
        return params;
    }

    private static PatientSearchCriteria buildPatientCriteria(UUID facilityId, Map<String, List<String>> q) {
        return new PatientSearchCriteria(
                parseUuidParam(first(q, "_id")),
                facilityId,
                first(q, "identifier"),
                collectStringFilters(q, "name"),
                collectStringFilters(q, "family"),
                collectStringFilters(q, "given"),
                q.getOrDefault("birthdate", List.of()),
                parseDateParam(first(q, "birthdateFrom")),
                parseDateParam(first(q, "birthdateTo")),
                first(q, "gender"),
                first(q, "phone"),
                first(q, "email"),
                parseBoolParam(first(q, "active")),
                parseIntParam(first(q, "_offset"), 0),
                parseIntParam(first(q, "_count"), 20)
        );
    }

    private static PractitionerSearchCriteria buildPractitionerCriteria(UUID facilityId, Map<String, List<String>> q) {
        return new PractitionerSearchCriteria(
                parseUuidParam(first(q, "_id")),
                facilityId,
                first(q, "identifier"),
                collectStringFilters(q, "name"),
                collectStringFilters(q, "family"),
                collectStringFilters(q, "given"),
                first(q, "gender"),
                first(q, "qualification"),
                parseBoolParam(first(q, "active")),
                parseIntParam(first(q, "_offset"), 0),
                parseIntParam(first(q, "_count"), 20)
        );
    }

    private static EncounterSearchCriteria buildEncounterCriteria(UUID facilityId, Map<String, List<String>> q) {
        String pat = first(q, "patient") != null ? first(q, "patient") : first(q, "subject");
        return new EncounterSearchCriteria(
                parseUuidParam(first(q, "_id")),
                facilityId,
                pat,
                first(q, "status"),
                first(q, "class"),
                first(q, "practitioner"),
                first(q, "location"),
                first(q, "reason-code"),
                q.getOrDefault("date", List.of()),
                parseDateParam(first(q, "dateFrom")),
                parseDateParam(first(q, "dateTo")),
                parseIntParam(first(q, "_offset"), 0),
                parseIntParam(first(q, "_count"), 20)
        );
    }

    private static ObservationSearchCriteria buildObservationCriteria(UUID facilityId, Map<String, List<String>> q) {
        String pat = first(q, "patient") != null ? first(q, "patient") : first(q, "subject");
        return new ObservationSearchCriteria(
                parseUuidParam(first(q, "_id")),
                facilityId,
                first(q, "identifier"),
                pat,
                first(q, "encounter"),
                first(q, "performer"),
                first(q, "category"),
                first(q, "code"),
                first(q, "code:text"),
                first(q, "status"),
                q.getOrDefault("date", List.of()),
                parseDateParam(first(q, "dateFrom")),
                parseDateParam(first(q, "dateTo")),
                parseIntParam(first(q, "_offset"), 0),
                parseIntParam(first(q, "_count"), 20)
        );
    }

    private static List<StringSearchFilter> collectStringFilters(Map<String, List<String>> q, String base) {
        List<StringSearchFilter> filters = new ArrayList<>();
        String p = first(q, base);
        if (!isBlank(p)) filters.add(StringSearchFilter.prefix(p));
        String c = first(q, base + ":contains");
        if (!isBlank(c)) filters.add(StringSearchFilter.contains(c));
        String e = first(q, base + ":exact");
        if (!isBlank(e)) filters.add(StringSearchFilter.exact(e));
        return filters;
    }

    private static String first(Map<String, List<String>> q, String key) {
        List<String> list = q.get(key);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static UUID parseUuidParam(String val) {
        return isBlank(val) ? null : UUID.fromString(val.trim());
    }

    private static LocalDate parseDateParam(String val) {
        return isBlank(val) ? null : LocalDate.parse(val.trim());
    }

    private static Boolean parseBoolParam(String val) {
        return isBlank(val) ? null : Boolean.parseBoolean(val.trim());
    }

    private static int parseIntParam(String val, int fallback) {
        return isBlank(val) ? fallback : Integer.parseInt(val.trim());
    }

    private static String maxSeverity(String current, String candidate) {
        if ("critical".equalsIgnoreCase(current) || "critical".equalsIgnoreCase(candidate)) return "critical";
        if ("warning".equalsIgnoreCase(current) || "warning".equalsIgnoreCase(candidate)) return "warning";
        if ("info".equalsIgnoreCase(current) || "info".equalsIgnoreCase(candidate)) return "info";
        return "none";
    }

    private void emitOutboxEvent(String aggType, UUID aggId, String eventType, String json) {
        if (outboxRepo == null) return;
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(aggType + ":" + eventType + ":" + aggId + ":" + evt.getId());
        evt.setAggregateType(aggType);
        evt.setAggregateId(aggId);
        evt.setEventType(eventType);
        evt.setPayloadJson(json);
        outboxRepo.save(evt);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
