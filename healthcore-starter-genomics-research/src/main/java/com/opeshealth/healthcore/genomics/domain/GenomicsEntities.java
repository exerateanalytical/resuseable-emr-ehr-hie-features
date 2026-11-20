/*
 * =============================================================================
 * Opes HealthCore SDK — Genomics & Bioinformatics Metadata Entities
 * =============================================================================
 */
package com.opeshealth.healthcore.genomics.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class GenomicsEntities {

    private GenomicsEntities() {}

    @Entity
    @Table(name = "hc_gen_sequencing_runs")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class SequencingRunEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "run_code", nullable = false, length = 64)
        private String runCode;

        @Column(name = "sample_barcode", nullable = false, length = 64)
        private String sampleBarcode;

        @Column(name = "library_prep_kit", nullable = false, length = 120)
        private String libraryPrepKit;

        @Column(name = "sequencer_platform", nullable = false, length = 80)
        private String sequencerPlatform;

        @Column(name = "q30_pct", nullable = false, precision = 5, scale = 2)
        private BigDecimal q30Pct;

        @Column(name = "qc_passed", nullable = false)
        private boolean qcPassed = false;

        @Column(name = "status", nullable = false, length = 24)
        private String status = "COMPLETED"; // COMPLETED, QC_FAILED

        @Column(name = "executed_at", nullable = false)
        private Instant executedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getRunCode() { return runCode; }
        public void setRunCode(String runCode) { this.runCode = runCode; }
        public String getSampleBarcode() { return sampleBarcode; }
        public void setSampleBarcode(String sampleBarcode) { this.sampleBarcode = sampleBarcode; }
        public String getLibraryPrepKit() { return libraryPrepKit; }
        public void setLibraryPrepKit(String libraryPrepKit) { this.libraryPrepKit = libraryPrepKit; }
        public String getSequencerPlatform() { return sequencerPlatform; }
        public void setSequencerPlatform(String sequencerPlatform) { this.sequencerPlatform = sequencerPlatform; }
        public BigDecimal getQ30Pct() { return q30Pct; }
        public void setQ30Pct(BigDecimal q30Pct) { this.q30Pct = q30Pct; }
        public boolean isQcPassed() { return qcPassed; }
        public void setQcPassed(boolean qcPassed) { this.qcPassed = qcPassed; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getExecutedAt() { return executedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_gen_pipeline_datasets")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PipelineDatasetEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "sequencing_run_id", nullable = false)
        private UUID sequencingRunId;

        @Column(name = "dataset_accession", nullable = false, length = 64)
        private String datasetAccession;

        @Column(name = "pipeline_name_version", nullable = false, length = 120)
        private String pipelineNameVersion; // e.g., nf-core/sarek@3.4.2

        @Column(name = "reference_genome", nullable = false, length = 64)
        private String referenceGenome; // e.g., GRCh38.p14

        @Column(name = "vcf_uri", nullable = false, length = 500)
        private String vcfUri;

        @Column(name = "vcf_sha256", nullable = false, length = 64)
        private String vcfSha256;

        @Column(name = "access_tier", nullable = false, length = 32)
        private String accessTier = "CONTROLLED_DAC"; // CONTROLLED_DAC, CONSORTIUM, REGISTERED

        @Column(name = "status", nullable = false, length = 24)
        private String status = "CATALOGED"; // CATALOGED, RELEASED, REVOKED

        @Column(name = "approved_by", length = 120)
        private String approvedBy;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getSequencingRunId() { return sequencingRunId; }
        public void setSequencingRunId(UUID sequencingRunId) { this.sequencingRunId = sequencingRunId; }
        public String getDatasetAccession() { return datasetAccession; }
        public void setDatasetAccession(String datasetAccession) { this.datasetAccession = datasetAccession; }
        public String getPipelineNameVersion() { return pipelineNameVersion; }
        public void setPipelineNameVersion(String pipelineNameVersion) { this.pipelineNameVersion = pipelineNameVersion; }
        public String getReferenceGenome() { return referenceGenome; }
        public void setReferenceGenome(String referenceGenome) { this.referenceGenome = referenceGenome; }
        public String getVcfUri() { return vcfUri; }
        public void setVcfUri(String vcfUri) { this.vcfUri = vcfUri; }
        public String getVcfSha256() { return vcfSha256; }
        public void setVcfSha256(String vcfSha256) { this.vcfSha256 = vcfSha256; }
        public String getAccessTier() { return accessTier; }
        public void setAccessTier(String accessTier) { this.accessTier = accessTier; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
