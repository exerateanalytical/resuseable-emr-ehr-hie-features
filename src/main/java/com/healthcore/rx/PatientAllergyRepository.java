package com.healthcore.rx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatientAllergyRepository extends JpaRepository<PatientAllergyEntity, UUID> {
    List<PatientAllergyEntity> findByPatientIdAndStatus(UUID patientId, String status);
}
