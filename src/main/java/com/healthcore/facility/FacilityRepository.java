package com.healthcore.facility;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FacilityRepository extends JpaRepository<FacilityEntity, UUID> {
    Optional<FacilityEntity> findByCode(String code);
}
