package com.healthcore.fhir.patient;

import com.healthcore.mpi.PatientEntity;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface FhirPatientRepository extends JpaRepository<PatientEntity, UUID>, JpaSpecificationExecutor<PatientEntity> {

    Optional<PatientEntity> findByEnterpriseId(String enterpriseId);

    @Query("""
            SELECT DISTINCT p FROM PatientEntity p
            JOIN PatientIdentifierEntity pi ON pi.patientId = p.id
            WHERE (:systemUri IS NULL OR pi.systemUri = :systemUri)
              AND pi.idValue = :idValue
            """)
    Optional<PatientEntity> findBySystemAndIdentifierValue(
            @Param("systemUri") String systemUri,
            @Param("idValue") String idValue
    );

    static Specification<PatientEntity> withFhirSearchParams(
            String family,
            String given,
            LocalDate birthdate,
            String gender,
            String phone,
            String city,
            String identifier,
            Boolean active
    ) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();

            if (family != null && !family.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("familyName")), family.trim().toLowerCase() + "%"));
            }
            if (given != null && !given.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("givenName")), given.trim().toLowerCase() + "%"));
            }
            if (birthdate != null) {
                predicates.add(cb.equal(root.get("dateOfBirth"), birthdate));
            }
            if (gender != null && !gender.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("sex")), gender.trim().toUpperCase()));
            }
            if (phone != null && !phone.isBlank()) {
                query.distinct(true);
                var telecomJoin = root.join("telecoms", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.equal(root.get("primaryPhone"), phone.trim()),
                        cb.equal(telecomJoin.get("value"), phone.trim())
                ));
            }
            if (city != null && !city.isBlank()) {
                query.distinct(true);
                var addressJoin = root.join("addresses", JoinType.LEFT);
                String pattern = "%" + city.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("city")), pattern),
                        cb.like(cb.lower(addressJoin.get("city")), pattern)
                ));
            }
            if (identifier != null && !identifier.isBlank()) {
                predicates.add(cb.equal(root.get("enterpriseId"), identifier.trim()));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
