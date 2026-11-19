/*
 * =============================================================================
 * Opes HealthCore SDK — Patient Portal, Proxy Authorization & Messaging Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.portal.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * Facility-scoped Patient Portal entities enforcing strict patient/proxy identity binding
 * and auditable secure messaging threads.
 */
public final class PatientPortalEntities {

    private PatientPortalEntities() {}

    public enum PortalScope {
        APPOINTMENTS,
        RESULTS,
        MEDICATIONS,
        BILLING,
        CONSENT,
        MESSAGING
    }

    public enum ThreadStatus {
        OPEN,
        CLOSED
    }

    @Entity
    @Table(name = "hc_portal_accounts")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PatientPortalAccountEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "identity_subject", nullable = false, length = 160)
        private String identitySubject;

        @Column(name = "preferred_locale", nullable = false, length = 16)
        private String preferredLocale = "en";

        @Column(name = "contact_email", length = 160)
        private String contactEmail;

        @Column(name = "contact_phone", length = 64)
        private String contactPhone;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getIdentitySubject() { return identitySubject; }
        public void setIdentitySubject(String identitySubject) { this.identitySubject = identitySubject; }
        public String getPreferredLocale() { return preferredLocale; }
        public void setPreferredLocale(String preferredLocale) { this.preferredLocale = preferredLocale; }
        public String getContactEmail() { return contactEmail; }
        public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }
        public String getContactPhone() { return contactPhone; }
        public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_portal_proxy_grants")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PatientProxyGrantEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "proxy_subject", nullable = false, length = 160)
        private String proxySubject;

        @Column(name = "relationship", nullable = false, length = 48)
        private String relationship;

        @Column(name = "allowed_scopes_csv", nullable = false, length = 250)
        private String allowedScopesCsv;

        @Column(name = "expires_at", nullable = false)
        private Instant expiresAt;

        @Column(name = "revoked", nullable = false)
        private boolean revoked = false;

        @Column(name = "granted_by", nullable = false, length = 120)
        private String grantedBy;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getProxySubject() { return proxySubject; }
        public void setProxySubject(String proxySubject) { this.proxySubject = proxySubject; }
        public String getRelationship() { return relationship; }
        public void setRelationship(String relationship) { this.relationship = relationship; }
        public String getAllowedScopesCsv() { return allowedScopesCsv; }
        public void setAllowedScopesCsv(String allowedScopesCsv) { this.allowedScopesCsv = allowedScopesCsv; }
        public Instant getExpiresAt() { return expiresAt; }
        public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
        public boolean isRevoked() { return revoked; }
        public void setRevoked(boolean revoked) { this.revoked = revoked; }
        public String getGrantedBy() { return grantedBy; }
        public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_portal_message_threads")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PortalMessageThreadEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "subject", nullable = false, length = 200)
        private String subject;

        @Column(name = "care_team_department", nullable = false, length = 64)
        private String careTeamDepartment;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 16)
        private ThreadStatus status = ThreadStatus.OPEN;

        @Column(name = "created_by_subject", nullable = false, length = 160)
        private String createdBySubject;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getCareTeamDepartment() { return careTeamDepartment; }
        public void setCareTeamDepartment(String careTeamDepartment) { this.careTeamDepartment = careTeamDepartment; }
        public ThreadStatus getStatus() { return status; }
        public void setStatus(ThreadStatus status) { this.status = status; }
        public String getCreatedBySubject() { return createdBySubject; }
        public void setCreatedBySubject(String createdBySubject) { this.createdBySubject = createdBySubject; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_portal_messages")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class PortalMessageEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "thread_id", nullable = false)
        private UUID threadId;

        @Column(name = "sender_subject", nullable = false, length = 160)
        private String senderSubject;

        @Column(name = "sender_role", nullable = false, length = 24)
        private String senderRole;

        @Lob
        @Column(name = "body", nullable = false)
        private String body;

        @Column(name = "read_at")
        private Instant readAt;

        @Column(name = "sent_at", nullable = false)
        private Instant sentAt = Instant.now();

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getThreadId() { return threadId; }
        public void setThreadId(UUID threadId) { this.threadId = threadId; }
        public String getSenderSubject() { return senderSubject; }
        public void setSenderSubject(String senderSubject) { this.senderSubject = senderSubject; }
        public String getSenderRole() { return senderRole; }
        public void setSenderRole(String senderRole) { this.senderRole = senderRole; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
        public Instant getReadAt() { return readAt; }
        public void setReadAt(Instant readAt) { this.readAt = readAt; }
        public Instant getSentAt() { return sentAt; }
    }
}
