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

import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxPrescribingAlert;
import com.opeshealth.healthcore.quickstart.service.PharmacogenomicsCdsService.PgxPrescriptionCheckRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Closed-Loop Barcoded Medication Administration (BCMA) &amp; Inpatient eMAR Safety Service.
 *
 * <p>Enforces the **5 Rights of Medication Administration** at the bedside (Right Patient wristband
 * barcode, Right Medication GS1 GTIN barcode, Right Dose within tolerance, Right Route, Right Time
 * schedule window), cross-checks **Pharmacogenomics (CPIC)** contraindications, and enforces
 * mandatory **Dual-Nurse Cosignature** for High-Alert / Look-Alike Sound-Alike (LASA) medications
 * (insulin, heparin, opioids, concentrated electrolytes, chemotherapy).</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class BcmaMedicationSafetyService {

    private static final Set<String> HIGH_ALERT_LASA_KEYWORDS = Set.of(
            "insulin", "heparin", "morphine", "fentanyl", "hydromorphone",
            "potassium chloride", "kcl", "warfarin", "epinephrine", "norepinephrine",
            "vincristine", "methotrexate"
    );

    public enum BcmaVerificationOutcome {
        VERIFIED_ADMINISTERED,
        BLOCKED_WRONG_PATIENT,
        BLOCKED_WRONG_MEDICATION_BARCODE,
        BLOCKED_WRONG_DOSE,
        BLOCKED_WRONG_ROUTE,
        BLOCKED_OUTSIDE_TIME_WINDOW,
        BLOCKED_PGX_CONTRAINDICATION,
        BLOCKED_HIGH_ALERT_MISSING_DUAL_NURSE
    }

    public record EmarScheduledOrderRequest(
            UUID facilityId,
            UUID patientId,
            String patientWristbandBarcode,
            String medicationName,
            String rxNormOrAtcCode,
            String medicationGtinBarcode,
            double orderedDoseAmount,
            String doseUnit,
            String routeCode,
            Instant scheduledTime,
            Integer windowMinutesBeforeAfter,
            Boolean explicitHighAlertLasa,
            String orderingPractitionerId
    ) {}

    public record EmarScheduledMedicationOrder(
            UUID orderId,
            UUID facilityId,
            UUID patientId,
            String patientWristbandBarcode,
            String medicationName,
            String rxNormOrAtcCode,
            String medicationGtinBarcode,
            double orderedDoseAmount,
            String doseUnit,
            String routeCode,
            Instant scheduledTime,
            int windowMinutesBeforeAfter,
            boolean highAlertLasaMedication,
            String orderingPractitionerId,
            String status,
            Instant createdAt
    ) {}

    public record BedsideBarcodeScanRequest(
            UUID orderId,
            UUID scannedPatientId,
            String scannedWristbandBarcode,
            String scannedMedicationGtinBarcode,
            double administeredDoseAmount,
            String doseUnit,
            String administeredRouteCode,
            String primaryNurseBadgeId,
            String secondaryWitnessNurseBadgeId,
            Instant scanTimestamp,
            boolean overrideTimeWindowWithJustification,
            String clinicalNotes
    ) {}

    public record FiveRightsCheckMatrix(
            boolean rightPatient,
            boolean rightMedication,
            boolean rightDose,
            boolean rightRoute,
            boolean rightTime,
            boolean pgxSafetyCleared,
            boolean highAlertDualSignOffSatisfied
    ) {}

    public record BcmaAdministrationRecord(
            UUID administrationId,
            UUID orderId,
            UUID facilityId,
            UUID patientId,
            String medicationName,
            String medicationGtinBarcode,
            double administeredDoseAmount,
            String doseUnit,
            String routeCode,
            String primaryNurseBadgeId,
            String secondaryWitnessNurseBadgeId,
            boolean highAlertLasaMedication,
            FiveRightsCheckMatrix fiveRightsMatrix,
            BcmaVerificationOutcome outcome,
            List<String> safetyAlerts,
            String fhirMedicationAdministrationJson,
            Instant recordedAt
    ) {}

    private final PharmacogenomicsCdsService pgxService;
    private final FhirSubscriptionTopicService subscriptionTopicService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final Map<UUID, EmarScheduledMedicationOrder> ordersById = new ConcurrentHashMap<>();
    private final Map<UUID, List<BcmaAdministrationRecord>> administrationsByPatient = new ConcurrentHashMap<>();

    @Autowired
    public BcmaMedicationSafetyService(
            @Autowired(required = false) PharmacogenomicsCdsService pgxService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.pgxService = pgxService;
        this.subscriptionTopicService = subscriptionTopicService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public BcmaMedicationSafetyService() {
        this(null, null, null);
    }

    /**
     * Schedules an inpatient eMAR medication order with barcode identifiers for closed-loop BCMA verification.
     */
    @ClinicalAudit(action = "BCMA_EMAR_ORDER_SCHEDULE", resourceType = "MedicationRequest")
    public EmarScheduledMedicationOrder scheduleEmarOrder(EmarScheduledOrderRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("EmarScheduledOrderRequest with patientId is required");
        }
        if (request.medicationName() == null || request.medicationName().isBlank()) {
            throw new IllegalArgumentException("medicationName is required");
        }
        if (request.medicationGtinBarcode() == null || request.medicationGtinBarcode().isBlank()) {
            throw new IllegalArgumentException("medicationGtinBarcode (GS1 DataMatrix / GTIN) is required");
        }
        if (request.orderedDoseAmount() <= 0) {
            throw new IllegalArgumentException("orderedDoseAmount must be positive");
        }

        UUID orderId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        String wristband = (request.patientWristbandBarcode() != null && !request.patientWristbandBarcode().isBlank())
                ? request.patientWristbandBarcode().trim()
                : "WB-" + request.patientId();

        boolean highAlert = Boolean.TRUE.equals(request.explicitHighAlertLasa())
                || isHighAlertMedication(request.medicationName());

        EmarScheduledMedicationOrder order = new EmarScheduledMedicationOrder(
                orderId,
                facilityId,
                request.patientId(),
                wristband,
                request.medicationName().trim(),
                request.rxNormOrAtcCode() != null ? request.rxNormOrAtcCode().trim() : "RX-STANDARD",
                request.medicationGtinBarcode().trim(),
                request.orderedDoseAmount(),
                request.doseUnit() != null ? request.doseUnit().trim() : "mg",
                request.routeCode() != null ? request.routeCode().trim().toUpperCase(Locale.ROOT) : "PO",
                request.scheduledTime() != null ? request.scheduledTime() : Instant.now(),
                (request.windowMinutesBeforeAfter() != null && request.windowMinutesBeforeAfter() > 0)
                        ? request.windowMinutesBeforeAfter()
                        : 60,
                highAlert,
                request.orderingPractitionerId() != null ? request.orderingPractitionerId().trim() : "DR-ATTENDING",
                "active",
                Instant.now()
        );

        ordersById.put(orderId, order);
        return order;
    }

    public List<EmarScheduledMedicationOrder> listPatientScheduledOrders(UUID patientId) {
        return ordersById.values().stream()
                .filter(o -> patientId == null || Objects.equals(patientId, o.patientId()))
                .sorted(Comparator.comparing(EmarScheduledMedicationOrder::scheduledTime))
                .toList();
    }

    /**
     * Executes bedside 5-Rights barcode verification, PGx safety check, and High-Alert dual-nurse
     * witness verification before recording an HL7 FHIR R4 {@code MedicationAdministration}.
     */
    @ClinicalAudit(action = "BCMA_BEDSIDE_SCAN_VERIFY", resourceType = "MedicationAdministration")
    public BcmaAdministrationRecord verifyAndAdministerMedication(BedsideBarcodeScanRequest scan) {
        if (scan == null || scan.orderId() == null) {
            throw new IllegalArgumentException("BedsideBarcodeScanRequest with orderId is required");
        }
        EmarScheduledMedicationOrder order = ordersById.get(scan.orderId());
        if (order == null) {
            throw new NoSuchElementException("eMAR Scheduled Medication Order not found: " + scan.orderId());
        }
        if (scan.primaryNurseBadgeId() == null || scan.primaryNurseBadgeId().isBlank()) {
            throw new IllegalArgumentException("primaryNurseBadgeId is required for bedside BCMA administration");
        }

        Instant scanTime = scan.scanTimestamp() != null ? scan.scanTimestamp() : Instant.now();
        List<String> alerts = new ArrayList<>();

        // 1. Right Patient (Patient UUID + Wristband Barcode)
        boolean rightPatient = Objects.equals(order.patientId(), scan.scannedPatientId())
                && order.patientWristbandBarcode().equalsIgnoreCase(
                scan.scannedWristbandBarcode() != null ? scan.scannedWristbandBarcode().trim() : "");
        if (!rightPatient) {
            alerts.add("RIGHT_PATIENT_VIOLATION: Scanned wristband '" + scan.scannedWristbandBarcode()
                    + "' does not match patient order wristband '" + order.patientWristbandBarcode() + "'");
        }

        // 2. Right Medication (GS1 GTIN Barcode)
        boolean rightMedication = order.medicationGtinBarcode().equalsIgnoreCase(
                scan.scannedMedicationGtinBarcode() != null ? scan.scannedMedicationGtinBarcode().trim() : "");
        if (!rightMedication) {
            alerts.add("RIGHT_MEDICATION_VIOLATION: Scanned GTIN '" + scan.scannedMedicationGtinBarcode()
                    + "' does not match ordered GTIN '" + order.medicationGtinBarcode()
                    + "' (" + order.medicationName() + ")");
        }

        // 3. Right Dose (within 5% clinical rounding tolerance and matching unit)
        boolean sameUnit = order.doseUnit().equalsIgnoreCase(
                scan.doseUnit() != null ? scan.doseUnit().trim() : order.doseUnit());
        double doseDiffRatio = Math.abs(order.orderedDoseAmount() - scan.administeredDoseAmount()) / order.orderedDoseAmount();
        boolean rightDose = sameUnit && doseDiffRatio <= 0.05;
        if (!rightDose) {
            alerts.add("RIGHT_DOSE_VIOLATION: Scanned dose " + scan.administeredDoseAmount() + " " + scan.doseUnit()
                    + " differs from ordered dose " + order.orderedDoseAmount() + " " + order.doseUnit());
        }

        // 4. Right Route
        boolean rightRoute = order.routeCode().equalsIgnoreCase(
                scan.administeredRouteCode() != null ? scan.administeredRouteCode().trim() : "");
        if (!rightRoute) {
            alerts.add("RIGHT_ROUTE_VIOLATION: Scanned route '" + scan.administeredRouteCode()
                    + "' does not match ordered route '" + order.routeCode() + "'");
        }

        // 5. Right Time (within scheduled window +/- windowMinutesBeforeAfter)
        long minutesDelta = Math.abs(Duration.between(order.scheduledTime(), scanTime).toMinutes());
        boolean rightTime = minutesDelta <= order.windowMinutesBeforeAfter() || scan.overrideTimeWindowWithJustification();
        if (!rightTime) {
            alerts.add("RIGHT_TIME_VIOLATION: Scan timestamp is " + minutesDelta
                    + " minutes outside scheduled administration window (+/- " + order.windowMinutesBeforeAfter() + " min)");
        }

        // 6. Pharmacogenomic (CPIC) safety check
        boolean pgxCleared = true;
        if (pgxService != null) {
            PgxPrescribingAlert pgxAlert = pgxService.evaluateMedicationOrder(new PgxPrescriptionCheckRequest(
                    order.facilityId(),
                    order.patientId(),
                    order.medicationName(),
                    order.rxNormOrAtcCode(),
                    order.orderedDoseAmount() + " " + order.doseUnit()
            ));
            if (pgxAlert.blockAdministrationWithoutOverride()) {
                pgxCleared = false;
                alerts.add("PGX_CONTRAINDICATION_BLOCK: " + pgxAlert.implicatedGene() + " "
                        + pgxAlert.patientDiplotype() + " — " + pgxAlert.dosingRecommendation());
            }
        }

        // 7. High-Alert / LASA Dual-Nurse Independent Verification
        boolean dualSignOffOk = true;
        if (order.highAlertLasaMedication()) {
            String primary = scan.primaryNurseBadgeId().trim();
            String witness = scan.secondaryWitnessNurseBadgeId() != null
                    ? scan.secondaryWitnessNurseBadgeId().trim()
                    : "";
            if (witness.isEmpty() || primary.equalsIgnoreCase(witness)) {
                dualSignOffOk = false;
                alerts.add("HIGH_ALERT_LASA_VIOLATION: High-alert medication '" + order.medicationName()
                        + "' requires an independent second witness nurse badge cosignature");
            }
        }

        FiveRightsCheckMatrix matrix = new FiveRightsCheckMatrix(
                rightPatient,
                rightMedication,
                rightDose,
                rightRoute,
                rightTime,
                pgxCleared,
                dualSignOffOk
        );

        BcmaVerificationOutcome outcome;
        if (!rightPatient) {
            outcome = BcmaVerificationOutcome.BLOCKED_WRONG_PATIENT;
        } else if (!rightMedication) {
            outcome = BcmaVerificationOutcome.BLOCKED_WRONG_MEDICATION_BARCODE;
        } else if (!rightDose) {
            outcome = BcmaVerificationOutcome.BLOCKED_WRONG_DOSE;
        } else if (!rightRoute) {
            outcome = BcmaVerificationOutcome.BLOCKED_WRONG_ROUTE;
        } else if (!rightTime) {
            outcome = BcmaVerificationOutcome.BLOCKED_OUTSIDE_TIME_WINDOW;
        } else if (!pgxCleared) {
            outcome = BcmaVerificationOutcome.BLOCKED_PGX_CONTRAINDICATION;
        } else if (!dualSignOffOk) {
            outcome = BcmaVerificationOutcome.BLOCKED_HIGH_ALERT_MISSING_DUAL_NURSE;
        } else {
            outcome = BcmaVerificationOutcome.VERIFIED_ADMINISTERED;
        }

        UUID adminId = UUID.randomUUID();
        String fhirStatus = outcome == BcmaVerificationOutcome.VERIFIED_ADMINISTERED ? "completed" : "not-done";
        String fhirJson = """
                {
                  "resourceType": "MedicationAdministration",
                  "id": "%s",
                  "status": "%s",
                  "medicationCodeableConcept": {
                    "coding": [{"system": "http://www.nlm.nih.gov/research/umls/rxnorm", "code": "%s", "display": "%s"}]
                  },
                  "subject": {"reference": "Patient/%s"},
                  "effectiveDateTime": "%s",
                  "performer": [{"actor": {"identifier": {"value": "%s"}}}],
                  "request": {"reference": "MedicationRequest/%s"},
                  "dosage": {
                    "route": {"text": "%s"},
                    "dose": {"value": %s, "unit": "%s"}
                  }
                }
                """.formatted(
                adminId,
                fhirStatus,
                order.rxNormOrAtcCode(),
                order.medicationName(),
                order.patientId(),
                scanTime,
                scan.primaryNurseBadgeId().trim(),
                order.orderId(),
                scan.administeredRouteCode(),
                scan.administeredDoseAmount(),
                order.doseUnit()
        ).trim();

        BcmaAdministrationRecord record = new BcmaAdministrationRecord(
                adminId,
                order.orderId(),
                order.facilityId(),
                order.patientId(),
                order.medicationName(),
                scan.scannedMedicationGtinBarcode(),
                scan.administeredDoseAmount(),
                order.doseUnit(),
                scan.administeredRouteCode(),
                scan.primaryNurseBadgeId().trim(),
                scan.secondaryWitnessNurseBadgeId(),
                order.highAlertLasaMedication(),
                matrix,
                outcome,
                List.copyOf(alerts),
                fhirJson,
                scanTime
        );

        administrationsByPatient
                .computeIfAbsent(order.patientId(), k -> new ArrayList<>())
                .add(record);

        if (outcome != BcmaVerificationOutcome.VERIFIED_ADMINISTERED && subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    order.facilityId(),
                    FhirSubscriptionTopicService.TOPIC_BCMA_ADVERSE_MEDICATION_ALERT,
                    "MedicationAdministration",
                    adminId.toString(),
                    order.patientId(),
                    "CRITICAL",
                    "BCMA Bedside Block (" + outcome + ") for Patient/" + order.patientId()
                            + " on " + order.medicationName() + ": " + String.join("; ", alerts),
                    Map.of(
                            "administrationId", adminId.toString(),
                            "outcome", outcome.name(),
                            "medicationName", order.medicationName()
                    )
            ));
        }

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    order.facilityId().toString(),
                    scan.primaryNurseBadgeId().trim(),
                    "ROLE_NURSE",
                    "BCMA_BEDSIDE_VERIFICATION_" + outcome.name(),
                    "MedicationAdministration",
                    adminId.toString(),
                    order.patientId(),
                    "POST",
                    "/api/v1/bcma/administer",
                    "127.0.0.1",
                    null,
                    outcome == BcmaVerificationOutcome.VERIFIED_ADMINISTERED ? "SUCCESS" : "FAILURE",
                    0L,
                    "BCMA verification for " + order.medicationName() + " outcome=" + outcome
            ));
        }

        return record;
    }

    public List<BcmaAdministrationRecord> listPatientAdministrations(UUID patientId) {
        List<BcmaAdministrationRecord> list = administrationsByPatient.get(patientId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(BcmaAdministrationRecord::recordedAt).reversed())
                .toList();
    }

    private static boolean isHighAlertMedication(String medicationName) {
        String lower = medicationName.toLowerCase(Locale.ROOT);
        for (String kw : HIGH_ALERT_LASA_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
