package com.healthcore.api;

import com.healthcore.emr.EncounterEntity;
import com.healthcore.emr.EncounterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/emr")
public class EncounterController {

    private final EncounterService encounterService;

    public EncounterController(EncounterService encounterService) {
        this.encounterService = encounterService;
    }

    @PostMapping("/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN', 'ADMIN')")
    public EncounterEntity recordEncounter(@Valid @RequestBody RecordEncounterRequest req) {
        List<EncounterService.ObservationInput> obs = req.observations() == null ? List.of() :
                req.observations().stream()
                        .map(o -> new EncounterService.ObservationInput(
                                o.loincCode(), o.displayName(), o.valueNumeric(),
                                o.valueText(), o.unit(), o.interpretation()
                        ))
                        .toList();

        return encounterService.recordEncounterAndQueueHieSync(new EncounterService.RecordEncounterCommand(
                req.patientId(),
                req.clinicianId(),
                req.encounterType(),
                req.chiefComplaint(),
                req.soapNotes(),
                req.diagnosisCodes(),
                obs
        ));
    }

    @GetMapping("/patients/{patientId}/summary")
    @PreAuthorize("hasAnyRole('CLINICIAN', 'ADMIN')")
    public EncounterService.LongitudinalSummary getLongitudinalSummary(
            @PathVariable UUID patientId,
            @RequestHeader(value = "X-Break-Glass-Reason", required = false) String breakGlassReason
    ) {
        return encounterService.getCrossFacilityPatientSummary(patientId, breakGlassReason);
    }

    public record ObservationDto(
            @NotBlank String loincCode,
            @NotBlank String displayName,
            BigDecimal valueNumeric,
            String valueText,
            String unit,
            String interpretation
    ) {}

    public record RecordEncounterRequest(
            @NotNull UUID patientId,
            @NotBlank String clinicianId,
            @NotBlank String encounterType,
            String chiefComplaint,
            Map<String, Object> soapNotes,
            List<String> diagnosisCodes,
            List<ObservationDto> observations
    ) {}
}
