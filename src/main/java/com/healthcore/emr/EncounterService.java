package com.healthcore.emr;

import com.healthcore.audit.ClinicalAudit;
import com.healthcore.consent.ConsentEnforcementService;
import com.healthcore.hie.FhirR4Mapper;
import com.healthcore.hie.HieOutboxEventEntity;
import com.healthcore.hie.HieOutboxRepository;
import com.healthcore.mpi.PatientEntity;
import com.healthcore.mpi.PatientRepository;
import com.healthcore.tenant.FacilityContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EncounterService {

    private final EncounterRepository encounterRepository;
    private final ObservationRepository observationRepository;
    private final PatientRepository patientRepository;
    private final HieOutboxRepository outboxRepository;
    private final FhirR4Mapper fhirR4Mapper;
    private final ConsentEnforcementService consentEnforcementService;

    public EncounterService(
            EncounterRepository encounterRepository,
            ObservationRepository observationRepository,
            PatientRepository patientRepository,
            HieOutboxRepository outboxRepository,
            FhirR4Mapper fhirR4Mapper,
            ConsentEnforcementService consentEnforcementService
    ) {
        this.encounterRepository = encounterRepository;
        this.observationRepository = observationRepository;
        this.patientRepository = patientRepository;
        this.outboxRepository = outboxRepository;
        this.fhirR4Mapper = fhirR4Mapper;
        this.consentEnforcementService = consentEnforcementService;
    }

    @Transactional
    @ClinicalAudit(action = "CREATE", resourceType = "ENCOUNTER")
    public EncounterEntity recordEncounterAndQueueHieSync(RecordEncounterCommand cmd) {
        UUID facilityId = FacilityContext.requireFacilityId();
        PatientEntity patient = patientRepository.findById(cmd.patientId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown patient: " + cmd.patientId()));

        EncounterEntity encounter = new EncounterEntity();
        encounter.setPatientId(patient.getId());
        encounter.setFacilityId(facilityId);
        encounter.setClinicianId(cmd.clinicianId());
        encounter.setEncounterType(cmd.encounterType());
        encounter.setStatus("COMPLETED");
        encounter.setChiefComplaint(cmd.chiefComplaint());
        encounter.setSoapNotes(cmd.soapNotes() != null ? cmd.soapNotes() : Map.of());
        encounter.setDiagnosisCodes(cmd.diagnosisCodes() != null ? cmd.diagnosisCodes() : List.of());
        encounter.setEndedAt(Instant.now());
        EncounterEntity savedEncounter = encounterRepository.save(encounter);

        List<ObservationEntity> savedObs = cmd.observations().stream().map(o -> {
            ObservationEntity obs = new ObservationEntity();
            obs.setEncounterId(savedEncounter.getId());
            obs.setPatientId(patient.getId());
            obs.setFacilityId(facilityId);
            obs.setLoincCode(o.loincCode());
            obs.setDisplayName(o.displayName());
            obs.setValueNumeric(o.valueNumeric());
            obs.setValueText(o.valueText());
            obs.setUnit(o.unit());
            obs.setInterpretation(o.interpretation());
            return observationRepository.save(obs);
        }).toList();

        String fhirBundleJson = fhirR4Mapper.toTransactionBundleJson(patient, savedEncounter, savedObs);
        HieOutboxEventEntity outbox = new HieOutboxEventEntity();
        outbox.setIdempotencyKey("ENCOUNTER-" + savedEncounter.getId());
        outbox.setAggregateType("ENCOUNTER");
        outbox.setAggregateId(savedEncounter.getId());
        outbox.setFacilityId(facilityId);
        outbox.setFhirResourceType("Bundle");
        outbox.setPayloadJson(fhirBundleJson);
        outboxRepository.save(outbox);

        return savedEncounter;
    }

    @Transactional(readOnly = true)
    @ClinicalAudit(action = "READ", resourceType = "EHR_LONGITUDINAL_SUMMARY")
    public LongitudinalSummary getCrossFacilityPatientSummary(UUID patientId, String emergencyBreakGlassReason) {
        UUID requestingFacilityId = FacilityContext.requireFacilityId();
        consentEnforcementService.assertHieAccessOrBreakGlass(patientId, requestingFacilityId, emergencyBreakGlassReason);

        PatientEntity patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown patient: " + patientId));
        List<EncounterEntity> encounters = encounterRepository.findByPatientIdOrderByStartedAtDesc(patientId);
        List<ObservationEntity> observations = observationRepository.findByPatientIdOrderByObservedAtDesc(patientId);
        return new LongitudinalSummary(patient, encounters, observations);
    }

    public record ObservationInput(
            String loincCode,
            String displayName,
            BigDecimal valueNumeric,
            String valueText,
            String unit,
            String interpretation
    ) {}

    public record RecordEncounterCommand(
            UUID patientId,
            String clinicianId,
            String encounterType,
            String chiefComplaint,
            Map<String, Object> soapNotes,
            List<String> diagnosisCodes,
            List<ObservationInput> observations
    ) {}

    public record LongitudinalSummary(
            PatientEntity patient,
            List<EncounterEntity> encounters,
            List<ObservationEntity> observations
    ) {}
}
