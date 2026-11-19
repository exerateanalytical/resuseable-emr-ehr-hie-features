/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 FHIR R4 Bulk Data Access ($export) Cohort Service
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart.service;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.emr.domain.EmrEntities.EncounterEntity;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.quickstart.domain.ObservationEntity;
import com.opeshealth.healthcore.quickstart.domain.PractitionerEntity;
import com.opeshealth.healthcore.quickstart.service.EncounterService.EncounterSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.ObservationService.ObservationSearchCriteria;
import com.opeshealth.healthcore.quickstart.service.PractitionerService.PractitionerSearchCriteria;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.service.PatientService;
import com.opeshealth.healthcore.service.PatientService.PatientRecord;
import com.opeshealth.healthcore.service.PatientService.PatientSearchCriteria;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HL7 FHIR R4 Bulk Data Access IG v2.0 ({@code $export}) service enabling practitioners
 * and clinical systems to export patient cohorts in newline-delimited JSON
 * ({@code application/fhir+ndjson}) or FHIR R4 collection {@code Bundle} format.
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Patient-level cohort exports ({@code /Patient/$export})</li>
 *   <li>Practitioner-attributed patient cohort exports ({@code /Practitioner/{id}/$export}),
 *       automatically resolving all patients cared for by the practitioner across
 *       {@link EncounterService} and {@link ObservationService}</li>
 *   <li>Explicit patient UUID cohort lists, {@code _type} filtering
 *       ({@code Patient,Encounter,Observation,Practitioner}), and {@code _since} incremental timestamps</li>
 *   <li>Asynchronous job manifest polling ({@code /$export-status/{jobId}}), NDJSON file downloads,
 *       and job cancellation/deletion</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class FhirBulkDataExportService {

    public static final String NDJSON_MEDIA_TYPE = "application/fhir+ndjson";
    public static final Set<String> SUPPORTED_OUTPUT_FORMATS = Set.of(
            "application/fhir+ndjson",
            "application/ndjson",
            "ndjson",
            "application/fhir+json",
            "json"
    );
    public static final List<String> DEFAULT_EXPORT_TYPES = List.of(
            "Patient", "Encounter", "Observation", "Practitioner"
    );
    public static final Set<String> SUPPORTED_RESOURCE_TYPES = Set.of(
            "Patient", "Encounter", "Observation", "Practitioner"
    );

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final UUID DEFAULT_FACILITY_ID = new UUID(0L, 1L);

    /** Input parameters for a FHIR R4 Bulk Data cohort export request. */
    public record BulkExportRequest(
            UUID facilityId,
            UUID practitionerId,
            List<UUID> patientIds,
            List<String> resourceTypes,
            Instant since,
            String outputFormat,
            String requestedBy,
            String requestUrl
    ) {}

    /** Metadata and SHA-256 integrity hash for a single generated NDJSON file in a Bulk Data export job. */
    public record BulkExportFileItem(
            String type,
            String url,
            int count,
            String sha256Checksum
    ) {}

    /** Normative HL7 FHIR Bulk Data status manifest returned when an export job completes. */
    public record BulkExportManifest(
            UUID jobId,
            UUID facilityId,
            UUID practitionerId,
            Instant transactionTime,
            String request,
            boolean requiresAccessToken,
            int cohortPatientCount,
            int totalExportedResources,
            List<BulkExportFileItem> output,
            List<Map<String, Object>> error
    ) {}

    /** Internal state of a completed or cancelled FHIR Bulk Data export job. */
    public record BulkExportJob(
            UUID jobId,
            UUID facilityId,
            UUID practitionerId,
            String status,
            Instant createdAt,
            Instant expiresAt,
            String requestUrl,
            String requestedBy,
            int cohortPatientCount,
            Map<String, String> ndjsonByType,
            Map<String, Integer> countByType,
            Map<String, String> sha256ByType
    ) {}

    private final PatientService patientService;
    private final PractitionerService practitionerService;
    private final EncounterService encounterService;
    private final ObservationService observationService;
    private final Map<UUID, BulkExportJob> jobStore = new ConcurrentHashMap<>();

    public FhirBulkDataExportService(
            PatientService patientService,
            PractitionerService practitionerService,
            EncounterService encounterService,
            ObservationService observationService) {
        this.patientService = Objects.requireNonNull(patientService, "PatientService is required");
        this.practitionerService = Objects.requireNonNull(practitionerService, "PractitionerService is required");
        this.encounterService = Objects.requireNonNull(encounterService, "EncounterService is required");
        this.observationService = Objects.requireNonNull(observationService, "ObservationService is required");
    }

    /**
     * Validates and executes a FHIR R4 Bulk Data cohort export job, assembling newline-delimited
     * FHIR R4 JSON ({@code application/fhir+ndjson}) streams for each requested resource type.
     */
    @ClinicalAudit(action = "FHIR_BULK_DATA_EXPORT_INITIATE", resourceType = "BulkDataExport")
    public BulkExportJob initiateCohortExport(BulkExportRequest request) {
        Objects.requireNonNull(request, "BulkExportRequest must not be null");
        validateOutputFormat(request.outputFormat());
        List<String> requestedTypes = normalizeAndValidateTypes(request.resourceTypes());
        UUID facilityId = request.facilityId() != null ? request.facilityId() : DEFAULT_FACILITY_ID;

        // If practitionerId is specified, verify practitioner exists
        PractitionerEntity targetPractitioner = null;
        if (request.practitionerId() != null) {
            targetPractitioner = practitionerService.getPractitionerById(request.practitionerId());
        }

        // 1. Load facility encounters and observations to resolve practitioner attribution and cohort clinical data
        List<EncounterEntity> allEncounters = encounterService.searchEncounters(new EncounterSearchCriteria(
                null, facilityId, null, null, null, null, null, null, List.of(), null, null, 0, 100
        )).encounters();

        List<ObservationEntity> allObservations = observationService.searchObservations(new ObservationSearchCriteria(
                null, facilityId, null, null, null, null, null, null, null, null, List.of(), null, null, 0, 100
        )).observations();

        // 2. Determine the target patient cohort UUIDs
        Set<UUID> cohortPatientIds = resolveCohortPatientIds(
                facilityId, request.practitionerId(), request.patientIds(), allEncounters, allObservations);

        // 3. Load and filter Patient, Encounter, Observation, and Practitioner resources by cohort and _since
        Instant since = request.since();
        List<Patient> cohortPatients = new ArrayList<>();
        for (UUID patId : cohortPatientIds) {
            patientService.findRecordById(patId)
                    .filter(rec -> facilityId.equals(rec.facilityId()))
                    .filter(rec -> since == null || rec.updatedAt() == null || !rec.updatedAt().isBefore(since))
                    .map(PatientService::toFhirPatient)
                    .ifPresent(cohortPatients::add);
        }

        List<Encounter> cohortEncounters = allEncounters.stream()
                .filter(e -> e.getPatientId() != null && cohortPatientIds.contains(e.getPatientId()))
                .filter(e -> request.practitionerId() == null || Objects.equals(request.practitionerId(), e.getPractitionerId()))
                .filter(e -> since == null || e.getStartedAt() == null || !e.getStartedAt().isBefore(since))
                .map(EncounterService::toFhirEncounter)
                .toList();

        List<Observation> cohortObservations = allObservations.stream()
                .filter(o -> o.getPatientId() != null && cohortPatientIds.contains(o.getPatientId()))
                .filter(o -> since == null || o.getUpdatedAt() == null || !o.getUpdatedAt().isBefore(since))
                .map(ObservationService::toFhirObservation)
                .toList();

        Set<UUID> referencedPractitionerIds = new LinkedHashSet<>();
        if (request.practitionerId() != null) {
            referencedPractitionerIds.add(request.practitionerId());
        }
        allEncounters.stream()
                .filter(e -> e.getPatientId() != null && cohortPatientIds.contains(e.getPatientId()))
                .map(EncounterEntity::getPractitionerId)
                .filter(Objects::nonNull)
                .forEach(referencedPractitionerIds::add);
        allObservations.stream()
                .filter(o -> o.getPatientId() != null && cohortPatientIds.contains(o.getPatientId()))
                .map(ObservationEntity::getPractitionerId)
                .filter(Objects::nonNull)
                .forEach(referencedPractitionerIds::add);

        List<Practitioner> cohortPractitioners = new ArrayList<>();
        if (targetPractitioner != null) {
            cohortPractitioners.add(PractitionerService.toFhirPractitioner(targetPractitioner));
        } else {
            List<PractitionerEntity> facPractitioners = practitionerService.searchPractitioners(
                    new PractitionerSearchCriteria(null, facilityId, null, List.of(), List.of(), List.of(), null, null, null, 0, 100)
            ).practitioners();
            for (PractitionerEntity pe : facPractitioners) {
                if (referencedPractitionerIds.isEmpty() || referencedPractitionerIds.contains(pe.getId())) {
                    if (since == null || pe.getUpdatedAt() == null || !pe.getUpdatedAt().isBefore(since)) {
                        cohortPractitioners.add(PractitionerService.toFhirPractitioner(pe));
                    }
                }
            }
        }

        // 4. Encode NDJSON files for each requested resource type
        Map<String, String> ndjsonByType = new LinkedHashMap<>();
        Map<String, Integer> countByType = new LinkedHashMap<>();
        Map<String, String> sha256ByType = new LinkedHashMap<>();

        for (String type : requestedTypes) {
            List<? extends IBaseResource> resources = switch (type) {
                case "Patient" -> cohortPatients;
                case "Encounter" -> cohortEncounters;
                case "Observation" -> cohortObservations;
                case "Practitioner" -> cohortPractitioners;
                default -> List.of();
            };
            String ndjson = encodeToNdjson(resources);
            ndjsonByType.put(type, ndjson);
            countByType.put(type, resources.size());
            sha256ByType.put(type, sha256Hex(ndjson));
        }

        UUID jobId = UuidV7Generator.nextUuid();
        Instant now = Instant.now();
        BulkExportJob job = new BulkExportJob(
                jobId,
                facilityId,
                request.practitionerId(),
                "COMPLETED",
                now,
                now.plusSeconds(86_400),
                request.requestUrl() != null ? request.requestUrl() : "/api/v1/fhir/Patient/$export",
                request.requestedBy() != null ? request.requestedBy() : "SYSTEM",
                cohortPatientIds.size(),
                Map.copyOf(ndjsonByType),
                Map.copyOf(countByType),
                Map.copyOf(sha256ByType)
        );
        jobStore.put(jobId, job);
        return job;
    }

    /**
     * Builds the normative HL7 FHIR Bulk Data status manifest for a completed export job.
     */
    @ClinicalAudit(action = "FHIR_BULK_DATA_EXPORT_STATUS", resourceType = "BulkDataExport")
    public BulkExportManifest getExportJobManifest(UUID jobId, String baseDownloadPrefix) {
        BulkExportJob job = getRequiredActiveJob(jobId);
        String prefix = (baseDownloadPrefix == null || baseDownloadPrefix.isBlank())
                ? "/api/v1/fhir/$export-files/" + jobId
                : baseDownloadPrefix;

        List<BulkExportFileItem> outputItems = new ArrayList<>();
        int totalResources = 0;
        for (Map.Entry<String, Integer> entry : job.countByType().entrySet()) {
            String type = entry.getKey();
            int count = entry.getValue();
            totalResources += count;
            if (count > 0) {
                outputItems.add(new BulkExportFileItem(
                        type,
                        prefix + "/" + type + ".ndjson",
                        count,
                        job.sha256ByType().getOrDefault(type, "")
                ));
            }
        }
        return new BulkExportManifest(
                job.jobId(),
                job.facilityId(),
                job.practitionerId(),
                job.createdAt(),
                job.requestUrl(),
                true,
                job.cohortPatientCount(),
                totalResources,
                List.copyOf(outputItems),
                List.of()
        );
    }

    /**
     * Retrieves the generated {@code application/fhir+ndjson} content for a specific resource type in an export job.
     */
    @ClinicalAudit(action = "FHIR_BULK_DATA_EXPORT_DOWNLOAD", resourceType = "BulkDataExport")
    public String downloadNdjsonFile(UUID jobId, String resourceType) {
        BulkExportJob job = getRequiredActiveJob(jobId);
        String normalizedType = normalizeResourceTypeName(resourceType);
        if (!job.ndjsonByType().containsKey(normalizedType)) {
            throw new NoSuchElementException(
                    "Resource type '" + resourceType + "' was not included in bulk export job " + jobId);
        }
        return job.ndjsonByType().get(normalizedType);
    }

    /**
     * Assembles a single HL7 FHIR R4 {@code Bundle} of type {@code collection} containing all exported
     * cohort resources for a job.
     */
    public Bundle exportJobAsFhirBundle(UUID jobId) {
        BulkExportJob job = getRequiredActiveJob(jobId);
        Bundle bundle = new Bundle();
        bundle.setId(job.jobId().toString());
        bundle.setType(Bundle.BundleType.COLLECTION);
        var parser = FHIR_R4.newJsonParser();
        int total = 0;
        for (Map.Entry<String, String> entry : job.ndjsonByType().entrySet()) {
            String ndjson = entry.getValue();
            if (ndjson == null || ndjson.isBlank()) continue;
            for (String line : ndjson.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                org.hl7.fhir.r4.model.Resource res = (org.hl7.fhir.r4.model.Resource) parser.parseResource(line);
                bundle.addEntry()
                        .setFullUrl(res.fhirType() + "/" + res.getIdElement().getIdPart())
                        .setResource(res);
                total++;
            }
        }
        bundle.setTotal(total);
        return bundle;
    }

    /**
     * Cancels and deletes a FHIR Bulk Data export job and purges its cached NDJSON streams.
     */
    @ClinicalAudit(action = "FHIR_BULK_DATA_EXPORT_DELETE", resourceType = "BulkDataExport")
    public void deleteExportJob(UUID jobId) {
        if (jobId == null || !jobStore.containsKey(jobId)) {
            throw new NoSuchElementException("Bulk Data export job not found: " + jobId);
        }
        jobStore.remove(jobId);
    }

    public String encodeFhirBundle(Bundle bundle) {
        return FHIR_R4.newJsonParser().setPrettyPrint(true).encodeResourceToString(bundle);
    }

    public static List<String> parseTypeQueryParam(String typeParam) {
        if (typeParam == null || typeParam.isBlank()) {
            return DEFAULT_EXPORT_TYPES;
        }
        List<String> parsed = new ArrayList<>();
        for (String token : typeParam.split(",")) {
            if (!token.isBlank()) {
                parsed.add(token.trim());
            }
        }
        return normalizeAndValidateTypes(parsed);
    }

    private BulkExportJob getRequiredActiveJob(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("Bulk export jobId is required");
        }
        BulkExportJob job = jobStore.get(jobId);
        if (job == null) {
            throw new NoSuchElementException("Bulk Data export job not found or expired: " + jobId);
        }
        return job;
    }

    private Set<UUID> resolveCohortPatientIds(
            UUID facilityId,
            UUID practitionerId,
            List<UUID> explicitPatientIds,
            List<EncounterEntity> encounters,
            List<ObservationEntity> observations) {
        Set<UUID> cohort = new LinkedHashSet<>();

        if (explicitPatientIds != null && !explicitPatientIds.isEmpty()) {
            cohort.addAll(explicitPatientIds);
        }

        if (practitionerId != null) {
            Set<UUID> attributed = new LinkedHashSet<>();
            for (EncounterEntity enc : encounters) {
                if (Objects.equals(practitionerId, enc.getPractitionerId()) && enc.getPatientId() != null) {
                    attributed.add(enc.getPatientId());
                }
            }
            for (ObservationEntity obs : observations) {
                if (Objects.equals(practitionerId, obs.getPractitionerId()) && obs.getPatientId() != null) {
                    attributed.add(obs.getPatientId());
                }
            }
            if (cohort.isEmpty()) {
                cohort.addAll(attributed);
            } else {
                cohort.retainAll(attributed);
            }
            return cohort;
        }

        if (!cohort.isEmpty()) {
            return cohort;
        }

        List<PatientRecord> facPatients = patientService.searchPatients(new PatientSearchCriteria(
                null, facilityId, null, List.of(), List.of(), List.of(), List.of(), null, null, null, null, null, 0, 100
        )).patients();
        for (PatientRecord rec : facPatients) {
            cohort.add(rec.id());
        }
        return cohort;
    }

    private static void validateOutputFormat(String outputFormat) {
        if (outputFormat == null || outputFormat.isBlank()) {
            return;
        }
        String cleaned = outputFormat.trim().toLowerCase(Locale.ROOT);
        int semicolon = cleaned.indexOf(';');
        if (semicolon >= 0) {
            cleaned = cleaned.substring(0, semicolon).trim();
        }
        if (!SUPPORTED_OUTPUT_FORMATS.contains(cleaned)) {
            throw new IllegalArgumentException(
                    "Unsupported FHIR Bulk Data _outputFormat '" + outputFormat
                            + "'. Supported formats: application/fhir+ndjson, application/ndjson, ndjson.");
        }
    }

    private static List<String> normalizeAndValidateTypes(List<String> rawTypes) {
        if (rawTypes == null || rawTypes.isEmpty()) {
            return DEFAULT_EXPORT_TYPES;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawTypes) {
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split(",")) {
                if (part.isBlank()) continue;
                String canonical = normalizeResourceTypeName(part.trim());
                if (!SUPPORTED_RESOURCE_TYPES.contains(canonical)) {
                    throw new IllegalArgumentException(
                            "Unsupported FHIR Bulk Data _type '" + part.trim()
                                    + "'. Supported cohort resource types: Patient, Encounter, Observation, Practitioner.");
                }
                normalized.add(canonical);
            }
        }
        return normalized.isEmpty() ? DEFAULT_EXPORT_TYPES : List.copyOf(normalized);
    }

    private static String normalizeResourceTypeName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(".ndjson")) {
            cleaned = cleaned.substring(0, cleaned.length() - 7);
        }
        return switch (cleaned.toLowerCase(Locale.ROOT)) {
            case "patient" -> "Patient";
            case "encounter" -> "Encounter";
            case "observation" -> "Observation";
            case "practitioner" -> "Practitioner";
            default -> cleaned;
        };
    }

    private static String encodeToNdjson(List<? extends IBaseResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return "";
        }
        var compactParser = FHIR_R4.newJsonParser().setPrettyPrint(false);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resources.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(compactParser.encodeResourceToString(resources.get(i)));
        }
        return sb.toString();
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
