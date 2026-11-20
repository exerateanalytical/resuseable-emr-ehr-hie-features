/*
 * =============================================================================
 * Opes HealthCore SDK — Genomics & Bioinformatics Metadata Service & REST API
 * =============================================================================
 */
package com.opeshealth.healthcore.genomics.service;

import com.opeshealth.healthcore.genomics.domain.GenomicsEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@Service
public class GenomicsResearchService {

    /**
     * Pluggable variant store adapter SPI (e.g., GA4GH htsget, OpenCGA, Hail, object storage VCF index).
     */
    public interface VariantStoreAdapterSpi {
        boolean verifyArtifactAccessible(String vcfUri, String expectedSha256);
    }

    public interface SequencingRunRepository extends JpaRepository<SequencingRunEntity, UUID> {
        Optional<SequencingRunEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<SequencingRunEntity> findByFacilityIdOrderByExecutedAtDesc(UUID facilityId);
    }

    public interface PipelineDatasetRepository extends JpaRepository<PipelineDatasetEntity, UUID> {
        Optional<PipelineDatasetEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<PipelineDatasetEntity> findByFacilityIdOrderByCreatedAtDesc(UUID facilityId);
    }

    public record RecordSequencingRunRequest(
            String runCode,
            String sampleBarcode,
            String libraryPrepKit,
            String sequencerPlatform,
            BigDecimal q30Pct
    ) {}

    public record CatalogPipelineDatasetRequest(
            UUID sequencingRunId,
            String datasetAccession,
            String pipelineNameVersion,
            String referenceGenome,
            String vcfUri,
            String vcfSha256,
            String accessTier
    ) {}

    private static final BigDecimal MIN_Q30_THRESHOLD = BigDecimal.valueOf(80);

    private final SequencingRunRepository runRepo;
    private final PipelineDatasetRepository datasetRepo;
    private final VariantStoreAdapterSpi variantStoreSpi;

    public GenomicsResearchService(
            SequencingRunRepository runRepo,
            PipelineDatasetRepository datasetRepo,
            ObjectProvider<VariantStoreAdapterSpi> variantStoreProvider) {
        this.runRepo = runRepo;
        this.datasetRepo = datasetRepo;
        this.variantStoreSpi = variantStoreProvider != null
                ? variantStoreProvider.getIfAvailable(() -> (uri, sha) -> uri != null && !uri.isBlank() && sha != null && sha.length() == 64)
                : (uri, sha) -> uri != null && !uri.isBlank() && sha != null && sha.length() == 64;
    }

    @Transactional
    @ClinicalAudit(action = "GENOMICS_RUN_RECORD", resourceType = "SequencingRun")
    public SequencingRunEntity recordSequencingRun(RecordSequencingRunRequest req) {
        UUID facId = requireFacility();
        boolean passed = req.q30Pct() != null && req.q30Pct().compareTo(MIN_Q30_THRESHOLD) >= 0;
        SequencingRunEntity run = new SequencingRunEntity();
        run.setFacilityId(facId);
        run.setRunCode(req.runCode().trim().toUpperCase(Locale.ROOT));
        run.setSampleBarcode(req.sampleBarcode().trim().toUpperCase(Locale.ROOT));
        run.setLibraryPrepKit(req.libraryPrepKit().trim());
        run.setSequencerPlatform(req.sequencerPlatform().trim());
        run.setQ30Pct(req.q30Pct());
        run.setQcPassed(passed);
        run.setStatus(passed ? "COMPLETED" : "QC_FAILED");
        return runRepo.save(run);
    }

    @Transactional
    @ClinicalAudit(action = "GENOMICS_DATASET_CATALOG", resourceType = "PipelineDataset")
    public PipelineDatasetEntity catalogPipelineDataset(CatalogPipelineDatasetRequest req) {
        UUID facId = requireFacility();
        SequencingRunEntity run = runRepo.findByIdAndFacilityId(req.sequencingRunId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Sequencing run not found: " + req.sequencingRunId()));
        if (!run.isQcPassed()) {
            throw new IllegalStateException("Cannot catalog bioinformatics dataset from a QC_FAILED sequencing run (Q30=" + run.getQ30Pct() + "%).");
        }
        if (!variantStoreSpi.verifyArtifactAccessible(req.vcfUri(), req.vcfSha256())) {
            throw new IllegalArgumentException("VariantStoreAdapterSpi rejected VCF artifact URI or SHA-256 digest.");
        }
        PipelineDatasetEntity ds = new PipelineDatasetEntity();
        ds.setFacilityId(facId);
        ds.setSequencingRunId(run.getId());
        ds.setDatasetAccession(req.datasetAccession().trim().toUpperCase(Locale.ROOT));
        ds.setPipelineNameVersion(req.pipelineNameVersion().trim());
        ds.setReferenceGenome(req.referenceGenome().trim());
        ds.setVcfUri(req.vcfUri().trim());
        ds.setVcfSha256(req.vcfSha256().trim().toLowerCase(Locale.ROOT));
        ds.setAccessTier(req.accessTier() == null ? "CONTROLLED_DAC" : req.accessTier().trim().toUpperCase(Locale.ROOT));
        ds.setStatus("CATALOGED");
        return datasetRepo.save(ds);
    }

    @Transactional
    @ClinicalAudit(action = "GENOMICS_DATASET_RELEASE", resourceType = "PipelineDataset")
    public PipelineDatasetEntity releaseDataset(UUID datasetId, String dacApproverId) {
        UUID facId = requireFacility();
        PipelineDatasetEntity ds = datasetRepo.findByIdAndFacilityId(datasetId, facId)
                .orElseThrow(() -> new NoSuchElementException("Pipeline dataset not found: " + datasetId));
        ds.setApprovedBy(dacApproverId.trim());
        ds.setStatus("RELEASED");
        return datasetRepo.save(ds);
    }

    @Transactional(readOnly = true)
    public List<PipelineDatasetEntity> listDatasets() {
        return datasetRepo.findByFacilityIdOrderByCreatedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Genomics Research operations."));
    }

    @RestController
    @RequestMapping("/api/v1/lifesciences/genomics")
    public static class GenomicsResearchController {
        private final GenomicsResearchService service;

        public GenomicsResearchController(GenomicsResearchService service) {
            this.service = service;
        }

        @PostMapping("/runs")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public SequencingRunEntity recordRun(@RequestBody RecordSequencingRunRequest req) {
            return service.recordSequencingRun(req);
        }

        @GetMapping("/datasets")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:read')")
        public List<PipelineDatasetEntity> listDatasets() {
            return service.listDatasets();
        }

        @PostMapping("/datasets")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public PipelineDatasetEntity catalogDataset(@RequestBody CatalogPipelineDatasetRequest req) {
            return service.catalogPipelineDataset(req);
        }

        @PostMapping("/datasets/{id}/release")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public PipelineDatasetEntity release(@PathVariable UUID id, @RequestParam String dacApproverId) {
            return service.releaseDataset(id, dacApproverId);
        }
    }
}
