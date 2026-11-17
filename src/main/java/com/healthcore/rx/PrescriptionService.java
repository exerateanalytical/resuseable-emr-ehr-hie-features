package com.healthcore.rx;

import com.healthcore.audit.ClinicalAudit;
import com.healthcore.tenant.FacilityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PrescriptionService {

    private final PrescriptionRepository prescriptionRepository;
    private final PatientAllergyRepository allergyRepository;

    public PrescriptionService(PrescriptionRepository prescriptionRepository, PatientAllergyRepository allergyRepository) {
        this.prescriptionRepository = prescriptionRepository;
        this.allergyRepository = allergyRepository;
    }

    @Transactional
    @ClinicalAudit(action = "CREATE", resourceType = "PRESCRIPTION")
    public PrescriptionEntity issuePrescription(IssuePrescriptionCommand cmd) {
        UUID facilityId = FacilityContext.requireFacilityId();
        List<PatientAllergyEntity> activeAllergies = allergyRepository.findByPatientIdAndStatus(cmd.patientId(), "ACTIVE");

        String medLower = cmd.medicationName().toLowerCase(Locale.ROOT);
        for (PatientAllergyEntity allergy : activeAllergies) {
            String substanceLower = allergy.getSubstanceName().toLowerCase(Locale.ROOT);
            boolean codeMatch = cmd.medicationCode() != null && cmd.medicationCode().equalsIgnoreCase(allergy.getSubstanceCode());
            boolean nameMatch = medLower.contains(substanceLower) || substanceLower.contains(medLower);
            if ((codeMatch || nameMatch) && (cmd.allergyOverrideReason() == null || cmd.allergyOverrideReason().isBlank())) {
                throw new IllegalArgumentException(
                        "Allergy conflict detected with " + allergy.getSubstanceName() + " (" + allergy.getSeverity() + "). Provide clinical override reason to proceed.");
            }
        }

        PrescriptionEntity rx = new PrescriptionEntity();
        rx.setEncounterId(cmd.encounterId());
        rx.setPatientId(cmd.patientId());
        rx.setFacilityId(facilityId);
        rx.setPrescriberId(cmd.prescriberId());
        rx.setMedicationCode(cmd.medicationCode());
        rx.setMedicationName(cmd.medicationName());
        rx.setDosage(cmd.dosage());
        rx.setFrequency(cmd.frequency());
        rx.setDurationDays(cmd.durationDays());
        rx.setQuantity(cmd.quantity());
        rx.setInstructions(cmd.instructions());
        rx.setAllergyOverrideReason(cmd.allergyOverrideReason());
        return prescriptionRepository.save(rx);
    }

    @Transactional
    @ClinicalAudit(action = "CREATE", resourceType = "PATIENT_ALLERGY")
    public PatientAllergyEntity recordAllergy(UUID patientId, String substanceCode, String substanceName, String reaction, String severity) {
        PatientAllergyEntity allergy = new PatientAllergyEntity();
        allergy.setPatientId(patientId);
        allergy.setSubstanceCode(substanceCode);
        allergy.setSubstanceName(substanceName);
        allergy.setReaction(reaction);
        allergy.setSeverity(severity.toUpperCase(Locale.ROOT));
        return allergyRepository.save(allergy);
    }

    public record IssuePrescriptionCommand(
            UUID encounterId,
            UUID patientId,
            String prescriberId,
            String medicationCode,
            String medicationName,
            String dosage,
            String frequency,
            int durationDays,
            int quantity,
            String instructions,
            String allergyOverrideReason
    ) {}
}
