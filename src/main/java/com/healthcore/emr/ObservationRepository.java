package com.healthcore.emr;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ObservationRepository extends JpaRepository<ObservationEntity, UUID> {
    List<ObservationEntity> findByEncounterId(UUID encounterId);
    List<ObservationEntity> findByPatientIdOrderByObservedAtDesc(UUID patientId);
}
