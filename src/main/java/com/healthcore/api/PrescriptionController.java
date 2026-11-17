package com.healthcore.api;

import com.healthcore.rx.PatientAllergyEntity;
import com.healthcore.rx.PrescriptionEntity;
import com.healthcore.rx.PrescriptionRepository;
import com.healthcore.rx.PrescriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rx")
public class PrescriptionController {

    private final PrescriptionService prescriptionService;
    private final PrescriptionRepository prescriptionRepository;

    public PrescriptionController(PrescriptionService prescriptionService, PrescriptionRepository prescriptionRepository) {
        this.prescriptionService = prescriptionService;
        this.prescriptionRepository = prescriptionRepository;
    }

    @PostMapping("/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN', 'ADMIN')")
    public PrescriptionEntity issue(@Valid @RequestBody IssueRxRequest req) {
        return prescriptionService.issuePrescription(new PrescriptionService.IssuePrescriptionCommand(
                req.encounterId(),
                req.patientId(),
                req.prescriberId(),
                req.medicationCode(),
                req.medicationName(),
                req.dosage(),
                req.frequency(),
                req.durationDays(),
                req.quantity(),
                req.instructions(),
                req.allergyOverrideReason()
        ));
    }

    @GetMapping("/patients/{patientId}/prescriptions")
    @PreAuthorize("hasAnyRole('CLINICIAN', 'PHARMACIST', 'ADMIN')")
    public List<PrescriptionEntity> history(@PathVariable UUID patientId) {
        return prescriptionRepository.findByPatientIdOrderByPrescribedAtDesc(patientId);
    }

    @PostMapping("/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN', 'NURSE', 'ADMIN')")
    public PatientAllergyEntity addAllergy(@Valid @RequestBody AddAllergyRequest req) {
        return prescriptionService.recordAllergy(
                req.patientId(), req.substanceCode(), req.substanceName(), req.reaction(), req.severity()
        );
    }

    public record IssueRxRequest(
            @NotNull UUID encounterId,
            @NotNull UUID patientId,
            @NotBlank String prescriberId,
            String medicationCode,
            @NotBlank String medicationName,
            @NotBlank String dosage,
            @NotBlank String frequency,
            @Min(1) int durationDays,
            @Min(1) int quantity,
            String instructions,
            String allergyOverrideReason
    ) {}

    public record AddAllergyRequest(
            @NotNull UUID patientId,
            String substanceCode,
            @NotBlank String substanceName,
            String reaction,
            @NotBlank String severity
    ) {}
}
