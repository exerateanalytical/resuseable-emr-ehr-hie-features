/*
 * =============================================================================
 * Opes HealthCore SDK — Life Sciences Research LIMS & ELN Service & REST API
 * =============================================================================
 */
package com.opeshealth.healthcore.lifesciences.lims.service;

import com.opeshealth.healthcore.lifesciences.lims.domain.LifeSciencesLimsEntities.*;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureCommand;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureProof;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class LifeSciencesLimsService {

    /**
     * Extension point for laboratory instruments (ELISA, PCR, LC-MS, flow cytometers)
     * to ingest raw assay observations into ELN experiments.
     */
    public interface ResearchInstrumentAdapterSpi {
        String parseRawPayloadToJson(String instrumentId, String rawPayload);
    }

    public interface ResearchStudyRepository extends JpaRepository<ResearchStudyEntity, UUID> {
        Optional<ResearchStudyEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ResearchStudyEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
    }

    public interface ScientificSampleRepository extends JpaRepository<ScientificSampleEntity, UUID> {
        Optional<ScientificSampleEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ScientificSampleEntity> findByFacilityIdAndStudyId(UUID facilityId, UUID studyId);
    }

    public interface ElnExperimentRepository extends JpaRepository<ElnExperimentEntity, UUID> {
        Optional<ElnExperimentEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ElnExperimentEntity> findByFacilityIdAndStudyId(UUID facilityId, UUID studyId);
    }

    public record CreateStudyRequest(
            String studyCode,
            String title,
            String principalInvestigator,
            String protocolVersion
    ) {}

    public record RegisterSampleRequest(
            UUID studyId,
            String sampleBarcode,
            String sourceClassification,
            String sourceIdentifier,
            String materialType,
            UUID parentSampleId,
            String custodian,
            String storageLocation
    ) {}

    public record RecordElnExperimentRequest(
            UUID studyId,
            String experimentCode,
            String instrumentId,
            String notebookMarkdown,
            String observationsJson,
            String authorId
    ) {}

    private final ResearchStudyRepository studyRepo;
    private final ScientificSampleRepository sampleRepo;
    private final ElnExperimentRepository elnRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public LifeSciencesLimsService(
            ResearchStudyRepository studyRepo,
            ScientificSampleRepository sampleRepo,
            ElnExperimentRepository elnRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.studyRepo = studyRepo;
        this.sampleRepo = sampleRepo;
        this.elnRepo = elnRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_STUDY_CREATE", resourceType = "ResearchStudy")
    public ResearchStudyEntity createStudy(CreateStudyRequest req) {
        UUID facId = requireFacility();
        ResearchStudyEntity study = new ResearchStudyEntity();
        study.setFacilityId(facId);
        study.setStudyCode(req.studyCode().trim().toUpperCase(Locale.ROOT));
        study.setTitle(req.title().trim());
        study.setPrincipalInvestigator(req.principalInvestigator().trim());
        study.setProtocolVersion(req.protocolVersion() == null ? "v1.0" : req.protocolVersion().trim());
        study.setStatus("DRAFT");
        return studyRepo.save(study);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_ETHICS_APPROVE", resourceType = "ResearchStudy")
    public ResearchStudyEntity approveEthicsAndActivate(UUID studyId, String ethicsApprovalRef) {
        UUID facId = requireFacility();
        if (ethicsApprovalRef == null || ethicsApprovalRef.isBlank()) {
            throw new IllegalArgumentException("Ethics/IRB approval reference is required to activate a research study.");
        }
        ResearchStudyEntity study = studyRepo.findByIdAndFacilityId(studyId, facId)
                .orElseThrow(() -> new NoSuchElementException("Research study not found: " + studyId));
        study.setEthicsApprovalRef(ethicsApprovalRef.trim());
        study.setEthicsApproved(true);
        study.setStatus("ACTIVE");
        return studyRepo.save(study);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_SAMPLE_REGISTER", resourceType = "ScientificSample")
    public ScientificSampleEntity registerScientificSample(RegisterSampleRequest req) {
        UUID facId = requireFacility();
        ResearchStudyEntity study = studyRepo.findByIdAndFacilityId(req.studyId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Research study not found: " + req.studyId()));
        if (!study.isEthicsApproved() || !"ACTIVE".equals(study.getStatus())) {
            throw new IllegalStateException("Cannot register research samples before study Ethics/IRB approval is active.");
        }
        ScientificSampleEntity sample = new ScientificSampleEntity();
        sample.setFacilityId(facId);
        sample.setStudyId(req.studyId());
        sample.setSampleBarcode(req.sampleBarcode().trim().toUpperCase(Locale.ROOT));
        sample.setSourceClassification(req.sourceClassification().trim().toUpperCase(Locale.ROOT));
        sample.setSourceIdentifier(req.sourceIdentifier().trim());
        sample.setMaterialType(req.materialType().trim());
        sample.setParentSampleId(req.parentSampleId());
        sample.setCustodian(req.custodian().trim());
        sample.setStorageLocation(req.storageLocation());
        sample.setStatus("REGISTERED");
        return sampleRepo.save(sample);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_SAMPLE_TRANSFER", resourceType = "ScientificSample")
    public ScientificSampleEntity transferCustody(UUID sampleId, String newCustodian, String newLocation) {
        UUID facId = requireFacility();
        ScientificSampleEntity sample = sampleRepo.findByIdAndFacilityId(sampleId, facId)
                .orElseThrow(() -> new NoSuchElementException("Scientific sample not found: " + sampleId));
        sample.setCustodian(newCustodian.trim());
        if (newLocation != null && !newLocation.isBlank()) {
            sample.setStorageLocation(newLocation.trim());
        }
        return sampleRepo.save(sample);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_ELN_RECORD", resourceType = "ElnExperiment")
    public ElnExperimentEntity recordElnExperiment(RecordElnExperimentRequest req) {
        UUID facId = requireFacility();
        ResearchStudyEntity study = studyRepo.findByIdAndFacilityId(req.studyId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Research study not found: " + req.studyId()));
        if (!"ACTIVE".equals(study.getStatus())) {
            throw new IllegalStateException("Study must be ACTIVE to log ELN experiments.");
        }
        ElnExperimentEntity exp = new ElnExperimentEntity();
        exp.setFacilityId(facId);
        exp.setStudyId(req.studyId());
        exp.setExperimentCode(req.experimentCode().trim().toUpperCase(Locale.ROOT));
        exp.setProtocolVersion(study.getProtocolVersion());
        exp.setInstrumentId(req.instrumentId());
        exp.setNotebookMarkdown(req.notebookMarkdown());
        exp.setObservationsJson(req.observationsJson());
        exp.setAuthorId(req.authorId().trim());
        exp.setStatus("SUBMITTED_FOR_REVIEW");
        return elnRepo.save(exp);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_ELN_AMEND", resourceType = "ElnExperiment")
    public ElnExperimentEntity amendElnExperiment(UUID experimentId, String updatedMarkdown, String updatedObservationsJson) {
        UUID facId = requireFacility();
        ElnExperimentEntity exp = elnRepo.findByIdAndFacilityId(experimentId, facId)
                .orElseThrow(() -> new NoSuchElementException("ELN experiment not found: " + experimentId));
        if ("SIGNED_LOCKED".equals(exp.getStatus())) {
            throw new IllegalStateException("Cannot edit a signed/locked ELN experiment; create a linked addendum experiment instead.");
        }
        exp.setRevisionNumber(exp.getRevisionNumber() + 1);
        exp.setNotebookMarkdown(updatedMarkdown);
        exp.setObservationsJson(updatedObservationsJson);
        exp.setUpdatedAt(Instant.now());
        return elnRepo.save(exp);
    }

    @Transactional
    @ClinicalAudit(action = "LSLIMS_ELN_SIGN", resourceType = "ElnExperiment")
    public ElnExperimentEntity reviewAndSignEln(UUID experimentId, String reviewerId) {
        UUID facId = requireFacility();
        ElnExperimentEntity exp = elnRepo.findByIdAndFacilityId(experimentId, facId)
                .orElseThrow(() -> new NoSuchElementException("ELN experiment not found: " + experimentId));
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                reviewerId, "SCIENTIFIC_REVIEWER", "ELN_EXPERIMENT_VERIFIED_LOCKED",
                "ElnExperiment", experimentId.toString(),
                exp.getExperimentCode() + "|" + exp.getRevisionNumber() + "|" + exp.getNotebookMarkdown(),
                exp.getAuthorId(), true
        ));
        exp.setReviewerId(proof.signerId());
        exp.setReviewSigSha256(proof.signatureDigestSha256());
        exp.setStatus("SIGNED_LOCKED");
        exp.setUpdatedAt(proof.signedAt());
        return elnRepo.save(exp);
    }

    @Transactional(readOnly = true)
    public List<ResearchStudyEntity> listStudies() {
        return studyRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Life Sciences LIMS operations."));
    }

    @RestController
    @RequestMapping("/api/v1/lifesciences/lims")
    public static class LifeSciencesLimsController {
        private final LifeSciencesLimsService service;

        public LifeSciencesLimsController(LifeSciencesLimsService service) {
            this.service = service;
        }

        @GetMapping("/studies")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:read')")
        public List<ResearchStudyEntity> listStudies() {
            return service.listStudies();
        }

        @PostMapping("/studies")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public ResearchStudyEntity createStudy(@RequestBody CreateStudyRequest req) {
            return service.createStudy(req);
        }

        @PostMapping("/studies/{id}/approve-ethics")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public ResearchStudyEntity approveEthics(@PathVariable UUID id, @RequestParam String ethicsRef) {
            return service.approveEthicsAndActivate(id, ethicsRef);
        }

        @PostMapping("/samples")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public ScientificSampleEntity registerSample(@RequestBody RegisterSampleRequest req) {
            return service.registerScientificSample(req);
        }

        @PostMapping("/samples/{id}/transfer")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public ScientificSampleEntity transfer(@PathVariable UUID id, @RequestParam String custodian, @RequestParam(required = false) String location) {
            return service.transferCustody(id, custodian, location);
        }

        @PostMapping("/eln")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public ElnExperimentEntity recordEln(@RequestBody RecordElnExperimentRequest req) {
            return service.recordElnExperiment(req);
        }

        @PostMapping("/eln/{id}/sign")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public ElnExperimentEntity signEln(@PathVariable UUID id, @RequestParam String reviewerId) {
            return service.reviewAndSignEln(id, reviewerId);
        }
    }
}
