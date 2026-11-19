/*
 * =============================================================================
 * Opes HealthCore SDK — Longitudinal Chart & IPS Summarizer AI Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * Copyright (c) 2026 Opes Health Systems. Apache-2.0 License.
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.ClinicalSummarizationRequest;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.ClinicalSummaryResult;
import com.opeshealth.healthcore.ai.summarizer.ClinicalSummarizer.ClinicalSummaryStyle;
import com.opeshealth.healthcore.quickstart.service.FhirChartPortabilityService.ChartEverythingExportResponse;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Capability 3: Longitudinal Chart &amp; International Patient Summary (IPS) Summarizer.
 *
 * <p>Delegates FHIR R4 resource processing, mandatory HIPAA Safe-Harbor PHI redaction,
 * Gemini API summarization, and clinician attestation registration to the reusable
 * {@link ClinicalSummarizer} component from {@code healthcore-starter-clinical-ai}, while
 * integrating DS4P-filtered {@code Patient/{id}/$everything} chart retrieval from
 * {@link FhirChartPortabilityService}.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ChartSummarizationAiService {

    public enum SummaryMode {
        CLINICAL_CONCISE,
        SBAR_HANDOFF,
        ED_BRIEF,
        PATIENT_DISCHARGE,
        IPS_EXECUTIVE_BRIEF
    }

    public record ChartSummaryRequest(
            UUID facilityId,
            UUID patientId,
            String clinicianId,
            SummaryMode mode,
            String targetLanguage,
            String breakTheGlassToken,
            String supplementalFhirOrIpsJson
    ) {}

    public record ChartSummaryDraftResponse(
            UUID draftId,
            String attestationStatus,
            SummaryMode mode,
            String modelId,
            int ds4pRedactedSensitiveResources,
            int phiIdentifiersRedacted,
            JsonNode structuredSummary,
            long latencyMs
    ) {}

    private final ClinicalSummarizer clinicalSummarizer;
    private final FhirChartPortabilityService chartPortabilityService;

    @Autowired
    public ChartSummarizationAiService(
            ClinicalSummarizer clinicalSummarizer,
            @Autowired(required = false) FhirChartPortabilityService chartPortabilityService) {
        this.clinicalSummarizer = Objects.requireNonNull(clinicalSummarizer, "ClinicalSummarizer is required");
        this.chartPortabilityService = chartPortabilityService;
    }

    @ClinicalAudit(action = "CLINICAL_AI_CHART_SUMMARY", resourceType = "Patient")
    public ChartSummaryDraftResponse summarizePatientChart(ChartSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ChartSummaryRequest is required.");
        }

        SummaryMode mode = request.mode() != null ? request.mode() : SummaryMode.SBAR_HANDOFF;
        List<String> fhirPayloads = new ArrayList<>();
        int ds4pHiddenCount = 0;

        if (request.patientId() != null && chartPortabilityService != null) {
            ChartEverythingExportResponse exported = chartPortabilityService.exportPatientEverything(
                    request.patientId(),
                    request.clinicianId() != null ? request.clinicianId() : "CLINICIAN",
                    request.breakTheGlassToken(),
                    null,
                    null
            );
            ds4pHiddenCount = exported.redactedSensitiveResourcesCount();
            if (exported.fhirBundleJson() != null && !exported.fhirBundleJson().isBlank()) {
                fhirPayloads.add(exported.fhirBundleJson());
            }
        }

        if (request.supplementalFhirOrIpsJson() != null && !request.supplementalFhirOrIpsJson().isBlank()) {
            fhirPayloads.add(request.supplementalFhirOrIpsJson());
        }

        if (fhirPayloads.isEmpty()) {
            throw new IllegalArgumentException("Either an existing patientId or supplementalFhirOrIpsJson is required.");
        }

        ClinicalSummaryResult result = clinicalSummarizer.summarizeFhirResources(
                new ClinicalSummarizationRequest(
                        request.facilityId(),
                        request.patientId(),
                        request.clinicianId(),
                        mapStyle(mode),
                        request.targetLanguage(),
                        fhirPayloads,
                        null
                )
        );

        return new ChartSummaryDraftResponse(
                result.draftId(),
                result.attestationStatus(),
                mode,
                result.modelId(),
                ds4pHiddenCount,
                result.totalPhiRedactions(),
                result.structuredSummary(),
                result.latencyMs()
        );
    }

    private static ClinicalSummaryStyle mapStyle(SummaryMode mode) {
        return switch (mode) {
            case CLINICAL_CONCISE -> ClinicalSummaryStyle.CLINICAL_CONCISE;
            case SBAR_HANDOFF -> ClinicalSummaryStyle.SBAR_HANDOFF;
            case ED_BRIEF -> ClinicalSummaryStyle.ED_RAPID_TRIAGE;
            case PATIENT_DISCHARGE -> ClinicalSummaryStyle.PATIENT_DISCHARGE;
            case IPS_EXECUTIVE_BRIEF -> ClinicalSummaryStyle.IPS_EXECUTIVE_BRIEF;
        };
    }
}
