/*
 * =============================================================================
 * Opes HealthCore SDK — Spring GraphQL FHIR R4 API Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.graphql;

import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.PractitionerEntity;
import com.opeshealth.healthcore.quickstart.graphql.FhirGraphQlTypes.*;
import com.opeshealth.healthcore.quickstart.service.CdsHooksService;
import com.opeshealth.healthcore.quickstart.service.EncounterService;
import com.opeshealth.healthcore.quickstart.service.EncounterService.EncounterSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.EncounterService.EncounterSearchResult;
import com.opeshealth.healthcore.quickstart.service.ObservationService;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchResult;
import com.opeshealth.healthcore.quickstart.service.PractitionerService;
import com.opeshealth.healthcore.quickstart.service.PractitionerService.PractitionerSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.PractitionerService.PractitionerSearchResult;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.service.PatientService.PatientSearchCriteria;
import com.opeshealth.healthcore.service.PatientService.PatientSearchResult;
import com.opeshealth.healthcore.service.PatientService.StringSearchFilter;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Spring GraphQL controller providing flexible FHIR R4 querying, nested cross-resource
 * graph traversal ({@code Patient -> Encounters -> Observations}), and validated mutations
 * alongside the existing REST endpoints at {@code /graphql}.
 */
@Controller
public class FhirGraphQlController {

    private final PatientService patientService;
    private final PractitionerService practitionerService;
    private final EncounterService encounterService;
    private final ObservationService observationService;
    private final CdsHooksService cdsHooksService;

    public FhirGraphQlController(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService) {
        this(patientService, practitionerService, encounterService, observationService, (CdsHooksService) null);
    }

    @Autowired
    public FhirGraphQlController(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            ObjectProvider<CdsHooksService> cdsHooksServiceProvider) {
        this(
                patientService,
                practitionerService,
                encounterService,
                observationService,
                cdsHooksServiceProvider != null ? cdsHooksServiceProvider.getIfAvailable() : null
        );
    }

    public FhirGraphQlController(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService,
            CdsHooksService cdsHooksService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.practitionerService = Objects.requireNonNull(practitionerService, "PractitionerService is required");
        this.encounterService = Objects.requireNonNull(encounterService, "EncounterService is required");
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
        this.cdsHooksService = cdsHooksService;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    public GqlPatient patient(@Argument UUID id) {
        PatientAggregateEntity entity = patientService.getPatientById(id);
        Patient fhir = patientService.getFhirPatientById(entity.getId());
        return FhirGraphQlTypes.fromFhirPatient(fhir, entity.getFacilityId());
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    public GqlPatientConnection patients(
            @Argument PatientFilterInput filter,
            @Argument Integer offset,
            @Argument Integer count) {
        PatientFilterInput f = filter != null
                ? filter
                : new PatientFilterInput(null, null, null, null, null, null, null, null, null, null, null, null, null);
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(f.facilityId());
        List<StringSearchFilter> nameFilters = new ArrayList<>();
        if (f.name() != null && !f.name().isBlank()) nameFilters.add(StringSearchFilter.prefix(f.name()));
        if (f.nameContains() != null && !f.nameContains().isBlank()) nameFilters.add(StringSearchFilter.contains(f.nameContains()));

        PatientSearchCriteria criteria = new PatientSearchCriteria(
                f.id(),
                scopedFacility,
                f.identifier(),
                nameFilters,
                f.family() != null && !f.family().isBlank() ? List.of(StringSearchFilter.prefix(f.family())) : List.of(),
                f.given() != null && !f.given().isBlank() ? List.of(StringSearchFilter.prefix(f.given())) : List.of(),
                List.of(),
                f.birthdateFrom(),
                f.birthdateTo(),
                f.gender(),
                f.phone(),
                f.email(),
                f.active(),
                offset != null ? offset : 0,
                count != null ? count : 20
        );
        PatientSearchResult result = patientService.searchPatients(criteria);
        List<GqlPatient> items = result.patients().stream()
                .map(p -> FhirGraphQlTypes.fromFhirPatient(
                        patientService.getFhirPatientById(p.getId()), p.getFacilityId()))
                .toList();
        return new GqlPatientConnection(result.total(), result.offset(), result.count(), items);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    public GqlPractitioner practitioner(@Argument UUID id) {
        PractitionerEntity entity = practitionerService.getPractitionerById(id);
        return FhirGraphQlTypes.fromFhirPractitioner(
                PractitionerService.toFhirPractitioner(entity), entity.getFacilityId());
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    public GqlPractitionerConnection practitioners(
            @Argument PractitionerFilterInput filter,
            @Argument Integer offset,
            @Argument Integer count) {
        PractitionerFilterInput f = filter != null
                ? filter
                : new PractitionerFilterInput(null, null, null, null, null, null, null, null, null);
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(f.facilityId());
        PractitionerSearchCriteria criteria = new PractitionerSearchCriteria(
                f.id(),
                scopedFacility,
                f.identifier(),
                f.name() != null && !f.name().isBlank() ? List.of(StringSearchFilter.prefix(f.name())) : List.of(),
                f.family() != null && !f.family().isBlank() ? List.of(StringSearchFilter.prefix(f.family())) : List.of(),
                f.given() != null && !f.given().isBlank() ? List.of(StringSearchFilter.prefix(f.given())) : List.of(),
                f.gender(),
                f.qualification(),
                f.active(),
                offset != null ? offset : 0,
                count != null ? count : 20
        );
        PractitionerSearchResult result = practitionerService.searchPractitioners(criteria);
        List<GqlPractitioner> items = result.practitioners().stream()
                .map(p -> FhirGraphQlTypes.fromFhirPractitioner(
                        PractitionerService.toFhirPractitioner(p), p.getFacilityId()))
                .toList();
        return new GqlPractitionerConnection(result.total(), result.offset(), result.count(), items);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public GqlEncounter encounter(@Argument UUID id) {
        return FhirGraphQlTypes.fromFhirEncounter(encounterService.getFhirEncounterById(id));
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public GqlEncounterConnection encounters(
            @Argument EncounterFilterInput filter,
            @Argument Integer offset,
            @Argument Integer count) {
        EncounterFilterInput f = filter != null
                ? filter
                : new EncounterFilterInput(null, null, null, null, null, null, null, null, null, null);
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(f.facilityId());
        EncounterSearchCriteria criteria = new EncounterSearchCriteria(
                f.id(),
                scopedFacility,
                f.patient(),
                f.status(),
                f.encounterClass(),
                f.practitioner(),
                f.location(),
                f.reasonCode(),
                List.of(),
                f.dateFrom(),
                f.dateTo(),
                offset != null ? offset : 0,
                count != null ? count : 20
        );
        EncounterSearchResult result = encounterService.searchEncounters(criteria);
        List<GqlEncounter> items = result.encounters().stream()
                .map(FhirGraphQlTypes::fromEncounterEntity)
                .toList();
        return new GqlEncounterConnection(result.total(), result.offset(), result.count(), items);
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    public GqlObservation observation(@Argument UUID id) {
        return FhirGraphQlTypes.fromFhirObservation(observationService.getFhirObservationById(id));
    }

    @QueryMapping
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    public GqlObservationConnection observations(
            @Argument ObservationFilterInput filter,
            @Argument Integer offset,
            @Argument Integer count) {
        ObservationFilterInput f = filter != null
                ? filter
                : new ObservationFilterInput(null, null, null, null, null, null, null, null, null, null, null, null);
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(f.facilityId());
        ObservationSearchCriteria criteria = new ObservationSearchCriteria(
                f.id(),
                scopedFacility,
                f.identifier(),
                f.patient(),
                f.encounter(),
                f.performer(),
                f.category(),
                f.code(),
                f.codeText(),
                f.status(),
                List.of(),
                f.dateFrom(),
                f.dateTo(),
                offset != null ? offset : 0,
                count != null ? count : 20
        );
        ObservationSearchResult result = observationService.searchObservations(criteria);
        List<GqlObservation> items = result.observations().stream()
                .map(o -> FhirGraphQlTypes.fromFhirObservation(ObservationService.toFhirObservation(o)))
                .toList();
        return new GqlObservationConnection(result.total(), result.offset(), result.count(), items);
    }

    // =========================================================================
    // Mutations (Validated via HAPI FHIR R4 + StrictErrorHandler + Domain Rules)
    // =========================================================================

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    public GqlPatient createPatient(@Argument PatientCreateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("PatientCreateInput must not be null");
        }
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(input.facilityId());
        String json = FhirGraphQlTypes.toFhirPatientJson(input);
        Patient created = patientService.createFromFhirJson(scopedFacility, json);
        if (cdsHooksService != null) {
            cdsHooksService.triggerPatientInteraction(created, scopedFacility);
        }
        return FhirGraphQlTypes.fromFhirPatient(created, scopedFacility);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    public GqlPatient updatePatient(
            @Argument UUID id,
            @Argument PatientUpdateInput input,
            @Argument String expectedVersion) {
        if (input == null) {
            throw new IllegalArgumentException("PatientUpdateInput must not be null");
        }
        PatientAggregateEntity entity = patientService.getPatientById(id);
        Patient existingFhir = patientService.getFhirPatientById(id);
        String json = FhirGraphQlTypes.toFhirPatientUpdateJson(existingFhir, input);
        Patient updated = patientService.updateFromFhirJson(id, json, expectedVersion);
        return FhirGraphQlTypes.fromFhirPatient(updated, entity.getFacilityId());
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    public GqlDeactivationResult deactivatePatient(@Argument UUID id) {
        PatientAggregateEntity saved = patientService.deactivatePatient(id);
        return new GqlDeactivationResult(
                saved.getId().toString(),
                "Patient",
                saved.isActive() ? "active" : "inactive",
                Instant.now().toString()
        );
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    public GqlPractitioner createPractitioner(@Argument PractitionerCreateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("PractitionerCreateInput must not be null");
        }
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(input.facilityId());
        String json = FhirGraphQlTypes.toFhirPractitionerJson(input);
        Practitioner created = practitionerService.createFromFhirJson(scopedFacility, json);
        return FhirGraphQlTypes.fromFhirPractitioner(created, scopedFacility);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    public GqlDeactivationResult deactivatePractitioner(@Argument UUID id) {
        PractitionerEntity saved = practitionerService.deactivatePractitioner(id);
        return new GqlDeactivationResult(
                saved.getId().toString(),
                "Practitioner",
                saved.isActive() ? "active" : "inactive",
                Instant.now().toString()
        );
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public GqlEncounter createEncounter(@Argument EncounterCreateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("EncounterCreateInput must not be null");
        }
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(input.facilityId());
        String json = FhirGraphQlTypes.toFhirEncounterJson(input);
        Encounter created = encounterService.createFromFhirJson(scopedFacility, json);
        if (cdsHooksService != null) {
            cdsHooksService.triggerEncounterInteraction(created, scopedFacility);
        }
        return FhirGraphQlTypes.fromFhirEncounter(created);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public GqlEncounter updateEncounter(
            @Argument UUID id,
            @Argument EncounterUpdateInput input,
            @Argument String expectedVersion) {
        if (input == null) {
            throw new IllegalArgumentException("EncounterUpdateInput must not be null");
        }
        String json = FhirGraphQlTypes.toFhirEncounterJson(input);
        Encounter updated = encounterService.updateFromFhirJson(id, json, expectedVersion);
        if (cdsHooksService != null) {
            cdsHooksService.triggerEncounterInteraction(updated, com.opeshealth.healthcore.security.tenant.FacilityContext.requireFacilityId());
        }
        return FhirGraphQlTypes.fromFhirEncounter(updated);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_facility:manage')")
    public GqlDeactivationResult cancelEncounter(@Argument UUID id) {
        EncounterEntity saved = encounterService.cancelEncounter(id);
        return new GqlDeactivationResult(
                saved.getId().toString(),
                "Encounter",
                saved.getStatus(),
                Instant.now().toString()
        );
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_vitals:write') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    public GqlObservation createObservation(@Argument ObservationCreateInput input) {
        if (input == null) {
            throw new IllegalArgumentException("ObservationCreateInput must not be null");
        }
        UUID scopedFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.requireAuthorizedFacilityId(input.facilityId());
        String json = FhirGraphQlTypes.toFhirObservationJson(input);
        Observation created = observationService.createFromFhirJson(scopedFacility, json);
        if (cdsHooksService != null) {
            cdsHooksService.triggerObservationInteraction(created, scopedFacility);
        }
        return FhirGraphQlTypes.fromFhirObservation(created);
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_lab:verify') or hasAuthority('PERM_facility:manage')")
    public GqlDeactivationResult markObservationEnteredInError(@Argument UUID id) {
        ObservationEntity saved = observationService.markEnteredInError(id);
        return new GqlDeactivationResult(
                saved.getId().toString(),
                "Observation",
                saved.getStatus(),
                Instant.now().toString()
        );
    }

    // =========================================================================
    // Nested Cross-Resource @SchemaMapping Field Resolvers
    // =========================================================================

    @SchemaMapping(typeName = "FhirPatient", field = "encounters")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public List<GqlEncounter> patientEncounters(
            GqlPatient patient,
            @Argument String status,
            @Argument Integer count) {
        if (patient == null || patient.id() == null) return List.of();
        UUID facilityId = parseUuid(patient.facilityId());
        EncounterSearchResult result = encounterService.searchEncounters(new EncounterSearchCriteria(
                null,
                facilityId,
                "Patient/" + patient.id(),
                status,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                0,
                count != null ? count : 20
        ));
        return result.encounters().stream()
                .map(FhirGraphQlTypes::fromEncounterEntity)
                .toList();
    }

    @SchemaMapping(typeName = "FhirPatient", field = "observations")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    public List<GqlObservation> patientObservations(
            GqlPatient patient,
            @Argument String code,
            @Argument String category,
            @Argument Integer count) {
        if (patient == null || patient.id() == null) return List.of();
        UUID facilityId = parseUuid(patient.facilityId());
        ObservationSearchResult result = observationService.searchObservations(new ObservationSearchCriteria(
                null,
                facilityId,
                null,
                "Patient/" + patient.id(),
                null,
                null,
                category,
                code,
                null,
                null,
                List.of(),
                null,
                null,
                0,
                count != null ? count : 20
        ));
        return result.observations().stream()
                .map(o -> FhirGraphQlTypes.fromFhirObservation(ObservationService.toFhirObservation(o)))
                .toList();
    }

    @SchemaMapping(typeName = "FhirPractitioner", field = "encounters")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public List<GqlEncounter> practitionerEncounters(
            GqlPractitioner practitioner,
            @Argument String status,
            @Argument Integer count) {
        if (practitioner == null || practitioner.id() == null) return List.of();
        UUID facilityId = parseUuid(practitioner.facilityId());
        EncounterSearchResult result = encounterService.searchEncounters(new EncounterSearchCriteria(
                null,
                facilityId,
                null,
                status,
                null,
                "Practitioner/" + practitioner.id(),
                null,
                null,
                List.of(),
                null,
                null,
                0,
                count != null ? count : 20
        ));
        return result.encounters().stream()
                .map(FhirGraphQlTypes::fromEncounterEntity)
                .toList();
    }

    @SchemaMapping(typeName = "FhirEncounter", field = "patient")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    public GqlPatient encounterPatient(GqlEncounter encounter) {
        if (encounter == null || encounter.subject() == null) return null;
        UUID patId = parseReferenceUuid(encounter.subject().reference());
        if (patId == null) return null;
        return patientService.findPatientById(patId)
                .map(p -> FhirGraphQlTypes.fromFhirPatient(
                        patientService.getFhirPatientById(p.getId()), p.getFacilityId()))
                .orElse(null);
    }

    @SchemaMapping(typeName = "FhirEncounter", field = "attendingPractitioner")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_staff:onboard') or hasAuthority('PERM_facility:manage')")
    public GqlPractitioner encounterAttendingPractitioner(GqlEncounter encounter) {
        if (encounter == null || encounter.participant() == null || encounter.participant().isEmpty()) {
            return null;
        }
        GqlReference ind = encounter.participant().get(0).individual();
        UUID pracId = ind != null ? parseReferenceUuid(ind.reference()) : null;
        if (pracId == null) return null;
        return practitionerService.findPractitionerById(pracId)
                .map(p -> FhirGraphQlTypes.fromFhirPractitioner(
                        PractitionerService.toFhirPractitioner(p), p.getFacilityId()))
                .orElse(null);
    }

    @SchemaMapping(typeName = "FhirEncounter", field = "observations")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_lab:result:enter') or hasAuthority('PERM_lab:verify')")
    public List<GqlObservation> encounterObservations(
            GqlEncounter encounter,
            @Argument String code,
            @Argument Integer count) {
        if (encounter == null || encounter.id() == null) return List.of();
        UUID facilityId = encounter.serviceProvider() != null
                ? parseReferenceUuid(encounter.serviceProvider().reference())
                : null;
        ObservationSearchResult result = observationService.searchObservations(new ObservationSearchCriteria(
                null,
                facilityId,
                null,
                null,
                "Encounter/" + encounter.id(),
                null,
                null,
                code,
                null,
                null,
                List.of(),
                null,
                null,
                0,
                count != null ? count : 20
        ));
        return result.observations().stream()
                .map(o -> FhirGraphQlTypes.fromFhirObservation(ObservationService.toFhirObservation(o)))
                .toList();
    }

    @SchemaMapping(typeName = "FhirObservation", field = "patient")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    public GqlPatient observationPatient(GqlObservation observation) {
        if (observation == null || observation.subject() == null) return null;
        UUID patId = parseReferenceUuid(observation.subject().reference());
        if (patId == null) return null;
        return patientService.findPatientById(patId)
                .map(p -> FhirGraphQlTypes.fromFhirPatient(
                        patientService.getFhirPatientById(p.getId()), p.getFacilityId()))
                .orElse(null);
    }

    @SchemaMapping(typeName = "FhirObservation", field = "encounter")
    @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_encounter:draft') or hasAuthority('PERM_encounter:sign')")
    public GqlEncounter observationEncounter(GqlObservation observation) {
        if (observation == null || observation.encounterReference() == null) return null;
        UUID encId = parseReferenceUuid(observation.encounterReference().reference());
        if (encId == null) return null;
        return encounterService.findEncounterById(encId)
                .map(FhirGraphQlTypes::fromEncounterEntity)
                .orElse(null);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static UUID parseReferenceUuid(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String trimmed = ref.trim();
        int slash = trimmed.lastIndexOf('/');
        String candidate = slash >= 0 && slash + 1 < trimmed.length() ? trimmed.substring(slash + 1) : trimmed;
        return parseUuid(candidate);
    }
}
