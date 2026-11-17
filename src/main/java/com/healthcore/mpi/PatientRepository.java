package com.healthcore.mpi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<PatientEntity, UUID> {

    Optional<PatientEntity> findByEnterpriseId(String enterpriseId);

    @Query("""
            SELECT p FROM PatientEntity p
            JOIN PatientIdentifierEntity pi ON pi.patientId = p.id
            WHERE pi.idType = :idType AND pi.idValue = :idValue
            """)
    Optional<PatientEntity> findByIdentifier(@Param("idType") String idType, @Param("idValue") String idValue);

    @Query(value = """
            SELECT * FROM patients p
            WHERE p.date_of_birth = :dob
              AND (
                similarity(p.given_name || ' ' || p.family_name, :fullName) > 0.35
                OR (:phone IS NOT NULL AND p.primary_phone = :phone)
              )
            ORDER BY similarity(p.given_name || ' ' || p.family_name, :fullName) DESC
            LIMIT 10
            """, nativeQuery = true)
    List<PatientEntity> fuzzyMatchCandidates(
            @Param("fullName") String fullName,
            @Param("dob") LocalDate dob,
            @Param("phone") String phone
    );
}
