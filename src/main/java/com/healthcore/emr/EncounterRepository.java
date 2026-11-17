package com.healthcore.emr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EncounterRepository extends JpaRepository<EncounterEntity, UUID> {
    List<EncounterEntity> findByPatientIdOrderByStartedAtDesc(UUID patientId);
    List<EncounterEntity> findByFacilityIdAndPatientIdOrderByStartedAtDesc(UUID facilityId, UUID patientId);
}
