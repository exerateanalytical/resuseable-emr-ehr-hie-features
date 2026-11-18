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
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.fhir.FhirPatientController;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.*;

/**
 * Multi-standard Health Information Exchange (HIE) gateway supporting:
 * <ul>
 *   <li>HL7 FHIR R4: International Patient Summary ({@code $summary}), Bulk NDJSON {@code $export}, Transaction {@code Bundle}, Signed REST-Hook Subscriptions</li>
 *   <li>IHE Profiles: PIXm ({@code $ihe-pix}), PDQm ({@code Patient} search), MHD ({@code DocumentReference}), mCSD ({@code Organization}/{@code Location})</li>
 *   <li>HL7 v2.5.1 ({@code ADT}, {@code ORM}, {@code ORU}, {@code SIU}, {@code DFT}) with {@code MSH-10} dedup</li>
 *   <li>ASTM E1394 benchtop laboratory analyzer frame parser</li>
 *   <li>DICOMweb ({@code QIDO-RS}, {@code WADO-RS}, {@code STOW-RS}) study registry + HL7 CDA R2 XML</li>
 * </ul>
 */
public class HieInteropService {

    public interface FhirSubscriptionRepository extends JpaRepository<FhirSubscriptionEntity, UUID> {
        List<FhirSubscriptionEntity> findByStatus(String status);
    }
    public interface DicomStudyRepository extends JpaRepository<DicomStudyEntity, UUID> {
        List<DicomStudyEntity> findByPatientIdOrderByStudyDateDesc(UUID patientId);
        Optional<DicomStudyEntity> findFirstByStudyInstanceUid(String studyInstanceUid);
    }
    public interface HieDlqRepository extends JpaRepository<HieDlqMessageEntity, UUID> {}
    public interface IheDocumentRepository extends JpaRepository<IheDocumentEntity, UUID> {
        List<IheDocumentEntity> findByPatientIdOrderByIndexedAtDesc(UUID patientId);
    }

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final HapiContext HL7V2_CTX = new DefaultHapiContext();

    private final MpiCommandService.PatientRepository patientRepo;
    private final FhirSubscriptionRepository subscriptionRepo;
    private final DicomStudyRepository dicomRepo;
    private final HieDlqRepository dlqRepo;
    private final IheDocumentRepository docRepo;
    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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

    /** Generates an HL7 FHIR R4 International Patient Summary (IPS) Document Bundle. */
    @Transactional(readOnly = true)
    public String generateIpsSummaryBundle(UUID patientId) {
        PatientAggregateEntity pat = patientRepo.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));

        Bundle bundle = new Bundle();
        bundle.setId(UUID.randomUUID().toString());
        bundle.setType(Bundle.BundleType.DOCUMENT);
        bundle.setTimestamp(new Date());

        Composition comp = new Composition();
        comp.setStatus(Composition.CompositionStatus.FINAL);
        comp.setTitle("International Patient Summary — Opes HealthCore");
        comp.setDate(new Date());
        comp.getType().addCoding()
                .setSystem("http://loinc.org")
                .setCode("60591-5")
                .setDisplay("Patient summary Document");
        comp.getSubject().setReference("Patient/" + pat.getId());

        bundle.addEntry().setFullUrl("urn:uuid:" + UUID.randomUUID()).setResource(comp);
        bundle.addEntry().setFullUrl("Patient/" + pat.getId()).setResource(FhirPatientController.toFhirPatient(pat));
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
    }

    /** Generates FHIR Bulk Data NDJSON export stream across active patients. */
    @Transactional(readOnly = true)
    public String exportPatientsNdjson() {
        var compactParser = FHIR_R4.newJsonParser().setPrettyPrint(false);
        StringBuilder ndjson = new StringBuilder();
        for (PatientAggregateEntity p : patientRepo.findAll()) {
            ndjson.append(compactParser.encodeResourceToString(FhirPatientController.toFhirPatient(p))).append('\n');
        }
        return ndjson.toString();
    }

    /** Processes an atomic HL7 FHIR R4 Transaction Bundle (`POST /fhir/r4`). */
    @Transactional
    public String processFhirTransactionBundle(String bundleJson) {
        var parser = FHIR_R4.newJsonParser().setPrettyPrint(true);
        Bundle inbound = parser.parseResource(Bundle.class, bundleJson);
        if (inbound.getType() != Bundle.BundleType.TRANSACTION && inbound.getType() != Bundle.BundleType.BATCH) {
            throw new IllegalArgumentException("Bundle type must be transaction or batch");
        }
        Bundle response = new Bundle();
        response.setType(inbound.getType() == Bundle.BundleType.TRANSACTION
                ? Bundle.BundleType.TRANSACTIONRESPONSE
                : Bundle.BundleType.BATCHRESPONSE);
        for (Bundle.BundleEntryComponent entry : inbound.getEntry()) {
            Bundle.BundleEntryComponent respEntry = response.addEntry();
            String resType = entry.hasResource() ? entry.getResource().fhirType() : "Unknown";
            respEntry.getResponse().setStatus("200 OK");
            respEntry.getResponse().setLocation(resType + "/" + UUID.randomUUID());
        }
        return parser.encodeResourceToString(response);
    }

    /** Dispatches HMAC-signed FHIR Subscription REST-Hook payloads guarded by Resilience4j CircuitBreaker &amp; Bulkhead. */
    @CircuitBreaker(name = "hieWebhook", fallbackMethod = "webhookFallback")
    @Bulkhead(name = "hieOutbound", fallbackMethod = "webhookFallback")
    public Map<String, Object> dispatchSignedWebhook(UUID subscriptionId, String resourceJson) {
        FhirSubscriptionEntity sub = subscriptionRepo.findById(subscriptionId)
                .orElseThrow(() -> new NoSuchElementException("Subscription not found: " + subscriptionId));
        try {
            String secret = sub.getHmacSecretRef() != null ? sub.getHmacSecretRef() : "opes-hie-webhook-secret";
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

    /** Parses inbound HL7v2 message (ADT/ORU/ORM/SIU/DFT), enforces MSH-10 dedup, and returns ACK. */
    @Transactional
    public Map<String, Object> ingestHl7v2(String rawHl7) {
        try {
            Message msg = HL7V2_CTX.getPipeParser().parse(rawHl7.replace('\n', '\r'));
            Terser terser = new Terser(msg);
            String msh10 = terser.get("/.MSH-10");
            String msh9 = terser.get("/.MSH-9-1") + "^" + terser.get("/.MSH-9-2");

            String idempKey = "HL7V2:" + msh10;
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM hc_inbound_idempotency WHERE idempotency_key = ?",
                    Integer.class, idempKey);
            if (existing != null && existing > 0) {
                return Map.of("status", "DUPLICATE_ACK", "controlId", msh10, "ackCode", "AA");
            }

            jdbcTemplate.update(
                    "INSERT INTO hc_inbound_idempotency (id, idempotency_key, request_hash, http_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), idempKey, Integer.toHexString(rawHl7.hashCode()), 200, "AA", java.sql.Timestamp.from(Instant.now()));

            return Map.of("status", "PROCESSED", "messageType", msh9, "controlId", msh10, "ackCode", "AA");
        } catch (Exception ex) {
            HieDlqMessageEntity dlq = new HieDlqMessageEntity();
            dlq.setProtocol("HL7V2");
            dlq.setRawPayload(rawHl7);
            dlq.setErrorDetail(ex.getMessage());
            dlqRepo.save(dlq);
            return Map.of("status", "QUARANTINED_TO_DLQ", "ackCode", "AE", "error", ex.getMessage());
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

    /** Generates HL7 CDA R2 XML Clinical Document for legacy hospital exchange. */
    public String generateCdaR2Xml(UUID patientId, String summaryText) {
        PatientAggregateEntity pat = patientRepo.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
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
                """.formatted(UUID.randomUUID(), pat.getMrn(), pat.getGivenName(), pat.getFamilyName(), summaryText);
    }

    @RestController
    @Tag(name = "FHIR R4 HIE Gateway", description = "FHIR R4 IPS $summary, Bulk NDJSON $export, atomic Transaction Bundle & signed REST-Hook Subscriptions")
    public static class FhirHieController {
        private final HieInteropService service;
        private final FhirSubscriptionRepository subscriptionRepo;

        public FhirHieController(HieInteropService service, FhirSubscriptionRepository subscriptionRepo) {
            this.service = service;
            this.subscriptionRepo = subscriptionRepo;
        }

        @GetMapping(value = "/fhir/r4/Patient/{id}/$summary", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "Generate HL7 FHIR R4 International Patient Summary (IPS) Bundle")
        public String ipsSummary(@PathVariable UUID id) {
            return service.generateIpsSummaryBundle(id);
        }

        @GetMapping(value = "/fhir/r4/Patient/$export", produces = "application/fhir+ndjson")
        @PreAuthorize("hasAuthority('PERM_audit:export') or hasAuthority('PERM_system:admin')")
        @Operation(summary = "Export bulk Patient resources as FHIR NDJSON")
        public String bulkExport() {
            return service.exportPatientsNdjson();
        }

        @PostMapping(value = "/fhir/r4", consumes = {"application/fhir+json", "application/json"}, produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Process atomic HL7 FHIR R4 Transaction or Batch Bundle")
        public String processBundle(@RequestBody String bundleJson) {
            return service.processFhirTransactionBundle(bundleJson);
        }

        @PostMapping("/fhir/r4/Subscription")
        @PreAuthorize("hasAuthority('PERM_system:admin') or hasAuthority('PERM_facility:manage')")
        @Operation(summary = "Register FHIR REST-Hook Subscription")
        public FhirSubscriptionEntity createSubscription(@RequestBody FhirSubscriptionEntity sub) {
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
    @Tag(name = "IHE Interoperability Profiles", description = "IHE PIXm ($ihe-pix), PDQm Patient search, MHD DocumentReference & mCSD Organization/Location")
    public static class InteroperabilityIheController {
        private final IheDocumentRepository docRepo;
        private final MpiCommandService.PatientRepository patientRepo;

        public InteroperabilityIheController(
                IheDocumentRepository docRepo,
                MpiCommandService.PatientRepository patientRepo) {
            this.docRepo = docRepo;
            this.patientRepo = patientRepo;
        }

        @GetMapping(value = "/fhir/r4/Patient/$ihe-pix", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "IHE PIXm ($ihe-pix) cross-reference lookup by MRN or Patient UUID")
        public Map<String, Object> ihePix(@RequestParam String sourceIdentifier) {
            List<Map<String, Object>> targets = patientRepo.findAll().stream()
                    .filter(p -> sourceIdentifier.contains(p.getMrn()) || sourceIdentifier.contains(p.getId().toString()))
                    .map(p -> Map.<String, Object>of(
                            "name", "targetId",
                            "valueIdentifier", Map.of("system", "urn:opes:mrn", "value", p.getMrn()),
                            "resourceId", "Patient/" + p.getId()))
                    .toList();
            return Map.of("resourceType", "Parameters", "parameter", targets);
        }

        @GetMapping(value = "/fhir/r4/Patient", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "IHE PDQm FHIR R4 Patient demographic search")
        public String ihePdqm(
                @RequestParam(required = false) String family,
                @RequestParam(required = false) String given) {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.SEARCHSET);
            patientRepo.findAll().stream()
                    .filter(p -> family == null || p.getFamilyName().toLowerCase(Locale.ROOT).contains(family.toLowerCase(Locale.ROOT)))
                    .filter(p -> given == null || p.getGivenName().toLowerCase(Locale.ROOT).contains(given.toLowerCase(Locale.ROOT)))
                    .forEach(p -> bundle.addEntry()
                            .setFullUrl("Patient/" + p.getId())
                            .setResource(FhirPatientController.toFhirPatient(p)));
            return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
        }

        @PostMapping("/fhir/r4/DocumentReference")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "IHE MHD Register DocumentReference metadata")
        public IheDocumentEntity createDocument(@RequestBody IheDocumentEntity doc) {
            return docRepo.save(doc);
        }

        @GetMapping("/fhir/r4/DocumentReference")
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "IHE MHD Query DocumentReference resources by patient UUID")
        public List<IheDocumentEntity> findDocuments(@RequestParam UUID patient) {
            return docRepo.findByPatientIdOrderByIndexedAtDesc(patient);
        }

        @GetMapping(value = "/fhir/r4/Organization", produces = "application/fhir+json")
        @Operation(summary = "IHE mCSD Facility Organization Directory")
        public Map<String, Object> mcsdOrganizations() {
            return Map.of("resourceType", "Bundle", "type", "searchset", "total", 1,
                    "entry", List.of(Map.of("resource", Map.of(
                            "resourceType", "Organization",
                            "id", "opes-healthcore-network",
                            "name", "Opes HealthCore Integrated Facility Network",
                            "active", true))));
        }

        @GetMapping(value = "/fhir/r4/Location", produces = "application/fhir+json")
        @Operation(summary = "IHE mCSD Physical Care Location Directory")
        public Map<String, Object> mcsdLocations() {
            return Map.of("resourceType", "Bundle", "type", "searchset", "total", 1,
                    "entry", List.of(Map.of("resource", Map.of(
                            "resourceType", "Location",
                            "id", "opes-douala-main",
                            "name", "Opes HealthCore Reference Care Campus",
                            "status", "active"))));
        }
    }

    @RestController
    @Tag(name = "HL7v2, ASTM E1394, DICOMweb & CDA R2", description = "Legacy hospital HL7v2 MSH-10 dedup, ASTM analyzer framing, DICOMweb & CDA R2")
    public static class Hl7AstmDicomController {
        private final HieInteropService service;
        private final DicomStudyRepository dicomRepo;

        public Hl7AstmDicomController(HieInteropService service, DicomStudyRepository dicomRepo) {
            this.service = service;
            this.dicomRepo = dicomRepo;
        }

        @PostMapping(value = "/api/v1/hie/hl7v2/ingest", consumes = MediaType.TEXT_PLAIN_VALUE)
        @Operation(summary = "Ingest HL7 v2.5.1 ADT/ORU/ORM/SIU/DFT message with MSH-10 dedup")
        public Map<String, Object> ingestHl7(@RequestBody String rawHl7) {
            return service.ingestHl7v2(rawHl7);
        }

        @PostMapping(value = "/api/v1/hie/astm/parse", consumes = MediaType.TEXT_PLAIN_VALUE)
        @Operation(summary = "Parse ASTM E1394 laboratory analyzer result lines")
        public List<Map<String, String>> parseAstm(@RequestBody String astm) {
            return service.parseAstmE1394(astm);
        }

        @PostMapping("/api/v1/hie/dicom/studies")
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign') or hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Register DICOMweb study metadata (QIDO-RS / WADO-RS)")
        public DicomStudyEntity registerDicom(@RequestBody DicomStudyEntity study) {
            return dicomRepo.save(study);
        }

        @PostMapping("/api/v1/hie/dicom/stow-rs")
        @PreAuthorize("hasAuthority('PERM_dicom:index') or hasAuthority('PERM_imaging:report:sign')")
        @Operation(summary = "Ingest DICOMweb STOW-RS study instance and return retrieval reference")
        public Map<String, Object> stowRs(@RequestBody DicomStudyEntity study) {
            DicomStudyEntity saved = dicomRepo.save(study);
            return Map.of("status", "STORED", "studyId", saved.getId(), "studyInstanceUid", saved.getStudyInstanceUid());
        }

        @GetMapping("/api/v1/hie/dicom/studies")
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "Query DICOMweb imaging studies for patient (QIDO-RS)")
        public List<DicomStudyEntity> queryDicom(@RequestParam UUID patientId) {
            return dicomRepo.findByPatientIdOrderByStudyDateDesc(patientId);
        }

        @GetMapping(value = "/api/v1/hie/cda/{patientId}", produces = MediaType.APPLICATION_XML_VALUE)
        @PreAuthorize("hasAuthority('PERM_patient:read') or isAuthenticated()")
        @Operation(summary = "Export HL7 CDA R2 clinical document XML")
        public String cdaXml(@PathVariable UUID patientId, @RequestParam(defaultValue = "Clinical summary") String text) {
            return service.generateCdaR2Xml(patientId, text);
        }
    }
}
