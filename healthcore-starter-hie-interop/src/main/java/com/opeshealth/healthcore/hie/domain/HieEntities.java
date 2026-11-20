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
        @Column(name = "class_code", length = 120) private String classCode;
        @Column(name = "format_code", length = 240) private String formatCode;
        @Column(name = "repository_unique_id", length = 240) private String repositoryUniqueId;
        @Column(name = "sha256_hash", length = 64) private String sha256Hash;
        @Column(name = "document_status", length = 32) private String status;

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
        public String getClassCode() { return classCode; }
        public void setClassCode(String classCode) { this.classCode = classCode; }
        public String getFormatCode() { return formatCode; }
        public void setFormatCode(String formatCode) { this.formatCode = formatCode; }
        public String getRepositoryUniqueId() { return repositoryUniqueId; }
        public void setRepositoryUniqueId(String repositoryUniqueId) { this.repositoryUniqueId = repositoryUniqueId; }
        public String getSha256Hash() { return sha256Hash; }
        public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    @Entity @Table(name = "hc_hl7_orders")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class Hl7OrderRecordEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "mrn", nullable = false, length = 64) private String mrn;
        @Column(name = "placer_order_number", nullable = false, length = 96) private String placerOrderNumber;
        @Column(name = "filler_order_number", length = 96) private String fillerOrderNumber;
        @Column(name = "order_control", nullable = false, length = 16) private String orderControl;
        @Column(name = "universal_service_code", nullable = false, length = 64) private String universalServiceCode;
        @Column(name = "universal_service_text", length = 240) private String universalServiceText;
        @Column(name = "status", nullable = false, length = 32) private String status = "ACTIVE";
        @Column(name = "msh_control_id", nullable = false, length = 96) private String mshControlId;
        @Column(name = "received_at", nullable = false) private Instant receivedAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getMrn() { return mrn; }
        public void setMrn(String mrn) { this.mrn = mrn; }
        public String getPlacerOrderNumber() { return placerOrderNumber; }
        public void setPlacerOrderNumber(String placerOrderNumber) { this.placerOrderNumber = placerOrderNumber; }
        public String getFillerOrderNumber() { return fillerOrderNumber; }
        public void setFillerOrderNumber(String fillerOrderNumber) { this.fillerOrderNumber = fillerOrderNumber; }
        public String getOrderControl() { return orderControl; }
        public void setOrderControl(String orderControl) { this.orderControl = orderControl; }
        public String getUniversalServiceCode() { return universalServiceCode; }
        public void setUniversalServiceCode(String universalServiceCode) { this.universalServiceCode = universalServiceCode; }
        public String getUniversalServiceText() { return universalServiceText; }
        public void setUniversalServiceText(String universalServiceText) { this.universalServiceText = universalServiceText; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getMshControlId() { return mshControlId; }
        public void setMshControlId(String mshControlId) { this.mshControlId = mshControlId; }
        public Instant getReceivedAt() { return receivedAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Entity @Table(name = "hc_hl7_results")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class Hl7ObservationResultEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "mrn", nullable = false, length = 64) private String mrn;
        @Column(name = "placer_order_number", length = 96) private String placerOrderNumber;
        @Column(name = "observation_code", nullable = false, length = 64) private String observationCode;
        @Column(name = "observation_text", length = 240) private String observationText;
        @Column(name = "value_type", length = 16) private String valueType;
        @Column(name = "observation_value", nullable = false, length = 400) private String observationValue;
        @Column(name = "units", length = 64) private String units;
        @Column(name = "reference_range", length = 120) private String referenceRange;
        @Column(name = "abnormal_flag", length = 16) private String abnormalFlag;
        @Column(name = "result_status", nullable = false, length = 16) private String resultStatus = "F";
        @Column(name = "msh_control_id", nullable = false, length = 96) private String mshControlId;
        @Column(name = "observed_at", nullable = false) private Instant observedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getMrn() { return mrn; }
        public void setMrn(String mrn) { this.mrn = mrn; }
        public String getPlacerOrderNumber() { return placerOrderNumber; }
        public void setPlacerOrderNumber(String placerOrderNumber) { this.placerOrderNumber = placerOrderNumber; }
        public String getObservationCode() { return observationCode; }
        public void setObservationCode(String observationCode) { this.observationCode = observationCode; }
        public String getObservationText() { return observationText; }
        public void setObservationText(String observationText) { this.observationText = observationText; }
        public String getValueType() { return valueType; }
        public void setValueType(String valueType) { this.valueType = valueType; }
        public String getObservationValue() { return observationValue; }
        public void setObservationValue(String observationValue) { this.observationValue = observationValue; }
        public String getUnits() { return units; }
        public void setUnits(String units) { this.units = units; }
        public String getReferenceRange() { return referenceRange; }
        public void setReferenceRange(String referenceRange) { this.referenceRange = referenceRange; }
        public String getAbnormalFlag() { return abnormalFlag; }
        public void setAbnormalFlag(String abnormalFlag) { this.abnormalFlag = abnormalFlag; }
        public String getResultStatus() { return resultStatus; }
        public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
        public String getMshControlId() { return mshControlId; }
        public void setMshControlId(String mshControlId) { this.mshControlId = mshControlId; }
        public Instant getObservedAt() { return observedAt; }
    }

    @Entity @Table(name = "hc_hl7_appointments")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class Hl7ScheduleEventEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "mrn", length = 64) private String mrn;
        @Column(name = "external_appointment_id", nullable = false, length = 96) private String externalAppointmentId;
        @Column(name = "trigger_event", nullable = false, length = 16) private String triggerEvent;
        @Column(name = "appointment_status", nullable = false, length = 32) private String appointmentStatus;
        @Column(name = "appointment_reason", length = 240) private String appointmentReason;
        @Column(name = "scheduled_start_raw", length = 64) private String scheduledStartRaw;
        @Column(name = "msh_control_id", nullable = false, length = 96) private String mshControlId;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getMrn() { return mrn; }
        public void setMrn(String mrn) { this.mrn = mrn; }
        public String getExternalAppointmentId() { return externalAppointmentId; }
        public void setExternalAppointmentId(String externalAppointmentId) { this.externalAppointmentId = externalAppointmentId; }
        public String getTriggerEvent() { return triggerEvent; }
        public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
        public String getAppointmentStatus() { return appointmentStatus; }
        public void setAppointmentStatus(String appointmentStatus) { this.appointmentStatus = appointmentStatus; }
        public String getAppointmentReason() { return appointmentReason; }
        public void setAppointmentReason(String appointmentReason) { this.appointmentReason = appointmentReason; }
        public String getScheduledStartRaw() { return scheduledStartRaw; }
        public void setScheduledStartRaw(String scheduledStartRaw) { this.scheduledStartRaw = scheduledStartRaw; }
        public String getMshControlId() { return mshControlId; }
        public void setMshControlId(String mshControlId) { this.mshControlId = mshControlId; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Entity @Table(name = "hc_hl7_financial_txns")
    @org.hibernate.annotations.Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class Hl7FinancialTransactionEntity {
        @Id @Column(name = "id", nullable = false, updatable = false) private UUID id = UuidV7Generator.nextUuid();
        @Column(name = "facility_id", nullable = false) private UUID facilityId;
        @Column(name = "patient_id") private UUID patientId;
        @Column(name = "mrn", nullable = false, length = 64) private String mrn;
        @Column(name = "transaction_id", nullable = false, length = 96) private String transactionId;
        @Column(name = "transaction_type", nullable = false, length = 16) private String transactionType;
        @Column(name = "transaction_code", nullable = false, length = 64) private String transactionCode;
        @Column(name = "transaction_description", length = 240) private String transactionDescription;
        @Column(name = "amount", nullable = false, precision = 14, scale = 2) private java.math.BigDecimal amount = java.math.BigDecimal.ZERO;
        @Column(name = "currency_code", nullable = false, length = 8) private String currencyCode = "XAF";
        @Column(name = "reversed", nullable = false) private boolean reversed = false;
        @Column(name = "msh_control_id", nullable = false, length = 96) private String mshControlId;
        @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getMrn() { return mrn; }
        public void setMrn(String mrn) { this.mrn = mrn; }
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
        public String getTransactionCode() { return transactionCode; }
        public void setTransactionCode(String transactionCode) { this.transactionCode = transactionCode; }
        public String getTransactionDescription() { return transactionDescription; }
        public void setTransactionDescription(String transactionDescription) { this.transactionDescription = transactionDescription; }
        public java.math.BigDecimal getAmount() { return amount; }
        public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        public boolean isReversed() { return reversed; }
        public void setReversed(boolean reversed) { this.reversed = reversed; }
        public String getMshControlId() { return mshControlId; }
        public void setMshControlId(String mshControlId) { this.mshControlId = mshControlId; }
        public Instant getPostedAt() { return postedAt; }
    }
}
