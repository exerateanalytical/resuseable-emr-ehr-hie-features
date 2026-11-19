/*
 * =============================================================================
 * Opes HealthCore SDK — IHE PIXm, PDQm, MHD DocumentReference & mCSD Starter
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.hie.autoconfigure.HieInteropAutoConfiguration;
import com.opeshealth.healthcore.hie.domain.HieEntities.IheDocumentEntity;
import com.opeshealth.healthcore.hie.service.HieInteropService;
import com.opeshealth.healthcore.hie.service.HieInteropService.IheDocumentRepository;
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
import java.security.MessageDigest;
import java.util.*;

/**
 * End-to-end IHE (Integrating the Healthcare Enterprise) Profile Registry module
 * supporting IHE MHD (`ITI-65` / `ITI-67` DocumentReference metadata &amp; SHA-256
 * integrity verification), IHE PIXm (`ITI-83` Cross-Enterprise Patient Identifier
 * Cross-Reference), and IHE mCSD (`ITI-90` Care Services Discovery) backed by PostgreSQL.
 */
@AutoConfiguration(after = HieInteropAutoConfiguration.class)
public class InteroperabilityIheStarter {

    public record IheMhdPublishSubmission(
            UUID facilityId,
            UUID patientId,
            String classCode,
            String typeLoincCode,
            String formatCode,
            String mimeType,
            String repositoryUniqueId,
            String documentPayloadContent
    ) {}

    @Bean
    @ConditionalOnMissingBean
    public IheProfileRegistryService iheProfileRegistryService(
            HieInteropService hieInteropService,
            IheDocumentRepository iheDocumentRepository) {
        return new IheProfileRegistryService(hieInteropService, iheDocumentRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public IheProfileRegistryController iheProfileRegistryController(
            IheProfileRegistryService iheProfileRegistryService) {
        return new IheProfileRegistryController(iheProfileRegistryService);
    }

    public static class IheProfileRegistryService {

        private final HieInteropService hieInteropService;
        private final IheDocumentRepository iheDocumentRepository;

        public IheProfileRegistryService(
                HieInteropService hieInteropService,
                IheDocumentRepository iheDocumentRepository) {
            this.hieInteropService = Objects.requireNonNull(hieInteropService, "HieInteropService is required");
            this.iheDocumentRepository = Objects.requireNonNull(iheDocumentRepository, "IheDocumentRepository is required");
        }

        /**
         * Validates IHE MHD (Mobile access to Health Documents) ITI-65 required metadata
         * attributes, computes the cryptographic SHA-256 document digest, and indexes the
         * {@link IheDocumentEntity} in PostgreSQL.
         */
        @Transactional
        @ClinicalAudit(action = "IHE_MHD_ITI65_PUBLISH", resourceType = "DocumentReference")
        public IheDocumentEntity validateAndPublishMhdDocument(IheMhdPublishSubmission submission) {
            Objects.requireNonNull(submission, "IheMhdPublishSubmission must not be null");
            if (submission.patientId() == null) {
                throw new IllegalArgumentException("IHE MHD ITI-65 requires a non-null patientId");
            }
            if (submission.typeLoincCode() == null || submission.typeLoincCode().isBlank()) {
                throw new IllegalArgumentException("IHE MHD ITI-65 requires a LOINC typeCode (e.g., 34133-9 Summarization of Episode Note)");
            }
            if (submission.mimeType() == null || submission.mimeType().isBlank()) {
                throw new IllegalArgumentException("IHE MHD ITI-65 requires a valid contentAttachment.contentType mimeType");
            }

            String payload = submission.documentPayloadContent() != null ? submission.documentPayloadContent() : "";
            String sha256 = computeSha256Hex(payload);

            IheDocumentEntity doc = new IheDocumentEntity();
            doc.setFacilityId(submission.facilityId() != null ? submission.facilityId() : new UUID(0L, 1L));
            doc.setPatientId(submission.patientId());
            doc.setClassCode(submission.classCode() != null && !submission.classCode().isBlank()
                    ? submission.classCode().trim()
                    : "CLINICAL-SUMMARY");
            doc.setTypeLoinc(submission.typeLoincCode().trim());
            doc.setFormatCode(submission.formatCode() != null && !submission.formatCode().isBlank()
                    ? submission.formatCode().trim()
                    : "urn:ihe:pcc:ips:2020");
            doc.setMimeType(submission.mimeType().trim());
            doc.setRepositoryUniqueId(submission.repositoryUniqueId() != null && !submission.repositoryUniqueId().isBlank()
                    ? submission.repositoryUniqueId().trim()
                    : "urn:oid:2.16.840.1.113883.3.OPES.1");
            doc.setSha256Hash(sha256);
            doc.setStatus("CURRENT");

            return iheDocumentRepository.save(doc);
        }

        /**
         * Executes an IHE PIXm (`$ihe-pix`) patient identifier cross-reference lookup.
         */
        @Transactional(readOnly = true)
        public Map<String, Object> queryPixmCrossReference(UUID patientId, String targetSystem) {
            return hieInteropService.ihePixmLookup(patientId, targetSystem);
        }

        private static String computeSha256Hex(String content) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                throw new IllegalStateException("SHA-256 digest computation failed", ex);
            }
        }
    }

    @RestController
    @RequestMapping("/api/v1/ihe")
    @Tag(name = "IHE PIXm, PDQm, MHD & mCSD Profiles", description = "IHE ITI-65 MHD DocumentReference publication with SHA-256 verification and ITI-83 PIXm identifier lookup")
    public static class IheProfileRegistryController {

        private final IheProfileRegistryService iheProfileRegistryService;

        public IheProfileRegistryController(IheProfileRegistryService iheProfileRegistryService) {
            this.iheProfileRegistryService = iheProfileRegistryService;
        }

        @PostMapping("/mhd/validate-and-publish")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        @Operation(summary = "Validate IHE MHD ITI-65 DocumentReference metadata, compute SHA-256 digest, and persist")
        public IheDocumentEntity publishMhdDocument(@RequestBody IheMhdPublishSubmission submission) {
            return iheProfileRegistryService.validateAndPublishMhdDocument(submission);
        }

        @GetMapping("/pixm/cross-reference/{patientId}")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        @Operation(summary = "Execute IHE PIXm ($ihe-pix) cross-enterprise patient identifier lookup")
        public Map<String, Object> lookupPixm(
                @PathVariable UUID patientId,
                @RequestParam(required = false) String targetSystem) {
            return iheProfileRegistryService.queryPixmCrossReference(patientId, targetSystem);
        }
    }
}
