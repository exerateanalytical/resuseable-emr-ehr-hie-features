package com.healthcore.mpi;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PatientIdentifierRepository extends JpaRepository<PatientIdentifierEntity, UUID> {
    List<PatientIdentifierEntity> findByPatientId(UUID patientId);
}
