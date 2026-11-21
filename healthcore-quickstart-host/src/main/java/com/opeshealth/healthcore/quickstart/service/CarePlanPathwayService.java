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

import com.opeshealth.healthcore.quickstart.service.FhirAuditLoggingService.FhirAccessAuditCommand;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Longitudinal HL7 FHIR R4 {@code CarePlan}, {@code Goal}, {@code Task}, and Automated
 * Care-Gap Closure Engine for Chronic Disease &amp; Maternal Pathways.
 *
 * <p>Supports evidence-based protocols ({@code DIABETES_T2}, {@code HYPERTENSION},
 * {@code HIV_ART}, {@code TB_DOT}, {@code MATERNAL_ANC}, {@code HEART_FAILURE}) with
 * target clinical goals, scheduled milestone tasks, and automated overdue care-gap detection.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class CarePlanPathwayService {

    public enum ClinicalPathwayCode {
        DIABETES_T2,
        HYPERTENSION,
        HIV_ART,
        TB_DOT,
        MATERNAL_ANC,
        HEART_FAILURE
    }

    public record EnrollCarePlanRequest(
            UUID facilityId,
            UUID patientId,
            ClinicalPathwayCode pathwayCode,
            String leadClinicianId,
            LocalDate startDate
    ) {}

    public record CarePlanGoal(
            UUID goalId,
            String description,
            String loincCode,
            String targetMeasureText,
            String achievementStatus,
            LocalDate targetDate
    ) {}

    public record CarePlanTask(
            UUID taskId,
            String title,
            String code,
            String status,
            LocalDate dueDate,
            Instant completedAt,
            String completedBy
    ) {}

    public record LongitudinalCarePlan(
            UUID carePlanId,
            UUID facilityId,
            UUID patientId,
            ClinicalPathwayCode pathwayCode,
            String title,
            String status,
            String leadClinicianId,
            LocalDate startDate,
            List<CarePlanGoal> goals,
            List<CarePlanTask> tasks,
            String fhirCarePlanJson,
            Instant createdAt
    ) {}

    public record DetectedCareGap(
            UUID carePlanId,
            UUID taskId,
            ClinicalPathwayCode pathwayCode,
            String gapTitle,
            LocalDate dueDate,
            long daysOverdue,
            String severity,
            String recommendedAction
    ) {}

    public record CareGapEvaluationSummary(
            UUID patientId,
            int activeCarePlansCount,
            int openTasksCount,
            int overdueCareGapsCount,
            List<DetectedCareGap> overdueGaps,
            Instant evaluatedAt
    ) {}

    private static final class CarePlanState {
        final UUID carePlanId;
        final UUID facilityId;
        final UUID patientId;
        final ClinicalPathwayCode pathwayCode;
        final String title;
        volatile String status;
        final String leadClinicianId;
        final LocalDate startDate;
        final List<CarePlanGoal> goals = new CopyOnWriteArrayList<>();
        final List<CarePlanTask> tasks = new CopyOnWriteArrayList<>();
        final Instant createdAt;

        CarePlanState(UUID carePlanId, UUID facilityId, UUID patientId, ClinicalPathwayCode pathwayCode,
                      String title, String status, String leadClinicianId, LocalDate startDate, Instant createdAt) {
            this.carePlanId = carePlanId;
            this.facilityId = facilityId;
            this.patientId = patientId;
            this.pathwayCode = pathwayCode;
            this.title = title;
            this.status = status;
            this.leadClinicianId = leadClinicianId;
            this.startDate = startDate;
            this.createdAt = createdAt;
        }
    }

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final Map<UUID, CarePlanState> carePlansById = new ConcurrentHashMap<>();

    @Autowired
    public CarePlanPathwayService(@Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public CarePlanPathwayService() {
        this(null);
    }

    /**
     * Enrolls a patient into an evidence-based longitudinal CarePlan with standard FHIR Goals and Tasks.
     */
    @ClinicalAudit(action = "FHIR_CAREPLAN_ENROLL", resourceType = "CarePlan")
    public LongitudinalCarePlan enrollPatientInPathway(EnrollCarePlanRequest request) {
        if (request == null || request.patientId() == null || request.pathwayCode() == null) {
            throw new IllegalArgumentException("EnrollCarePlanRequest with patientId and pathwayCode is required");
        }
        UUID id = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        LocalDate start = request.startDate() != null ? request.startDate() : LocalDate.now();
        String clinician = request.leadClinicianId() != null && !request.leadClinicianId().isBlank()
                ? request.leadClinicianId().trim()
                : "dr.care.coordinator";

        CarePlanState state = new CarePlanState(
                id, facilityId, request.patientId(), request.pathwayCode(),
                buildPathwayTitle(request.pathwayCode()), "active", clinician, start, Instant.now());
        populateDefaultGoalsAndTasks(state);
        carePlansById.put(id, state);

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                    facilityId.toString(),
                    clinician,
                    "ROLE_PHYSICIAN",
                    "FHIR_CAREPLAN_CREATED",
                    "CarePlan",
                    id.toString(),
                    request.patientId(),
                    "POST",
                    "/api/v1/care-plans/enroll",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Enrolled patient in " + request.pathwayCode() + " longitudinal pathway"
            ));
        }
        return toSnapshot(state);
    }

    /**
     * Marks a CarePlan milestone task completed and checks if associated clinical goals are achieved.
     */
    @ClinicalAudit(action = "FHIR_CAREPLAN_TASK_COMPLETE", resourceType = "Task")
    public LongitudinalCarePlan completeCarePlanTask(UUID carePlanId, UUID taskId, String clinicianId) {
        CarePlanState state = carePlansById.get(carePlanId);
        if (state == null) {
            throw new NoSuchElementException("CarePlan not found: " + carePlanId);
        }
        String actor = clinicianId != null && !clinicianId.isBlank() ? clinicianId.trim() : "clinician";
        boolean matched = false;
        for (int i = 0; i < state.tasks.size(); i++) {
            CarePlanTask t = state.tasks.get(i);
            if (taskId.equals(t.taskId())) {
                state.tasks.set(i, new CarePlanTask(
                        t.taskId(), t.title(), t.code(), "completed", t.dueDate(), Instant.now(), actor));
                matched = true;
                break;
            }
        }
        if (!matched) {
            throw new NoSuchElementException("CarePlan Task not found: " + taskId);
        }
        boolean allDone = state.tasks.stream().allMatch(t -> "completed".equalsIgnoreCase(t.status()));
        if (allDone) {
            for (int i = 0; i < state.goals.size(); i++) {
                CarePlanGoal g = state.goals.get(i);
                state.goals.set(i, new CarePlanGoal(
                        g.goalId(), g.description(), g.loincCode(), g.targetMeasureText(), "achieved", g.targetDate()));
            }
        }
        return toSnapshot(state);
    }

    /**
     * Evaluates all active CarePlans for a patient as of {@code referenceDate} to identify overdue care gaps.
     */
    public CareGapEvaluationSummary evaluatePatientCareGaps(UUID patientId, LocalDate referenceDate) {
        if (patientId == null) {
            throw new IllegalArgumentException("patientId is required");
        }
        LocalDate asOf = referenceDate != null ? referenceDate : LocalDate.now();
        List<CarePlanState> activePlans = carePlansById.values().stream()
                .filter(p -> patientId.equals(p.patientId) && "active".equalsIgnoreCase(p.status))
                .toList();

        int openTasks = 0;
        List<DetectedCareGap> overdueGaps = new ArrayList<>();
        for (CarePlanState plan : activePlans) {
            for (CarePlanTask task : plan.tasks) {
                if (!"completed".equalsIgnoreCase(task.status())) {
                    openTasks++;
                    if (task.dueDate() != null && asOf.isAfter(task.dueDate())) {
                        long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(task.dueDate(), asOf);
                        String severity = daysOverdue >= 30 ? "HIGH" : "MEDIUM";
                        overdueGaps.add(new DetectedCareGap(
                                plan.carePlanId,
                                task.taskId(),
                                plan.pathwayCode,
                                task.title(),
                                task.dueDate(),
                                daysOverdue,
                                severity,
                                "Schedule " + task.title() + " and send patient recall notification"
                        ));
                    }
                }
            }
        }
        return new CareGapEvaluationSummary(
                patientId,
                activePlans.size(),
                openTasks,
                overdueGaps.size(),
                List.copyOf(overdueGaps),
                Instant.now()
        );
    }

    public List<LongitudinalCarePlan> listPatientCarePlans(UUID patientId) {
        return carePlansById.values().stream()
                .filter(p -> patientId == null || Objects.equals(patientId, p.patientId))
                .sorted(Comparator.comparing((CarePlanState p) -> p.createdAt).reversed())
                .map(this::toSnapshot)
                .toList();
    }

    private static void populateDefaultGoalsAndTasks(CarePlanState state) {
        LocalDate s = state.startDate;
        switch (state.pathwayCode) {
            case DIABETES_T2 -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Maintain Glycemic Control HbA1c < 7.0%",
                        "4548-4", "HbA1c < 7.0%", "in-progress", s.plusDays(90)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "Quarterly HbA1c Laboratory Test",
                        "LOINC-4548-4", "requested", s.plusDays(90), null, null));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "Annual Retinal Eye Exam & Foot Screening",
                        "SNOMED-252779009", "requested", s.plusDays(180), null, null));
            }
            case HYPERTENSION -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Achieve Blood Pressure < 130/80 mmHg",
                        "85354-9", "BP < 130/80 mmHg", "in-progress", s.plusDays(30)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "30-Day Home BP Log Review & Titration",
                        "LOINC-85354-9", "requested", s.plusDays(30), null, null));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "Renal Function (eGFR / Serum Potassium) Panel",
                        "LOINC-33914-3", "requested", s.plusDays(90), null, null));
            }
            case HIV_ART -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Sustained HIV Viral Load Suppression < 1000 copies/mL",
                        "20447-9", "VL < 1000 copies/mL", "in-progress", s.plusDays(180)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "6-Month HIV Viral Load Assay",
                        "LOINC-20447-9", "requested", s.plusDays(180), null, null));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "90-Day ART Multi-Month Dispensing Refill",
                        "ATC-J05AR", "requested", s.plusDays(90), null, null));
            }
            case TB_DOT -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Sputum Smear Conversion at Month 2",
                        "11477-7", "AFB Negative", "in-progress", s.plusDays(60)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "Month-2 Sputum AFB Follow-Up Test",
                        "LOINC-11477-7", "requested", s.plusDays(60), null, null));
            }
            case MATERNAL_ANC -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Complete WHO 8-Contact Antenatal Care Pathway",
                        "57059-8", "8 ANC Contacts", "in-progress", s.plusDays(180)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "ANC Visit 2 — BP, Hemoglobin & Tetanus Toxoid",
                        "WHO-ANC-2", "requested", s.plusDays(28), null, null));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "Third-Trimester Obstetric Ultrasound",
                        "LOINC-11525-3", "requested", s.plusDays(112), null, null));
            }
            case HEART_FAILURE -> {
                state.goals.add(new CarePlanGoal(UUID.randomUUID(), "Zero 30-Day Unplanned Readmissions & Stable Weight",
                        "29463-7", "Weight delta < 2 kg/wk", "in-progress", s.plusDays(30)));
                state.tasks.add(new CarePlanTask(UUID.randomUUID(), "7-Day Post-Discharge Cardiology Telehealth Check",
                        "SNOMED-448337001", "requested", s.plusDays(7), null, null));
            }
        }
    }

    private LongitudinalCarePlan toSnapshot(CarePlanState state) {
        String fhirJson = """
                {
                  "resourceType": "CarePlan",
                  "id": "%s",
                  "status": "%s",
                  "intent": "plan",
                  "title": "%s",
                  "subject": {"reference": "Patient/%s"},
                  "period": {"start": "%s"},
                  "goalCount": %d,
                  "activityCount": %d
                }
                """.formatted(
                state.carePlanId,
                state.status.toLowerCase(Locale.ROOT),
                state.title,
                state.patientId,
                state.startDate,
                state.goals.size(),
                state.tasks.size()
        ).trim();

        return new LongitudinalCarePlan(
                state.carePlanId,
                state.facilityId,
                state.patientId,
                state.pathwayCode,
                state.title,
                state.status,
                state.leadClinicianId,
                state.startDate,
                List.copyOf(state.goals),
                List.copyOf(state.tasks),
                fhirJson,
                state.createdAt
        );
    }

    private static String buildPathwayTitle(ClinicalPathwayCode code) {
        return switch (code) {
            case DIABETES_T2 -> "Type 2 Diabetes Mellitus Longitudinal Care Pathway";
            case HYPERTENSION -> "Hypertension & Cardiovascular Risk Management Pathway";
            case HIV_ART -> "WHO HIV Antiretroviral Therapy (ART) Retention Pathway";
            case TB_DOT -> "Tuberculosis Directly Observed Therapy (DOTS) Pathway";
            case MATERNAL_ANC -> "WHO 8-Contact Maternal Antenatal Care (ANC) Pathway";
            case HEART_FAILURE -> "Heart Failure Post-Discharge Transition Care Pathway";
        };
    }
}
