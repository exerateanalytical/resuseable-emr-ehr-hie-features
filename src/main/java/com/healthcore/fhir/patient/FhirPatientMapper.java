package com.healthcore.fhir.patient;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import com.healthcore.mpi.*;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Component
public class FhirPatientMapper {

    public static final String BLOOD_GROUP_EXT_URL = "http://healthcore.cm/fhir/StructureDefinition/blood-group";
    private final FhirContext fhirContext = FhirContext.forR4();

    public Patient parseFhirPatient(String json) {
        IParser parser = fhirContext.newJsonParser();
        return parser.parseResource(Patient.class, json);
    }

    public String encode(Resource resource) {
        return fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(resource);
    }

    public Patient toFhirPatient(PatientEntity entity, List<PatientIdentifierEntity> extraIdentifiers) {
        Patient patient = new Patient();
        patient.setId(entity.getId().toString());
        patient.setActive(entity.isActive());

        // Enterprise MPI identifier + linked identifiers (CNI, MRN, Insurance)
        patient.addIdentifier()
                .setUse(Identifier.IdentifierUse.OFFICIAL)
                .setSystem("urn:healthcore:enterprise-mpi")
                .setValue(entity.getEnterpriseId());

        for (PatientIdentifierEntity id : extraIdentifiers) {
            Identifier fhirId = patient.addIdentifier()
                    .setSystem(id.getSystemUri() != null ? id.getSystemUri() : "urn:healthcore:" + id.getIdType().toLowerCase())
                    .setValue(id.getIdValue());
            if (id.getUseCode() != null) {
                try {
                    fhirId.setUse(Identifier.IdentifierUse.fromCode(id.getUseCode().toLowerCase()));
                } catch (Exception ignored) {}
            }
        }

        // HumanName with prefix, given, middle names, family, suffix
        HumanName name = patient.addName()
                .setUse(HumanName.NameUse.OFFICIAL)
                .setFamily(entity.getFamilyName())
                .addGiven(entity.getGivenName());
        if (entity.getMiddleNames() != null) {
            entity.getMiddleNames().forEach(name::addGiven);
        }
        if (entity.getNamePrefix() != null) {
            name.addPrefix(entity.getNamePrefix());
        }
        if (entity.getNameSuffix() != null) {
            name.addSuffix(entity.getNameSuffix());
        }

        if (entity.getDateOfBirth() != null) {
            patient.setBirthDate(Date.valueOf(entity.getDateOfBirth()));
        }
        patient.setGender(mapToFhirGender(entity.getSex()));

        // Deceased
        if (entity.getDeceasedDateTime() != null) {
            patient.setDeceased(new DateTimeType(java.util.Date.from(entity.getDeceasedDateTime())));
        } else if (entity.isDeceasedBoolean()) {
            patient.setDeceased(new BooleanType(true));
        }

        // Multiple birth
        if (entity.getMultipleBirthInteger() != null) {
            patient.setMultipleBirth(new IntegerType(entity.getMultipleBirthInteger()));
        } else if (entity.getMultipleBirthBoolean() != null) {
            patient.setMultipleBirth(new BooleanType(entity.getMultipleBirthBoolean()));
        }

        // Marital status
        if (entity.getMaritalStatusCode() != null) {
            patient.setMaritalStatus(new CodeableConcept().addCoding(new Coding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus")
                    .setCode(entity.getMaritalStatusCode())));
        }

        // Telecoms
        if (entity.getTelecoms().isEmpty() && entity.getPrimaryPhone() != null) {
            patient.addTelecom()
                    .setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.MOBILE)
                    .setValue(entity.getPrimaryPhone());
        } else {
            for (PatientTelecomEntity t : entity.getTelecoms()) {
                ContactPoint cp = patient.addTelecom().setValue(t.getValue());
                try {
                    cp.setSystem(ContactPoint.ContactPointSystem.fromCode(t.getSystem().toLowerCase()));
                } catch (Exception ignored) {}
                if (t.getUseCode() != null) {
                    try {
                        cp.setUse(ContactPoint.ContactPointUse.fromCode(t.getUseCode().toLowerCase()));
                    } catch (Exception ignored) {}
                }
                if (t.getRank() != null) {
                    cp.setRank(t.getRank());
                }
            }
        }

        // Addresses
        if (entity.getAddresses().isEmpty() && (entity.getCity() != null || entity.getAddressLine() != null)) {
            Address addr = patient.addAddress();
            if (entity.getAddressLine() != null) addr.addLine(entity.getAddressLine());
            if (entity.getCity() != null) addr.setCity(entity.getCity());
        } else {
            for (PatientAddressEntity a : entity.getAddresses()) {
                Address addr = patient.addAddress()
                        .setCity(a.getCity())
                        .setDistrict(a.getDistrict())
                        .setState(a.getStateRegion())
                        .setPostalCode(a.getPostalCode())
                        .setCountry(a.getCountry());
                if (a.getLine1() != null) addr.addLine(a.getLine1());
                if (a.getLine2() != null) addr.addLine(a.getLine2());
                if (a.getUseCode() != null) {
                    try { addr.setUse(Address.AddressUse.fromCode(a.getUseCode().toLowerCase())); } catch (Exception ignored) {}
                }
                if (a.getAddressType() != null) {
                    try { addr.setType(Address.AddressType.fromCode(a.getAddressType().toLowerCase())); } catch (Exception ignored) {}
                }
            }
        }

        // Emergency Contacts / Next-of-Kin
        for (PatientContactEntity c : entity.getContacts()) {
            Patient.ContactComponent cc = patient.addContact();
            cc.addRelationship().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0131")
                    .setCode(c.getRelationshipCode())
                    .setDisplay(c.getRelationshipDisplay());
            if (c.getFamilyName() != null || c.getGivenName() != null) {
                HumanName cn = new HumanName();
                if (c.getFamilyName() != null) cn.setFamily(c.getFamilyName());
                if (c.getGivenName() != null) cn.addGiven(c.getGivenName());
                cc.setName(cn);
            }
            if (c.getPhone() != null) {
                cc.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE).setValue(c.getPhone());
            }
            if (c.getEmail() != null) {
                cc.addTelecom().setSystem(ContactPoint.ContactPointSystem.EMAIL).setValue(c.getEmail());
            }
            if (c.getGender() != null) {
                cc.setGender(mapToFhirGender(c.getGender()));
            }
        }

        // Communications / Spoken Languages
        for (PatientCommunicationEntity comm : entity.getCommunications()) {
            Patient.PatientCommunicationComponent pc = patient.addCommunication();
            pc.setPreferred(comm.isPreferred());
            pc.getLanguage().addCoding()
                    .setSystem("urn:ietf:bcp:47")
                    .setCode(comm.getLanguageCode())
                    .setDisplay(comm.getLanguageDisplay());
        }

        // Managing organization & GP
        if (entity.getManagingOrganizationId() != null) {
            patient.setManagingOrganization(new Reference("Organization/" + entity.getManagingOrganizationId()));
        }
        if (entity.getGeneralPractitionerRef() != null) {
            patient.addGeneralPractitioner(new Reference(entity.getGeneralPractitionerRef()));
        }

        // Blood group extension
        if (entity.getBloodGroup() != null) {
            patient.addExtension(BLOOD_GROUP_EXT_URL, new StringType(entity.getBloodGroup()));
        }

        return patient;
    }

    public void applyFhirToEntity(Patient fhir, PatientEntity entity) {
        if (fhir.hasActive()) {
            entity.setActive(fhir.getActive());
        }

        if (fhir.hasName()) {
            HumanName primaryName = fhir.getNameFirstRep();
            if (primaryName.hasFamily()) {
                entity.setFamilyName(primaryName.getFamily().trim());
            }
            if (!primaryName.getGiven().isEmpty()) {
                entity.setGivenName(primaryName.getGiven().get(0).getValue().trim());
                List<String> middles = primaryName.getGiven().stream()
                        .skip(1)
                        .map(StringType::getValue)
                        .filter(Objects::nonNull)
                        .toList();
                entity.setMiddleNames(new ArrayList<>(middles));
            }
            if (!primaryName.getPrefix().isEmpty()) {
                entity.setNamePrefix(primaryName.getPrefixAsSingleString());
            }
            if (!primaryName.getSuffix().isEmpty()) {
                entity.setNameSuffix(primaryName.getSuffixAsSingleString());
            }
        }

        if (entity.getGivenName() == null || entity.getFamilyName() == null) {
            throw new IllegalArgumentException("FHIR Patient must include name.given and name.family");
        }

        if (fhir.hasBirthDate()) {
            LocalDate dob = fhir.getBirthDate().toInstant().atZone(ZoneOffset.UTC).toLocalDate();
            entity.setDateOfBirth(dob);
        }
        if (entity.getDateOfBirth() == null) {
            throw new IllegalArgumentException("FHIR Patient must include birthDate");
        }

        entity.setSex(fhir.hasGender() ? fhir.getGender().toCode().toUpperCase() : "UNKNOWN");

        if (fhir.hasMaritalStatus() && !fhir.getMaritalStatus().getCoding().isEmpty()) {
            entity.setMaritalStatusCode(fhir.getMaritalStatus().getCodingFirstRep().getCode());
        }

        if (fhir.hasDeceasedBooleanType()) {
            entity.setDeceasedBoolean(fhir.getDeceasedBooleanType().booleanValue());
            entity.setDeceasedDateTime(null);
        } else if (fhir.hasDeceasedDateTimeType()) {
            entity.setDeceasedBoolean(true);
            entity.setDeceasedDateTime(fhir.getDeceasedDateTimeType().getValue().toInstant());
        }

        if (fhir.hasMultipleBirthBooleanType()) {
            entity.setMultipleBirthBoolean(fhir.getMultipleBirthBooleanType().booleanValue());
            entity.setMultipleBirthInteger(null);
        } else if (fhir.hasMultipleBirthIntegerType()) {
            entity.setMultipleBirthBoolean(true);
            entity.setMultipleBirthInteger(fhir.getMultipleBirthIntegerType().getValue());
        }

        // Telecoms
        List<PatientTelecomEntity> telecoms = new ArrayList<>();
        String firstPhone = null;
        for (ContactPoint cp : fhir.getTelecom()) {
            if (!cp.hasValue()) continue;
            PatientTelecomEntity te = new PatientTelecomEntity();
            te.setSystem(cp.hasSystem() ? cp.getSystem().toCode() : "phone");
            te.setValue(cp.getValue().trim());
            te.setUseCode(cp.hasUse() ? cp.getUse().toCode() : null);
            te.setRank(cp.hasRank() ? cp.getRank() : null);
            telecoms.add(te);
            if (firstPhone == null && "phone".equalsIgnoreCase(te.getSystem())) {
                firstPhone = te.getValue();
            }
        }
        entity.setPrimaryPhone(firstPhone);
        entity.replaceTelecoms(telecoms);

        // Addresses
        List<PatientAddressEntity> addresses = new ArrayList<>();
        String firstCity = null;
        String firstLine = null;
        for (Address addr : fhir.getAddress()) {
            PatientAddressEntity ae = new PatientAddressEntity();
            ae.setUseCode(addr.hasUse() ? addr.getUse().toCode() : null);
            ae.setAddressType(addr.hasType() ? addr.getType().toCode() : null);
            if (addr.getLine().size() > 0) ae.setLine1(addr.getLine().get(0).getValue());
            if (addr.getLine().size() > 1) ae.setLine2(addr.getLine().get(1).getValue());
            ae.setCity(addr.getCity());
            ae.setDistrict(addr.getDistrict());
            ae.setStateRegion(addr.getState());
            ae.setPostalCode(addr.getPostalCode());
            ae.setCountry(addr.getCountry());
            addresses.add(ae);
            if (firstCity == null) firstCity = ae.getCity();
            if (firstLine == null) firstLine = ae.getLine1();
        }
        entity.setCity(firstCity);
        entity.setAddressLine(firstLine);
        entity.replaceAddresses(addresses);

        // Emergency Contacts
        List<PatientContactEntity> contacts = new ArrayList<>();
        for (Patient.ContactComponent cc : fhir.getContact()) {
            PatientContactEntity ce = new PatientContactEntity();
            String relCode = cc.hasRelationship() && !cc.getRelationshipFirstRep().getCoding().isEmpty()
                    ? cc.getRelationshipFirstRep().getCodingFirstRep().getCode()
                    : "C";
            String relDisplay = cc.hasRelationship() && !cc.getRelationshipFirstRep().getCoding().isEmpty()
                    ? cc.getRelationshipFirstRep().getCodingFirstRep().getDisplay()
                    : null;
            ce.setRelationshipCode(relCode);
            ce.setRelationshipDisplay(relDisplay);
            if (cc.hasName()) {
                ce.setFamilyName(cc.getName().getFamily());
                if (!cc.getName().getGiven().isEmpty()) {
                    ce.setGivenName(cc.getName().getGiven().get(0).getValue());
                }
            }
            if (cc.hasGender()) {
                ce.setGender(cc.getGender().toCode().toUpperCase());
            }
            for (ContactPoint cp : cc.getTelecom()) {
                if (cp.getSystem() == ContactPoint.ContactPointSystem.PHONE && ce.getPhone() == null) {
                    ce.setPhone(cp.getValue());
                } else if (cp.getSystem() == ContactPoint.ContactPointSystem.EMAIL && ce.getEmail() == null) {
                    ce.setEmail(cp.getValue());
                }
            }
            if (cc.hasAddress() && cc.getAddress().hasText()) {
                ce.setAddressSummary(cc.getAddress().getText());
            }
            contacts.add(ce);
        }
        entity.replaceContacts(contacts);

        // Communications
        List<PatientCommunicationEntity> comms = new ArrayList<>();
        String preferredLang = null;
        for (Patient.PatientCommunicationComponent pcc : fhir.getCommunication()) {
            if (!pcc.hasLanguage() || pcc.getLanguage().getCoding().isEmpty()) continue;
            Coding coding = pcc.getLanguage().getCodingFirstRep();
            PatientCommunicationEntity ce = new PatientCommunicationEntity();
            ce.setLanguageCode(coding.getCode());
            ce.setLanguageDisplay(coding.getDisplay());
            ce.setPreferred(pcc.getPreferred());
            comms.add(ce);
            if (pcc.getPreferred() && preferredLang == null) {
                preferredLang = coding.getCode();
            }
        }
        entity.setPreferredLanguage(preferredLang);
        entity.replaceCommunications(comms);

        // Managing Organization & GP
        if (fhir.hasManagingOrganization() && fhir.getManagingOrganization().hasReference()) {
            String ref = fhir.getManagingOrganization().getReference();
            String rawId = ref.contains("/") ? ref.substring(ref.lastIndexOf('/') + 1) : ref;
            try {
                entity.setManagingOrganizationId(UUID.fromString(rawId));
            } catch (IllegalArgumentException ignored) {}
        }
        if (!fhir.getGeneralPractitioner().isEmpty()) {
            entity.setGeneralPractitionerRef(fhir.getGeneralPractitionerFirstRep().getReference());
        }

        // Blood Group extension
        Extension bgExt = fhir.getExtensionByUrl(BLOOD_GROUP_EXT_URL);
        if (bgExt != null && bgExt.getValue() instanceof StringType st) {
            entity.setBloodGroup(st.getValue());
        }

        entity.setUpdatedAt(Instant.now());
    }

    private Enumerations.AdministrativeGender mapToFhirGender(String sex) {
        if (sex == null) return Enumerations.AdministrativeGender.UNKNOWN;
        return switch (sex.toUpperCase()) {
            case "MALE" -> Enumerations.AdministrativeGender.MALE;
            case "FEMALE" -> Enumerations.AdministrativeGender.FEMALE;
            case "OTHER" -> Enumerations.AdministrativeGender.OTHER;
            default -> Enumerations.AdministrativeGender.UNKNOWN;
        };
    }
}
