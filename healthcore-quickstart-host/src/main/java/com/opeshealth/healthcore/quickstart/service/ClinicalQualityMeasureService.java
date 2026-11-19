/*
 * Copyright (c) 2026 Opes Health Systems (https://www.opeshealthsystems.com)
 *
 * Author: Jude Nshome — Opes Health Systems
 * Email: nshome@opesware.com
 * Organization: Opes Health Systems, Douala, Cameroon
 *
 * Licensed under the Opes HealthCore Enterprise SDK License.
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Quantity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HL7 FHIR R4 Electronic Clinical Quality Measures (eCQM / CQL) &amp; {@code Measure/{id}/$evaluate-measure}
 * Population Reporting Engine.
 *
 * <p>Evaluates HEDIS, CMS eCQM, and WHO National Program quality indicators (Diabetes HbA1c Control,
 * Controlling High Blood Pressure, HIV Viral Load Suppression, Pediatric Immunization Coverage,
 * and 30-Day Unplanned Readmission Rate) and emits standards-compliant HL7 FHIR R4
 * {@code MeasureReport} JSON resources.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class ClinicalQualityMeasureService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();

    public record QualityMeasureDefinition(
            String measureId,
            String canonicalUrl,
            String title,
            String domain,
            String scoringType,
            String targetMetricCode,
            String thresholdOperator,
            double targetThresholdValue,
            String cqlExpressionSummary,
            boolean active
    ) {}

    public record PatientMeasureEvidence(
            UUID patientId,
            boolean meetsInitialPopulation,
            boolean meetsDenominatorExclusion,
            boolean meetsDenominatorException,
            Double observedNumericValue,
            Boolean booleanNumeratorMet,
            String stratumKey
    ) {}

    public record EvaluateMeasureRequest(
            UUID facilityId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String reportType,
            List<PatientMeasureEvidence> patientCohort
    ) {}

    public record MeasureEvaluationResult(
            UUID reportId,
            String measureId,
            String measureTitle,
            LocalDate periodStart,
            LocalDate periodEnd,
            int initialPopulationCount,
            int denominatorCount,
            int denominatorExclusionCount,
            int denominatorExceptionCount,
            int numeratorCount,
            double performanceScorePercent,
            Map<String, Double> performanceRateByStratum,
            String fhirMeasureReportJson,
            Instant evaluatedAt
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<String, QualityMeasureDefinition> measuresById = new ConcurrentHashMap<>();
    private final Map<UUID, MeasureEvaluationResult> reportsById = new ConcurrentHashMap<>();

    @Autowired
    public ClinicalQualityMeasureService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        seedStandardQualityMeasures();
    }

    public ClinicalQualityMeasureService() {
        this(null);
    }

    public List<QualityMeasureDefinition> listQualityMeasures() {
        return measuresById.values().stream()
                .sorted(Comparator.comparing(QualityMeasureDefinition::measureId))
                .toList();
    }

    @ClinicalAudit(action = "FHIR_MEASURE_REGISTER", resourceType = "Measure")
    public QualityMeasureDefinition registerQualityMeasure(QualityMeasureDefinition definition) {
        if (definition == null || definition.measureId() == null || definition.measureId().isBlank()) {
            throw new IllegalArgumentException("QualityMeasureDefinition with measureId is required");
        }
        measuresById.put(definition.measureId().trim(), definition);
        return definition;
    }

    /**
     * Executes HL7 FHIR R4 {@code Measure/{measureId}/$evaluate-measure} across the supplied patient cohort
     * and returns both structured numerator/denominator/stratification metrics and a FHIR R4 {@code MeasureReport}.
     */
    @ClinicalAudit(action = "FHIR_MEASURE_EVALUATE", resourceType = "MeasureReport")
    public MeasureEvaluationResult evaluateMeasure(String measureId, EvaluateMeasureRequest request) {
        if (measureId == null || measureId.isBlank()) {
            throw new IllegalArgumentException("measureId is required");
        }
        QualityMeasureDefinition measure = measuresById.get(measureId.trim());
        if (measure == null) {
            throw new NoSuchElementException("FHIR Quality Measure not found: " + measureId);
        }
        if (request == null || request.patientCohort() == null) {
            throw new IllegalArgumentException("EvaluateMeasureRequest with patientCohort is required");
        }

        LocalDate periodStart = request.periodStart() != null ? request.periodStart() : LocalDate.now().withDayOfYear(1);
        LocalDate periodEnd = request.periodEnd() != null ? request.periodEnd() : LocalDate.now();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;

        int initialPop = 0;
        int exclusions = 0;
        int exceptions = 0;
        int denominator = 0;
        int numerator = 0;

        Map<String, int[]> stratumCounters = new LinkedHashMap<>();

        for (PatientMeasureEvidence ev : request.patientCohort()) {
            if (ev == null || !ev.meetsInitialPopulation()) {
                continue;
            }
            initialPop++;
            if (ev.meetsDenominatorExclusion()) {
                exclusions++;
                continue;
            }
            boolean numeratorMet = evaluateNumeratorCriteria(measure, ev);
            if (!numeratorMet && ev.meetsDenominatorException()) {
                exceptions++;
                continue;
            }
            denominator++;
            if (numeratorMet) {
                numerator++;
            }
            String stratum = (ev.stratumKey() != null && !ev.stratumKey().isBlank()) ? ev.stratumKey().trim() : "ALL";
            int[] pair = stratumCounters.computeIfAbsent(stratum, k -> new int[2]);
            pair[0]++; // denominator
            if (numeratorMet) {
                pair[1]++; // numerator
            }
        }

        double scorePercent = denominator == 0
                ? 0.0
                : BigDecimal.valueOf((numerator * 100.0) / denominator)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();

        Map<String, Double> stratumRates = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : stratumCounters.entrySet()) {
            int den = e.getValue()[0];
            int num = e.getValue()[1];
            double rate = den == 0 ? 0.0 : BigDecimal.valueOf((num * 100.0) / den).setScale(2, RoundingMode.HALF_UP).doubleValue();
            stratumRates.put(e.getKey(), rate);
        }

        UUID reportId = UUID.randomUUID();
        MeasureReport fhirReport = buildFhirMeasureReport(
                reportId, measure, periodStart, periodEnd, initialPop, denominator, exclusions, exceptions, numerator, scorePercent);
        String fhirJson = FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(fhirReport);

        MeasureEvaluationResult result = new MeasureEvaluationResult(
                reportId,
                measure.measureId(),
                measure.title(),
                periodStart,
                periodEnd,
                initialPop,
                denominator,
                exclusions,
                exceptions,
                numerator,
                scorePercent,
                Map.copyOf(stratumRates),
                fhirJson,
                Instant.now()
        );
        reportsById.put(reportId, result);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    "ECQM_ENGINE",
                    "ROLE_QUALITY_ANALYST",
                    "FHIR_MEASURE_EVALUATED",
                    "MeasureReport",
                    reportId.toString(),
                    null,
                    "POST",
                    "/api/v1/fhir/Measure/" + measure.measureId() + "/$evaluate-measure",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Evaluated " + measure.measureId() + " num=" + numerator + "/den=" + denominator + " (" + scorePercent + "%)"
            ));
        }

        return result;
    }

    public MeasureEvaluationResult getMeasureReport(UUID reportId) {
        MeasureEvaluationResult report = reportsById.get(reportId);
        if (report == null) {
            throw new NoSuchElementException("FHIR MeasureReport not found: " + reportId);
        }
        return report;
    }

    private static boolean evaluateNumeratorCriteria(QualityMeasureDefinition measure, PatientMeasureEvidence ev) {
        if (ev.booleanNumeratorMet() != null) {
            return ev.booleanNumeratorMet();
        }
        if (ev.observedNumericValue() == null) {
            return false;
        }
        double val = ev.observedNumericValue();
        double target = measure.targetThresholdValue();
        return switch (measure.thresholdOperator().toUpperCase(Locale.ROOT)) {
            case "LT" -> val < target;
            case "LTE" -> val <= target;
            case "GT" -> val > target;
            case "GTE" -> val >= target;
            default -> val <= target;
        };
    }

    private static MeasureReport buildFhirMeasureReport(
            UUID reportId,
            QualityMeasureDefinition measure,
            LocalDate start,
            LocalDate end,
            int initialPop,
            int denominator,
            int exclusions,
            int exceptions,
            int numerator,
            double scorePercent) {
        MeasureReport report = new MeasureReport();
        report.setId(reportId.toString());
        report.setStatus(MeasureReport.MeasureReportStatus.COMPLETE);
        report.setType(MeasureReport.MeasureReportType.SUMMARY);
        report.setMeasure(measure.canonicalUrl());
        report.setDate(new Date());
        report.setPeriod(new Period()
                .setStart(Date.from(start.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .setEnd(Date.from(end.atStartOfDay(ZoneOffset.UTC).toInstant())));

        MeasureReport.MeasureReportGroupComponent group = report.addGroup();
        addPopulation(group, "initial-population", initialPop);
        addPopulation(group, "denominator", denominator);
        addPopulation(group, "denominator-exclusion", exclusions);
        addPopulation(group, "denominator-exception", exceptions);
        addPopulation(group, "numerator", numerator);
        group.setMeasureScore(new Quantity().setValue(scorePercent / 100.0).setUnit("ratio"));
        return report;
    }

    private static void addPopulation(MeasureReport.MeasureReportGroupComponent group, String code, int count) {
        group.addPopulation()
                .setCode(new CodeableConcept().addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/measure-population")
                        .setCode(code)))
                .setCount(count);
    }

    private void seedStandardQualityMeasures() {
        registerQualityMeasure(new QualityMeasureDefinition(
                "CMS122-HBA1C-CONTROL",
                "https://opeshealthsystems.com/fhir/Measure/CMS122-HBA1C-CONTROL",
                "Diabetes: Glycemic Control (HbA1c < 7.0%)",
                "CHRONIC_DISEASE",
                "proportion",
                "http://loinc.org|4548-4",
                "LT",
                7.0,
                "define Numerator: Latest(Observation[LOINC 4548-4]).value < 7.0 '%'",
                true
        ));
        registerQualityMeasure(new QualityMeasureDefinition(
                "CMS165-BP-CONTROL",
                "https://opeshealthsystems.com/fhir/Measure/CMS165-BP-CONTROL",
                "Controlling High Blood Pressure (Systolic BP < 140 mmHg)",
                "CARDIOVASCULAR",
                "proportion",
                "http://loinc.org|8480-6",
                "LT",
                140.0,
                "define Numerator: Latest(Observation[LOINC 8480-6]).value < 140 'mm[Hg]'",
                true
        ));
        registerQualityMeasure(new QualityMeasureDefinition(
                "WHO-HIV-VL-SUPPRESSION",
                "https://opeshealthsystems.com/fhir/Measure/WHO-HIV-VL-SUPPRESSION",
                "WHO 95-95-95 HIV Viral Load Suppression (< 1000 copies/mL)",
                "PUBLIC_HEALTH",
                "proportion",
                "http://loinc.org|20447-9",
                "LT",
                1000.0,
                "define Numerator: Latest(Observation[LOINC 20447-9]).value < 1000 'copies/mL'",
                true
        ));
        registerQualityMeasure(new QualityMeasureDefinition(
                "WHO-EPI-PENTA3-COVERAGE",
                "https://opeshealthsystems.com/fhir/Measure/WHO-EPI-PENTA3-COVERAGE",
                "WHO EPI Pediatric Pentavalent-3 Immunization Coverage",
                "IMMUNIZATION",
                "proportion",
                "http://hl7.org/fhir/sid/cvx|198",
                "GTE",
                3.0,
                "define Numerator: Count(Immunization[CVX 198]) >= 3 doses before 12 months",
                true
        ));
        registerQualityMeasure(new QualityMeasureDefinition(
                "HOSP-30D-READMISSION",
                "https://opeshealthsystems.com/fhir/Measure/HOSP-30D-READMISSION",
                "Hospital-Wide 30-Day Unplanned Readmission Rate",
                "INPATIENT_SAFETY",
                "proportion",
                "urn:opes:metric|readmission-30d",
                "LTE",
                0.0,
                "define Numerator: Unplanned inpatient readmission within 30 days of index discharge",
                true
        ));
    }
}
