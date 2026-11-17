package com.healthcore.mpi;

import com.healthcore.audit.ClinicalAudit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class MpiService {

    private final PatientRepository patientRepository;
    private final PatientIdentifierRepository identifierRepository;

    public MpiService(PatientRepository patientRepository, PatientIdentifierRepository identifierRepository) {
        this.patientRepository = patientRepository;
        this.identifierRepository = identifierRepository;
    }

    @Transactional
    @ClinicalAudit(action = "CREATE", resourceType = "PATIENT")
    public PatientEntity registerPatient(RegisterPatientCommand cmd) {
        if (cmd.nationalId() != null && !cmd.nationalId().isBlank()) {
            var existing = patientRepository.findByIdentifier("NATIONAL_ID", cmd.nationalId().trim());
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        PatientEntity patient = new PatientEntity();
        patient.setEnterpriseId("PAT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        patient.setGivenName(cmd.givenName().trim());
        patient.setFamilyName(cmd.familyName().trim());
        patient.setDateOfBirth(cmd.dateOfBirth());
        patient.setSex(cmd.sex());
        patient.setPrimaryPhone(cmd.primaryPhone());
        patient.setCity(cmd.city());
        patient.setAddressLine(cmd.addressLine());
        patient.setBloodGroup(cmd.bloodGroup());
        PatientEntity saved = patientRepository.save(patient);

        if (cmd.nationalId() != null && !cmd.nationalId().isBlank()) {
            linkIdentifier(saved.getId(), null, "NATIONAL_ID", cmd.nationalId().trim(), true);
        }
        if (cmd.facilityMrn() != null && !cmd.facilityMrn().isBlank() && cmd.facilityId() != null) {
            linkIdentifier(saved.getId(), cmd.facilityId(), "MRN", cmd.facilityMrn().trim(), false);
        }
        return saved;
    }

    @Transactional
    public void linkIdentifier(UUID patientId, UUID facilityId, String idType, String idValue, boolean primary) {
        PatientIdentifierEntity id = new PatientIdentifierEntity();
        id.setPatientId(patientId);
        id.setFacilityId(facilityId);
        id.setIdType(idType);
        id.setIdValue(idValue);
        id.setPrimary(primary);
        identifierRepository.save(id);
    }

    @Transactional(readOnly = true)
    public List<PatientEntity> findCandidates(String fullName, LocalDate dob, String phone) {
        return patientRepository.fuzzyMatchCandidates(fullName, dob, phone);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "READ", resourceType = "PATIENT")
    public PatientEntity getPatient(UUID patientId) {
        return patientRepository.findById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Patient not found: " + patientId));
    }

    public record RegisterPatientCommand(
            String givenName,
            String familyName,
            LocalDate dateOfBirth,
            String sex,
            String primaryPhone,
            String city,
            String addressLine,
            String bloodGroup,
            String nationalId,
            UUID facilityId,
            String facilityMrn
    ) {}
}
