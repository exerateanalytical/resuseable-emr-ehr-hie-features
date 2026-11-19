/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 v2.5.1, ASTM E1394 & DICOMweb Imaging Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.autoconfigure.HieInteropAutoConfiguration;
import com.opeshealth.healthcore.hie.domain.HieEntities.DicomStudyEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.DicomStudyRepository;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * End-to-end Clinical Analyzer &amp; Medical Imaging Gateway module providing
 * ASTM E1394 frame modulo-256 checksum calculation and parsing, HL7 v2.5.1 pipe
 * ingestion with `MSA|AA` / `MSA|AE` ACK generation, and PostgreSQL DICOMweb
 * (`QIDO-RS` / `STOW-RS`) study indexing.
 */
@AutoConfiguration(after = HieInteropAutoConfiguration.class)
public class Hl7AstmDicomStarter {

    public record AstmAnalyzerIngestionResult(
            String calculatedModulo256ChecksumHex,
            int recordCount,
            List<Map<String, String>> parsedResults
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public ClinicalDeviceAndImagingGatewayService clinicalDeviceAndImagingGatewayService(
            HieInteropService hieInteropService,
            DicomStudyRepository dicomStudyRepository) {
        return new ClinicalDeviceAndImagingGatewayService(hieInteropService, dicomStudyRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClinicalDeviceAndImagingGatewayController clinicalDeviceAndImagingGatewayController(
            ClinicalDeviceAndImagingGatewayService gatewayService) {
        return new ClinicalDeviceAndImagingGatewayController(gatewayService);
    }

    public static class ClinicalDeviceAndImagingGatewayService {

        private final HieInteropService hieInteropService;
        private final DicomStudyRepository dicomStudyRepository;

        public ClinicalDeviceAndImagingGatewayService(
                HieInteropService hieInteropService,
                DicomStudyRepository dicomStudyRepository) {
            this.hieInteropService = Objects.requireNonNull(hieInteropService, "HieInteropService is required");
            this.dicomStudyRepository = Objects.requireNonNull(dicomStudyRepository, "DicomStudyRepository is required");
        }

        /**
         * Computes the ASTM E1381/E1394 frame modulo-256 checksum (2-character uppercase hex)
         * and parses all `R|` result segments from a benchtop laboratory analyzer frame.
         */
        @ClinicalAudit(action = "ASTM_E1394_ANALYZER_INGEST", resourceType = "Observation")
        public AstmAnalyzerIngestionResult verifyAndParseAstmAnalyzerFrame(String astmPayload) {
            if (astmPayload == null || astmPayload.isBlank()) {
                throw new IllegalArgumentException("ASTM E1394 frame payload must not be empty");
            }
            String checksumHex = computeAstmModulo256Checksum(astmPayload);
            List<Map<String, String>> parsed = hieInteropService.parseAstmAnalyzerFrame(astmPayload);
            return new AstmAnalyzerIngestionResult(checksumHex, parsed.size(), List.copyOf(parsed));
        }

        /**
         * Ingests an HL7 v2.5.1 pipe-delimited message under the caller's active facility tenant
         * with {@code MSH-10} deduplication and returns the HL7 v2 acknowledgment ({@code ACK}).
         */
        @Transactional
        @ClinicalAudit(action = "HL7V2_PIPE_INGEST", resourceType = "HL7v2Message")
        public Map<String, Object> ingestHl7v2MessageWithAck(UUID facilityId, String rawHl7Pipe) {
            UUID activeFacility = HieInteropService.requireTenantFacilityId();
            if (facilityId != null && !facilityId.equals(activeFacility)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Cross-tenant HL7v2 ingestion denied: X-Facility-Id does not match authenticated tenant.");
            }
            return hieInteropService.ingestHl7v2Message(activeFacility, rawHl7Pipe);
        }

        /**
         * Queries tenant-scoped DICOM study metadata persisted in {@code hc_dicom_studies},
         * optionally filtered by patientId and imaging modality ({@code CT}, {@code MR}, {@code CR}, {@code US}, {@code DX}).
         */
        @Transactional(readOnly = true)
        public List<DicomStudyEntity> searchDicomStudies(UUID patientId, String modality) {
            UUID activeFacility = HieInteropService.requireTenantFacilityId();
            List<DicomStudyEntity> base = patientId != null
                    ? dicomStudyRepository.findByFacilityIdAndPatientIdOrderByStudyDateDesc(activeFacility, patientId)
                    : dicomStudyRepository.findByFacilityIdOrderByStudyDateDesc(activeFacility);
            if (modality == null || modality.isBlank()) {
                return base;
            }
            String normModality = modality.trim().toUpperCase(Locale.ROOT);
            return base.stream()
                    .filter(d -> normModality.equalsIgnoreCase(d.getModality()))
                    .toList();
        }

        public static String computeAstmModulo256Checksum(String frameBody) {
            byte[] bytes = frameBody.getBytes(StandardCharsets.US_ASCII);
            int sum = 0;
            for (byte b : bytes) {
                sum = (sum + (b & 0xFF)) & 0xFF;
            }
            return String.format(Locale.ROOT, "%02X", sum);
        }
    }

    @RestController
    @RequestMapping("/api/v1/hl7-astm-dicom")
    @Tag(name = "HL7 v2.5.1, ASTM E1394 Analyzers & DICOMweb", description = "ASTM E1394 lab analyzer frame checksum & parsing, HL7 v2.5.1 ACK ingestion, and DICOMweb QIDO-RS query")
    public static class ClinicalDeviceAndImagingGatewayController {

        private final ClinicalDeviceAndImagingGatewayService gatewayService;

        public ClinicalDeviceAndImagingGatewayController(ClinicalDeviceAndImagingGatewayService gatewayService) {
            this.gatewayService = gatewayService;
        }

        @PostMapping("/astm/verify-and-ingest")
        @PreAuthorize("hasAuthority('PERM_lab:result:enter')")
        @Operation(summary = "Compute ASTM E1394 modulo-256 frame checksum and parse analyzer result segments")
        public AstmAnalyzerIngestionResult ingestAstmFrame(@RequestBody String astmPayload) {
            return gatewayService.verifyAndParseAstmAnalyzerFrame(astmPayload);
        }

        @PostMapping("/hl7v2/ingest-with-ack")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_lab:result:enter')")
        @Operation(summary = "Ingest HL7 v2.5.1 pipe message with MSH-10 deduplication and return HL7 ACK")
        public Map<String, Object> ingestHl7v2(
                @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityId,
                @RequestBody String rawHl7Pipe) {
            return gatewayService.ingestHl7v2MessageWithAck(facilityId, rawHl7Pipe);
        }

        @GetMapping("/dicomweb/studies")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Query DICOMweb QIDO-RS studies by patientId and modality")
        public List<DicomStudyEntity> queryDicomStudies(
                @RequestParam(required = false) UUID patientId,
                @RequestParam(required = false) String modality) {
            return gatewayService.searchDicomStudies(patientId, modality);
        }
    }
}
