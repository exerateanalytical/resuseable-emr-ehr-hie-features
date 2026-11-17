/*
 * =============================================================================
 * Opes HealthCore SDK — 18+ Role Granular RBAC & Clinical Permission Matrix
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.security.rbac;

import java.util.Set;

/**
 * Pre-seeded hierarchical RBAC catalog tailored for hospitals, clinics, pharmacies,
 * diagnostic centers, specialty clinics, and community health programs.
 */
public enum HealthcareRole {
    SUPER_ADMIN(Set.of("system:admin", "facility:onboard", "audit:export", "template:publish", "dr:backup")),
    FACILITY_ADMIN(Set.of("facility:manage", "staff:onboard", "template:customize", "dashboard:executive", "audit:read")),
    CHIEF_MEDICAL_OFFICER(Set.of("encounter:sign", "note:cosign", "rx:controlled:prescribe", "break_glass:review", "dashboard:clinical")),
    ATTENDING_PHYSICIAN(Set.of("patient:read", "patient:write", "encounter:sign", "note:cosign", "order:sign", "rx:prescribe", "rx:controlled:prescribe", "ot:schedule")),
    SPECIALIST_OPHTHALMOLOGIST(Set.of("patient:read", "encounter:sign", "specialty:eye:write", "order:sign", "rx:prescribe")),
    DENTIST(Set.of("patient:read", "encounter:sign", "specialty:dental:write", "order:sign", "rx:prescribe")),
    CARDIOLOGIST(Set.of("patient:read", "encounter:sign", "specialty:cardiac:write", "order:sign", "rx:prescribe")),
    PEDIATRICIAN(Set.of("patient:read", "encounter:sign", "specialty:peds:write", "immunization:write", "order:sign", "rx:prescribe")),
    DERMATOLOGIST(Set.of("patient:read", "encounter:sign", "specialty:derm:write", "order:sign", "rx:prescribe")),
    RESIDENT_TRAINEE(Set.of("patient:read", "encounter:draft", "order:draft", "rx:draft")), // Requires attending co-sign
    NURSE(Set.of("patient:read", "triage:write", "vitals:write", "emar:administer", "io:write", "ward:handover")),
    PHARMACIST(Set.of("patient:read", "rx:read", "rx:dispense", "pharmacy:stock:adjust", "rx:controlled:dispense")),
    LAB_SCIENTIST(Set.of("patient:read", "lab:accession", "lab:result:enter", "lab:verify", "bloodbank:crossmatch")),
    RADIOLOGIST(Set.of("patient:read", "imaging:report:sign", "dicom:index")),
    CHW_COMMUNITY_WORKER(Set.of("chw:household:write", "chw:visit:write", "chw:referral:create", "immunization:record")),
    BILLING_INSURANCE_CLERK(Set.of("billing:invoice", "billing:collect", "claim:submit", "uhc:verify")),
    COMPLIANCE_AUDITOR(Set.of("audit:read", "audit:verify_chain", "audit:export", "break_glass:review")),
    PATIENT(Set.of("self:record:read", "self:consent:manage", "self:appointment:request", "self:payment:initiate"));

    private final Set<String> permissions;

    HealthcareRole(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Set<String> permissions() {
        return permissions;
    }
}
