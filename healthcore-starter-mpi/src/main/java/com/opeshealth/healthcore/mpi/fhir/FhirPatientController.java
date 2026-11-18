/*
 * =============================================================================
 * Opes HealthCore SDK — FHIR R4 Patient Resource Mapper & REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mpi.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientAggregateEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientDemographicsEntity;
import com.opeshealth.healthcore.mpi.domain.MpiEntities.PatientIdentifierEntity;
import com.opeshealth.healthcore.mpi.match.FellegiSunterMatcher.MatchCandidate;
import com.opeshealth.healthcore.mpi.service.MpiCommandService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.RegisterPatientCommand;
import com.opeshealth.healthcore.security.tenant.FacilityTenantInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Exposes HL7 FHIR R4 {@code /fhir/r4/Patient} endpoints with {@code ETag} / {@code If-Match}
 * optimistic concurrency, full FHIR R4 demographic/identifier/telecom/address/contact mapping,
 * alongside probabilistic MPI matching and Golden Record merge/unmerge endpoints.
 */
@RestController
@Tag(name = "MPI & FHIR R4 Patient", description = "Master Patient Index, Fellegi-Sunter matching, and FHIR R4 Patient API")
public class FhirPatientController {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final String MRN_SYSTEM = "urn:opes:mrn";
    private static final String NATIONAL_ID_SYSTEM = "urn:opes:national-id";

    private final MpiCommandService mpiService;
    private final MpiCommandService.PatientRepository patientRepository;
    private final MpiCommandService.PatientIdentifierRepository identifierRepository;
    private final MpiCommandService.PatientDemographicsRepository demographicsRepository;

    public FhirPatientController(
            MpiCommandService mpiService,
            MpiCommandService.PatientRepository patientRepository) {
        this(mpiService, patientRepository, null, null);
    }

    public FhirPatientController(
            MpiCommandService mpiService,
            MpiCommandService.PatientRepository patientRepository,
            MpiCommandService.PatientIdentifierRepository identifierRepository,
            MpiCommandService.PatientDemographicsRepository demographicsRepository) {
        this.mpiService = mpiService;
        this.patientRepository = patientRepository;
        this.identifierRepository = identifierRepository;
        this.demographicsRepository = demographicsRepository;
    }

    @PostMapping("/api/v1/mpi/patients")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Register a new patient Golden Record with encrypted National ID blind index")
    public PatientAggregateEntity register(@RequestBody RegisterPatientCommand cmd) {
        return mpiService.registerPatient(cmd);
    }

    @GetMapping("/api/v1/mpi/patients/match")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Run probabilistic Fellegi-Sunter duplicate patient match")
    public List<MatchCandidate> match(
            @RequestParam String familyName,
            @RequestParam String givenName,
            @RequestParam LocalDate birthDate,
            @RequestParam String gender,
            @RequestParam(required = false) String phone) {
        return mpiService.findProbabilisticMatches(familyName, givenName, birthDate, gender, phone);
    }

    @GetMapping("/api/v1/mpi/patients/fuzzy-search")
    @PreAuthorize("hasAuthority('PERM_patient:read')")
    @Operation(summary = "Fuzzy patient name/MRN lookup using PostgreSQL pg_trgm GIN trigram similarity")
    public List<PatientAggregateEntity> fuzzySearch(
            @RequestParam UUID facilityId,
            @RequestParam String query,
            @RequestParam(defaultValue = "20") int limit) {
        return mpiService.findByFacilityAndFuzzyName(facilityId, query, limit);
    }

    @PostMapping("/api/v1/mpi/patients/merge")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Reversibly merge duplicate patient record into surviving Golden Record")
    public Object merge(
            @RequestParam UUID facilityId,
            @RequestParam UUID sourceId,
            @RequestParam UUID survivingId,
            @RequestParam String mergedBy,
            @RequestParam(defaultValue = "Duplicate registration") String reason) {
        return mpiService.mergePatients(facilityId, sourceId, survivingId, mergedBy, reason);
    }

    @PostMapping("/api/v1/mpi/patients/unmerge")
    @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
    @Operation(summary = "Reverse a prior MPI patient merge by mergeHistoryId")
    public Object unmerge(
            @RequestParam UUID mergeHistoryId,
            @RequestParam String reversedBy,
            @RequestParam(defaultValue = "False positive merge") String reason) {
        return mpiService.unmergePatients(mergeHistoryId, reversedBy, reason);
    }

    @PostMapping(value = "/fhir/r4/Patient", consumes = {"application/fhir+json", "application/json"}, produces = "application/fhir+json")
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    @Operation(summary = "Create HL7 FHIR R4 Patient resource mapping identifiers, HumanName, telecom, address, and contacts")
    public ResponseEntity<String> createFhirPatient(
            @RequestHeader(value = "X-Facility-Id", required = false) UUID headerFacilityId,
            @RequestBody String fhirPatientJson) {
        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        Patient incoming = json.parseResource(Patient.class, fhirPatientJson);
        UUID facilityId = headerFacilityId != null
                ? headerFacilityId
                : FacilityTenantInterceptor.currentFacility();
        if (facilityId == null) {
            facilityId = new UUID(0L, 1L);
        }

        RegisterPatientCommand cmd = toRegisterCommand(facilityId, incoming);
        PatientAggregateEntity saved = mpiService.registerPatient(cmd);
        saveExtraIdentifiers(saved.getId(), incoming, cmd.nationalIdSystem());

        List<PatientIdentifierEntity> idents = identifierRepository != null
                ? identifierRepository.findByPatientIdOrderByIdentifierSystemAsc(saved.getId())
                : List.of();
        PatientDemographicsEntity demo = demographicsRepository != null
                ? demographicsRepository.findByPatientId(saved.getId()).orElse(null)
                : null;

        return ResponseEntity.created(URI.create("/fhir/r4/Patient/" + saved.getId()))
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .eTag("W/\"" + saved.getVersion() + "\"")
                .body(json.encodeResourceToString(toFhirPatient(saved, idents, demo)));
    }

    @GetMapping(value = "/fhir/r4/Patient/{id}", produces = "application/fhir+json")
    @Operation(summary = "Read HL7 FHIR R4 Patient resource with ETag")
    public ResponseEntity<String> readFhirPatient(@PathVariable UUID id) {
        PatientAggregateEntity entity = patientRepository.findById(id)
                .map(mpiService::resolveSurviving)
                .orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        List<PatientIdentifierEntity> idents = identifierRepository != null
                ? identifierRepository.findByPatientIdOrderByIdentifierSystemAsc(entity.getId())
                : List.of();
        PatientDemographicsEntity demo = demographicsRepository != null
                ? demographicsRepository.findByPatientId(entity.getId()).orElse(null)
                : null;

        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .eTag("W/\"" + entity.getVersion() + "\"")
                .body(json.encodeResourceToString(toFhirPatient(entity, idents, demo)));
    }

    @PutMapping(value = "/fhir/r4/Patient/{id}", consumes = {"application/fhir+json", "application/json"}, produces = "application/fhir+json")
    @PreAuthorize("hasAuthority('PERM_patient:write')")
    @Operation(summary = "Update HL7 FHIR R4 Patient resource enforcing If-Match ETag optimistic concurrency")
    public ResponseEntity<String> updateFhirPatient(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody String fhirPatientJson) {
        PatientAggregateEntity entity = patientRepository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        String currentEtag = "W/\"" + entity.getVersion() + "\"";
        String rawVersion = Long.toString(entity.getVersion());
        if (ifMatch != null && !ifMatch.isBlank()
                && !Objects.equals(ifMatch.trim(), currentEtag)
                && !Objects.equals(ifMatch.replace("\"", "").replace("W/", "").trim(), rawVersion)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"conflict\",\"diagnostics\":\"Stale ETag in If-Match header; expected " + currentEtag + "\"}]}");
        }
        IParser json = FHIR_R4.newJsonParser().setPrettyPrint(true);
        Patient incoming = json.parseResource(Patient.class, fhirPatientJson);
        applyFhirUpdates(entity, incoming);
        entity.setUpdatedAt(Instant.now());
        PatientAggregateEntity saved = patientRepository.save(entity);

        PatientDemographicsEntity demo = upsertDemographicsFromFhir(saved.getId(), incoming);
        upsertIdentifiersFromFhir(saved.getId(), incoming);
        List<PatientIdentifierEntity> idents = identifierRepository != null
                ? identifierRepository.findByPatientIdOrderByIdentifierSystemAsc(saved.getId())
                : List.of();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/fhir+json"))
                .eTag("W/\"" + saved.getVersion() + "\"")
                .body(json.encodeResourceToString(toFhirPatient(saved, idents, demo)));
    }

    public static Patient toFhirPatient(PatientAggregateEntity e) {
        return toFhirPatient(e, List.of(), null);
    }

    public static Patient toFhirPatient(
            PatientAggregateEntity e,
            List<PatientIdentifierEntity> identifiers,
            PatientDemographicsEntity demo) {
        Patient p = new Patient();
        p.setId(e.getId().toString());
        p.getMeta().setVersionId(Long.toString(e.getVersion()));
        if (e.getUpdatedAt() != null) {
            p.getMeta().setLastUpdated(Date.from(e.getUpdatedAt()));
        }

        // Identifiers: canonical MRN + encrypted vault identifiers
        Identifier mrnIdent = p.addIdentifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem(MRN_SYSTEM)
                .setValue(e.getMrn());
        mrnIdent.getType().addCoding()
                .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                .setCode("MR")
                .setDisplay("Medical record number");

        if (identifiers != null) {
            for (PatientIdentifierEntity ident : identifiers) {
                if (MRN_SYSTEM.equals(ident.getIdentifierSystem())) {
                    continue;
                }
                Identifier fhirIdent = p.addIdentifier()
                        .setSystem(ident.getIdentifierSystem())
                        .setValue(ident.getEncryptedValue());
                if (ident.getIdentifierUse() != null) {
                    try {
                        fhirIdent.setUse(Identifier.IdentifierUse.fromCode(ident.getIdentifierUse().toLowerCase(Locale.ROOT)));
                    } catch (Exception ignored) {
                        fhirIdent.setUse(Identifier.IdentifierUse.OFFICIAL);
                    }
                }
                if (ident.getTypeCode() != null && !ident.getTypeCode().isBlank()) {
                    fhirIdent.getType().addCoding()
                            .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                            .setCode(ident.getTypeCode());
                }
                if (ident.getAssignerName() != null && !ident.getAssignerName().isBlank()) {
                    fhirIdent.setAssigner(new Reference().setDisplay(ident.getAssignerName()));
                }
            }
        }

        // HumanName: prefix, given, middle, family
        HumanName name = p.addName().setUse(HumanName.NameUse.OFFICIAL);
        if (e.getNamePrefix() != null && !e.getNamePrefix().isBlank()) {
            name.addPrefix(e.getNamePrefix());
        }
        if (e.getFamilyName() != null && !e.getFamilyName().isBlank()) {
            name.setFamily(e.getFamilyName());
        }
        if (e.getGivenName() != null && !e.getGivenName().isBlank()) {
            name.addGiven(e.getGivenName());
        }
        if (e.getMiddleName() != null && !e.getMiddleName().isBlank()) {
            name.addGiven(e.getMiddleName());
        }

        p.setActive(e.isActive());
        if (e.getDeceasedAt() != null) {
            p.setDeceased(new DateTimeType(Date.from(e.getDeceasedAt())));
        } else {
            p.setDeceased(new BooleanType(e.isDeceased()));
        }
        if (e.getBirthDate() != null) {
            p.setBirthDate(Date.from(e.getBirthDate().atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (e.getGender() != null && !e.getGender().isBlank()) {
            try {
                p.setGender(AdministrativeGender.fromCode(e.getGender().toLowerCase(Locale.ROOT)));
            } catch (Exception ignored) {
                p.setGender(AdministrativeGender.UNKNOWN);
            }
        }

        // Telecom: mobile/primary phone, secondary phone, email
        if (e.getPrimaryPhone() != null && !e.getPrimaryPhone().isBlank()) {
            p.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.MOBILE)
                    .setRank(1)
                    .setValue(e.getPrimaryPhone());
        }
        if (e.getSecondaryPhone() != null && !e.getSecondaryPhone().isBlank()) {
            p.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.HOME)
                    .setRank(2)
                    .setValue(e.getSecondaryPhone());
        }
        if (e.getEmail() != null && !e.getEmail().isBlank()) {
            p.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                    .setUse(ContactPoint.ContactPointUse.HOME)
                    .setValue(e.getEmail());
        }

        if (e.getMaritalStatusCode() != null && !e.getMaritalStatusCode().isBlank()) {
            p.setMaritalStatus(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus")
                    .setCode(e.getMaritalStatusCode())));
        }

        if (e.getPreferredLanguage() != null && !e.getPreferredLanguage().isBlank()) {
            Patient.PatientCommunicationComponent comm = p.addCommunication();
            comm.setLanguage(new CodeableConcept().addCoding(new Coding()
                    .setSystem("urn:ietf:bcp:47")
                    .setCode(e.getPreferredLanguage())));
            comm.setPreferred(demo == null || demo.isCommunicationPreferred());
        }

        if (demo != null) {
            if (hasAnyAddressField(demo)) {
                Address addr = p.addAddress();
                if (demo.getAddressUse() != null) {
                    try {
                        addr.setUse(Address.AddressUse.fromCode(demo.getAddressUse().toLowerCase(Locale.ROOT)));
                    } catch (Exception ignored) {
                        addr.setUse(Address.AddressUse.HOME);
                    }
                }
                if (demo.getAddressLine() != null && !demo.getAddressLine().isBlank()) {
                    addr.addLine(demo.getAddressLine());
                }
                if (demo.getCityOrVillage() != null) addr.setCity(demo.getCityOrVillage());
                if (demo.getDistrict() != null) addr.setDistrict(demo.getDistrict());
                if (demo.getRegionOrState() != null) addr.setState(demo.getRegionOrState());
                if (demo.getPostalCode() != null) addr.setPostalCode(demo.getPostalCode());
                if (demo.getCountry() != null) addr.setCountry(demo.getCountry());
            }

            if (demo.getEmergencyContactName() != null || demo.getEmergencyContactPhone() != null || demo.getEmergencyContactEmail() != null) {
                Patient.ContactComponent contact = p.addContact();
                contact.addRelationship().addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/v2-0131")
                        .setCode("C")
                        .setDisplay(demo.getEmergencyRelationship() != null ? demo.getEmergencyRelationship() : "Emergency Contact"));
                if (demo.getEmergencyContactName() != null && !demo.getEmergencyContactName().isBlank()) {
                    contact.setName(new HumanName().setText(demo.getEmergencyContactName()));
                }
                if (demo.getEmergencyContactPhone() != null && !demo.getEmergencyContactPhone().isBlank()) {
                    contact.addTelecom()
                            .setSystem(ContactPoint.ContactPointSystem.PHONE)
                            .setValue(demo.getEmergencyContactPhone());
                }
                if (demo.getEmergencyContactEmail() != null && !demo.getEmergencyContactEmail().isBlank()) {
                    contact.addTelecom()
                            .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                            .setValue(demo.getEmergencyContactEmail());
                }
            }
        }
        return p;
    }

    private static RegisterPatientCommand toRegisterCommand(UUID facilityId, Patient incoming) {
        String mrn = null;
        String nationalIdSystem = null;
        String nationalIdValue = null;
        for (Identifier ident : incoming.getIdentifier()) {
            if (MRN_SYSTEM.equals(ident.getSystem()) && ident.hasValue()) {
                mrn = ident.getValue();
            } else if (nationalIdValue == null && ident.hasValue()) {
                nationalIdSystem = ident.hasSystem() ? ident.getSystem() : NATIONAL_ID_SYSTEM;
                nationalIdValue = ident.getValue();
            }
        }

        String prefix = null;
        String family = "Unknown";
        String given = "Unknown";
        String middle = null;
        if (!incoming.getName().isEmpty()) {
            HumanName hn = incoming.getNameFirstRep();
            if (!hn.getPrefix().isEmpty()) prefix = hn.getPrefixAsSingleText();
            if (hn.hasFamily()) family = hn.getFamily();
            List<StringType> givens = hn.getGiven();
            if (!givens.isEmpty()) {
                given = givens.getFirst().getValue();
                if (givens.size() > 1) {
                    middle = String.join(" ", givens.subList(1, givens.size()).stream().map(StringType::getValue).toList());
                }
            }
        }

        LocalDate birthDate = incoming.hasBirthDate()
                ? incoming.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                : LocalDate.of(1970, 1, 1);
        String gender = incoming.hasGender() ? incoming.getGender().toCode().toUpperCase(Locale.ROOT) : "UNKNOWN";

        String primaryPhone = null;
        String secondaryPhone = null;
        String email = null;
        for (ContactPoint cp : incoming.getTelecom()) {
            if (!cp.hasValue()) continue;
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL && email == null) {
                email = cp.getValue();
            } else if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE || cp.getSystem() == null) {
                if (primaryPhone == null) primaryPhone = cp.getValue();
                else if (secondaryPhone == null) secondaryPhone = cp.getValue();
            }
        }

        String maritalStatus = incoming.hasMaritalStatus() && !incoming.getMaritalStatus().getCoding().isEmpty()
                ? incoming.getMaritalStatus().getCodingFirstRep().getCode()
                : null;
        String lang = !incoming.getCommunication().isEmpty() && incoming.getCommunicationFirstRep().hasLanguage()
                && !incoming.getCommunicationFirstRep().getLanguage().getCoding().isEmpty()
                ? incoming.getCommunicationFirstRep().getLanguage().getCodingFirstRep().getCode()
                : "en";

        String addrLine = null;
        String city = null;
        String district = null;
        String state = null;
        String postal = null;
        String country = null;
        if (!incoming.getAddress().isEmpty()) {
            Address addr = incoming.getAddressFirstRep();
            if (!addr.getLine().isEmpty()) {
                addrLine = String.join(", ", addr.getLine().stream().map(StringType::getValue).toList());
            }
            if (addr.hasCity()) city = addr.getCity();
            if (addr.hasDistrict()) district = addr.getDistrict();
            if (addr.hasState()) state = addr.getState();
            if (addr.hasPostalCode()) postal = addr.getPostalCode();
            if (addr.hasCountry()) country = addr.getCountry();
        }

        String ecName = null;
        String ecPhone = null;
        String ecEmail = null;
        String ecRel = null;
        if (!incoming.getContact().isEmpty()) {
            Patient.ContactComponent cc = incoming.getContactFirstRep();
            if (cc.hasName()) {
                ecName = cc.getName().hasText() ? cc.getName().getText() : cc.getName().getNameAsSingleText();
            }
            for (ContactPoint cp : cc.getTelecom()) {
                if (!cp.hasValue()) continue;
                if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL && ecEmail == null) ecEmail = cp.getValue();
                else if (ecPhone == null) ecPhone = cp.getValue();
            }
            if (!cc.getRelationship().isEmpty() && !cc.getRelationshipFirstRep().getCoding().isEmpty()) {
                Coding rel = cc.getRelationshipFirstRep().getCodingFirstRep();
                ecRel = rel.hasDisplay() ? rel.getDisplay() : rel.getCode();
            }
        }

        return new RegisterPatientCommand(
                facilityId, mrn, prefix, family, given, middle, birthDate, gender, null,
                primaryPhone, secondaryPhone, email, maritalStatus, lang,
                nationalIdSystem, nationalIdValue,
                addrLine, city, district, state, postal, country,
                ecName, ecPhone, ecEmail, ecRel
        );
    }

    private static void applyFhirUpdates(PatientAggregateEntity entity, Patient incoming) {
        if (!incoming.getName().isEmpty()) {
            HumanName hn = incoming.getNameFirstRep();
            if (!hn.getPrefix().isEmpty()) entity.setNamePrefix(hn.getPrefixAsSingleText());
            if (hn.hasFamily()) entity.setFamilyName(hn.getFamily());
            List<StringType> givens = hn.getGiven();
            if (!givens.isEmpty()) {
                entity.setGivenName(givens.getFirst().getValue());
                if (givens.size() > 1) {
                    entity.setMiddleName(String.join(" ", givens.subList(1, givens.size()).stream().map(StringType::getValue).toList()));
                }
            }
        }
        if (incoming.hasActive()) entity.setActive(incoming.getActive());
        if (incoming.hasGender()) entity.setGender(incoming.getGender().toCode().toUpperCase(Locale.ROOT));
        if (incoming.hasBirthDate()) {
            entity.setBirthDate(incoming.getBirthDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
        }
        if (incoming.hasDeceasedBooleanType()) {
            entity.setDeceased(incoming.getDeceasedBooleanType().booleanValue());
            if (!entity.isDeceased()) entity.setDeceasedAt(null);
        } else if (incoming.hasDeceasedDateTimeType()) {
            entity.setDeceased(true);
            entity.setDeceasedAt(incoming.getDeceasedDateTimeType().getValue().toInstant());
        }
        if (incoming.hasMaritalStatus() && !incoming.getMaritalStatus().getCoding().isEmpty()) {
            entity.setMaritalStatusCode(incoming.getMaritalStatus().getCodingFirstRep().getCode());
        }
        if (!incoming.getCommunication().isEmpty() && incoming.getCommunicationFirstRep().hasLanguage()
                && !incoming.getCommunicationFirstRep().getLanguage().getCoding().isEmpty()) {
            entity.setPreferredLanguage(incoming.getCommunicationFirstRep().getLanguage().getCodingFirstRep().getCode());
        }

        boolean sawPrimaryPhone = false;
        for (ContactPoint cp : incoming.getTelecom()) {
            if (!cp.hasValue()) continue;
            if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL) {
                entity.setEmail(cp.getValue());
            } else if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE || cp.getSystem() == null) {
                if (!sawPrimaryPhone) {
                    entity.setPrimaryPhone(cp.getValue());
                    sawPrimaryPhone = true;
                } else {
                    entity.setSecondaryPhone(cp.getValue());
                }
            }
        }
    }

    private PatientDemographicsEntity upsertDemographicsFromFhir(UUID patientId, Patient incoming) {
        if (demographicsRepository == null) return null;
        PatientDemographicsEntity demo = demographicsRepository.findByPatientId(patientId)
                .orElseGet(() -> {
                    PatientDemographicsEntity created = new PatientDemographicsEntity();
                    created.setPatientId(patientId);
                    return created;
                });

        if (!incoming.getAddress().isEmpty()) {
            Address addr = incoming.getAddressFirstRep();
            if (addr.hasUse()) demo.setAddressUse(addr.getUse().toCode().toUpperCase(Locale.ROOT));
            if (!addr.getLine().isEmpty()) {
                demo.setAddressLine(String.join(", ", addr.getLine().stream().map(StringType::getValue).toList()));
            }
            if (addr.hasCity()) demo.setCityOrVillage(addr.getCity());
            if (addr.hasDistrict()) demo.setDistrict(addr.getDistrict());
            if (addr.hasState()) demo.setRegionOrState(addr.getState());
            if (addr.hasPostalCode()) demo.setPostalCode(addr.getPostalCode());
            if (addr.hasCountry()) demo.setCountry(addr.getCountry());
        }

        if (!incoming.getContact().isEmpty()) {
            Patient.ContactComponent cc = incoming.getContactFirstRep();
            if (cc.hasName()) {
                demo.setEmergencyContactName(cc.getName().hasText() ? cc.getName().getText() : cc.getName().getNameAsSingleText());
            }
            for (ContactPoint cp : cc.getTelecom()) {
                if (!cp.hasValue()) continue;
                if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL) demo.setEmergencyContactEmail(cp.getValue());
                else demo.setEmergencyContactPhone(cp.getValue());
            }
            if (!cc.getRelationship().isEmpty() && !cc.getRelationshipFirstRep().getCoding().isEmpty()) {
                Coding rel = cc.getRelationshipFirstRep().getCodingFirstRep();
                demo.setEmergencyRelationship(rel.hasDisplay() ? rel.getDisplay() : rel.getCode());
            }
        }

        if (!incoming.getCommunication().isEmpty() && incoming.getCommunicationFirstRep().hasPreferred()) {
            demo.setCommunicationPreferred(incoming.getCommunicationFirstRep().getPreferred());
        }
        return demographicsRepository.save(demo);
    }

    private void upsertIdentifiersFromFhir(UUID patientId, Patient incoming) {
        if (identifierRepository == null) return;
        for (Identifier ident : incoming.getIdentifier()) {
            if (!ident.hasValue() || MRN_SYSTEM.equals(ident.getSystem())) {
                continue;
            }
            String sys = ident.hasSystem() ? ident.getSystem() : NATIONAL_ID_SYSTEM;
            PatientIdentifierEntity entity = identifierRepository
                    .findFirstByPatientIdAndIdentifierSystem(patientId, sys)
                    .orElseGet(() -> {
                        PatientIdentifierEntity created = new PatientIdentifierEntity();
                        created.setPatientId(patientId);
                        created.setIdentifierSystem(sys);
                        return created;
                    });
            entity.setEncryptedValue(ident.getValue());
            if (ident.hasUse()) entity.setIdentifierUse(ident.getUse().toCode().toUpperCase(Locale.ROOT));
            if (ident.hasType() && !ident.getType().getCoding().isEmpty()) {
                entity.setTypeCode(ident.getType().getCodingFirstRep().getCode());
            }
            if (ident.hasAssigner() && ident.getAssigner().hasDisplay()) {
                entity.setAssignerName(ident.getAssigner().getDisplay());
            }
            identifierRepository.save(entity);
        }
    }

    private void saveExtraIdentifiers(UUID patientId, Patient incoming, String alreadySavedSystem) {
        if (identifierRepository == null) return;
        boolean skippedFirstNonMrn = false;
        for (Identifier ident : incoming.getIdentifier()) {
            if (!ident.hasValue() || MRN_SYSTEM.equals(ident.getSystem())) {
                continue;
            }
            String sys = ident.hasSystem() ? ident.getSystem() : NATIONAL_ID_SYSTEM;
            if (!skippedFirstNonMrn && Objects.equals(sys, alreadySavedSystem)) {
                skippedFirstNonMrn = true;
                continue;
            }
            PatientIdentifierEntity extra = new PatientIdentifierEntity();
            extra.setPatientId(patientId);
            extra.setIdentifierSystem(sys);
            if (ident.hasUse()) extra.setIdentifierUse(ident.getUse().toCode().toUpperCase(Locale.ROOT));
            if (ident.hasType() && !ident.getType().getCoding().isEmpty()) {
                extra.setTypeCode(ident.getType().getCodingFirstRep().getCode());
            }
            if (ident.hasAssigner() && ident.getAssigner().hasDisplay()) {
                extra.setAssignerName(ident.getAssigner().getDisplay());
            }
            extra.setEncryptedValue(ident.getValue());
            identifierRepository.save(extra);
        }
    }

    private static boolean hasAnyAddressField(PatientDemographicsEntity demo) {
        return demo.getAddressLine() != null
                || demo.getCityOrVillage() != null
                || demo.getDistrict() != null
                || demo.getRegionOrState() != null
                || demo.getPostalCode() != null
                || demo.getCountry() != null;
    }
}
