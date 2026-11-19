/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.CandidateResearchPatient;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.CohortScreeningResponse;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.OmopCdmExportBundle;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.ResearchEConsentRecord;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.ResearchEConsentRequest;
import com.opeshealth.healthcore.quickstart.service.ClinicalResearchOmopService.ResearchStudyProtocol;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * REST Controller for Clinical Research Protocols, Dynamic Research eConsent,
 * Trial Eligibility Cohort Screening, and HIPAA Safe-Harbor OMOP CDM v5.4 Export.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@RestController
@RequestMapping("/api/v1/research")
@Tag(name = "Clinical Research, Dynamic eConsent & OMOP CDM v5.4 Export",
        description = "Study protocol management, dynamic eConsent opt-in/withdrawal, cohort eligibility screening, and Safe-Harbor OMOP CDM v5.4 export")
public class ClinicalResearchOmopController {

    private final ClinicalResearchOmopService researchService;

    public ClinicalResearchOmopController(ClinicalResearchOmopService researchService) {
        this.researchService = Objects.requireNonNull(researchService, "ClinicalResearchOmopService is required");
    }

    @GetMapping(value = "/studies", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List registered clinical research study protocols")
    public ResponseEntity<List<ResearchStudyProtocol>> listStudies() {
        return ResponseEntity.ok(researchService.listStudyProtocols());
    }

    @PostMapping(value = "/studies", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Register a new clinical research protocol")
    public ResponseEntity<ResearchStudyProtocol> registerStudy(
            @RequestBody ResearchStudyProtocol protocol) {
        return ResponseEntity.status(201).body(researchService.registerStudyProtocol(protocol));
    }

    @PostMapping(value = "/econsent", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Record a participant's dynamic electronic research consent (eConsent)")
    public ResponseEntity<ResearchEConsentRecord> signEConsent(
            @RequestBody ResearchEConsentRequest request) {
        return ResponseEntity.status(201).body(researchService.signResearchEConsent(request));
    }

    @PostMapping(value = "/econsent/withdraw", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Withdraw a participant's research eConsent")
    public ResponseEntity<ResearchEConsentRecord> withdrawEConsent(
            @RequestParam("studyId") String studyId,
            @RequestParam("patientId") UUID patientId) {
        return ResponseEntity.ok(researchService.withdrawResearchEConsent(studyId, patientId));
    }

    @PostMapping(value = "/studies/{studyId}/screen-cohort", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Screen candidate patients against protocol inclusion/exclusion criteria and active eConsent")
    public ResponseEntity<CohortScreeningResponse> screenCohort(
            @PathVariable("studyId") String studyId,
            @RequestBody List<CandidateResearchPatient> candidates) {
        return ResponseEntity.ok(researchService.screenTrialEligibility(studyId, candidates));
    }

    @PostMapping(value = "/studies/{studyId}/omop-export", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Export HIPAA Safe-Harbor de-identified OMOP CDM v5.4 dataset for consented participants")
    public ResponseEntity<OmopCdmExportBundle> exportOmopCdm(
            @PathVariable("studyId") String studyId,
            @RequestBody List<CandidateResearchPatient> candidates) {
        return ResponseEntity.ok(researchService.exportDeidentifiedOmopCdmV54(studyId, candidates));
    }
}
