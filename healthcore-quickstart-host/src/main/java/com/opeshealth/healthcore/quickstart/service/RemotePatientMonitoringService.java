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

import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.service.FhirSubscriptionTopicService.SubscriptionEventDispatchRequest;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remote Patient Monitoring (RPM), Wearable IoMT Telemetry &amp; Clinical Early Warning Score Service
 * (Royal College of Physicians {@code NEWS2}, {@code MEWS}, and Sepsis {@code qSOFA}).
 *
 * <p>Ingests continuous wearable or bedside physiologic telemetry streams, persists HL7 FHIR R4
 * {@code Observation} resources via {@link ObservationService}, computes deterministic NEWS2 (0–20),
 * MEWS (0–14), and qSOFA (0–3) scores, triggers Rapid Response Team (RRT) escalations, and
 * dispatches real-time {@code critical-observation-alert} notifications.</p>
 *
 * @author Jude Nshome — Opes Health Systems (nshome@opesware.com)
 */
@Service
public class RemotePatientMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(RemotePatientMonitoringService.class);

    public enum ConsciousnessAcvpu {
        ALERT,
        CONFUSION,
        VOICE,
        PAIN,
        UNRESPONSIVE
    }

    public enum ClinicalEscalationTier {
        LOW("NEWS2 0–4: Continue routine 12-hourly or 4–6 hourly RPM monitoring"),
        LOW_MEDIUM_SINGLE_RED_3("Single parameter score of 3 (Red Score): Urgent ward-based clinician review within 30 minutes"),
        MEDIUM_URGENT_REVIEW("NEWS2 5–6: Urgent clinician review with acute illness competencies & hourly vitals"),
        HIGH_EMERGENCY_RRT("NEWS2 >= 7 or qSOFA >= 2: Emergency Rapid Response Team (RRT) / ICU escalation & continuous telemetry");

        private final String protocolAction;

        ClinicalEscalationTier(String protocolAction) {
            this.protocolAction = protocolAction;
        }

        public String protocolAction() {
            return protocolAction;
        }
    }

    public record WearableDeviceRegistrationRequest(
            UUID facilityId,
            UUID patientId,
            String deviceSerialNumber,
            String deviceType,
            String manufacturer,
            boolean hypercapnicRespiratoryFailureScale2
    ) {}

    public record RegisteredWearableDevice(
            UUID deviceId,
            UUID facilityId,
            UUID patientId,
            String deviceSerialNumber,
            String deviceType,
            String manufacturer,
            boolean hypercapnicRespiratoryFailureScale2,
            String status,
            Instant registeredAt
    ) {}

    public record RpmVitalTelemetryPacket(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID deviceId,
            int respirationRatePerMin,
            int spo2Percent,
            boolean onSupplementalOxygen,
            boolean hypercapnicScale2,
            int systolicBpMmHg,
            int diastolicBpMmHg,
            int heartRateBpm,
            ConsciousnessAcvpu consciousness,
            double temperatureCelsius
    ) {}

    public record EarlyWarningAssessment(
            UUID assessmentId,
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            UUID deviceId,
            int news2TotalScore,
            int mewsTotalScore,
            int qsofaTotalScore,
            boolean singleParameterRedScoreThree,
            boolean sepsisScreeningPositive,
            ClinicalEscalationTier escalationTier,
            String clinicalRecommendation,
            boolean rapidResponseTeamTriggered,
            Map<String, Integer> news2ParameterSubScores,
            List<UUID> persistedObservationIds,
            Instant assessedAt
    ) {}

    private final ObservationService observationService;
    private final FhirSubscriptionTopicService subscriptionTopicService;
    private final FhirAuditLoggingService fhirAuditLoggingService;

    private final Map<UUID, RegisteredWearableDevice> devicesById = new ConcurrentHashMap<>();
    private final Map<UUID, List<EarlyWarningAssessment>> assessmentsByPatient = new ConcurrentHashMap<>();

    @Autowired
    public RemotePatientMonitoringService(
            ObservationService observationService,
            @Autowired(required = false) FhirSubscriptionTopicService subscriptionTopicService,
            @Autowired(required = false) FhirAuditLoggingService fhirAuditLoggingService) {
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
        this.subscriptionTopicService = subscriptionTopicService;
        this.fhirAuditLoggingService = fhirAuditLoggingService;
    }

    public RemotePatientMonitoringService(ObservationService observationService) {
        this(observationService, null, null);
    }

    @ClinicalAudit(action = "RPM_DEVICE_REGISTER", resourceType = "Device")
    public RegisteredWearableDevice registerWearableDevice(WearableDeviceRegistrationRequest request) {
        if (request == null || request.patientId() == null) {
            throw new IllegalArgumentException("WearableDeviceRegistrationRequest with patientId is required");
        }
        if (request.deviceSerialNumber() == null || request.deviceSerialNumber().isBlank()) {
            throw new IllegalArgumentException("deviceSerialNumber is required");
        }

        UUID deviceId = UUID.randomUUID();
        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(request.facilityId());
        RegisteredWearableDevice device = new RegisteredWearableDevice(
                deviceId,
                facilityId,
                request.patientId(),
                request.deviceSerialNumber().trim(),
                request.deviceType() != null ? request.deviceType().trim() : "MULTIPARAMETER_WEARABLE_PATCH",
                request.manufacturer() != null ? request.manufacturer().trim() : "Opes BioTelemetry",
                request.hypercapnicRespiratoryFailureScale2(),
                "active",
                Instant.now()
        );
        devicesById.put(deviceId, device);
        return device;
    }

    public List<RegisteredWearableDevice> listPatientDevices(UUID patientId) {
        return devicesById.values().stream()
                .filter(d -> patientId == null || Objects.equals(patientId, d.patientId()))
                .toList();
    }

    /**
     * Ingests a remote or bedside telemetry vital-signs packet, persists HL7 FHIR R4 {@code Observation}
     * resources, calculates NEWS2, MEWS, and qSOFA scores, and triggers clinical escalation alerts.
     */
    @ClinicalAudit(action = "RPM_TELEMETRY_INGEST", resourceType = "Observation")
    public EarlyWarningAssessment ingestTelemetryAndEvaluateScores(RpmVitalTelemetryPacket packet) {
        if (packet == null || packet.patientId() == null) {
            throw new IllegalArgumentException("RpmVitalTelemetryPacket with patientId is required");
        }
        validatePhysiologicalBounds(packet);

        UUID facilityId = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(packet.facilityId());
        ConsciousnessAcvpu acvpu = packet.consciousness() != null ? packet.consciousness() : ConsciousnessAcvpu.ALERT;

        // Calculate Royal College of Physicians NEWS2 sub-scores
        int respScore = scoreNews2Respiration(packet.respirationRatePerMin());
        int spo2Score = packet.hypercapnicScale2()
                ? scoreNews2Spo2Scale2(packet.spo2Percent(), packet.onSupplementalOxygen())
                : scoreNews2Spo2Scale1(packet.spo2Percent());
        int o2Score = packet.onSupplementalOxygen() ? 2 : 0;
        int sbpScore = scoreNews2SystolicBp(packet.systolicBpMmHg());
        int hrScore = scoreNews2HeartRate(packet.heartRateBpm());
        int acvpuScore = acvpu == ConsciousnessAcvpu.ALERT ? 0 : 3;
        int tempScore = scoreNews2Temperature(packet.temperatureCelsius());

        int news2Total = respScore + spo2Score + o2Score + sbpScore + hrScore + acvpuScore + tempScore;
        boolean singleRed3 = respScore == 3 || spo2Score == 3 || sbpScore == 3
                || hrScore == 3 || acvpuScore == 3 || tempScore == 3;

        // Calculate MEWS (Modified Early Warning Score)
        int mewsTotal = scoreMewsRespiration(packet.respirationRatePerMin())
                + scoreMewsHeartRate(packet.heartRateBpm())
                + scoreMewsSystolicBp(packet.systolicBpMmHg())
                + scoreMewsTemperature(packet.temperatureCelsius())
                + scoreMewsConsciousness(acvpu);

        // Calculate Sepsis-3 qSOFA (RR >= 22, SBP <= 100, Altered mental status != ALERT)
        int qsofaTotal = (packet.respirationRatePerMin() >= 22 ? 1 : 0)
                + (packet.systolicBpMmHg() <= 100 ? 1 : 0)
                + (acvpu != ConsciousnessAcvpu.ALERT ? 1 : 0);

        boolean sepsisPositive = qsofaTotal >= 2 || (news2Total >= 5 && (packet.temperatureCelsius() >= 38.3 || packet.temperatureCelsius() < 36.0));

        ClinicalEscalationTier tier;
        if (news2Total >= 7 || qsofaTotal >= 2) {
            tier = ClinicalEscalationTier.HIGH_EMERGENCY_RRT;
        } else if (news2Total >= 5) {
            tier = ClinicalEscalationTier.MEDIUM_URGENT_REVIEW;
        } else if (singleRed3) {
            tier = ClinicalEscalationTier.LOW_MEDIUM_SINGLE_RED_3;
        } else {
            tier = ClinicalEscalationTier.LOW;
        }

        boolean rrtTriggered = tier == ClinicalEscalationTier.HIGH_EMERGENCY_RRT;

        // Persist LOINC-coded FHIR R4 Observations into ObservationService
        List<UUID> obsIds = new ArrayList<>();
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "9279-1", "Respiratory rate", BigDecimal.valueOf(packet.respirationRatePerMin()), "/min"));
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "59408-5", "Oxygen saturation in Arterial blood by Pulse oximetry", BigDecimal.valueOf(packet.spo2Percent()), "%"));
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "8480-6", "Systolic blood pressure", BigDecimal.valueOf(packet.systolicBpMmHg()), "mm[Hg]"));
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "8867-4", "Heart rate", BigDecimal.valueOf(packet.heartRateBpm()), "/min"));
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "8310-5", "Body temperature", BigDecimal.valueOf(packet.temperatureCelsius()), "Cel"));
        obsIds.add(persistVitalObservation(facilityId, packet.patientId(), packet.encounterId(),
                "89260-4", "National Early Warning Score 2 (NEWS2)", BigDecimal.valueOf(news2Total), "{score}"));

        Map<String, Integer> subScores = new LinkedHashMap<>();
        subScores.put("respirationRate", respScore);
        subScores.put("spo2", spo2Score);
        subScores.put("supplementalOxygen", o2Score);
        subScores.put("systolicBp", sbpScore);
        subScores.put("heartRate", hrScore);
        subScores.put("consciousnessAcvpu", acvpuScore);
        subScores.put("temperature", tempScore);

        EarlyWarningAssessment assessment = new EarlyWarningAssessment(
                UUID.randomUUID(),
                facilityId,
                packet.patientId(),
                packet.encounterId(),
                packet.deviceId(),
                news2Total,
                mewsTotal,
                qsofaTotal,
                singleRed3,
                sepsisPositive,
                tier,
                tier.protocolAction() + (sepsisPositive ? " | SEPSIS-3 SCREENING POSITIVE: Initiate Sepsis Six bundle within 1 hour." : ""),
                rrtTriggered,
                Map.copyOf(subScores),
                List.copyOf(obsIds),
                Instant.now()
        );

        assessmentsByPatient
                .computeIfAbsent(packet.patientId(), k -> new ArrayList<>())
                .add(assessment);

        if (tier != ClinicalEscalationTier.LOW && subscriptionTopicService != null) {
            subscriptionTopicService.publishClinicalEvent(new SubscriptionEventDispatchRequest(
                    facilityId,
                    FhirSubscriptionTopicService.TOPIC_CRITICAL_OBSERVATION_ALERT,
                    "Observation",
                    obsIds.get(obsIds.size() - 1).toString(),
                    packet.patientId(),
                    rrtTriggered ? "CRITICAL" : "HIGH",
                    "RPM Early Warning Alert for Patient/" + packet.patientId()
                            + ": NEWS2=" + news2Total + ", MEWS=" + mewsTotal + ", qSOFA=" + qsofaTotal
                            + " (" + tier.name() + ")",
                    Map.of(
                            "assessmentId", assessment.assessmentId().toString(),
                            "news2Score", news2Total,
                            "qsofaScore", qsofaTotal,
                            "sepsisPositive", sepsisPositive,
                            "escalationTier", tier.name()
                    )
            ));
        }

        if (fhirAuditLoggingService != null) {
            fhirAuditLoggingService.recordFhirAccess(new FhirAuditLoggingService.FhirAccessAuditCommand(
                    facilityId.toString(),
                    "RPM_TELEMETRY_GATEWAY",
                    "IOT_DEVICE",
                    "RPM_EARLY_WARNING_EVALUATED",
                    "Observation",
                    assessment.assessmentId().toString(),
                    packet.patientId(),
                    "POST",
                    "/api/v1/rpm/telemetry",
                    "127.0.0.1",
                    null,
                    "SUCCESS",
                    0L,
                    "Evaluated RPM telemetry NEWS2=" + news2Total + " qSOFA=" + qsofaTotal + " tier=" + tier
            ));
        }

        log.info("RPM telemetry assessed for patient={}: NEWS2={} MEWS={} qSOFA={} tier={}",
                packet.patientId(), news2Total, mewsTotal, qsofaTotal, tier);
        return assessment;
    }

    public List<EarlyWarningAssessment> getPatientAssessments(UUID patientId) {
        Objects.requireNonNull(patientId, "patientId is required");
        List<EarlyWarningAssessment> list = assessmentsByPatient.get(patientId);
        if (list == null) {
            return List.of();
        }
        return list.stream()
                .sorted(Comparator.comparing(EarlyWarningAssessment::assessedAt).reversed())
                .toList();
    }

    private UUID persistVitalObservation(
            UUID facilityId,
            UUID patientId,
            UUID encounterId,
            String loincCode,
            String display,
            BigDecimal value,
            String ucumUnit) {

        ObservationEntity obs = new ObservationEntity();
        obs.setFacilityId(facilityId);
        obs.setStatus("final");
        obs.setCategoryCode("vital-signs");
        obs.setCategoryDisplay("Vital Signs");
        obs.setCodeSystem("http://loinc.org");
        obs.setCode(loincCode);
        obs.setCodeDisplay(display);
        obs.setSubjectReference("Patient/" + patientId);
        obs.setPatientId(patientId);
        if (encounterId != null) {
            obs.setEncounterReference("Encounter/" + encounterId);
            obs.setEncounterId(encounterId);
        }
        obs.setValueType("Quantity");
        obs.setValueQuantity(value);
        obs.setValueUnit(ucumUnit);
        obs.setValueCode("http://unitsofmeasure.org|" + ucumUnit);
        obs.setEffectiveAt(Instant.now());
        ObservationEntity saved = observationService.createObservation(obs);
        return saved.getId();
    }

    private static void validatePhysiologicalBounds(RpmVitalTelemetryPacket p) {
        if (p.respirationRatePerMin() < 2 || p.respirationRatePerMin() > 80) {
            throw new IllegalArgumentException("Respiration rate out of physiological bounds (2–80 /min)");
        }
        if (p.spo2Percent() < 40 || p.spo2Percent() > 100) {
            throw new IllegalArgumentException("SpO2 out of physiological bounds (40–100%)");
        }
        if (p.systolicBpMmHg() < 30 || p.systolicBpMmHg() > 320) {
            throw new IllegalArgumentException("Systolic BP out of physiological bounds (30–320 mmHg)");
        }
        if (p.heartRateBpm() < 15 || p.heartRateBpm() > 280) {
            throw new IllegalArgumentException("Heart rate out of physiological bounds (15–280 bpm)");
        }
        if (p.temperatureCelsius() < 25.0 || p.temperatureCelsius() > 44.5) {
            throw new IllegalArgumentException("Core temperature out of physiological bounds (25.0–44.5 °C)");
        }
    }

    public static int scoreNews2Respiration(int rr) {
        if (rr <= 8) return 3;
        if (rr <= 11) return 1;
        if (rr <= 20) return 0;
        if (rr <= 24) return 2;
        return 3;
    }

    public static int scoreNews2Spo2Scale1(int spo2) {
        if (spo2 <= 91) return 3;
        if (spo2 <= 93) return 2;
        if (spo2 <= 95) return 1;
        return 0;
    }

    public static int scoreNews2Spo2Scale2(int spo2, boolean onOxygen) {
        if (spo2 <= 83) return 3;
        if (spo2 <= 85) return 2;
        if (spo2 <= 87) return 1;
        if (spo2 <= 92) return 0;
        if (!onOxygen) return 0;
        if (spo2 <= 94) return 1;
        if (spo2 <= 96) return 2;
        return 3;
    }

    public static int scoreNews2SystolicBp(int sbp) {
        if (sbp <= 90) return 3;
        if (sbp <= 100) return 2;
        if (sbp <= 110) return 1;
        if (sbp <= 219) return 0;
        return 3;
    }

    public static int scoreNews2HeartRate(int hr) {
        if (hr <= 40) return 3;
        if (hr <= 50) return 1;
        if (hr <= 90) return 0;
        if (hr <= 110) return 1;
        if (hr <= 130) return 2;
        return 3;
    }

    public static int scoreNews2Temperature(double tempC) {
        if (tempC <= 35.0) return 3;
        if (tempC <= 36.0) return 1;
        if (tempC <= 38.0) return 0;
        if (tempC <= 39.0) return 1;
        return 2;
    }

    private static int scoreMewsRespiration(int rr) {
        if (rr < 9) return 2;
        if (rr <= 14) return 0;
        if (rr <= 20) return 1;
        if (rr <= 29) return 2;
        return 3;
    }

    private static int scoreMewsHeartRate(int hr) {
        if (hr <= 40) return 2;
        if (hr <= 50) return 1;
        if (hr <= 100) return 0;
        if (hr <= 110) return 1;
        if (hr <= 129) return 2;
        return 3;
    }

    private static int scoreMewsSystolicBp(int sbp) {
        if (sbp <= 70) return 3;
        if (sbp <= 80) return 2;
        if (sbp <= 100) return 1;
        if (sbp <= 199) return 0;
        return 2;
    }

    private static int scoreMewsTemperature(double tempC) {
        if (tempC < 35.0) return 2;
        if (tempC <= 38.4) return 0;
        return 2;
    }

    private static int scoreMewsConsciousness(ConsciousnessAcvpu acvpu) {
        return switch (acvpu) {
            case ALERT -> 0;
            case CONFUSION, VOICE -> 1;
            case PAIN -> 2;
            case UNRESPONSIVE -> 3;
        };
    }
}
