/*
 * =============================================================================
 * Opes HealthCore SDK — Semantic Chart Search & Clinical Trial Cohort Matching
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient;
import com.opeshealth.healthcore.ai.client.GeminiClinicalApiClient.EmbeddingResult;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService.AiCapabilityType;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway;
import com.opeshealth.healthcore.ai.privacy.PhiRedactionGateway.RedactedClinicalPayload;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capability 9: Semantic Chart Search &amp; Clinical Trial Cohort Matching Service.
 *
 * <p>Generates dense vector embeddings via {@code gemini-embedding-2} strictly over Safe-Harbor
 * redacted clinical notes, lab/radiology reports, and research eligibility protocols, enabling
 * tenant-scoped cosine-similarity semantic retrieval and clinical trial cohort matching.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class SemanticClinicalSearchAiService {

    public enum ClinicalDocumentKind {
        CLINICAL_NOTE,
        LAB_REPORT,
        RADIOLOGY_REPORT,
        DISCHARGE_SUMMARY,
        TRIAL_PROTOCOL
    }

    public record IndexDocumentRequest(
            UUID facilityId,
            UUID patientId,
            String sourceResourceRef,
            ClinicalDocumentKind documentKind,
            String rawClinicalText,
            Map<String, String> tags
    ) {}

    public record IndexedClinicalVectorRecord(
            UUID documentId,
            UUID facilityId,
            UUID patientId,
            String sourceResourceRef,
            ClinicalDocumentKind documentKind,
            String redactedSnippet,
            int vectorDimensions,
            int totalPhiRedactions,
            String modelId,
            Instant indexedAt
    ) {}

    public record SemanticSearchRequest(
            UUID facilityId,
            UUID optionalPatientId,
            ClinicalDocumentKind optionalDocumentKind,
            String naturalLanguageQuery,
            int topK,
            double minCosineScore
    ) {}

    public record SemanticSearchHit(
            UUID documentId,
            UUID patientId,
            String sourceResourceRef,
            ClinicalDocumentKind documentKind,
            double cosineSimilarity,
            String redactedSnippet,
            Map<String, String> tags
    ) {}

    public record SemanticSearchResponse(
            String modelId,
            String redactedQuery,
            int totalCandidatesEvaluated,
            List<SemanticSearchHit> hits,
            long latencyMs
    ) {}

    private record StoredVectorEntry(
            UUID documentId,
            UUID facilityId,
            UUID patientId,
            String sourceResourceRef,
            ClinicalDocumentKind documentKind,
            String redactedSnippet,
            Map<String, String> tags,
            List<Double> embeddingVector,
            Instant indexedAt
    ) {}

    private final GeminiClinicalApiClient geminiClient;
    private final PhiRedactionGateway phiRedactionGateway;
    private final ClinicalAiGovernanceService governanceService;
    private final Map<UUID, StoredVectorEntry> vectorStore = new ConcurrentHashMap<>();

    public SemanticClinicalSearchAiService(
            GeminiClinicalApiClient geminiClient,
            PhiRedactionGateway phiRedactionGateway,
            ClinicalAiGovernanceService governanceService) {
        this.geminiClient = Objects.requireNonNull(geminiClient);
        this.phiRedactionGateway = Objects.requireNonNull(phiRedactionGateway);
        this.governanceService = Objects.requireNonNull(governanceService);
    }

    @ClinicalAudit(action = "CLINICAL_AI_SEMANTIC_INDEX_DOCUMENT", resourceType = "DocumentReference")
    public IndexedClinicalVectorRecord indexClinicalDocument(IndexDocumentRequest request) {
        if (request == null || request.rawClinicalText() == null || request.rawClinicalText().isBlank()) {
            throw new IllegalArgumentException("rawClinicalText is required to index a clinical document.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.SEMANTIC_CLINICAL_SEARCH);

        RedactedClinicalPayload redacted = phiRedactionGateway.redact(request.rawClinicalText());
        EmbeddingResult embedding = geminiClient.embedClinicalText(redacted.redactedText());

        UUID docId = UUID.randomUUID();
        UUID safeFac = request.facilityId() != null ? request.facilityId() : new UUID(0L, 0L);
        ClinicalDocumentKind kind = request.documentKind() != null
                ? request.documentKind() : ClinicalDocumentKind.CLINICAL_NOTE;
        String snippet = redacted.redactedText().length() > 400
                ? redacted.redactedText().substring(0, 400) + "..."
                : redacted.redactedText();

        StoredVectorEntry entry = new StoredVectorEntry(
                docId,
                safeFac,
                request.patientId(),
                Objects.requireNonNullElse(request.sourceResourceRef(), kind.name() + "/" + docId),
                kind,
                snippet,
                request.tags() != null ? Map.copyOf(request.tags()) : Map.of(),
                embedding.values(),
                Instant.now()
        );
        vectorStore.put(docId, entry);

        return new IndexedClinicalVectorRecord(
                docId,
                safeFac,
                request.patientId(),
                entry.sourceResourceRef(),
                kind,
                snippet,
                embedding.values().size(),
                redacted.totalRedactions(),
                embedding.modelId(),
                entry.indexedAt()
        );
    }

    @ClinicalAudit(action = "CLINICAL_AI_SEMANTIC_SEARCH", resourceType = "DocumentReference")
    public SemanticSearchResponse searchClinicallySimilar(SemanticSearchRequest request) {
        if (request == null || request.naturalLanguageQuery() == null || request.naturalLanguageQuery().isBlank()) {
            throw new IllegalArgumentException("naturalLanguageQuery is required.");
        }
        governanceService.assertTenantAiAllowed(request.facilityId(), AiCapabilityType.SEMANTIC_CLINICAL_SEARCH);

        RedactedClinicalPayload redactedQuery = phiRedactionGateway.redact(request.naturalLanguageQuery());
        EmbeddingResult queryEmbedding = geminiClient.embedClinicalText(redactedQuery.redactedText());

        int limit = request.topK() > 0 ? Math.min(request.topK(), 50) : 10;
        double minScore = Math.max(-1.0, Math.min(1.0, request.minCosineScore()));
        UUID safeFac = request.facilityId() != null ? request.facilityId() : new UUID(0L, 0L);

        List<StoredVectorEntry> candidates = vectorStore.values().stream()
                .filter(e -> safeFac.equals(e.facilityId()))
                .filter(e -> request.optionalPatientId() == null || request.optionalPatientId().equals(e.patientId()))
                .filter(e -> request.optionalDocumentKind() == null || request.optionalDocumentKind() == e.documentKind())
                .toList();

        List<SemanticSearchHit> hits = rankByCosineSimilarity(queryEmbedding.values(), candidates, limit, minScore);
        return new SemanticSearchResponse(
                queryEmbedding.modelId(),
                redactedQuery.redactedText(),
                candidates.size(),
                hits,
                queryEmbedding.latencyMs()
        );
    }

    /**
     * Inserts a pre-computed vector entry (useful for batch backfill or deterministic unit testing).
     */
    public UUID registerPrecomputedVector(
            UUID facilityId,
            UUID patientId,
            String sourceResourceRef,
            ClinicalDocumentKind kind,
            String rawText,
            List<Double> vector,
            Map<String, String> tags) {
        RedactedClinicalPayload redacted = phiRedactionGateway.redact(rawText);
        UUID docId = UUID.randomUUID();
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        vectorStore.put(docId, new StoredVectorEntry(
                docId, safeFac, patientId, sourceResourceRef, kind,
                redacted.redactedText(), tags != null ? Map.copyOf(tags) : Map.of(),
                List.copyOf(vector), Instant.now()));
        return docId;
    }

    public List<SemanticSearchHit> searchWithPrecomputedQueryVector(
            UUID facilityId,
            ClinicalDocumentKind optionalKind,
            List<Double> queryVector,
            int topK,
            double minCosineScore) {
        UUID safeFac = facilityId != null ? facilityId : new UUID(0L, 0L);
        List<StoredVectorEntry> candidates = vectorStore.values().stream()
                .filter(e -> safeFac.equals(e.facilityId()))
                .filter(e -> optionalKind == null || optionalKind == e.documentKind())
                .toList();
        return rankByCosineSimilarity(queryVector, candidates, Math.max(1, topK), minCosineScore);
    }

    public static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<SemanticSearchHit> rankByCosineSimilarity(
            List<Double> queryVector,
            List<StoredVectorEntry> candidates,
            int limit,
            double minScore) {
        return candidates.stream()
                .map(entry -> new SemanticSearchHit(
                        entry.documentId(),
                        entry.patientId(),
                        entry.sourceResourceRef(),
                        entry.documentKind(),
                        cosineSimilarity(queryVector, entry.embeddingVector()),
                        entry.redactedSnippet(),
                        entry.tags()
                ))
                .filter(hit -> hit.cosineSimilarity() >= minScore)
                .sorted(Comparator.comparingDouble(SemanticSearchHit::cosineSimilarity).reversed())
                .limit(limit)
                .toList();
    }
}
