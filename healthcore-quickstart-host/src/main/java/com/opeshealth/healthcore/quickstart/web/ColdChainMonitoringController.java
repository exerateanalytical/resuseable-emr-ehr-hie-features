/*
 * =============================================================================
 * Opes HealthCore SDK — Cold-Chain Telemetry & Excursion Alerting Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.web;

import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.ColdChainAlert;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.ColdChainComplianceReport;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.ColdChainSensorReading;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.ColdChainTelemetryReceipt;
import com.opeshealth.healthcore.quickstart.service.ColdChainMonitoringService.SupplyThermalProfile;
import com.opeshealth.healthcore.quickstart.service.ObservationService;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * REST and HL7 FHIR R4 controller for vaccine, biologic, and pharmaceutical
 * cold-chain storage &amp; transport monitoring backed by {@link ColdChainMonitoringService}
 * and {@link ObservationService}.
 */
@RestController
@RequestMapping("/api/v1/cold-chain")
@Tag(
        name = "FHIR R4 Cold-Chain & Vaccine Sensor Telemetry",
        description = "Vaccine and pharmaceutical cold-chain storage & transport telemetry integrated with HL7 FHIR R4 Observation, automated excursion alerting, and Mean Kinetic Temperature (MKT) compliance"
)
public class ColdChainMonitoringController {

    private static final String FHIR_JSON = "application/fhir+json";

    public record AlertAcknowledgePayload(
            String actorId,
            String resolutionNotes
    ) {}

    private final ColdChainMonitoringService coldChainService;
    private final ObservationService observationService;

    public ColdChainMonitoringController(
            ColdChainMonitoringService coldChainService,
            ObservationService observationService) {
        this.coldChainService = Objects.requireNonNull(coldChainService, "ColdChainMonitoringService is required");
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
    }

    @PostMapping(
            value = "/telemetry",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, FHIR_JSON}
    )
    @Operation(summary = "Record a cold-chain temperature and sensor reading, persist a FHIR R4 Observation, and evaluate excursion alerts")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cold-chain telemetry persisted as FHIR R4 Observation and alerts evaluated"),
            @ApiResponse(responseCode = "400", description = "Invalid sensor reading payload")
    })
    @ClinicalAudit(action = "COLDCHAIN_TELEMETRY_INGEST", resourceType = "Observation")
    public ResponseEntity<?> ingestSensorTelemetry(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @RequestParam(name = "format", defaultValue = "json") String format,
            @RequestBody ColdChainSensorReading reading,
            HttpServletResponse response) {
        UUID resolvedFacility = reading.facilityId() != null
                ? reading.facilityId()
                : (facilityHeader != null ? facilityHeader : FacilityContext.getFacilityId().orElse(new UUID(0L, 1L)));

        ColdChainSensorReading scopedReading = new ColdChainSensorReading(
                resolvedFacility,
                reading.equipmentOrSensorId(),
                reading.supplyBatchCode(),
                reading.supplyName(),
                reading.thermalProfile(),
                reading.monitoringStage(),
                reading.temperatureValue(),
                reading.temperatureUnit(),
                reading.humidityPercent(),
                reading.doorOpenSeconds(),
                reading.mainsPowerConnected(),
                reading.batteryPercent(),
                reading.shockGForce(),
                reading.routeOrLocationLabel(),
                reading.recordedAt()
        );

        ColdChainTelemetryReceipt receipt = coldChainService.recordSensorTelemetry(scopedReading);
        if (response != null) {
            response.setHeader("ETag", "W/\"1\"");
            response.setHeader("Location", "/api/v1/observations/" + receipt.observationId());
        }

        if ("fhir".equalsIgnoreCase(format)
                || (acceptHeader != null && acceptHeader.toLowerCase(Locale.ROOT).contains(FHIR_JSON))) {
            String fhirJson = observationService.encodeFhirResource(receipt.fhirObservation());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.parseMediaType(FHIR_JSON))
                    .body(fhirJson);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("observationId", receipt.observationId());
        body.put("fhirObservationSubject", receipt.fhirObservationSubject());
        body.put("normalizedTempCelsius", receipt.normalizedTempCelsius());
        body.put("fhirInterpretationCode", receipt.fhirInterpretationCode());
        body.put("fhirInterpretationDisplay", receipt.fhirInterpretationDisplay());
        body.put("excursionDetected", receipt.excursionDetected());
        body.put("batchDispositionStatus", receipt.batchDispositionStatus());
        body.put("triggeredAlertCount", receipt.triggeredAlerts().size());
        body.put("triggeredAlerts", receipt.triggeredAlerts());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping(value = "/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List active or historical cold-chain thermal and environmental excursion alerts")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cold-chain excursion alerts returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized request")
    })
    @ClinicalAudit(action = "COLDCHAIN_ALERTS_LIST", resourceType = "Observation")
    public ResponseEntity<Map<String, Object>> listAlerts(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestParam(name = "facilityId", required = false) UUID facilityParam,
            @RequestParam(name = "equipmentOrSensorId", required = false) String equipmentOrSensorId,
            @RequestParam(name = "activeOnly", defaultValue = "true") boolean activeOnly,
            HttpServletResponse response) {
        UUID resolvedFacility = facilityParam != null ? facilityParam : facilityHeader;
        List<ColdChainAlert> alerts = coldChainService.listAlerts(resolvedFacility, equipmentOrSensorId, activeOnly);
        if (response != null) {
            response.setHeader("ETag", "W/\"" + alerts.size() + "\"");
        }
        return ResponseEntity.ok(Map.of(
                "total", alerts.size(),
                "activeOnly", activeOnly,
                "alerts", alerts
        ));
    }

    @PostMapping(
            value = "/alerts/{alertId}/acknowledge",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Acknowledge a cold-chain excursion alert and record quarantine disposition notes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Alert acknowledged and quarantine action logged"),
            @ApiResponse(responseCode = "404", description = "Alert not found")
    })
    @ClinicalAudit(action = "COLDCHAIN_ALERT_ACK", resourceType = "Observation")
    public ResponseEntity<ColdChainAlert> acknowledgeAlert(
            @PathVariable UUID alertId,
            @RequestBody(required = false) AlertAcknowledgePayload payload,
            HttpServletResponse response) {
        String actor = payload != null ? payload.actorId() : null;
        String notes = payload != null ? payload.resolutionNotes() : null;
        ColdChainAlert updated = coldChainService.acknowledgeAlert(alertId, actor, notes);
        if (response != null) {
            response.setHeader("ETag", "W/\"2\"");
        }
        return ResponseEntity.ok(updated);
    }

    @GetMapping(
            value = "/compliance/{equipmentOrSensorId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(summary = "Evaluate cold-chain storage or transport compliance and Mean Kinetic Temperature (MKT) from persisted FHIR Observations")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cold-chain MKT and compliance report computed"),
            @ApiResponse(responseCode = "400", description = "Invalid equipment ID or thermal profile")
    })
    @ClinicalAudit(action = "COLDCHAIN_COMPLIANCE_REPORT", resourceType = "Observation")
    public ResponseEntity<ColdChainComplianceReport> evaluateCompliance(
            @PathVariable String equipmentOrSensorId,
            @RequestHeader(value = "X-Facility-Id", required = false) UUID facilityHeader,
            @RequestParam(name = "facilityId", required = false) UUID facilityParam,
            @RequestParam(name = "profile", defaultValue = "WHO_STANDARD_REFRIGERATED_2C_8C") SupplyThermalProfile profile,
            HttpServletResponse response) {
        UUID resolvedFacility = facilityParam != null ? facilityParam : facilityHeader;
        ColdChainComplianceReport report = coldChainService.evaluateEquipmentOrShipmentCompliance(
                resolvedFacility, equipmentOrSensorId, profile);
        if (response != null) {
            response.setHeader("ETag", "W/\"" + report.totalObservationsAnalyzed() + "\"");
        }
        return ResponseEntity.ok(report);
    }
}
