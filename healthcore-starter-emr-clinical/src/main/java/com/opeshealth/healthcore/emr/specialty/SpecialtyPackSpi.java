/*
 * =============================================================================
 * Opes HealthCore SDK — Pluggable Specialty Clinic SPI & 5 Built-In Packs
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.emr.specialty;

import java.util.List;
import java.util.Map;

/**
 * Service Provider Interface (SPI) enabling any team to compose a standalone specialized
 * clinic system (Dental, Ophthalmology/Eye, Cardiology, Pediatrics, Dermatology, or custom)
 * in under an hour without modifying core EMR tables.
 */
public interface SpecialtyPackSpi {

    String specialtyCode();
    String displayName();
    List<String> requiredFieldKeys();
    void validatePayload(Map<String, Object> payload);

    /** Dental Clinic Pack: FDI/Universal odontogram, periodontal pocket depths, endodontic/prosthetic plan. */
    class DentalPack implements SpecialtyPackSpi {
        @Override public String specialtyCode() { return "DENTAL"; }
        @Override public String displayName() { return "Dental & Oral Surgery Clinic Pack"; }
        @Override public List<String> requiredFieldKeys() {
            return List.of("toothNumberFdi", "surfaceCode", "periodontalPocketMm", "procedureIcd");
        }
        @Override public void validatePayload(Map<String, Object> payload) {
            if (!payload.containsKey("toothNumberFdi")) {
                throw new IllegalArgumentException("Dental pack requires toothNumberFdi (FDI 11-48 / 51-85)");
            }
        }
    }

    /** Ophthalmology / Eye Clinic Pack: OD/OS Visual Acuity, IOP mmHg, refraction, slit-lamp & fundus. */
    class EyeClinicPack implements SpecialtyPackSpi {
        @Override public String specialtyCode() { return "EYE_CLINIC"; }
        @Override public String displayName() { return "Ophthalmology & Optometry Clinic Pack"; }
        @Override public List<String> requiredFieldKeys() {
            return List.of("odVisualAcuity", "osVisualAcuity", "odIopMmHg", "osIopMmHg", "refractionRx");
        }
        @Override public void validatePayload(Map<String, Object> payload) {
            if (!payload.containsKey("odVisualAcuity") || !payload.containsKey("osVisualAcuity")) {
                throw new IllegalArgumentException("Eye Clinic pack requires odVisualAcuity and osVisualAcuity");
            }
        }
    }

    /** Cardiac Center Pack: 12-lead ECG interpretation, LVEF%, NYHA class, INR anticoagulation target. */
    class CardiacPack implements SpecialtyPackSpi {
        @Override public String specialtyCode() { return "CARDIAC"; }
        @Override public String displayName() { return "Cardiology & Anticoagulation Center Pack"; }
        @Override public List<String> requiredFieldKeys() {
            return List.of("ecgRhythm", "lvefPercent", "nyhaClass", "inrTargetRange");
        }
        @Override public void validatePayload(Map<String, Object> payload) {
            if (!payload.containsKey("ecgRhythm")) {
                throw new IllegalArgumentException("Cardiac pack requires ecgRhythm");
            }
        }
    }

    /** Pediatrics Pack: WHO Growth Z-scores (WAZ/HAZ/WHZ), MUAC cm, developmental milestones, PEWS. */
    class PediatricPack implements SpecialtyPackSpi {
        @Override public String specialtyCode() { return "PEDIATRICS"; }
        @Override public String displayName() { return "Pediatrics & Child Growth Clinic Pack"; }
        @Override public List<String> requiredFieldKeys() {
            return List.of("weightForAgeZScore", "muacMm", "developmentalMilestonesMet", "pewsScore");
        }
        @Override public void validatePayload(Map<String, Object> payload) {
            if (!payload.containsKey("muacMm") && !payload.containsKey("weightForAgeZScore")) {
                throw new IllegalArgumentException("Pediatric pack requires muacMm or weightForAgeZScore");
            }
        }
    }

    /** Dermatology Pack: ABCDE lesion checklist, body-map coordinates, Fitzpatrick phototype, dermatoscope ref. */
    class DermatologyPack implements SpecialtyPackSpi {
        @Override public String specialtyCode() { return "DERMATOLOGY"; }
        @Override public String displayName() { return "Dermatology & Wound Care Pack"; }
        @Override public List<String> requiredFieldKeys() {
            return List.of("anatomicalSite", "fitzpatrickPhototype", "abcdeScore", "lesionDiameterMm");
        }
        @Override public void validatePayload(Map<String, Object> payload) {
            if (!payload.containsKey("anatomicalSite")) {
                throw new IllegalArgumentException("Dermatology pack requires anatomicalSite");
            }
        }
    }
}
