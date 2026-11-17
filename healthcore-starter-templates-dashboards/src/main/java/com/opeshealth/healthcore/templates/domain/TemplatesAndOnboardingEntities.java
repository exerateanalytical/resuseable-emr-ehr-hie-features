/*
 * =============================================================================
 * Opes HealthCore SDK — Institutional Onboarding, 600+ Templates & DR Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.templates.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class TemplatesAndOnboardingEntities {

    private TemplatesAndOnboardingEntities() {}

    /**
     * Pre-built facility profiles that auto-wire enabled modules, default RBAC roles,
     * tariff tiers, and applicable document template packs in under 5 minutes.
     */
    public enum FacilityProfile {
        GENERAL_HOSPITAL("mpi,emr,pharmacy,lis,hie,uhc,hospital_ops,offline_sync,templates"),
        STANDALONE_PHARMACY("mpi,terminology,pharmacy,uhc,offline_sync,templates"),
        DIAGNOSTIC_LAB("mpi,terminology,lis,hie,uhc,offline_sync,templates"),
        EYE_CLINIC("mpi,terminology,emr:EYE_CLINIC,pharmacy,uhc,offline_sync,templates"),
        DENTAL_CLINIC("mpi,terminology,emr:DENTAL,pharmacy,uhc,offline_sync,templates"),
        CARDIAC_CENTER("mpi,terminology,emr:CARDIAC,lis,uhc,offline_sync,templates"),
        PEDIATRIC_CLINIC("mpi,terminology,emr:PEDIATRICS,public_health:EPI,uhc,offline_sync,templates"),
        DERMATOLOGY_CLINIC("mpi,terminology,emr:DERMATOLOGY,pharmacy,uhc,offline_sync,templates"),
        CHW_COMMUNITY_HUB("mpi,public_health,offline_sync,templates");

        private final String defaultModulesCsv;
        FacilityProfile(String defaultModulesCsv) { this.defaultModulesCsv = defaultModulesCsv; }
        public String defaultModulesCsv() { return defaultModulesCsv; }
    }

    @Entity @Table(name = "hc_facility_nodes")
    public static class FacilityNodeEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "parent_node_id") private UUID parentNodeId;
        @Column(name = "node_type", nullable = false, length = 32) private String nodeType; // HEALTH_NETWORK, FACILITY, CAMPUS, DEPARTMENT, WARD
        @Column(name = "facility_profile", nullable = false, length = 48) private String facilityProfile;
        @Column(name = "code", nullable = false, length = 64) private String code;
        @Column(name = "name", nullable = false, length = 200) private String name;
        @Column(name = "country", length = 64) private String country;
        @Column(name = "city", length = 120) private String city;
        @Column(name = "enabled_modules_csv", length = 400) private String enabledModulesCsv;
        @Column(name = "active", nullable = false) private boolean active = true;

        public UUID getId() { return id; }
        public UUID getParentNodeId() { return parentNodeId; }
        public void setParentNodeId(UUID parentNodeId) { this.parentNodeId = parentNodeId; }
        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
        public String getFacilityProfile() { return facilityProfile; }
        public void setFacilityProfile(String facilityProfile) { this.facilityProfile = facilityProfile; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getEnabledModulesCsv() { return enabledModulesCsv; }
        public void setEnabledModulesCsv(String enabledModulesCsv) { this.enabledModulesCsv = enabledModulesCsv; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Entity @Table(name = "hc_document_templates")
    public static class DocumentTemplateEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "template_code", nullable = false, length = 80) private String templateCode;
        @Column(name = "pack_category", nullable = false, length = 64) private String packCategory;
        @Column(name = "facility_id") private UUID facilityId;
        @Column(name = "title_en", nullable = false, length = 240) private String titleEn;
        @Column(name = "title_fr", nullable = false, length = 240) private String titleFr;
        @Column(name = "version_semver", nullable = false, length = 24) private String versionSemver = "1.0.0";
        @Lob @Column(name = "fhir_questionnaire_json", nullable = false) private String fhirQuestionnaireJson;
        @Lob @Column(name = "xhtml_print_template", nullable = false) private String xhtmlPrintTemplate;
        @Column(name = "published", nullable = false) private boolean published = true;

        public UUID getId() { return id; }
        public String getTemplateCode() { return templateCode; }
        public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
        public String getPackCategory() { return packCategory; }
        public void setPackCategory(String packCategory) { this.packCategory = packCategory; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getTitleEn() { return titleEn; }
        public void setTitleEn(String titleEn) { this.titleEn = titleEn; }
        public String getTitleFr() { return titleFr; }
        public void setTitleFr(String titleFr) { this.titleFr = titleFr; }
        public String getVersionSemver() { return versionSemver; }
        public void setVersionSemver(String versionSemver) { this.versionSemver = versionSemver; }
        public String getFhirQuestionnaireJson() { return fhirQuestionnaireJson; }
        public void setFhirQuestionnaireJson(String fhirQuestionnaireJson) { this.fhirQuestionnaireJson = fhirQuestionnaireJson; }
        public String getXhtmlPrintTemplate() { return xhtmlPrintTemplate; }
        public void setXhtmlPrintTemplate(String xhtmlPrintTemplate) { this.xhtmlPrintTemplate = xhtmlPrintTemplate; }
        public boolean isPublished() { return published; }
        public void setPublished(boolean published) { this.published = published; }
    }

    @Entity @Table(name = "hc_backup_manifests")
    public static class BackupManifestEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "db_engine", nullable = false, length = 32) private String dbEngine;
        @Column(name = "snapshot_uri", nullable = false, length = 400) private String snapshotUri;
        @Column(name = "sha256_checksum", nullable = false, length = 64) private String sha256Checksum;
        @Column(name = "restore_verified", nullable = false) private boolean restoreVerified = false;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public String getDbEngine() { return dbEngine; }
        public void setDbEngine(String dbEngine) { this.dbEngine = dbEngine; }
        public String getSnapshotUri() { return snapshotUri; }
        public void setSnapshotUri(String snapshotUri) { this.snapshotUri = snapshotUri; }
        public String getSha256Checksum() { return sha256Checksum; }
        public void setSha256Checksum(String sha256Checksum) { this.sha256Checksum = sha256Checksum; }
        public boolean isRestoreVerified() { return restoreVerified; }
        public void setRestoreVerified(boolean restoreVerified) { this.restoreVerified = restoreVerified; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
