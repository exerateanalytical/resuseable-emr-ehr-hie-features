package com.healthcore.fhir.patient;

import com.healthcore.audit.ClinicalAudit;
import com.healthcore.hie.HieOutboxEventEntity;
import com.healthcore.hie.HieOutboxRepository;
import com.healthcore.mpi.PatientEntity;
import com.healthcore.mpi.PatientIdentifierEntity;
import com.healthcore.mpi.PatientIdentifierRepository;
import com.healthcore.tenant.FacilityContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class FhirPatientService {

    private final FhirPatientRepository patientRepository;
    private final PatientIdentifierRepository identifierRepository;
    private final FhirPatientMapper mapper;
    private final HieOutboxRepository outboxRepository;

    public FhirPatientService(
            FhirPatientRepository patientRepository,
            PatientIdentifierRepository identifierRepository,
            FhirPatientMapper mapper,
            HieOutboxRepository outboxRepository
    ) {
        this.patientRepository = patientRepository;
        this.identifierRepository = identifierRepository;
        this.mapper = mapper;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    @ClinicalAudit(action = "CREATE", resourceType = "FHIR_PATIENT")
    public FhirPatientResult createPatient(String fhirPatientJson) {
        Patient fhir = mapper.parseFhirPatient(fhirPatientJson);

        PatientEntity entity = new PatientEntity();
        entity.setEnterpriseId("PAT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        mapper.applyFhirToEntity(fhir, entity);

        PatientEntity saved = patientRepository.save(entity);
        List<PatientIdentifierEntity> extraIds = syncIdentifiers(saved.getId(), fhir);

        Patient canonical = mapper.toFhirPatient(saved, extraIds);
        String canonicalJson = mapper.encode(canonical);

        queuePatientOutboxEvent(saved, canonicalJson);
        return new FhirPatientResult(saved.getId(), canonicalJson);
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "READ", resourceType = "FHIR_PATIENT")
    public String getPatientById(UUID patientId) {
        PatientEntity entity = patientRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient/" + patientId + " not found"));
        List<PatientIdentifierEntity> extraIds = identifierRepository.findByPatientId(patientId);
        return mapper.encode(mapper.toFhirPatient(entity, extraIds));
    }

    @Transactional
    @ClinicalAudit(action = "UPDATE", resourceType = "FHIR_PATIENT")
    public String updatePatient(UUID patientId, String fhirPatientJson) {
        PatientEntity entity = patientRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient/" + patientId + " not found"));

        Patient fhir = mapper.parseFhirPatient(fhirPatientJson);
        mapper.applyFhirToEntity(fhir, entity);
        PatientEntity saved = patientRepository.save(entity);

        List<PatientIdentifierEntity> existing = identifierRepository.findByPatientId(patientId);
        identifierRepository.deleteAll(existing);
        List<PatientIdentifierEntity> updatedIds = syncIdentifiers(patientId, fhir);

        String canonicalJson = mapper.encode(mapper.toFhirPatient(saved, updatedIds));
        queuePatientOutboxEvent(saved, canonicalJson);
        return canonicalJson;
    }

    @Transactional
    @ClinicalAudit(action = "DEACTIVATE", resourceType = "FHIR_PATIENT")
    public void deactivatePatient(UUID patientId) {
        PatientEntity entity = patientRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("Patient/" + patientId + " not found"));
        entity.setActive(false);
        entity.setUpdatedAt(Instant.now());
        patientRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public String searchPatients(
            String family,
            String given,
            LocalDate birthdate,
            String gender,
            String phone,
            String city,
            String identifier,
            Boolean active,
            int page,
            int size
    ) {
        var spec = FhirPatientRepository.withFhirSearchParams(
                family, given, birthdate, gender, phone, city, identifier, active);
        Page<PatientEntity> results = patientRepository.findAll(
                spec, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by("familyName").ascending()));

        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal((int) results.getTotalElements());

        for (PatientEntity entity : results.getContent()) {
            List<PatientIdentifierEntity> ids = identifierRepository.findByPatientId(entity.getId());
            Patient fhirPatient = mapper.toFhirPatient(entity, ids);
            bundle.addEntry()
                    .setFullUrl("Patient/" + entity.getId())
                    .setResource(fhirPatient);
        }
        return mapper.encode(bundle);
    }

    private List<PatientIdentifierEntity> syncIdentifiers(UUID patientId, Patient fhir) {
        UUID facilityId = FacilityContext.getFacilityId().orElse(null);
        List<PatientIdentifierEntity> savedIds = new java.util.ArrayList<>();
        for (Identifier id : fhir.getIdentifier()) {
            if (!id.hasValue()) continue;
            if ("urn:healthcore:enterprise-mpi".equals(id.getSystem())) continue;

            PatientIdentifierEntity pie = new PatientIdentifierEntity();
            pie.setPatientId(patientId);
            pie.setFacilityId(facilityId);
            pie.setSystemUri(id.getSystem());
            pie.setUseCode(id.hasUse() ? id.getUse().toCode() : null);
            pie.setIdType(deriveIdType(id));
            pie.setIdValue(id.getValue().trim());
            pie.setPrimary(id.getUse() == Identifier.IdentifierUse.OFFICIAL);
            savedIds.add(identifierRepository.save(pie));
        }
        return savedIds;
    }

    private String deriveIdType(Identifier id) {
        if (id.hasType() && !id.getType().getCoding().isEmpty()) {
            return id.getType().getCodingFirstRep().getCode();
        }
        if (id.hasSystem() && id.getSystem().toLowerCase().contains("national")) {
            return "NATIONAL_ID";
        }
        return "EXTERNAL_ID";
    }

    private void queuePatientOutboxEvent(PatientEntity patient, String canonicalJson) {
        UUID facilityId = FacilityContext.getFacilityId().orElse(patient.getManagingOrganizationId());
        if (facilityId == null) {
            return;
        }
        HieOutboxEventEntity outbox = new HieOutboxEventEntity();
        outbox.setIdempotencyKey("PATIENT-" + patient.getId() + "-" + patient.getUpdatedAt().toEpochMilli());
        outbox.setAggregateType("PATIENT");
        outbox.setAggregateId(patient.getId());
        outbox.setFacilityId(facilityId);
        outbox.setFhirResourceType("Patient");
        outbox.setPayloadJson(canonicalJson);
        outboxRepository.save(outbox);
    }

    public record FhirPatientResult(UUID id, String fhirJson) {}
}
