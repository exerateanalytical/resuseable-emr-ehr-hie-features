/*
 * =============================================================================
 * Opes HealthCore SDK — HL7v2, ASTM, FHIR R4 IPS/NDJSON/Transaction, IHE & DICOMweb
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import com.opeshealth.healthcore.hie.domain.HieEntities.*;
import com.opeshealth.healthcore.hie.security.HieInteropSecurityConfiguration;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.fhir.FhirPatientController;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Multi-standard, tenant-isolated Health Information Exchange (HIE) gateway supporting:
 * <ul>
 *   <li>HL7 FHIR R4: Tenant-scoped Patient Summary ({@code $summary} assembled from persisted MPI,
 *       MHD documents, and DICOM study metadata), tenant-scoped Bulk NDJSON {@code $export},
 *       atomic Transaction / Batch {@code Bundle} for persisted {@code Patient} and
 *       {@code DocumentReference} resources, and HMAC-signed REST-Hook Subscriptions</li>
 *   <li>IHE Profiles: Tenant-scoped PIXm ({@code $ihe-pix}), PDQm ({@code Patient} search),
 *       MHD ({@code DocumentReference}), and database-backed mCSD ({@code Organization}/{@code Location})</li>
 *   <li>HL7 v2.5.1: Persisted {@code ADT^A01}/{@code A04}/{@code A08} MPI ingestion with
 *       tenant-scoped {@code MSH-10} deduplication; unsupported message types are explicitly
 *       rejected with {@code MSA|AR} instead of returning false-positive {@code AA} receipts</li>
 *   <li>ASTM E1394 benchtop laboratory analyzer frame parser</li>
 *   <li>DICOM Study Metadata Registry ({@code hc_dicom_studies}) + HL7 CDA R2 XML export
 *       (binary DICOM object storage requires an external PACS via {@link DicomPacsProxyService})</li>
 * </ul>
 */
public class HieInteropService {

    public interface FhirSubscriptionRepository extends JpaRepository<FhirSubscriptionEntity, UUID> {
        List<FhirSubscriptionEntity> findByStatus(String status);
        List<FhirSubscriptionEntity> findByFacilityId(UUID facilityId);
        Optional<FhirSubscriptionEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface DicomStudyRepository extends JpaRepository<DicomStudyEntity, UUID> {
        List<DicomStudyEntity> findByPatientIdOrderByStudyDateDesc(UUID patientId);
        List<DicomStudyEntity> findByFacilityIdOrderByStudyDateDesc(UUID facilityId);
        List<DicomStudyEntity> findByFacilityIdAndPatientIdOrderByStudyDateDesc(UUID facilityId, UUID patientId);
        Optional<DicomStudyEntity> findFirstByStudyInstanceUid(String studyInstanceUid);
        Optional<DicomStudyEntity> findFirstByFacilityIdAndStudyInstanceUid(UUID facilityId, String studyInstanceUid);

        @Modifying
        @Query("UPDATE DicomStudyEntity d SET d.patientId = :survivorId WHERE d.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }

    public interface HieDlqRepository extends JpaRepository<HieDlqMessageEntity, UUID> {}

    public interface IheDocumentRepository extends JpaRepository<IheDocumentEntity, UUID> {
        List<IheDocumentEntity> findByPatientIdOrderByIndexedAtDesc(UUID patientId);
        List<IheDocumentEntity> findByFacilityIdAndPatientIdOrderByIndexedAtDesc(UUID facilityId, UUID patientId);

        @Modifying
        @Query("UPDATE IheDocumentEntity i SET i.patientId = :survivorId WHERE i.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final HapiContext HL7V2_CTX = new DefaultHapiContext();
    private static final Set<String> SUPPORTED_HL7_ADT_EVENTS = Set.of("ADT^A01", "ADT^A04", "ADT^A08");

    private final MpiCommandService.PatientRepository patientRepo;
    private final FhirSubscriptionRepository subscriptionRepo;
    private final DicomStudyRepository dicomRepo;
    private final HieDlqRepository dlqRepo;
    private final IheDocumentRepository docRepo;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Value("${healthcore.hie.webhook.signing-secret:}")
    private String configuredWebhookSigningSecret = "";

    public HieInteropService(
            MpiCommandService.PatientRepository patientRepo,
            FhirSubscriptionRepository subscriptionRepo,
            DicomStudyRepository dicomRepo,
            HieDlqRepository dlqRepo,
            IheDocumentRepository docRepo,
            JdbcTemplate jdbcTemplate) {
        this.patientRepo = patientRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.dicomRepo = dicomRepo;
        this.dlqRepo = dlqRepo;
        this.docRepo = docRepo;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void setConfiguredWebhookSigningSecret(String secret) {
        this.configuredWebhookSigningSecret = secret != null ? secret : "";
    }

    /** Cascades MPI Golden Record merges to DICOM imaging studies and IHE MHD documents. */
    @EventListener
    @Transactional
    public void onPatientMerged(MpiCommandService.PatientMergedApplicationEvent event) {
        if (event == null) return;
        dicomRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
        docRepo.relinkPatient(event.sourcePatientId(), event.survivingPatientId());
    }

    /**
     * Resolves and enforces the caller's active facility tenant from {@link FacilityContext} and
     * cross-checks it against the verified JWT {@code facility_id} claim (when present in
     * {@link SecurityContextHolder}). Fails closed with {@link AccessDeniedException} when tenant
     * context is missing or does not match the caller's assigned organization.
     */
    public static UUID requireTenantFacilityId() {
        UUID contextFacility = FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException(
                        "Missing active facility tenant context; cross-tenant HIE access is prohibited."));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Optional<UUID> jwtFacility = HieInteropSecurityConfiguration.extractAssignedFacilityId(auth);
        if (jwtFacility.isPresent() && !jwtFacility.get().equals(contextFacility)) {
            throw new AccessDeniedException(
                    "Cross-organization access denied: active FacilityContext does not match JWT facility_id claim.");
        }
        return contextFacility;
    }

    /**
     * Tenant-scoped IHE PIXm ({@code $ihe-pix}) identifier cross-reference lookup for a patient UUID.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ihePixmLookup(UUID patientId, String targetSystem) {
        UUID facilityId = requireTenantFacilityId();
        PatientAggregateEntity patient = patientRepo.findByIdAndFacilityId(patientId, facilityId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Patient not found within active facility tenant: " + patientId));
        String system = (targetSystem != null && !targetSystem.isBlank()) ? targetSystem.trim() : "urn:opes:mrn";
        List<Map<String, Object>> targets = List.of(Map.of(
                "name", "targetId",
                "valueIdentifier", Map.of("system", system, "value", patient.getMrn()),
                "resourceId", "Patient/" + patient.getId()));
        return Map.of("resourceType", "Parameters", "parameter", targets);
    }

    /**
     * Generates a tenant-scoped FHIR R4 Patient Summary Document Bundle assembled from
     * persisted, facility-authorized records (Patient demographics, indexed MHD
     * {@code DocumentReference} entries, and {@code ImagingStudy} metadata).
     *
     * <p><strong>Scope notice:</strong> This endpoint produces a partial patient summary
     * limited to persisted records available in the HIE store and does not claim full
     * HL7 UV IPS profile certification unless allergies, medications, and active problem
     * lists are supplied and validated against the target IPS implementation guide.</p>
     */
    @Transactional(readOnly = true)
    public String generateIpsSummaryBundle(UUID patientId) {
        UUID facilityId = requireTenantFacilityId();
        PatientAggregateEntity pat = patientRepo.findByIdAndFacilityId(patientId, facilityId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Patient not found within active facility tenant: " + patientId));

        List<IheDocumentEntity> documents = docRepo != null
                ? docRepo.findByFacilityIdAndPatientIdOrderByIndexedAtDesc(facilityId, patientId)
                : List.of();
        List<DicomStudyEntity> studies = dicomRepo != null
                ? dicomRepo.findByFacilityIdAndPatientIdOrderByStudyDateDesc(facilityId, patientId)
                : List.of();

        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestamp(new Date());

        Composition comp = new Composition();
        comp.setId(UUID.randomUUID().toString());
        comp.setStatus(Composition.CompositionStatus.PRELIMINARY);
        comp.setTitle("Opes HealthCore Patient Summary (Persisted HIE Records Subset)");
        comp.setDate(new Date());
        comp.getType().addCoding()
                .setSystem("http://loinc.org")
                .setCode("60591-5")
                .setDisplay("Patient summary Document");
        comp.getSubject().setReference("Patient/" + pat.getId());
        comp.addAuthor().setDisplay("Opes HealthCore HIE Gateway (Facility " + facilityId + ")");

        // Assemble Clinical Documents section from persisted MHD documents
        Composition.SectionComponent docsSection = comp.addSection();
        docsSection.setTitle("Indexed Clinical Documents");
        docsSection.getCode().addCoding()
                .setSystem("http://loinc.org")
                .setCode("55107-7")
                .setDisplay("Addendum Document");
        if (documents.isEmpty()) {
            docsSection.getEmptyReason().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/list-empty-reason")
                    .setCode("unavailable")
                    .setDisplay("No clinical documents indexed for this patient in active facility");
        } else {
            for (IheDocumentEntity doc : documents) {
                docsSection.addEntry().setReference("DocumentReference/" + doc.getId());
            }
        }

        // Assemble Diagnostic Imaging section from persisted DICOM study metadata
        Composition.SectionComponent imagingSection = comp.addSection();
        imagingSection.setTitle("Diagnostic Imaging Studies");
        imagingSection.getCode().addCoding()
                .setSystem("http://loinc.org")
                .setCode("18748-4")
                .setDisplay("Diagnostic imaging study");
        if (studies.isEmpty()) {
            imagingSection.getEmptyReason().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/list-empty-reason")
                    .setCode("unavailable")
                    .setDisplay("No imaging studies indexed for this patient in active facility");
        } else {
            for (DicomStudyEntity study : studies) {
                imagingSection.addEntry().setReference("ImagingStudy/" + study.getId());
            }
        }

        // Explicitly mark Medication / Allergy / Problem IPS sections as not populated by HIE-only store
        for (String[] missingSection : List.of(
                new String[]{"Medication Summary", "10160-0", "History of Medication use Narrative"},
                new String[]{"Allergies and Intolerances", "48765-2", "Allergies and adverse reactions Document"},
                new String[]{"Problem List", "11450-4", "Problem list - Reported"})) {
            Composition.SectionComponent sec = comp.addSection();
            sec.setTitle(missingSection[0]);
            sec.getCode().addCoding()
                    .setSystem("http://loinc.org")
                    .setCode(missingSection[1])
                    .setDisplay(missingSection[2]);
            sec.getEmptyReason().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/list-empty-reason")
                    .setCode("notasked")
                    .setDisplay("Section requires EMR clinical feed; not populated from HIE index alone");
        }

        bundle.addEntry().setFullUrl("urn:uuid:" + comp.getId()).setResource(comp);
        bundle.addEntry().setFullUrl("Patient/" + pat.getId()).setResource(FhirPatientController.toFhirPatient(pat));

        for (IheDocumentEntity doc : documents) {
            DocumentReference dr = new DocumentReference();
            dr.setId(doc.getId().toString());
            dr.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
            dr.getType().addCoding().setSystem("http://loinc.org").setCode(doc.getDocumentTypeLoinc());
            dr.getSubject().setReference("Patient/" + pat.getId());
            dr.setDescription(doc.getTitle());
            dr.addContent().getAttachment()
                    .setContentType(doc.getContentType())
                    .setTitle(doc.getTitle())
                    .setUrl(doc.getContentUrlOrBase64());
            bundle.addEntry().setFullUrl("DocumentReference/" + doc.getId()).setResource(dr);
        }

        for (DicomStudyEntity study : studies) {
            ImagingStudy is = new ImagingStudy();
            is.setId(study.getId().toString());
            is.setStatus(ImagingStudy.ImagingStudyStatus.AVAILABLE);
            is.addIdentifier().setSystem("urn:dicom:uid").setValue("urn:oid:" + study.getStudyInstanceUid());
            is.getSubject().setReference("Patient/" + pat.getId());
            is.setDescription(study.getStudyDescription());
            is.setNumberOfSeries(study.getSeriesCount());
            is.addModality().setSystem("http://dicom.nema.org/resources/ontology/DCM").setCode(study.getModality());
            bundle.addEntry().setFullUrl("ImagingStudy/" + study.getId()).setResource(is);
        }

        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
    }

    /** Generates tenant-scoped FHIR Bulk Data NDJSON export stream restricted strictly to caller's facility. */
    @Transactional(readOnly = true)
    public String exportPatientsNdjson() {
        UUID facilityId = requireTenantFacilityId();
        var compactParser = FHIR_R4.newJsonParser().setPrettyPrint(false);
        StringBuilder ndjson = new StringBuilder();
        for (PatientAggregateEntity p : patientRepo.findByFacilityId(facilityId)) {
            if (facilityId.equals(p.getFacilityId())) {
                ndjson.append(compactParser.encodeResourceToString(FhirPatientController.toFhirPatient(p))).append('\n');
            }
        }
        return ndjson.toString();
    }

    /**
     * Processes an HL7 FHIR R4 {@code transaction} or {@code batch} Bundle with real validation,
     * tenant-scoped persistence, and actual persisted IDs/locations.
     *
     * <p>Supported operations:
     * <ul>
     *   <li>{@code POST Patient} — persists a new {@link PatientAggregateEntity} under active facility</li>
     *   <li>{@code PUT Patient/{id}} — updates an existing {@link PatientAggregateEntity} within active facility</li>
     *   <li>{@code POST DocumentReference} — persists a new {@link IheDocumentEntity} under active facility</li>
     * </ul>
     * For {@code transaction} bundles, any unsupported resource type, unsupported HTTP verb, or
     * validation error throws an exception to ensure full transactional rollback (atomicity).
     * For {@code batch} bundles, unsupported entries return {@code 501 Not Implemented} with an
     * {@code OperationOutcome} without fabricating success.</p>
     */
    @Transactional
    public String processFhirTransactionBundle(String bundleJson) {
        UUID facilityId = requireTenantFacilityId();
        var parser = FHIR_R4.newJsonParser().setPrettyPrint(true);
        Bundle inbound = parser.parseResource(Bundle.class, bundleJson);
        if (inbound.getType() != Bundle.BundleType.TRANSACTION && inbound.getType() != Bundle.BundleType.BATCH) {
            throw new IllegalArgumentException("Bundle type must be transaction or batch");
        }
        if (inbound.getEntry().isEmpty()) {
            throw new IllegalArgumentException("Bundle must contain at least one entry");
        }

        boolean isTransaction = inbound.getType() == Bundle.BundleType.TRANSACTION;
        Bundle response = new Bundle();
        response.setType(isTransaction
                ? Bundle.BundleType.TRANSACTIONRESPONSE
                : Bundle.BundleType.BATCHRESPONSE);

        for (Bundle.BundleEntryComponent entry : inbound.getEntry()) {
            Bundle.BundleEntryComponent respEntry = response.addEntry();
            try {
                if (!entry.hasRequest() || !entry.getRequest().hasMethod()) {
                    throw new IllegalArgumentException("Bundle entry is missing request.method");
                }
                if (!entry.hasResource()) {
                    throw new IllegalArgumentException("Bundle entry is missing resource payload");
                }
                Bundle.HTTPVerb method = entry.getRequest().getMethod();
                Resource resource = entry.getResource();

                if (resource instanceof Patient fhirPatient) {
                    if (method == Bundle.HTTPVerb.POST) {
                        PatientAggregateEntity entity = mapFhirPatientToEntity(fhirPatient, facilityId, null);
                        PatientAggregateEntity saved = patientRepo.save(entity);
                        respEntry.getResponse().setStatus("201 Created");
                        respEntry.getResponse().setLocation("Patient/" + saved.getId());
                        respEntry.setResource(FhirPatientController.toFhirPatient(saved));
                    } else if (method == Bundle.HTTPVerb.PUT) {
                        UUID targetId = extractUuidFromUrl(entry.getRequest().getUrl(), fhirPatient.getIdElement().getIdPart());
                        PatientAggregateEntity existing = patientRepo.findByIdAndFacilityId(targetId, facilityId)
                                .orElseThrow(() -> new NoSuchElementException(
                                        "Patient not found within active facility for PUT: " + targetId));
                        PatientAggregateEntity updated = patientRepo.save(mapFhirPatientToEntity(fhirPatient, facilityId, existing));
                        respEntry.getResponse().setStatus("200 OK");
                        respEntry.getResponse().setLocation("Patient/" + updated.getId());
                        respEntry.setResource(FhirPatientController.toFhirPatient(updated));
                    } else {
                        throw new UnsupportedOperationException(
                                "HTTP verb " + method + " is not supported for Patient in FHIR Bundle");
                    }
                } else if (resource instanceof DocumentReference fhirDoc && method == Bundle.HTTPVerb.POST) {
                    IheDocumentEntity docEntity = mapFhirDocumentToEntity(fhirDoc, facilityId);
                    IheDocumentEntity savedDoc = docRepo.save(docEntity);
                    respEntry.getResponse().setStatus("201 Created");
                    respEntry.getResponse().setLocation("DocumentReference/" + savedDoc.getId());
                } else {
                    throw new UnsupportedOperationException(
                            "Unsupported Bundle operation: " + method + " " + resource.fhirType()
                                    + ". Supported: POST/PUT Patient, POST DocumentReference.");
                }
            } catch (UnsupportedOperationException ex) {
                if (isTransaction) {
                    throw ex;
                }
                respEntry.getResponse().setStatus("501 Not Implemented");
                respEntry.getResponse().setOutcome(buildOperationOutcome(
                        OperationOutcome.IssueSeverity.ERROR, OperationOutcome.IssueType.NOTSUPPORTED, ex.getMessage()));
            } catch (IllegalArgumentException | NoSuchElementException ex) {
                if (isTransaction) {
                    throw ex;
                }
                respEntry.getResponse().setStatus(ex instanceof NoSuchElementException ? "404 Not Found" : "400 Bad Request");
                respEntry.getResponse().setOutcome(buildOperationOutcome(
                        OperationOutcome.IssueSeverity.ERROR, OperationOutcome.IssueType.INVALID, ex.getMessage()));
            }
        }
        return parser.encodeResourceToString(response);
    }

    /**
     * Dispatches HMAC-signed FHIR Subscription REST-Hook payloads guarded by Resilience4j
     * CircuitBreaker &amp; Bulkhead. Fails closed if no externalized signing secret is configured.
     */
    @CircuitBreaker(name = "hieWebhook", fallbackMethod = "webhookFallback")
    @Bulkhead(name = "hieOutbound", fallbackMethod = "webhookFallback")
    public Map<String, Object> dispatchSignedWebhook(UUID subscriptionId, String resourceJson) {
        UUID facilityId = requireTenantFacilityId();
        FhirSubscriptionEntity sub = subscriptionRepo.findByIdAndFacilityId(subscriptionId, facilityId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Subscription not found within active facility: " + subscriptionId));
        String secret = resolveWebhookSecret(sub);
        try {
            String signature = hmacSha256Hex(resourceJson, secret);
            HttpRequest req = HttpRequest.newBuilder(URI.create(sub.getEndpointUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/fhir+json")
                    .header("X-HealthCore-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(resourceJson))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return Map.of("subscriptionId", subscriptionId, "status", resp.statusCode(), "delivered", resp.statusCode() < 300);
        } catch (Exception ex) {
            throw new IllegalStateException("FHIR Subscription webhook dispatch error: " + ex.getMessage(), ex);
        }
    }

    private String resolveWebhookSecret(FhirSubscriptionEntity sub) {
        if (sub.getHmacSecretRef() != null && !sub.getHmacSecretRef().isBlank()) {
            String ref = sub.getHmacSecretRef().trim();
            // Support env:VAR_NAME indirection as well as per-subscription secret reference
            if (ref.startsWith("env:")) {
                String envVal = System.getenv(ref.substring(4));
                if (envVal != null && !envVal.isBlank()) {
                    return envVal;
                }
                throw new IllegalStateException("Webhook secret environment variable not set: " + ref.substring(4));
            }
            return ref;
        }
        if (configuredWebhookSigningSecret != null && !configuredWebhookSigningSecret.isBlank()) {
            return configuredWebhookSigningSecret;
        }
        throw new IllegalStateException(
                "FHIR Subscription webhook signing secret is not configured. Set subscription.hmacSecretRef or healthcore.hie.webhook.signing-secret.");
    }

    @SuppressWarnings("unused")
    private Map<String, Object> webhookFallback(UUID subscriptionId, String resourceJson, Throwable ex) {
        HieDlqMessageEntity dlq = new HieDlqMessageEntity();
        dlq.setProtocol("FHIR");
        dlq.setExternalMessageId(subscriptionId.toString());
        dlq.setRawPayload(resourceJson);
        dlq.setErrorDetail(ex.getMessage());
        dlqRepo.save(dlq);
        return Map.of("subscriptionId", subscriptionId, "delivered", false, "quarantinedToDlq", true, "reason", ex.getMessage());
    }

    private String hmacSha256Hex(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Parses inbound HL7 v2.5.1 messages using the caller's active tenant context,
     * applying supported {@code ADT^A01}/{@code A04}/{@code A08} messages to persisted MPI
     * patient records and explicitly rejecting unsupported message types with {@code MSA|AR}.
     */
    @Transactional
    public Map<String, Object> ingestHl7v2(String rawHl7) {
        return ingestHl7v2Message(requireTenantFacilityId(), rawHl7);
    }

    /**
     * Tenant-scoped HL7 v2.5.1 ingestion:
     * <ul>
     *   <li>Validates MSH-9 message type and MSH-10 control ID (rejects missing headers with {@code AE})</li>
     *   <li>Enforces tenant-scoped idempotency on {@code HL7V2:<facilityId>:<MSH-10>}</li>
     *   <li>Maps and persists {@code ADT^A01}, {@code ADT^A04}, and {@code ADT^A08} PID segments
     *       into {@link PatientAggregateEntity} within the tenant</li>
     *   <li>Rejects unimplemented message types ({@code ORM}, {@code ORU}, {@code SIU}, {@code DFT}, etc.)
     *       with {@code ackCode="AR"} and {@code status="UNSUPPORTED_MESSAGE_TYPE"} rather than
     *       acknowledging unapplied clinical payloads</li>
     * </ul>
     */
    @Transactional
    public Map<String, Object> ingestHl7v2Message(UUID facilityId, String rawHl7) {
        if (facilityId == null) {
            throw new AccessDeniedException("Facility tenant ID is required for HL7v2 ingestion.");
        }
        if (rawHl7 == null || rawHl7.isBlank()) {
            return Map.of("status", "REJECTED", "ackCode", "AE", "error", "Empty HL7v2 payload");
        }
        try {
            Message msg = HL7V2_CTX.getPipeParser().parse(rawHl7.replace('\n', '\r'));
            Terser terser = new Terser(msg);
            String msh10 = terser.get("/.MSH-10");
            String msgCode = terser.get("/.MSH-9-1");
            String triggerEvent = terser.get("/.MSH-9-2");
            if (msh10 == null || msh10.isBlank() || msgCode == null || msgCode.isBlank()) {
                return Map.of("status", "REJECTED", "ackCode", "AE", "error", "Missing mandatory MSH-9 or MSH-10 header");
            }
            String msh9 = triggerEvent != null && !triggerEvent.isBlank() ? msgCode + "^" + triggerEvent : msgCode;

            String idempKey = "HL7V2:" + facilityId + ":" + msh10;
            if (jdbcTemplate != null) {
                Integer existing = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM hc_inbound_idempotency WHERE idempotency_key = ?",
                        Integer.class, idempKey);
                if (existing != null && existing > 0) {
                    return Map.of("status", "DUPLICATE_ACK", "messageType", msh9, "controlId", msh10, "ackCode", "AA");
                }
            }

            if (!SUPPORTED_HL7_ADT_EVENTS.contains(msh9)) {
                HieDlqMessageEntity dlq = new HieDlqMessageEntity();
                dlq.setProtocol("HL7V2");
                dlq.setExternalMessageId(msh10);
                dlq.setRawPayload(rawHl7);
                dlq.setErrorDetail("Unsupported HL7v2 message type for clinical persistence: " + msh9);
                if (dlqRepo != null) {
                    dlqRepo.save(dlq);
                }
                return Map.of(
                        "status", "UNSUPPORTED_MESSAGE_TYPE",
                        "messageType", msh9,
                        "controlId", msh10,
                        "ackCode", "AR",
                        "supportedTypes", List.copyOf(SUPPORTED_HL7_ADT_EVENTS),
                        "detail", "Message was NOT applied to clinical records; only ADT^A01, ADT^A04, and ADT^A08 patient persistence is implemented."
                );
            }

            // Map PID segment into persisted tenant-scoped PatientAggregateEntity
            String mrn = firstNonBlank(terser.get("/.PID-3-1"), terser.get("/.PID-2-1"));
            String familyName = terser.get("/.PID-5-1");
            String givenName = terser.get("/.PID-5-2");
            String rawDob = terser.get("/.PID-7");
            String rawSex = terser.get("/.PID-8");

            if (mrn == null || familyName == null || familyName.isBlank()) {
                return Map.of(
                        "status", "VALIDATION_ERROR",
                        "messageType", msh9,
                        "controlId", msh10,
                        "ackCode", "AE",
                        "error", "ADT message requires PID-3 (MRN) and PID-5.1 (family name)"
                );
            }

            PatientAggregateEntity patient = patientRepo.findByFacilityIdAndMrn(facilityId, mrn)
                    .orElseGet(PatientAggregateEntity::new);
            patient.setFacilityId(facilityId);
            patient.setMrn(mrn);
            patient.setFamilyName(familyName.trim());
            patient.setGivenName(givenName != null && !givenName.isBlank() ? givenName.trim() : "Unknown");
            patient.setBirthDate(parseHl7BirthDate(rawDob));
            patient.setGender(normalizeHl7Gender(rawSex));
            patient.setUpdatedAt(Instant.now());
            PatientAggregateEntity saved = patientRepo.save(patient);

            if (jdbcTemplate != null) {
                jdbcTemplate.update(
                        "INSERT INTO hc_inbound_idempotency (id, idempotency_key, request_hash, http_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(), idempKey, Integer.toHexString(rawHl7.hashCode()), 200, "AA", java.sql.Timestamp.from(Instant.now()));
            }

            return Map.of(
                    "status", "APPLIED_TO_MPI",
                    "messageType", msh9,
                    "controlId", msh10,
                    "ackCode", "AA",
                    "patientId", saved.getId().toString(),
                    "mrn", saved.getMrn()
            );
        } catch (Exception ex) {
            if (dlqRepo != null) {
                HieDlqMessageEntity dlq = new HieDlqMessageEntity();
                dlq.setProtocol("HL7V2");
                dlq.setRawPayload(rawHl7);
                dlq.setErrorDetail(ex.getMessage());
                dlqRepo.save(dlq);
            }
            return Map.of("status", "QUARANTINED_TO_DLQ", "ackCode", "AE", "error", Objects.toString(ex.getMessage(), "HL7v2 parse error"));
        }
    }

    /** Parses ASTM E1394 benchtop lab analyzer result frame ({@code R|seq|^^^LOINC|val|unit}). */
    public List<Map<String, String>> parseAstmE1394(String astmPayload) {
        List<Map<String, String>> results = new ArrayList<>();
        for (String line : astmPayload.split("\\r?\\n")) {
            if (line.startsWith("R|")) {
                String[] parts = line.split("\\|");
                String code = parts.length > 2 ? parts[2].replace("^", "") : "";
                String value = parts.length > 3 ? parts[3] : "";
                String unit = parts.length > 4 ? parts[4] : "";
                results.add(Map.of("testCode", code, "value", value, "unit", unit));
            }
        }
        return results;
    }

    /** Compatibility alias used by {@code Hl7AstmDicomStarter}. */
    public List<Map<String, String>> parseAstmAnalyzerFrame(String astmPayload) {
        return parseAstmE1394(astmPayload);
    }

    /** Generates tenant-scoped HL7 CDA R2 XML Clinical Document for legacy hospital exchange. */
    @Transactional(readOnly = true)
    public String generateCdaR2Xml(UUID patientId, String summaryText) {
        UUID facilityId = requireTenantFacilityId();
        PatientAggregateEntity pat = patientRepo.findByIdAndFacilityId(patientId, facilityId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Patient not found within active facility tenant: " + patientId));
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ClinicalDocument xmlns="urn:hl7-org:v3">
                  <typeId root="2.16.840.1.113883.1.3" extension="POCD_HD000040"/>
                  <id root="%s"/>
                  <code code="18842-5" codeSystem="2.16.840.1.113883.6.1" displayName="Discharge summary"/>
                  <title>Opes HealthCore CDA R2 Summary</title>
                  <recordTarget><patientRole><id extension="%s"/><patient><name><given>%s</given><family>%s</family></name></patient></patientRole></recordTarget>
                  <component><structuredBody><component><section><text>%s</text></section></component></structuredBody></component>
                </ClinicalDocument>
                """.formatted(
                UUID.randomUUID(),
                escapeXml(pat.getMrn()),
                escapeXml(pat.getGivenName()),
                escapeXml(pat.getFamilyName()),
                escapeXml(summaryText));
    }

    /**
     * Queries persisted tenant-scoped Organization directory rows for IHE mCSD
     * ({@code GET /fhir/r4/Organization}) without returning hardcoded sample organizations.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> queryTenantOrganizations() {
        UUID facilityId = requireTenantFacilityId();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (jdbcTemplate != null) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT id, code, name, country, active FROM hc_facility_nodes WHERE id = ?",
                        facilityId);
                for (Map<String, Object> row : rows) {
                    entries.add(Map.of("resource", Map.of(
                            "resourceType", "Organization",
                            "id", String.valueOf(row.get("id")),
                            "identifier", List.of(Map.of("system", "urn:opes:facility-code", "value", String.valueOf(row.get("code")))),
                            "name", String.valueOf(row.get("name")),
                            "active", Boolean.TRUE.equals(row.get("active"))
                    )));
                }
            } catch (Exception ex) {
                // Table may not be provisioned when templates-dashboards starter is excluded; return empty searchset honestly
            }
        }
        return Map.of(
                "resourceType", "Bundle",
                "type", "searchset",
                "total", entries.size(),
                "entry", entries
        );
    }

    /**
     * Queries persisted tenant-scoped Location directory rows for IHE mCSD
     * ({@code GET /fhir/r4/Location}) from persisted ward/bed directory records without hardcoded sample data.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> queryTenantLocations() {
        UUID facilityId = requireTenantFacilityId();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (jdbcTemplate != null) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT DISTINCT ward_code FROM hc_ward_beds WHERE facility_id = ? ORDER BY ward_code ASC",
                        facilityId);
                for (Map<String, Object> row : rows) {
                    String wardCode = String.valueOf(row.get("ward_code"));
                    entries.add(Map.of("resource", Map.of(
                            "resourceType", "Location",
                            "id", facilityId + "-" + wardCode,
                            "name", wardCode,
                            "status", "active",
                            "managingOrganization", Map.of("reference", "Organization/" + facilityId)
                    )));
                }
            } catch (Exception ex) {
                // Table may not be provisioned when EMR starter is excluded; return empty searchset honestly
            }
        }
        return Map.of(
                "resourceType", "Bundle",
                "type", "searchset",
                "total", entries.size(),
                "entry", entries
        );
    }

    /**
     * Generates an HL7 FHIR R4 CapabilityStatement accurately reflecting ONLY the resources,
     * interactions, and operations actually implemented by the HIE gateway endpoints.
     */
    public String generateCapabilityStatementJson() {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setId("opes-healthcore-r4-capability");
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setDate(new Date());
        cs.setPublisher("Opes Health Systems — Jude Nshome");
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.setDescription("Implemented FHIR R4 HIE gateway capabilities. Advertises only verified, tenant-scoped endpoints.");
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+ndjson");

        CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);
        rest.getSecurity().setCors(true);
        rest.getSecurity().addService()
                .addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/restful-security-service")
                .setCode("SMART-on-FHIR");

        // System-level interactions implemented in POST /fhir/r4 (Patient POST/PUT and DocumentReference POST)
        rest.addInteraction().setCode(CapabilityStatement.SystemRestfulInteraction.TRANSACTION);
        rest.addInteraction().setCode(CapabilityStatement.SystemRestfulInteraction.BATCH);

        // Patient: search-type (GET /fhir/r4/Patient), plus $summary, $export, $ihe-pix
        CapabilityStatement.CapabilityStatementRestResourceComponent patientRes = rest.addResource();
        patientRes.setType("Patient");
        patientRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
        patientRes.addOperation().setName("summary").setDefinition("http://hl7.org/fhir/uv/ips/OperationDefinition/summary");
        patientRes.addOperation().setName("export").setDefinition("http://hl7.org/fhir/uv/bulkdata/OperationDefinition/export");
        patientRes.addOperation().setName("ihe-pix").setDefinition("https://profiles.ihe.net/ITI/PIXm/OperationDefinition/IHE.PIXm.pix");

        // DocumentReference: create (POST /fhir/r4/DocumentReference) and search-type (GET /fhir/r4/DocumentReference)
        CapabilityStatement.CapabilityStatementRestResourceComponent docRes = rest.addResource();
        docRes.setType("DocumentReference");
        docRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.CREATE);
        docRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);

        // Subscription: create (POST /fhir/r4/Subscription)
        CapabilityStatement.CapabilityStatementRestResourceComponent subRes = rest.addResource();
        subRes.setType("Subscription");
        subRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.CREATE);

        // Organization & Location (IHE mCSD): search-type backed by persisted tenant records
        CapabilityStatement.CapabilityStatementRestResourceComponent orgRes = rest.addResource();
        orgRes.setType("Organization");
        orgRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);

        CapabilityStatement.CapabilityStatementRestResourceComponent locRes = rest.addResource();
        locRes.setType("Location");
        locRes.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);

        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(cs);
    }

    // -------------------------------------------------------------------------
    // Internal Mapping & Validation Helpers
    // -------------------------------------------------------------------------

    private PatientAggregateEntity mapFhirPatientToEntity(
            Patient fhirPatient, UUID facilityId, PatientAggregateEntity target) {
        PatientAggregateEntity entity = target != null ? target : new PatientAggregateEntity();
        entity.setFacilityId(facilityId);

        String mrn = fhirPatient.getIdentifier().stream()
                .map(Identifier::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst()
                .orElse(target != null ? target.getMrn() : "FHIR-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setMrn(mrn);

        String family = fhirPatient.hasName() && fhirPatient.getNameFirstRep().hasFamily()
                ? fhirPatient.getNameFirstRep().getFamily().trim()
                : (target != null ? target.getFamilyName() : null);
        if (family == null || family.isBlank()) {
            throw new IllegalArgumentException("FHIR Patient resource requires name.family");
        }
        String given = fhirPatient.hasName() && fhirPatient.getNameFirstRep().hasGiven()
                ? fhirPatient.getNameFirstRep().getGivenAsSingleString().trim()
                : (target != null ? target.getGivenName() : "Unknown");
        entity.setFamilyName(family);
        entity.setGivenName(given.isBlank() ? "Unknown" : given);

        if (fhirPatient.hasBirthDate()) {
            entity.setBirthDate(fhirPatient.getBirthDate().toInstant().atZone(ZoneOffset.UTC).toLocalDate());
        } else if (target == null) {
            throw new IllegalArgumentException("FHIR Patient resource requires birthDate");
        }

        if (fhirPatient.hasGender()) {
            entity.setGender(fhirPatient.getGender().toCode());
        } else if (target == null) {
            entity.setGender("unknown");
        }
        entity.setUpdatedAt(Instant.now());
        return entity;
    }

    private IheDocumentEntity mapFhirDocumentToEntity(DocumentReference fhirDoc, UUID facilityId) {
        if (!fhirDoc.hasSubject() || !fhirDoc.getSubject().hasReference()) {
            throw new IllegalArgumentException("FHIR DocumentReference requires subject.reference (Patient/{uuid})");
        }
        UUID patientId = extractUuidFromUrl(fhirDoc.getSubject().getReference(), null);
        patientRepo.findByIdAndFacilityId(patientId, facilityId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Referenced Patient not found in active facility tenant: " + patientId));

        IheDocumentEntity doc = new IheDocumentEntity();
        doc.setFacilityId(facilityId);
        doc.setPatientId(patientId);
        String loinc = fhirDoc.hasType() && fhirDoc.getType().hasCoding()
                ? fhirDoc.getType().getCodingFirstRep().getCode()
                : "34133-9";
        doc.setDocumentTypeLoinc(loinc != null && !loinc.isBlank() ? loinc : "34133-9");
        doc.setTitle(fhirDoc.hasDescription() && !fhirDoc.getDescription().isBlank()
                ? fhirDoc.getDescription()
                : "FHIR Clinical Document");
        if (fhirDoc.hasContent() && fhirDoc.getContentFirstRep().hasAttachment()) {
            Attachment att = fhirDoc.getContentFirstRep().getAttachment();
            doc.setContentType(att.hasContentType() ? att.getContentType() : "application/pdf");
            String payload = att.hasUrl()
                    ? att.getUrl()
                    : (att.hasData() ? Base64.getEncoder().encodeToString(att.getData()) : "");
            if (payload.isBlank()) {
                throw new IllegalArgumentException("FHIR DocumentReference requires content.attachment.url or data");
            }
            doc.setContentUrlOrBase64(payload);
        } else {
            throw new IllegalArgumentException("FHIR DocumentReference requires content.attachment");
        }
        return doc;
    }

    private static UUID extractUuidFromUrl(String url, String fallbackIdPart) {
        String candidate = fallbackIdPart;
        if (url != null && !url.isBlank()) {
            int slash = url.lastIndexOf('/');
            candidate = slash >= 0 ? url.substring(slash + 1) : url;
        }
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("Missing UUID resource identifier in request URL");
        }
        return UUID.fromString(candidate.trim());
    }

    private static OperationOutcome buildOperationOutcome(
            OperationOutcome.IssueSeverity severity, OperationOutcome.IssueType code, String diagnostics) {
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setSeverity(severity).setCode(code).setDiagnostics(diagnostics);
        return oo;
    }

    private static LocalDate parseHl7BirthDate(String rawDob) {
        if (rawDob == null || rawDob.isBlank()) {
            return LocalDate.of(1900, 1, 1);
        }
        String digits = rawDob.replaceAll("[^0-9]", "");
        if (digits.length() >= 8) {
            return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        }
        return LocalDate.of(1900, 1, 1);
    }

    private static String normalizeHl7Gender(String rawSex) {
        if (rawSex == null) return "unknown";
        return switch (rawSex.trim().toUpperCase(Locale.ROOT)) {
            case "M" -> "male";
            case "F" -> "female";
            case "O" -> "other";
            default -> "unknown";
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private static String escapeXml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static void enforcePayloadFacilityMatchesTenant(UUID payloadFacilityId, UUID activeFacilityId) {
        if (payloadFacilityId != null && !payloadFacilityId.equals(activeFacilityId)) {
            throw new AccessDeniedException(
                    "Cross-tenant write denied: payload facilityId does not match authenticated tenant context.");
        }
    }

    @RestController
    @Tag(name = "FHIR R4 HIE Gateway", description = "FHIR R4 metadata CapabilityStatement, tenant-scoped Patient Summary $summary, Bulk NDJSON $export, validated Transaction/Batch Bundle & signed REST-Hook Subscriptions")
    public static class FhirHieController {
        private final HieInteropService service;
        private final FhirSubscriptionRepository subscriptionRepo;

        public FhirHieController(HieInteropService service, FhirSubscriptionRepository subscriptionRepo) {
            this.service = service;
            this.subscriptionRepo = subscriptionRepo;
        }

        @GetMapping(value = "/fhir/r4/metadata", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Return HL7 FHIR R4 CapabilityStatement for Opes HealthCore HIE Gateway")
        public String capabilityStatement() {
            requireTenantFacilityId();
            return service.generateCapabilityStatementJson();
        }

        @GetMapping(value = "/fhir/r4/Patient/{id}/$summary", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Generate tenant-scoped FHIR R4 Patient Summary Bundle from persisted HIE records")
        public String ipsSummary(@PathVariable UUID id) {
            return service.generateIpsSummaryBundle(id);
        }

        @GetMapping(value = "/fhir/r4/Patient/$export", produces = "application/fhir+ndjson")
        @PreAuthorize("hasAuthority('PERM_patient:read') and (hasAuthority('PERM_audit:export') or hasAuthority('PERM_system:admin'))")
        @Operation(summary = "Export tenant-scoped bulk Patient resources as FHIR NDJSON")
        public String bulkExport() {
            return service.exportPatientsNdjson();
        }

        @PostMapping(value = "/fhir/r4", consumes = {"application/fhir+json", "application/json"}, produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read') and (hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign'))")
        @Operation(summary = "Process validated HL7 FHIR R4 Transaction or Batch Bundle (Patient POST/PUT, DocumentReference POST)")
        public String processBundle(@RequestBody String bundleJson) {
            return service.processFhirTransactionBundle(bundleJson);
        }

        @PostMapping("/fhir/r4/Subscription")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Register tenant-scoped FHIR REST-Hook Subscription")
        public FhirSubscriptionEntity createSubscription(@RequestBody FhirSubscriptionEntity sub) {
            UUID facilityId = requireTenantFacilityId();
            enforcePayloadFacilityMatchesTenant(sub.getFacilityId(), facilityId);
            sub.setFacilityId(facilityId);
            return subscriptionRepo.save(sub);
        }

        @PostMapping("/fhir/r4/Subscription/{id}/$notify")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Dispatch HMAC-signed FHIR Subscription REST-Hook event")
        public Map<String, Object> notifySubscription(@PathVariable UUID id, @RequestBody String fhirJson) {
            return service.dispatchSignedWebhook(id, fhirJson);
        }
    }

    @RestController
    @Tag(name = "IHE Interoperability Profiles", description = "Tenant-scoped IHE PIXm ($ihe-pix), PDQm Patient search, MHD DocumentReference & DB-backed mCSD Organization/Location")
    public static class InteroperabilityIheController {
        private final IheDocumentRepository docRepo;
        private final MpiCommandService.PatientRepository patientRepo;
        private final HieInteropService service;

        public InteroperabilityIheController(
                IheDocumentRepository docRepo,
                MpiCommandService.PatientRepository patientRepo) {
            this(docRepo, patientRepo, null);
        }

        public InteroperabilityIheController(
                IheDocumentRepository docRepo,
                MpiCommandService.PatientRepository patientRepo,
                HieInteropService service) {
            this.docRepo = docRepo;
            this.patientRepo = patientRepo;
            this.service = service;
        }

        @GetMapping(value = "/fhir/r4/Patient/$ihe-pix", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Tenant-scoped IHE PIXm ($ihe-pix) cross-reference lookup by MRN or Patient UUID")
        public Map<String, Object> ihePix(@RequestParam String sourceIdentifier) {
            UUID facilityId = requireTenantFacilityId();
            List<Map<String, Object>> targets = patientRepo.findByFacilityId(facilityId).stream()
                    .filter(p -> facilityId.equals(p.getFacilityId()))
                    .filter(p -> sourceIdentifier.contains(p.getMrn()) || sourceIdentifier.contains(p.getId().toString()))
                    .map(p -> Map.<String, Object>of(
                            "name", "targetId",
                            "valueIdentifier", Map.of("system", "urn:opes:mrn", "value", p.getMrn()),
                            "resourceId", "Patient/" + p.getId()))
                    .toList();
            return Map.of("resourceType", "Parameters", "parameter", targets);
        }

        @GetMapping(value = "/fhir/r4/Patient", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Tenant-scoped IHE PDQm FHIR R4 Patient demographic search")
        public String ihePdqm(
                @RequestParam(required = false) String family,
                @RequestParam(required = false) String given) {
            UUID facilityId = requireTenantFacilityId();
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            patientRepo.findByFacilityId(facilityId).stream()
                    .filter(p -> facilityId.equals(p.getFacilityId()))
                    .filter(p -> family == null || p.getFamilyName().toLowerCase(Locale.ROOT).contains(family.toLowerCase(Locale.ROOT)))
                    .filter(p -> given == null || p.getGivenName().toLowerCase(Locale.ROOT).contains(given.toLowerCase(Locale.ROOT)))
                    .forEach(p -> bundle.addEntry()
                            .setFullUrl("Patient/" + p.getId())
                            .setResource(FhirPatientController.toFhirPatient(p)));
            return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
        }

        @PostMapping("/fhir/r4/DocumentReference")
        @PreAuthorize("hasAuthority('PERM_patient:read') and (hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign'))")
        @Operation(summary = "IHE MHD Register tenant-scoped DocumentReference metadata")
        public IheDocumentEntity createDocument(@RequestBody IheDocumentEntity doc) {
            UUID facilityId = requireTenantFacilityId();
            enforcePayloadFacilityMatchesTenant(doc.getFacilityId(), facilityId);
            doc.setFacilityId(facilityId);
            patientRepo.findByIdAndFacilityId(doc.getPatientId(), facilityId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "Patient not found within active facility tenant: " + doc.getPatientId()));
            return docRepo.save(doc);
        }

        @GetMapping("/fhir/r4/DocumentReference")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "IHE MHD Query tenant-scoped DocumentReference resources by patient UUID")
        public List<IheDocumentEntity> findDocuments(@RequestParam UUID patient) {
            UUID facilityId = requireTenantFacilityId();
            return docRepo.findByFacilityIdAndPatientIdOrderByIndexedAtDesc(facilityId, patient);
        }

        @GetMapping(value = "/fhir/r4/Organization", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "IHE mCSD Tenant-Scoped Organization Directory backed by persisted facility data")
        public Map<String, Object> mcsdOrganizations() {
            if (service != null) {
                return service.queryTenantOrganizations();
            }
            requireTenantFacilityId();
            return Map.of("resourceType", "Bundle", "type", "searchset", "total", 0, "entry", List.of());
        }

        @GetMapping(value = "/fhir/r4/Location", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "IHE mCSD Tenant-Scoped Physical Care Location Directory backed by persisted ward data")
        public Map<String, Object> mcsdLocations() {
            if (service != null) {
                return service.queryTenantLocations();
            }
            requireTenantFacilityId();
            return Map.of("resourceType", "Bundle", "type", "searchset", "total", 0, "entry", List.of());
        }
    }

    @RestController
    @Tag(name = "HL7v2, ASTM E1394, DICOM Metadata Registry & CDA R2", description = "Tenant-scoped HL7v2 ADT ingestion, ASTM analyzer parser, DICOM Study Metadata Registry & CDA R2 XML")
    public static class Hl7AstmDicomController {
        private final HieInteropService service;
        private final DicomStudyRepository dicomRepo;

        public Hl7AstmDicomController(HieInteropService service, DicomStudyRepository dicomRepo) {
            this.service = service;
            this.dicomRepo = dicomRepo;
        }

        @PostMapping(value = "/api/v1/hie/hl7v2/ingest", consumes = MediaType.TEXT_PLAIN_VALUE)
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_lab:result:enter')")
        @Operation(summary = "Ingest HL7 v2.5.1 ADT^A01/A04/A08 message into tenant MPI (rejects unsupported types with AR)")
        public Map<String, Object> ingestHl7(@RequestBody String rawHl7) {
            return service.ingestHl7v2(rawHl7);
        }

        @PostMapping(value = "/api/v1/hie/astm/parse", consumes = MediaType.TEXT_PLAIN_VALUE)
        @PreAuthorize("hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Parse ASTM E1394 laboratory analyzer result lines")
        public List<Map<String, String>> parseAstm(@RequestBody String astm) {
            requireTenantFacilityId();
            return service.parseAstmE1394(astm);
        }

        @PostMapping("/api/v1/hie/dicom/studies")
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign') or hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Register tenant-scoped DICOM study metadata (metadata registry only — does not store DICOM binary instances)")
        public DicomStudyEntity registerDicom(@RequestBody DicomStudyEntity study) {
            UUID facilityId = requireTenantFacilityId();
            enforcePayloadFacilityMatchesTenant(study.getFacilityId(), facilityId);
            study.setFacilityId(facilityId);
            return dicomRepo.save(study);
        }

        @PostMapping("/api/v1/hie/dicom/stow-rs")
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')")
        @Operation(summary = "Unsupported metadata-only STOW-RS endpoint — use binary PACS proxy /api/v1/hie/dicom/pacs/studies instead")
        public ResponseEntity<Map<String, Object>> stowRs(@RequestBody DicomStudyEntity study) {
            requireTenantFacilityId();
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                    "status", "NOT_IMPLEMENTED",
                    "error", "Metadata-only JSON payloads cannot satisfy DICOMweb STOW-RS binary instance storage.",
                    "recommendation", "Use POST /api/v1/hie/dicom/studies for study metadata registration, or POST /api/v1/hie/dicom/pacs/studies with application/dicom binary payloads and healthcore.hie.dicom.pacs-enabled=true."
            ));
        }

        @GetMapping("/api/v1/hie/dicom/studies")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Query tenant-scoped DICOM study metadata registry for a patient")
        public List<DicomStudyEntity> queryDicom(@RequestParam UUID patientId) {
            UUID facilityId = requireTenantFacilityId();
            return dicomRepo.findByFacilityIdAndPatientIdOrderByStudyDateDesc(facilityId, patientId);
        }

        @GetMapping(value = "/api/v1/hie/cda/{patientId}", produces = MediaType.APPLICATION_XML_VALUE)
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Export tenant-scoped HL7 CDA R2 clinical document XML")
        public String cdaXml(@PathVariable UUID patientId, @RequestParam(defaultValue = "Clinical summary") String text) {
            return service.generateCdaR2Xml(patientId, text);
        }
    }
}
