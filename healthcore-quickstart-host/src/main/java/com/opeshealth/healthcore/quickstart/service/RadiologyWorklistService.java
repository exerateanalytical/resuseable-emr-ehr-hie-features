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
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Radiology &amp; PACS Modality Worklist (**DICOM MWL**), **Modality Performed Procedure Step (MPPS)**,
 * Structured FHIR {@code DiagnosticReport} Sign-Off, and **Closed-Loop Critical Findings Escalation** Service.
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class RadiologyWorklistService {

    public enum ImagingModality {
        CT, MRI, XR, US, MAMMO, FLUORO
    }

    public enum MppsStatus {
        SCHEDULED,
        IN_PROGRESS,
        COMPLETED,
        DISCONTINUED
    }

    public record ScheduleImagingOrderRequest(
            UUID facilityId,
            UUID patientId,
            String orderingClinicianId,
            ImagingModality modality,
            String procedureCode,
            String procedureDescription,
            String bodySite,
            String clinicalIndication,
            String scheduledStationAeTitle,
            boolean statUrgent
    ) {}

    public record ModalityWorklistItem(
            UUID orderId,
            UUID facilityId,
            UUID patientId,
            String accessionNumber,
            String studyInstanceUid,
            String orderingClinicianId,
            ImagingModality modality,
            String procedureCode,
            String procedureDescription,
            String bodySite,
            String scheduledStationAeTitle,
            boolean statUrgent,
            MppsStatus mppsStatus,
            int dicomSeriesCount,
            int dicomInstancesCount,
            Instant scheduledAt,
            Instant completedAt
    ) {}

    public record MppsUpdateRequest(
            MppsStatus mppsStatus,
            String performingTechnologistId,
            int dicomSeriesCount,
            int dicomInstancesCount
    ) {}

    public record RadiologyReportSignOffRequest(
            UUID orderId,
            String radiologistId,
            String findingsText,
            String impressionText,
            boolean criticalFindingDetected,
            String criticalFindingCategory,
            boolean incidentalFindingFollowUpRequired
    ) {}

    public record RadiologyDiagnosticReport(
            UUID reportId,
            UUID orderId,
            UUID facilityId,
            UUID patientId,
            String accessionNumber,
            String studyInstanceUid,
            String radiologistId,
            String findingsText,
            String impressionText,
            boolean criticalFindingDetected,
            String criticalFindingCategory,
            boolean incidentalFindingFollowUpRequired,
            String acknowledgmentStatus,
            String acknowledgedByClinicianId,
            Instant acknowledgedAt,
            String fhirDiagnosticReportJson,
            Instant signedAt
    ) {}

    private final FhirAuditLoggingService fhirAuditLoggingService;
    private final FhirSubscriptionTopicService subscriptionTopicService;

    private final Map<UUID, ModalityWorklistItem> worklistByOrderId = new ConcurrentHashMap<>();
    private final Map<UUID, RadiologyDiagnosticReport> reportsById = new ConcurrentHashMap<>();

    @Autowired
    public RadiologyWorklistService(
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService) {
        this.fhirAuditLoggingService = fhirAuditLoggingService;
        this.subscriptionTopicService = subscriptionTopicService;
    }

    public RadiologyWorklistService() {
        this(null, null);
    }

    /**
     * Schedules an imaging {@code ServiceRequest} into the DICOM Modality Worklist (MWL)
     * with an assigned Accession Number and Study Instance UID.
     */
    @ClinicalAudit(action = "RADIOLOGY_MWL_SCHEDULE", resourceType = "ServiceRequest")
    public ModalityWorklistItem scheduleModalityWorklistOrder(ScheduleImagingOrderRequest request) {
        if (request == null || request.patientId() == null || request.modality() == null) {
            throw new IllegalArgumentException("ScheduleImagingOrderRequest with patientId and modality is required");
        }
        UUID orderId = UUID.randomUUID();
        UUID facilityId = request.facilityId() != null ? request.facilityId() : PatientService.DEFAULT_FACILITY_ID;
        String accession = "ACC-" + request.modality().name() + "-"
                + orderId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String studyUid = "1.2.826.0.1.3680043.8.498." + Math.abs(orderId.getMostSignificantBits());

        ModalityWorklistItem item = new ModalityWorklistItem(
                orderId,
                facilityId,
                request.patientId(),
                accession,
                studyUid,
                request.orderingClinicianId() != null ? request.orderingClinicianId().trim() : "dr.ordering",
                request.modality(),
                request.procedureCode() != null ? request.procedureCode().trim() : "71046",
                request.procedureDescription() != null ? request.procedureDescription().trim() : request.modality() + " Exam",
                request.bodySite() != null ? request.bodySite().trim() : "CHEST",
                request.scheduledStationAeTitle() != null ? request.scheduledStationAeTitle().trim() : "OPES_" + request.modality() + "_01",
                request.statUrgent(),
                MppsStatus.SCHEDULED,
                0,
                0,
                Instant.now(),
                null
        );
        worklistByOrderId.put(orderId, item);
        return item;
    }

    /**
     * Queries the DICOM Modality Worklist filtered by modality or station AE Title.
     */
    public List<ModalityWorklistItem> queryModalityWorklist(ImagingModality modality, MppsStatus status) {
        return worklistByOrderId.values().stream()
                .filter(i -> modality == null || i.modality() == modality)
                .filter(i -> status == null || i.mppsStatus() == status)
                .sorted(Comparator.comparing(ModalityWorklistItem::statUrgent).reversed()
                        .thenComparing(ModalityWorklistItem::scheduledAt))
                .toList();
    }

    /**
     * Updates DICOM Modality Performed Procedure Step (MPPS) acquisition state.
     */
    @ClinicalAudit(action = "RADIOLOGY_MPPS_UPDATE", resourceType = "ImagingStudy")
    public ModalityWorklistItem updateMppsStatus(UUID orderId, MppsUpdateRequest request) {
        ModalityWorklistItem existing = worklistByOrderId.get(orderId);
        if (existing == null) {
            throw new NoSuchElementException("Radiology MWL order not found: " + orderId);
        }
        MppsStatus nextStatus = request != null && request.mppsStatus() != null ? request.mppsStatus() : MppsStatus.COMPLETED;
        ModalityWorklistItem updated = new ModalityWorklistItem(
                existing.orderId(),
                existing.facilityId(),
                existing.patientId(),
                existing.accessionNumber(),
                existing.studyInstanceUid(),
                existing.orderingClinicianId(),
                existing.modality(),
                existing.procedureCode(),
                existing.procedureDescription(),
                existing.bodySite(),
                existing.scheduledStationAeTitle(),
                existing.statUrgent(),
                nextStatus,
                request != null ? Math.max(0, request.dicomSeriesCount()) : existing.dicomSeriesCount(),
                request != null ? Math.max(0, request.dicomInstancesCount()) : existing.dicomInstancesCount(),
                existing.scheduledAt(),
                nextStatus == MppsStatus.COMPLETED ? Instant.now() : existing.completedAt()
        );
        worklistByOrderId.put(orderId, updated);
        return updated;
    }

    /**
     * Signs off a structured FHIR {@code DiagnosticReport} for a completed imaging study and
     * automatically triggers closed-loop critical finding escalation when flagged.
     */
    @ClinicalAudit(action = "RADIOLOGY_REPORT_SIGN", resourceType = "DiagnosticReport")
    public RadiologyDiagnosticReport signRadiologyReport(RadiologyReportSignOffRequest request) {
        if (request == null || request.orderId() == null) {
            throw new IllegalArgumentException("RadiologyReportSignOffRequest with orderId is required");
        }
        ModalityWorklistItem order = worklistByOrderId.get(request.orderId());
        if (order == null) {
            throw new NoSuchElementException("Radiology MWL order not found: " + request.orderId());
        }
        UUID reportId = UUID.randomUUID();
        String radiologist = request.radiologistId() != null && !request.radiologistId().isBlank()
                ? request.radiologistId().trim()
                : "dr.radiologist";
        String ackStatus = request.criticalFindingDetected() ? "PENDING_CRITICAL_ACKNOWLEDGMENT" : "NOT_REQUIRED";
        Instant now = Instant.now();

        String fhirJson = """
                {
                  "resourceType": "DiagnosticReport",
                  "id": "%s",
                  "status": "final",
                  "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0074", "code": "RAD", "display": "Radiology"}]}],
                  "code": {"coding": [{"system": "http://loinc.org", "code": "%s", "display": "%s"}]},
                  "subject": {"reference": "Patient/%s"},
                  "issued": "%s",
                  "conclusion": "%s"
                }
                """.formatted(
                reportId,
                order.procedureCode(),
                order.procedureDescription(),
                order.patientId(),
                now,
                (request.impressionText() != null ? request.impressionText() : "").replace("\"", "'")
        ).trim();

        RadiologyDiagnosticReport report = new RadiologyDiagnosticReport(
                reportId,
                order.orderId(),
                order.facilityId(),
                order.patientId(),
                order.accessionNumber(),
                order.studyInstanceUid(),
                radiologist,
                request.findingsText() != null ? request.findingsText().trim() : "",
                request.impressionText() != null ? request.impressionText().trim() : "",
                request.criticalFindingDetected(),
                request.criticalFindingCategory(),
                request.incidentalFindingFollowUpRequired(),
                ackStatus,
                null,
                null,
                fhirJson,
                now
        );
        reportsById.put(reportId, report);

        if (request.criticalFindingDetected()) {
            if (fhirAuditLoggingService != null) {
                fhirAuditLoggingService.recordFhirAccess(new FhirAccessAuditCommand(
                        order.facilityId().toString(),
                        radiologist,
                        "ROLE_RADIOLOGIST",
                        "RADIOLOGY_CRITICAL_FINDING_ESCALATED",
                        "DiagnosticReport",
                        reportId.toString(),
                        order.patientId(),
                        "POST",
                        "/api/v1/radiology/reports",
                        "127.0.0.1",
                        null,
                        "SUCCESS",
                        0L,
                        "CRITICAL RADIOLOGY FINDING (" + request.criticalFindingCategory()
                                + ") escalated to ordering clinician " + order.orderingClinicianId()
                ));
            }
            if (subscriptionTopicService != null) {
                subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                        order.facilityId(),
                        FhirSubscriptionTopicService.TOPIC_CRITICAL_LAB_RESULT,
                        "DiagnosticReport",
                        reportId.toString(),
                        order.patientId(),
                        "CRITICAL",
                        "Critical Radiology Finding (" + request.criticalFindingCategory() + ") on "
                                + order.accessionNumber() + ": " + report.impressionText(),
                        Map.of(
                                "reportId", reportId.toString(),
                                "accessionNumber", order.accessionNumber(),
                                "orderingClinicianId", order.orderingClinicianId()
                        )
                ));
            }
        }
        return report;
    }

    /**
     * Closes the critical radiology finding safety loop when the ordering clinician acknowledges the alert.
     */
    @ClinicalAudit(action = "RADIOLOGY_CRITICAL_FINDING_ACK", resourceType = "DiagnosticReport")
    public RadiologyDiagnosticReport acknowledgeCriticalFinding(UUID reportId, String clinicianId) {
        RadiologyDiagnosticReport existing = reportsById.get(reportId);
        if (existing == null) {
            throw new NoSuchElementException("Radiology DiagnosticReport not found: " + reportId);
        }
        if (clinicianId == null || clinicianId.isBlank()) {
            throw new IllegalArgumentException("Acknowledging clinicianId is required");
        }
        RadiologyDiagnosticReport acknowledged = new RadiologyDiagnosticReport(
                existing.reportId(),
                existing.orderId(),
                existing.facilityId(),
                existing.patientId(),
                existing.accessionNumber(),
                existing.studyInstanceUid(),
                existing.radiologistId(),
                existing.findingsText(),
                existing.impressionText(),
                existing.criticalFindingDetected(),
                existing.criticalFindingCategory(),
                existing.incidentalFindingFollowUpRequired(),
                "ACKNOWLEDGED_CLOSED_LOOP",
                clinicianId.trim(),
                Instant.now(),
                existing.fhirDiagnosticReportJson(),
                existing.signedAt()
        );
        reportsById.put(reportId, acknowledged);
        return acknowledged;
    }

    public List<RadiologyDiagnosticReport> listUnacknowledgedCriticalFindings() {
        return reportsById.values().stream()
                .filter(r -> r.criticalFindingDetected() && !"ACKNOWLEDGED_CLOSED_LOOP".equals(r.acknowledgmentStatus()))
                .sorted(Comparator.comparing(RadiologyDiagnosticReport::signedAt))
                .toList();
    }
}
