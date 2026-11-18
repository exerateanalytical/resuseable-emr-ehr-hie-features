/*
 * =============================================================================
 * Opes HealthCore SDK — HIE Interop Entities (Subscriptions, DICOMweb, DLQ)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

public final class HieEntities {

    private HieEntities() {}

    @Entity @Table(name = "hc_fhir_subscriptions")
    public static class FhirSubscriptionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "criteria", nullable = false, length = 300) private String criteria;
        @Column(name = "endpoint_url", nullable = false, length = 400) private String endpointUrl;
        @Column(name = "hmac_secret_ref", length = 128) private String hmacSecretRef;
        @Column(name = "status", nullable = false, length = 24) private String status = "ACTIVE";
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getCriteria() { return criteria; }
        public void setCriteria(String criteria) { this.criteria = criteria; }
        public String getEndpointUrl() { return endpointUrl; }
        public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }
        public String getHmacSecretRef() { return hmacSecretRef; }
        public void setHmacSecretRef(String hmacSecretRef) { this.hmacSecretRef = hmacSecretRef; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity @Table(name = "hc_dicom_studies")
    public static class DicomStudyEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "study_instance_uid", nullable = false, length = 128) private String studyInstanceUid;
        @Column(name = "modality", nullable = false, length = 16) private String modality; // CR, CT, MR, US, DX
        @Column(name = "study_description", length = 300) private String studyDescription;
        @Column(name = "wado_rs_url", length = 400) private String wadoRsUrl;
        @Column(name = "series_count", nullable = false) private int seriesCount = 1;
        @Column(name = "study_date", nullable = false) private Instant studyDate = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getStudyInstanceUid() { return studyInstanceUid; }
        public void setStudyInstanceUid(String studyInstanceUid) { this.studyInstanceUid = studyInstanceUid; }
        public String getModality() { return modality; }
        public void setModality(String modality) { this.modality = modality; }
        public String getStudyDescription() { return studyDescription; }
        public void setStudyDescription(String studyDescription) { this.studyDescription = studyDescription; }
        public String getWadoRsUrl() { return wadoRsUrl; }
        public void setWadoRsUrl(String wadoRsUrl) { this.wadoRsUrl = wadoRsUrl; }
        public int getSeriesCount() { return seriesCount; }
        public void setSeriesCount(int seriesCount) { this.seriesCount = seriesCount; }
        public Instant getStudyDate() { return studyDate; }
    }

    @Entity @Table(name = "hc_hie_dlq_messages")
    public static class HieDlqMessageEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "protocol", nullable = false, length = 24) private String protocol; // HL7V2, FHIR, ASTM, DICOM
        @Column(name = "external_message_id", length = 128) private String externalMessageId;
        @Lob @Column(name = "raw_payload", nullable = false) private String rawPayload;
        @Column(name = "error_detail", length = 500) private String errorDetail;
        @Column(name = "replayed", nullable = false) private boolean replayed = false;
        @Column(name = "failed_at", nullable = false) private Instant failedAt = Instant.now();

        public UUID getId() { return id; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String protocol) { this.protocol = protocol; }
        public String getExternalMessageId() { return externalMessageId; }
        public void setExternalMessageId(String externalMessageId) { this.externalMessageId = externalMessageId; }
        public String getRawPayload() { return rawPayload; }
        public void setRawPayload(String rawPayload) { this.rawPayload = rawPayload; }
        public String getErrorDetail() { return errorDetail; }
        public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
        public boolean isReplayed() { return replayed; }
        public void setReplayed(boolean replayed) { this.replayed = replayed; }
        public Instant getFailedAt() { return failedAt; }
    }

    @Entity @Table(name = "hc_ihe_documents")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class IheDocumentEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id", nullable = false) private UUID patientId;
        @Column(name = "document_type_loinc", nullable = false, length = 32) private String documentTypeLoinc;
        @Column(name = "title", nullable = false, length = 240) private String title;
        @Column(name = "content_type", nullable = false, length = 80) private String contentType;
        @Lob @Column(name = "content_url_or_base64", nullable = false) private String contentUrlOrBase64;
        @Column(name = "indexed_at", nullable = false) private Instant indexedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getDocumentTypeLoinc() { return documentTypeLoinc; }
        public void setDocumentTypeLoinc(String documentTypeLoinc) { this.documentTypeLoinc = documentTypeLoinc; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        public String getContentUrlOrBase64() { return contentUrlOrBase64; }
        public void setContentUrlOrBase64(String contentUrlOrBase64) { this.contentUrlOrBase64 = contentUrlOrBase64; }
        public Instant getIndexedAt() { return indexedAt; }
    }
}
