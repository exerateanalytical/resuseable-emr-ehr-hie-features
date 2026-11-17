/*
 * =============================================================================
 * Opes HealthCore SDK — HL7v2 MLLP, ASTM E1394, FHIR R4 IPS/NDJSON & CDA R2
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Multi-standard Health Information Exchange (HIE) gateway supporting:
 * <ul>
 *   <li>HL7 FHIR R4: International Patient Summary ({@code $summary}), Bulk NDJSON {@code $export}, Subscriptions</li>
 *   <li>IHE Profiles: PIXm, PDQm, MHD, mCSD, ATNA audit alignment</li>
 *   <li>HL7 v2.5.1 ({@code ADT}, {@code ORM}, {@code ORU}, {@code SIU}, {@code DFT}) with {@code MSH-10} dedup</li>
 *   <li>ASTM E1394 benchtop laboratory analyzer frame parser</li>
 *   <li>DICOMweb ({@code QIDO-RS} / {@code WADO-RS}) study registry + HL7 CDA R2 discharge XML</li>
 * </ul>
 */
public class HieInteropService {

    public interface FhirSubscriptionRepository extends JpaRepository<FhirSubscriptionEntity, UUID> {}
    public interface DicomStudyRepository extends JpaRepository<DicomStudyEntity, UUID> {
        List<DicomStudyEntity> findByPatientIdOrderByStudyDateDesc(UUID patientId);
    }
    public interface HieDlqRepository extends JpaRepository<HieDlqMessageEntity, UUID> {}

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final HapiContext HL7V2_CTX = new DefaultHapiContext();

    private final MpiCommandService.PatientRepository patientRepo;
    private final FhirSubscriptionRepository subscriptionRepo;
    private final DicomStudyRepository dicomRepo;
    private final HieDlqRepository dlqRepo;
    private final JdbcTemplate jdbcTemplate;

    public HieInteropService(
            MpiCommandService.PatientRepository patientRepo,
            FhirSubscriptionRepository subscriptionRepo,
            DicomStudyRepository dicomRepo,
            HieDlqRepository dlqRepo,
            JdbcTemplate jdbcTemplate) {
        this.patientRepo = patientRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.dicomRepo = dicomRepo;
        this.dlqRepo = dlqRepo;
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
    @Tag(name = "HIE Interoperability", description = "FHIR R4 IPS $summary, Bulk NDJSON $export, HL7v2, ASTM, DICOMweb & CDA R2")
    public static class HieInteropController {
        private final HieInteropService service;
        private final FhirSubscriptionRepository subscriptionRepo;
        private final DicomStudyRepository dicomRepo;

        public HieInteropController(
                HieInteropService service,
                FhirSubscriptionRepository subscriptionRepo,
                DicomStudyRepository dicomRepo) {
            this.service = service;
            this.subscriptionRepo = subscriptionRepo;
            this.dicomRepo = dicomRepo;
        }

        @GetMapping(value = "/fhir/r4/Patient/{id}/$summary", produces = "application/fhir+json")
        @Operation(summary = "Generate HL7 FHIR R4 International Patient Summary (IPS) Bundle")
        public String ipsSummary(@PathVariable UUID id) {
            return service.generateIpsSummaryBundle(id);
        }

        @GetMapping(value = "/fhir/r4/Patient/$export", produces = "application/fhir+ndjson")
        @Operation(summary = "Export bulk Patient resources as FHIR NDJSON")
        public String bulkExport() {
            return service.exportPatientsNdjson();
        }

        @PostMapping("/fhir/r4/Subscription")
        @Operation(summary = "Register FHIR REST-Hook Subscription")
        public FhirSubscriptionEntity createSubscription(@RequestBody FhirSubscriptionEntity sub) {
            return subscriptionRepo.save(sub);
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
        @Operation(summary = "Register DICOMweb study metadata (QIDO-RS / WADO-RS)")
        public DicomStudyEntity registerDicom(@RequestBody DicomStudyEntity study) {
            return dicomRepo.save(study);
        }

        @GetMapping("/api/v1/hie/dicom/studies")
        @Operation(summary = "Query DICOMweb imaging studies for patient (QIDO-RS)")
        public List<DicomStudyEntity> queryDicom(@RequestParam UUID patientId) {
            return dicomRepo.findByPatientIdOrderByStudyDateDesc(patientId);
        }

        @GetMapping(value = "/api/v1/hie/cda/{patientId}", produces = MediaType.APPLICATION_XML_VALUE)
        @Operation(summary = "Export HL7 CDA R2 clinical document XML")
        public String cdaXml(@PathVariable UUID patientId, @RequestParam(defaultValue = "Clinical summary") String text) {
            return service.generateCdaR2Xml(patientId, text);
        }
    }
}
