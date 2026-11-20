package com.opeshealth.healthcore.genomics;

import com.opeshealth.healthcore.genomics.domain.GenomicsEntities.*;
import com.opeshealth.healthcore.genomics.service.GenomicsResearchService;
import com.opeshealth.healthcore.genomics.service.GenomicsResearchService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GenomicsResearchServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void blocksDatasetCatalogOnFailedSequencingQcAndVerifiesDigestProvenance() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        SequencingRunRepository runRepo = mock(SequencingRunRepository.class);
        PipelineDatasetRepository dsRepo = mock(PipelineDatasetRepository.class);
        when(runRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(dsRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        GenomicsResearchService service = new GenomicsResearchService(runRepo, dsRepo, null);

        SequencingRunEntity lowQ30 = service.recordSequencingRun(new RecordSequencingRunRequest(
                "RUN-001", "SMP-DNA-01", "TruSeq Nano", "NovaSeq X", BigDecimal.valueOf(68.4)));
        when(runRepo.findByIdAndFacilityId(lowQ30.getId(), facId)).thenReturn(Optional.of(lowQ30));
        assertFalse(lowQ30.isQcPassed());

        String validSha = "a".repeat(64);
        assertThrows(IllegalStateException.class, () -> service.catalogPipelineDataset(new CatalogPipelineDatasetRequest(
                lowQ30.getId(), "EGAD0001", "nf-core/sarek@3.4.2", "GRCh38.p14", "s3://genomics/a.vcf.gz", validSha, "CONTROLLED_DAC")));

        SequencingRunEntity goodRun = service.recordSequencingRun(new RecordSequencingRunRequest(
                "RUN-002", "SMP-DNA-01", "TruSeq Nano", "NovaSeq X", BigDecimal.valueOf(94.1)));
        when(runRepo.findByIdAndFacilityId(goodRun.getId(), facId)).thenReturn(Optional.of(goodRun));
        assertTrue(goodRun.isQcPassed());

        PipelineDatasetEntity ds = service.catalogPipelineDataset(new CatalogPipelineDatasetRequest(
                goodRun.getId(), "EGAD0002", "nf-core/sarek@3.4.2", "GRCh38.p14", "s3://genomics/b.vcf.gz", validSha, "CONTROLLED_DAC"));
        when(dsRepo.findByIdAndFacilityId(ds.getId(), facId)).thenReturn(Optional.of(ds));

        PipelineDatasetEntity released = service.releaseDataset(ds.getId(), "dac.chair");
        assertEquals("RELEASED", released.getStatus());
    }
}
