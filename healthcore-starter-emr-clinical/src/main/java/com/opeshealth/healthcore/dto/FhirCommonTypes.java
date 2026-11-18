/*
 * =============================================================================
 * Opes HealthCore SDK — Shared HL7 FHIR R4 DTO Building Blocks
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable Java 25 Jackson-serializable HL7 FHIR R4 complex datatype DTOs shared
 * across {@link PractitionerDto}, {@link EncounterDto}, and {@link ObservationDto}.
 */
public final class FhirCommonTypes {

    private FhirCommonTypes() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirMetaDto(
            String versionId,
            Instant lastUpdated,
            List<String> profile
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirCodingDto(
            String system,
            String code,
            String display
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirCodeableConceptDto(
            List<FhirCodingDto> coding,
            String text
    ) {
        public static FhirCodeableConceptDto of(String system, String code, String display) {
            return new FhirCodeableConceptDto(List.of(new FhirCodingDto(system, code, display)), display);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirIdentifierDto(
            String use,
            FhirCodeableConceptDto type,
            String system,
            String value,
            String assignerDisplay
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirHumanNameDto(
            String use,
            String text,
            String family,
            List<String> given,
            List<String> prefix,
            List<String> suffix
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirContactPointDto(
            String system,
            String value,
            String use,
            Integer rank
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirAddressDto(
            String use,
            List<String> line,
            String city,
            String district,
            String state,
            String postalCode,
            String country
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirReferenceDto(
            String reference,
            String type,
            FhirIdentifierDto identifier,
            String display
    ) {
        public static FhirReferenceDto toResource(String resourceType, String id, String display) {
            return new FhirReferenceDto(resourceType + "/" + id, resourceType, null, display);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirPeriodDto(
            Instant start,
            Instant end
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirQuantityDto(
            BigDecimal value,
            String unit,
            String system,
            String code
    ) {
        public static FhirQuantityDto ucum(BigDecimal value, String unit, String ucumCode) {
            return new FhirQuantityDto(value, unit, "http://unitsofmeasure.org", ucumCode);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FhirReferenceRangeDto(
            FhirQuantityDto low,
            FhirQuantityDto high,
            String text
    ) {}
}
