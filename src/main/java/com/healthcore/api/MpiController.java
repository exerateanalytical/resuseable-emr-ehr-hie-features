package com.healthcore.api;

import com.healthcore.mpi.MpiService;
import com.healthcore.mpi.PatientEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mpi/patients")
public class MpiController {

    private final MpiService mpiService;

    public MpiController(MpiService mpiService) {
        this.mpiService = mpiService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public PatientEntity register(@Valid @RequestBody RegisterPatientRequest req) {
        return mpiService.registerPatient(new MpiService.RegisterPatientCommand(
                req.givenName(),
                req.familyName(),
                req.dateOfBirth(),
                req.sex(),
                req.primaryPhone(),
                req.city(),
                req.addressLine(),
                req.bloodGroup(),
                req.nationalId(),
                req.facilityId(),
                req.facilityMrn()
        ));
    }

    @GetMapping("/match")
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public List<PatientEntity> matchCandidates(
            @RequestParam @NotBlank String fullName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(required = false) String phone
    ) {
        return mpiService.findCandidates(fullName, dob, phone);
    }

    @GetMapping("/{patientId}")
    @PreAuthorize("hasAnyRole('CLINICIAN', 'REGISTRAR', 'ADMIN')")
    public PatientEntity getById(@PathVariable UUID patientId) {
        return mpiService.getPatient(patientId);
    }

    public record RegisterPatientRequest(
            @NotBlank String givenName,
            @NotBlank String familyName,
            @NotNull LocalDate dateOfBirth,
            @NotBlank String sex,
            String primaryPhone,
            String city,
            String addressLine,
            String bloodGroup,
            String nationalId,
            UUID facilityId,
            String facilityMrn
    ) {}
}
