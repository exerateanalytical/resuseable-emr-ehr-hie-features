/*
 * =============================================================================
 * Opes HealthCore SDK — Cold-Chain Telemetry, FHIR Observation & Alerting Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.ColdChainRepository;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchResult;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Integrates cold-chain IoT/telemetry sensors for vaccines, biologics, blood products,
 * and temperature-sensitive pharmaceuticals with the HL7 FHIR R4 {@link ObservationService}.
 *
 * <p>Capabilities:</p>
 * <ul>
 *   <li>Persists every storage or transport telemetry reading as a normative HL7 FHIR R4
 *       {@code Observation} (LOINC {@code 60832-3}) with multi-component sensor metrics
 *       (temperature in UCUM {@code Cel}, relative humidity {@code %}, battery {@code %},
 *       door-open duration {@code s}, and transport shock {@code [g]})</li>
 *   <li>Evaluates readings against WHO &amp; USP cold-chain profiles ({@code +2°C to +8°C},
 *       {@code -25°C to -15°C}, {@code -90°C to -60°C}, and {@code +15°C to +25°C})</li>
 *   <li>Automatically generates and tracks real-time excursion alerts with quarantine
 *       directives (WHO Shake Test for freeze excursions, VVM inspection, or batch quarantine)</li>
 *   <li>Computes Mean Kinetic Temperature (MKT) and storage/transport excursion summaries
 *       directly from persisted FHIR {@code Observation} time-series records</li>
 * </ul>
 */
@Service
@Transactional
public class ColdChainMonitoringService {

    public static final String LOINC_SYSTEM = "http://loinc.org";
    public static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
    public static final String LOINC_CONTAINER_TEMP = "60832-3";
    public static final String LOINC_RELATIVE_HUMIDITY = "60833-1";
    public static final String COLD_CHAIN_CATEGORY_SYSTEM = "https://www.opeshealthsystems.com/fhir/CodeSystem/observation-category";
    public static final String COLD_CHAIN_CATEGORY_CODE = "cold-chain-telemetry";
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);

    /** Standard WHO &amp; USP thermal stability bands for medical supplies. */
    public enum SupplyThermalProfile {
        WHO_STANDARD_REFRIGERATED_2C_8C(
                new BigDecimal("2.00"),
                new BigDecimal("8.00"),
                new BigDecimal("0.50"),
                true,
                "WHO Standard Refrigerated Vaccine & Biologic (+2.0°C to +8.0°C)"
        ),
        FROZEN_BIOLOGIC_MINUS25C_MINUS15C(
                new BigDecimal("-25.00"),
                new BigDecimal("-15.00"),
                null,
                false,
                "Frozen Vaccine & Plasma Storage (-25.0°C to -15.0°C)"
        ),
        ULTRA_COLD_CHAIN_MINUS90C_MINUS60C(
                new BigDecimal("-90.00"),
                new BigDecimal("-60.00"),
                null,
                false,
                "Ultra-Cold Chain Biologic & mRNA Storage (-90.0°C to -60.0°C)"
        ),
        CONTROLLED_AMBIENT_DRUG_15C_25C(
                new BigDecimal("15.00"),
                new BigDecimal("25.00"),
                new BigDecimal("2.00"),
                false,
                "Controlled Room Temperature Pharmaceuticals & RDTs (+15.0°C to +25.0°C)"
        );

        private final BigDecimal minCelsius;
        private final BigDecimal maxCelsius;
        private final BigDecimal freezeWarningCelsius;
        private final boolean freezeSensitive;
        private final String label;

        SupplyThermalProfile(
                BigDecimal minCelsius,
                BigDecimal maxCelsius,
                BigDecimal freezeWarningCelsius,
                boolean freezeSensitive,
                String label) {
            this.minCelsius = minCelsius;
            this.maxCelsius = maxCelsius;
            this.freezeWarningCelsius = freezeWarningCelsius;
            this.freezeSensitive = freezeSensitive;
            this.label = label;
        }

        public BigDecimal minCelsius() { return minCelsius; }
        public BigDecimal maxCelsius() { return maxCelsius; }
        public BigDecimal freezeWarningCelsius() { return freezeWarningCelsius; }
        public boolean freezeSensitive() { return freezeSensitive; }
        public String label() { return label; }
    }

    /** Operational context of the monitored cold-chain asset. */
    public enum MonitoringStage {
        FIXED_STORAGE,
        IN_TRANSIT_TRANSPORT,
        OUTREACH_CARRIER
    }

    /** Incoming telemetry payload from an ILR refrigerator, cold-room logger, or GPS vaccine transport carrier. */
    public record ColdChainSensorReading(
            UUID facilityId,
            String equipmentOrSensorId,
            String supplyBatchCode,
            String supplyName,
            SupplyThermalProfile thermalProfile,
            MonitoringStage monitoringStage,
            BigDecimal temperatureValue,
            String temperatureUnit,
            BigDecimal humidityPercent,
            Integer doorOpenSeconds,
            Boolean mainsPowerConnected,
            Integer batteryPercent,
            BigDecimal shockGForce,
            String routeOrLocationLabel,
            Instant recordedAt
    ) {
        public ColdChainSensorReading {
            if (equipmentOrSensorId == null || equipmentOrSensorId.isBlank()) {
                throw new IllegalArgumentException("equipmentOrSensorId is required for cold-chain monitoring");
            }
            if (temperatureValue == null) {
                throw new IllegalArgumentException("temperatureValue is required for cold-chain monitoring");
            }
            if (thermalProfile == null) {
                thermalProfile = SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C;
            }
            if (monitoringStage == null) {
                monitoringStage = MonitoringStage.FIXED_STORAGE;
            }
            if (temperatureUnit == null || temperatureUnit.isBlank()) {
                temperatureUnit = "Cel";
            }
        }
    }

    /** Automated deviation alert emitted when a cold-chain reading breaches thermal or environmental thresholds. */
    public record ColdChainAlert(
            UUID alertId,
            UUID facilityId,
            UUID observationId,
            String equipmentOrSensorId,
            String supplyBatchCode,
            String supplyName,
            MonitoringStage monitoringStage,
            String alertType,
            String severity,
            BigDecimal observedTempCelsius,
            BigDecimal allowedMinCelsius,
            BigDecimal allowedMaxCelsius,
            BigDecimal deviationDeltaCelsius,
            String quarantineAction,
            String diagnosticMessage,
            boolean acknowledged,
            String acknowledgedBy,
            String resolutionNotes,
            Instant triggeredAt,
            Instant acknowledgedAt
    ) {}

    /** Complete result of recording a sensor reading and persisting its FHIR R4 Observation. */
    public record ColdChainTelemetryReceipt(
            UUID observationId,
            String fhirObservationSubject,
            BigDecimal normalizedTempCelsius,
            String fhirInterpretationCode,
            String fhirInterpretationDisplay,
            boolean excursionDetected,
            String batchDispositionStatus,
            List<ColdChainAlert> triggeredAlerts,
            Observation fhirObservation
    ) {}

    /** Analytical audit of storage or transport FHIR Observations including Mean Kinetic Temperature (MKT). */
    public record ColdChainComplianceReport(
            UUID facilityId,
            String equipmentOrSensorId,
            SupplyThermalProfile thermalProfile,
            int totalObservationsAnalyzed,
            int inRangeObservationCount,
            int heatExcursionCount,
            int freezeExcursionCount,
            BigDecimal complianceRatePercent,
            BigDecimal minObservedCelsius,
            BigDecimal maxObservedCelsius,
            BigDecimal meanObservedCelsius,
            BigDecimal meanKineticTemperatureCelsius,
            String overallDisposition,
            List<ColdChainAlert> activeAlerts
    ) {}

    private final ObservationService observationService;
    private final ColdChainRepository coldChainRepository;
    private final OutboxRelayService.OutboxRepository outboxRepository;
    private final Map<UUID, ColdChainAlert> alertLedger = new ConcurrentHashMap<>();

    public ColdChainMonitoringService(ObservationService observationService) {
        this(observationService, (ColdChainRepository) null, (OutboxRelayService.OutboxRepository) null);
    }

    @Autowired
    public ColdChainMonitoringService(
            ObservationService observationService,
            ObjectProvider<ColdChainRepository> coldChainRepoProvider,
            ObjectProvider<OutboxRelayService.OutboxRepository> outboxRepoProvider) {
        this(
                observationService,
                coldChainRepoProvider != null ? coldChainRepoProvider.getIfAvailable() : null,
                outboxRepoProvider != null ? outboxRepoProvider.getIfAvailable() : null
        );
    }

    public ColdChainMonitoringService(
            ObservationService observationService,
            ColdChainRepository coldChainRepository,
            OutboxRelayService.OutboxRepository outboxRepository) {
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
        this.coldChainRepository = coldChainRepository;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Records a cold-chain temperature &amp; multi-sensor telemetry reading, persists a linked
     * HL7 FHIR R4 {@code Observation} via {@link ObservationService}, evaluates storage/transport
     * deviation rules, and triggers automated alerts and transactional outbox notifications.
     */
    @ClinicalAudit(action = "COLDCHAIN_TELEMETRY_RECORD", resourceType = "Observation")
    public ColdChainTelemetryReceipt recordSensorTelemetry(ColdChainSensorReading reading) {
        Objects.requireNonNull(reading, "ColdChainSensorReading must not be null");
        UUID facilityId = reading.facilityId() != null ? reading.facilityId() : DEFAULT_FACILITY_ID;
        Instant effectiveAt = reading.recordedAt() != null ? reading.recordedAt() : Instant.now();
        BigDecimal tempCelsius = normalizeToCelsius(reading.temperatureValue(), reading.temperatureUnit());
        SupplyThermalProfile profile = reading.thermalProfile();

        // 1. Evaluate thermal excursion thresholds
        boolean freezeBreach = tempCelsius.compareTo(profile.minCelsius()) < 0
                || (profile.freezeSensitive()
                        && profile.freezeWarningCelsius() != null
                        && tempCelsius.compareTo(profile.freezeWarningCelsius()) <= 0);
        boolean heatBreach = tempCelsius.compareTo(profile.maxCelsius()) > 0;

        BigDecimal severeHeatCeiling = profile.maxCelsius().add(new BigDecimal("6.00"));
        boolean criticalHeatBreach = heatBreach && tempCelsius.compareTo(severeHeatCeiling) >= 0;

        String interpCode = "N";
        String interpDisplay = "Normal — Within Cold-Chain Specification";
        if (freezeBreach) {
            interpCode = "LL";
            interpDisplay = "Critical Low — Cold-Chain Freeze Excursion";
        } else if (criticalHeatBreach) {
            interpCode = "HH";
            interpDisplay = "Critical High — Severe Cold-Chain Heat Excursion";
        } else if (heatBreach) {
            interpCode = "H";
            interpDisplay = "High — Cold-Chain Upper Threshold Deviation";
        }

        // 2. Build and persist HL7 FHIR R4 Observation through ObservationService
        String cleanSensorId = reading.equipmentOrSensorId().trim();
        String subjectRef = cleanSensorId.startsWith("Device/") ? cleanSensorId : "Device/" + cleanSensorId;

        ObservationEntity obsEntity = new ObservationEntity();
        obsEntity.setFacilityId(facilityId);
        obsEntity.setStatus("final");
        obsEntity.setCategorySystem(COLD_CHAIN_CATEGORY_SYSTEM);
        obsEntity.setCategoryCode(COLD_CHAIN_CATEGORY_CODE);
        obsEntity.setCategoryDisplay("Cold-Chain Storage & Transport Telemetry (" + reading.monitoringStage().name() + ")");
        obsEntity.setCodeSystem(LOINC_SYSTEM);
        obsEntity.setCode(LOINC_CONTAINER_TEMP);
        obsEntity.setCodeDisplay("Room or container temperature");
        obsEntity.setCodeText("Cold-Chain Sensor Telemetry: " + cleanSensorId
                + (reading.supplyBatchCode() != null ? " [Batch " + reading.supplyBatchCode() + "]" : ""));
        obsEntity.setSubjectReference(subjectRef);
        if (reading.routeOrLocationLabel() != null && !reading.routeOrLocationLabel().isBlank()) {
            obsEntity.setPerformerDisplay(reading.routeOrLocationLabel().trim());
        }
        obsEntity.setEffectiveAt(effectiveAt);
        obsEntity.setIssuedAt(Instant.now());
        obsEntity.setValueType("Quantity");
        obsEntity.setValueQuantity(tempCelsius);
        obsEntity.setValueUnit("Cel");
        obsEntity.setValueUnitSystem(UCUM_SYSTEM);
        obsEntity.setValueUnitCode("Cel");
        obsEntity.setRefRangeLow(profile.minCelsius());
        obsEntity.setRefRangeHigh(profile.maxCelsius());
        obsEntity.setRefRangeText(profile.label());
        obsEntity.setInterpretationCode(interpCode);
        obsEntity.setInterpretationDisplay(interpDisplay);
        obsEntity.setComponentsJson(buildSensorComponentsJson(reading));

        ObservationEntity savedObs = observationService.createObservation(obsEntity);

        // 3. Synchronize with hc_cold_chain_telemetry table if repository is active
        if (coldChainRepository != null) {
            ColdChainTelemetryEntity row = new ColdChainTelemetryEntity();
            row.setFacilityId(facilityId);
            row.setEquipmentTag(cleanSensorId);
            row.setTempCelsius(tempCelsius);
            row.setExcursionAlert(freezeBreach || heatBreach);
            coldChainRepository.save(row);
        }

        // 4. Generate automated alerts for thermal & environmental deviations
        List<ColdChainAlert> triggeredAlerts = evaluateAndRegisterAlerts(
                facilityId, savedObs.getId(), reading, tempCelsius, freezeBreach, heatBreach, criticalHeatBreach, effectiveAt);

        String disposition = resolveBatchDisposition(freezeBreach, heatBreach, criticalHeatBreach, triggeredAlerts);
        if (!triggeredAlerts.isEmpty()) {
            savedObs.setNoteText("COLD-CHAIN ALERT [" + disposition + "]: "
                    + triggeredAlerts.stream().map(ColdChainAlert::diagnosticMessage).reduce((a, b) -> a + " | " + b).orElse(""));
        }

        Observation fhirObs = ObservationService.toFhirObservation(savedObs);
        return new ColdChainTelemetryReceipt(
                savedObs.getId(),
                subjectRef,
                tempCelsius,
                interpCode,
                interpDisplay,
                !triggeredAlerts.isEmpty(),
                disposition,
                List.copyOf(triggeredAlerts),
                fhirObs
        );
    }

    /**
     * Evaluates all persisted HL7 FHIR R4 {@code Observation} records for a storage unit or
     * transport shipment, calculates Mean Kinetic Temperature (MKT), and returns a compliance report.
     */
    @Transactional(readOnly = true)
    @ClinicalAudit(action = "COLDCHAIN_COMPLIANCE_AUDIT", resourceType = "Observation")
    public ColdChainComplianceReport evaluateEquipmentOrShipmentCompliance(
            UUID facilityId,
            String equipmentOrSensorId,
            SupplyThermalProfile profile) {
        if (equipmentOrSensorId == null || equipmentOrSensorId.isBlank()) {
            throw new IllegalArgumentException("equipmentOrSensorId is required");
        }
        SupplyThermalProfile targetProfile = profile != null
                ? profile
                : SupplyThermalProfile.WHO_STANDARD_REFRIGERATED_2C_8C;

        String cleanSensorId = equipmentOrSensorId.trim();
        String expectedSubject = cleanSensorId.startsWith("Device/") ? cleanSensorId : "Device/" + cleanSensorId;

        ObservationSearchResult searchResult = observationService.searchObservations(new ObservationSearchCriteria(
                null,
                facilityId,
                null,
                null,
                null,
                null,
                COLD_CHAIN_CATEGORY_CODE,
                LOINC_CONTAINER_TEMP,
                null,
                null,
                List.of(),
                null,
                null,
                0,
                100
        ));

        List<ObservationEntity> matchingObservations = searchResult.observations().stream()
                .filter(o -> expectedSubject.equalsIgnoreCase(o.getSubjectReference())
                        || cleanSensorId.equalsIgnoreCase(o.getSubjectReference()))
                .filter(o -> o.getValueQuantity() != null)
                .toList();

        List<ColdChainAlert> deviceAlerts = alertLedger.values().stream()
                .filter(a -> !a.acknowledged())
                .filter(a -> facilityId == null || Objects.equals(facilityId, a.facilityId()))
                .filter(a -> cleanSensorId.equalsIgnoreCase(a.equipmentOrSensorId()))
                .sorted(Comparator.comparing(ColdChainAlert::triggeredAt).reversed())
                .toList();

        if (matchingObservations.isEmpty()) {
            return new ColdChainComplianceReport(
                    facilityId,
                    cleanSensorId,
                    targetProfile,
                    0, 0, 0, 0,
                    new BigDecimal("100.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "NO_TELEMETRY_RECORDED",
                    deviceAlerts
            );
        }

        List<BigDecimal> temps = new ArrayList<>(matchingObservations.size());
        int inRange = 0;
        int heatCount = 0;
        int freezeCount = 0;
        BigDecimal minTemp = null;
        BigDecimal maxTemp = null;
        BigDecimal sumTemp = BigDecimal.ZERO;

        for (ObservationEntity obs : matchingObservations) {
            BigDecimal t = obs.getValueQuantity();
            temps.add(t);
            sumTemp = sumTemp.add(t);
            if (minTemp == null || t.compareTo(minTemp) < 0) minTemp = t;
            if (maxTemp == null || t.compareTo(maxTemp) > 0) maxTemp = t;

            boolean isFreeze = t.compareTo(targetProfile.minCelsius()) < 0
                    || (targetProfile.freezeSensitive()
                            && targetProfile.freezeWarningCelsius() != null
                            && t.compareTo(targetProfile.freezeWarningCelsius()) <= 0);
            boolean isHeat = t.compareTo(targetProfile.maxCelsius()) > 0;
            if (isFreeze) {
                freezeCount++;
            } else if (isHeat) {
                heatCount++;
            } else {
                inRange++;
            }
        }

        int total = matchingObservations.size();
        BigDecimal meanTemp = sumTemp.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal mktCelsius = computeMeanKineticTemperatureCelsius(temps);
        BigDecimal compliancePct = BigDecimal.valueOf(inRange * 100.0 / total).setScale(2, RoundingMode.HALF_UP);

        String disposition;
        if (freezeCount > 0 && targetProfile.freezeSensitive()) {
            disposition = "QUARANTINE_SHAKE_TEST_REQUIRED";
        } else if (mktCelsius.compareTo(targetProfile.maxCelsius().add(new BigDecimal("2.00"))) > 0) {
            disposition = "QUARANTINE_MKT_THERMAL_BREACH";
        } else if (heatCount > 0 || freezeCount > 0) {
            disposition = "HOLD_AND_INSPECT_VVM";
        } else {
            disposition = "WITHIN_SPEC_RELEASED";
        }

        return new ColdChainComplianceReport(
                facilityId,
                cleanSensorId,
                targetProfile,
                total,
                inRange,
                heatCount,
                freezeCount,
                compliancePct,
                minTemp,
                maxTemp,
                meanTemp,
                mktCelsius,
                disposition,
                deviceAlerts
        );
    }

    /**
     * Computes the USP &lt;1079&gt; / Haynes Mean Kinetic Temperature (MKT) in degrees Celsius
     * using activation energy $\Delta H = 83.144\text{ kJ/mol}$ and gas constant $R = 8.3144\times 10^{-3}\text{ kJ/(mol\cdot K)}$.
     */
    public static BigDecimal computeMeanKineticTemperatureCelsius(List<BigDecimal> celsiusReadings) {
        if (celsiusReadings == null || celsiusReadings.isEmpty()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        // deltaH / R = 83.144 / 0.0083144 = 10000.0 K
        final double deltaHOverR = 10_000.0;
        double sumExp = 0.0;
        for (BigDecimal c : celsiusReadings) {
            double kelvin = c.doubleValue() + 273.15;
            if (kelvin <= 1.0) {
                kelvin = 1.0;
            }
            sumExp += Math.exp(-deltaHOverR / kelvin);
        }
        double meanExp = sumExp / celsiusReadings.size();
        double mktKelvin = -deltaHOverR / Math.log(meanExp);
        double mktCelsius = mktKelvin - 273.15;
        return BigDecimal.valueOf(mktCelsius).setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns all active (or all) cold-chain alerts for a facility and optional equipment ID. */
    @Transactional(readOnly = true)
    public List<ColdChainAlert> listAlerts(UUID facilityId, String equipmentOrSensorId, boolean activeOnly) {
        return alertLedger.values().stream()
                .filter(a -> !activeOnly || !a.acknowledged())
                .filter(a -> facilityId == null || Objects.equals(facilityId, a.facilityId()))
                .filter(a -> equipmentOrSensorId == null || equipmentOrSensorId.isBlank()
                        || equipmentOrSensorId.trim().equalsIgnoreCase(a.equipmentOrSensorId()))
                .sorted(Comparator.comparing(ColdChainAlert::triggeredAt).reversed())
                .toList();
    }

    /** Acknowledges an active cold-chain excursion alert and records the pharmacist/engineer resolution action. */
    @ClinicalAudit(action = "COLDCHAIN_ALERT_ACKNOWLEDGE", resourceType = "Observation")
    public ColdChainAlert acknowledgeAlert(UUID alertId, String actorId, String resolutionNotes) {
        if (alertId == null) {
            throw new IllegalArgumentException("alertId is required");
        }
        ColdChainAlert existing = alertLedger.get(alertId);
        if (existing == null) {
            throw new NoSuchElementException("Cold-chain alert not found: " + alertId);
        }
        String resolvedActor = (actorId != null && !actorId.isBlank()) ? actorId.trim() : "coldchain.officer";
        String notes = (resolutionNotes != null && !resolutionNotes.isBlank())
                ? resolutionNotes.trim()
                : "Excursion reviewed and supply batch quarantine disposition recorded.";
        ColdChainAlert updated = new ColdChainAlert(
                existing.alertId(),
                existing.facilityId(),
                existing.observationId(),
                existing.equipmentOrSensorId(),
                existing.supplyBatchCode(),
                existing.supplyName(),
                existing.monitoringStage(),
                existing.alertType(),
                existing.severity(),
                existing.observedTempCelsius(),
                existing.allowedMinCelsius(),
                existing.allowedMaxCelsius(),
                existing.deviationDeltaCelsius(),
                existing.quarantineAction(),
                existing.diagnosticMessage(),
                true,
                resolvedActor,
                notes,
                existing.triggeredAt(),
                Instant.now()
        );
        alertLedger.put(alertId, updated);
        return updated;
    }

    public static BigDecimal normalizeToCelsius(BigDecimal value, String unit) {
        if (value == null) {
            throw new IllegalArgumentException("Temperature value must not be null");
        }
        if (unit == null || unit.isBlank() || "Cel".equalsIgnoreCase(unit.trim()) || "C".equalsIgnoreCase(unit.trim()) || "°C".equalsIgnoreCase(unit.trim())) {
            return value.setScale(2, RoundingMode.HALF_UP);
        }
        String u = unit.trim();
        if ("[degF]".equalsIgnoreCase(u) || "F".equalsIgnoreCase(u) || "°F".equalsIgnoreCase(u) || "degF".equalsIgnoreCase(u)) {
            return value.subtract(new BigDecimal("32"))
                    .divide(new BigDecimal("1.8"), 2, RoundingMode.HALF_UP);
        }
        if ("K".equalsIgnoreCase(u)) {
            return value.subtract(new BigDecimal("273.15")).setScale(2, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("Unsupported temperature unit for cold-chain sensor: " + unit);
    }

    private List<ColdChainAlert> evaluateAndRegisterAlerts(
            UUID facilityId,
            UUID observationId,
            ColdChainSensorReading reading,
            BigDecimal tempCelsius,
            boolean freezeBreach,
            boolean heatBreach,
            boolean criticalHeatBreach,
            Instant triggeredAt) {
        List<ColdChainAlert> alerts = new ArrayList<>();
        SupplyThermalProfile profile = reading.thermalProfile();

        if (freezeBreach) {
            BigDecimal floor = (profile.freezeSensitive() && profile.freezeWarningCelsius() != null
                    && tempCelsius.compareTo(profile.freezeWarningCelsius()) <= 0
                    && tempCelsius.compareTo(profile.minCelsius()) >= 0)
                    ? profile.freezeWarningCelsius()
                    : profile.minCelsius();
            BigDecimal delta = tempCelsius.subtract(floor).setScale(2, RoundingMode.HALF_UP);
            String action = profile.freezeSensitive()
                    ? "QUARANTINE_SHAKE_TEST_REQUIRED"
                    : "HOLD_AND_INSPECT_CONTAINER";
            String msg = "FREEZE EXCURSION on " + reading.equipmentOrSensorId()
                    + " (" + reading.monitoringStage() + "): observed " + tempCelsius + " Cel is below threshold "
                    + floor + " Cel" + (reading.supplyBatchCode() != null ? " for batch " + reading.supplyBatchCode() : "")
                    + ". Action: " + action + ".";
            alerts.add(registerAlert(facilityId, observationId, reading, "FREEZE_EXCURSION", "CRITICAL",
                    tempCelsius, profile.minCelsius(), profile.maxCelsius(), delta, action, msg, triggeredAt));
        } else if (heatBreach) {
            BigDecimal delta = tempCelsius.subtract(profile.maxCelsius()).setScale(2, RoundingMode.HALF_UP);
            String severity = criticalHeatBreach ? "CRITICAL" : "WARNING";
            String action = criticalHeatBreach ? "QUARANTINE_DISCARD_OR_LAB_POTENCY_TEST" : "HOLD_AND_INSPECT_VVM";
            String msg = "HEAT EXCURSION on " + reading.equipmentOrSensorId()
                    + " (" + reading.monitoringStage() + "): observed " + tempCelsius + " Cel exceeds upper limit "
                    + profile.maxCelsius() + " Cel by +" + delta + " Cel"
                    + (reading.supplyBatchCode() != null ? " for batch " + reading.supplyBatchCode() : "")
                    + ". Action: " + action + ".";
            alerts.add(registerAlert(facilityId, observationId, reading,
                    criticalHeatBreach ? "CRITICAL_HEAT_EXCURSION" : "HEAT_EXCURSION",
                    severity, tempCelsius, profile.minCelsius(), profile.maxCelsius(), delta, action, msg, triggeredAt));
        }

        if (reading.doorOpenSeconds() != null && reading.doorOpenSeconds() >= 300) {
            String msg = "DOOR/LID AJAR ALARM on " + reading.equipmentOrSensorId()
                    + ": open for " + reading.doorOpenSeconds() + " seconds (>= 300s limit).";
            alerts.add(registerAlert(facilityId, observationId, reading, "DOOR_AJAR_ALARM", "WARNING",
                    tempCelsius, profile.minCelsius(), profile.maxCelsius(), BigDecimal.ZERO,
                    "CLOSE_DOOR_AND_VERIFY_TEMPERATURE_RECOVERY", msg, triggeredAt));
        }

        if (Boolean.FALSE.equals(reading.mainsPowerConnected())
                && reading.batteryPercent() != null && reading.batteryPercent() < 20) {
            String msg = "CRITICAL POWER FAILURE on " + reading.equipmentOrSensorId()
                    + ": mains power disconnected and backup battery at " + reading.batteryPercent() + "%.";
            alerts.add(registerAlert(facilityId, observationId, reading, "CRITICAL_POWER_FAILURE", "CRITICAL",
                    tempCelsius, profile.minCelsius(), profile.maxCelsius(), BigDecimal.ZERO,
                    "TRANSFER_SUPPLIES_TO_BACKUP_COLD_CHAIN_UNIT", msg, triggeredAt));
        }

        if (reading.monitoringStage() != MonitoringStage.FIXED_STORAGE
                && reading.shockGForce() != null
                && reading.shockGForce().compareTo(new BigDecimal("3.50")) > 0) {
            String msg = "TRANSPORT SHOCK/VIBRATION ALERT on " + reading.equipmentOrSensorId()
                    + ": peak acceleration " + reading.shockGForce() + " g exceeds 3.50 g vial integrity limit.";
            alerts.add(registerAlert(facilityId, observationId, reading, "TRANSPORT_SHOCK_EXCURSION", "WARNING",
                    tempCelsius, profile.minCelsius(), profile.maxCelsius(), BigDecimal.ZERO,
                    "INSPECT_VIALS_FOR_PHYSICAL_DAMAGE", msg, triggeredAt));
        }

        if (reading.humidityPercent() != null && reading.humidityPercent().compareTo(new BigDecimal("75.0")) > 0) {
            String msg = "HIGH HUMIDITY EXCURSION on " + reading.equipmentOrSensorId()
                    + ": relative humidity " + reading.humidityPercent() + "% exceeds 75.0% moisture threshold.";
            alerts.add(registerAlert(facilityId, observationId, reading, "HUMIDITY_EXCURSION", "WARNING",
                    tempCelsius, profile.minCelsius(), profile.maxCelsius(), BigDecimal.ZERO,
                    "INSPECT_DESICCANT_AND_FOIL_PACKAGING", msg, triggeredAt));
        }

        return alerts;
    }

    private ColdChainAlert registerAlert(
            UUID facilityId,
            UUID observationId,
            ColdChainSensorReading reading,
            String alertType,
            String severity,
            BigDecimal tempCelsius,
            BigDecimal minCelsius,
            BigDecimal maxCelsius,
            BigDecimal deltaCelsius,
            String quarantineAction,
            String diagnosticMessage,
            Instant triggeredAt) {
        UUID alertId = UuidV7Generator.nextUuid();
        ColdChainAlert alert = new ColdChainAlert(
                alertId,
                facilityId,
                observationId,
                reading.equipmentOrSensorId().trim(),
                reading.supplyBatchCode(),
                reading.supplyName(),
                reading.monitoringStage(),
                alertType,
                severity,
                tempCelsius,
                minCelsius,
                maxCelsius,
                deltaCelsius,
                quarantineAction,
                diagnosticMessage,
                false,
                null,
                null,
                triggeredAt,
                null
        );
        alertLedger.put(alertId, alert);

        if (outboxRepository != null) {
            TransactionalOutboxEntity event = new TransactionalOutboxEntity();
            event.setFacilityId(facilityId);
            event.setAggregateType("ColdChainObservation");
            event.setAggregateId(observationId);
            event.setEventType("ColdChainExcursionAlertEvent");
            event.setPayloadJson("{\"alertId\":\"" + alertId
                    + "\",\"observationId\":\"" + observationId
                    + "\",\"equipmentId\":\"" + reading.equipmentOrSensorId().trim()
                    + "\",\"alertType\":\"" + alertType
                    + "\",\"severity\":\"" + severity
                    + "\",\"tempCelsius\":" + tempCelsius
                    + ",\"quarantineAction\":\"" + quarantineAction + "\"}");
            outboxRepository.save(event);
        }
        return alert;
    }

    private static String resolveBatchDisposition(
            boolean freezeBreach,
            boolean heatBreach,
            boolean criticalHeatBreach,
            List<ColdChainAlert> alerts) {
        if (freezeBreach) {
            return "QUARANTINE_SHAKE_TEST_REQUIRED";
        }
        if (criticalHeatBreach) {
            return "QUARANTINE_DISCARD_OR_LAB_POTENCY_TEST";
        }
        if (heatBreach) {
            return "HOLD_AND_INSPECT_VVM";
        }
        if (!alerts.isEmpty()) {
            return "HOLD_ENVIRONMENTAL_INSPECTION";
        }
        return "WITHIN_SPEC_RELEASED";
    }

    private String buildSensorComponentsJson(ColdChainSensorReading reading) {
        Observation shell = new Observation();
        shell.setStatus(Observation.ObservationStatus.FINAL);

        if (reading.humidityPercent() != null) {
            Observation.ObservationComponentComponent comp = shell.addComponent();
            comp.setCode(new CodeableConcept().addCoding(new Coding(LOINC_SYSTEM, LOINC_RELATIVE_HUMIDITY, "Relative humidity")));
            comp.setValue(new Quantity().setValue(reading.humidityPercent()).setUnit("%").setSystem(UCUM_SYSTEM).setCode("%"));
        }
        if (reading.doorOpenSeconds() != null) {
            Observation.ObservationComponentComponent comp = shell.addComponent();
            comp.setCode(new CodeableConcept().setText("Door or lid open duration"));
            comp.setValue(new Quantity().setValue(reading.doorOpenSeconds()).setUnit("s").setSystem(UCUM_SYSTEM).setCode("s"));
        }
        if (reading.batteryPercent() != null) {
            Observation.ObservationComponentComponent comp = shell.addComponent();
            comp.setCode(new CodeableConcept().setText("Sensor backup battery charge"));
            comp.setValue(new Quantity().setValue(reading.batteryPercent()).setUnit("%").setSystem(UCUM_SYSTEM).setCode("%"));
        }
        if (reading.shockGForce() != null) {
            Observation.ObservationComponentComponent comp = shell.addComponent();
            comp.setCode(new CodeableConcept().setText("Transport peak acceleration shock"));
            comp.setValue(new Quantity().setValue(reading.shockGForce()).setUnit("[g]").setSystem(UCUM_SYSTEM).setCode("[g]"));
        }
        if (reading.supplyBatchCode() != null && !reading.supplyBatchCode().isBlank()) {
            Observation.ObservationComponentComponent comp = shell.addComponent();
            comp.setCode(new CodeableConcept().setText("Monitored vaccine or drug batch lot"));
            comp.setValue(new StringType(reading.supplyBatchCode().trim()));
        }
        if (shell.getComponent().isEmpty()) {
            return null;
        }
        return observationService.encodeFhirResource(shell);
    }
}
