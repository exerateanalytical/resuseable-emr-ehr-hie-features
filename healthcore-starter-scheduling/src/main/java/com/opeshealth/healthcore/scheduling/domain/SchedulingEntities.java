/*
 * =============================================================================
 * Opes HealthCore SDK — Enterprise Scheduling & Queue Domain Entities
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.scheduling.domain;

import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Filter;

import java.time.Instant;
import java.util.UUID;

/**
 * Facility-scoped scheduling entities covering Resource Calendars (providers, rooms,
 * equipment, clinics), Appointment Types, Scheduled Appointments with recurring series
 * and daily queue numbers, and priority Waitlist entries.
 */
public final class SchedulingEntities {

    private SchedulingEntities() {}

    public enum ResourceKind {
        PROVIDER,
        ROOM,
        EQUIPMENT,
        CLINIC
    }

    public enum AppointmentStatus {
        BOOKED,
        CHECKED_IN,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        NO_SHOW,
        RESCHEDULED
    }

    public enum WaitlistStatus {
        WAITING,
        OFFERED,
        BOOKED,
        EXPIRED,
        CANCELLED
    }

    @Entity
    @Table(name = "hc_resource_calendars")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ResourceCalendarEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Enumerated(EnumType.STRING)
        @Column(name = "resource_kind", nullable = false, length = 24)
        private ResourceKind resourceKind = ResourceKind.PROVIDER;

        @Column(name = "resource_ref", nullable = false, length = 120)
        private String resourceRef;

        @Column(name = "display_name", nullable = false, length = 160)
        private String displayName;

        @Column(name = "slot_duration_minutes", nullable = false)
        private int slotDurationMinutes = 20;

        @Column(name = "max_concurrent_bookings", nullable = false)
        private int maxConcurrentBookings = 1;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public ResourceKind getResourceKind() { return resourceKind; }
        public void setResourceKind(ResourceKind resourceKind) { this.resourceKind = resourceKind; }
        public String getResourceRef() { return resourceRef; }
        public void setResourceRef(String resourceRef) { this.resourceRef = resourceRef; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public int getSlotDurationMinutes() { return slotDurationMinutes; }
        public void setSlotDurationMinutes(int slotDurationMinutes) { this.slotDurationMinutes = slotDurationMinutes; }
        public int getMaxConcurrentBookings() { return maxConcurrentBookings; }
        public void setMaxConcurrentBookings(int maxConcurrentBookings) { this.maxConcurrentBookings = maxConcurrentBookings; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "hc_appointment_types")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class AppointmentTypeEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "type_code", nullable = false, length = 64)
        private String typeCode;

        @Column(name = "display_name", nullable = false, length = 160)
        private String displayName;

        @Column(name = "specialty_code", length = 64)
        private String specialtyCode;

        @Column(name = "default_duration_minutes", nullable = false)
        private int defaultDurationMinutes = 20;

        @Column(name = "buffer_minutes", nullable = false)
        private int bufferMinutes = 0;

        @Column(name = "active", nullable = false)
        private boolean active = true;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public String getTypeCode() { return typeCode; }
        public void setTypeCode(String typeCode) { this.typeCode = typeCode; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getSpecialtyCode() { return specialtyCode; }
        public void setSpecialtyCode(String specialtyCode) { this.specialtyCode = specialtyCode; }
        public int getDefaultDurationMinutes() { return defaultDurationMinutes; }
        public void setDefaultDurationMinutes(int defaultDurationMinutes) { this.defaultDurationMinutes = defaultDurationMinutes; }
        public int getBufferMinutes() { return bufferMinutes; }
        public void setBufferMinutes(int bufferMinutes) { this.bufferMinutes = bufferMinutes; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    @Entity(name = "SchedulingAppointment")
    @Table(name = "hc_scheduled_appointments")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class ScheduledAppointmentEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "calendar_id", nullable = false)
        private UUID calendarId;

        @Column(name = "room_calendar_id")
        private UUID roomCalendarId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "appointment_type_code", nullable = false, length = 64)
        private String appointmentTypeCode;

        @Column(name = "practitioner_ref", nullable = false, length = 120)
        private String practitionerRef;

        @Column(name = "department_code", nullable = false, length = 64)
        private String departmentCode;

        @Column(name = "scheduled_start", nullable = false)
        private Instant scheduledStart;

        @Column(name = "scheduled_end", nullable = false)
        private Instant scheduledEnd;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 24)
        private AppointmentStatus status = AppointmentStatus.BOOKED;

        @Column(name = "visit_reason", length = 400)
        private String visitReason;

        @Column(name = "cancellation_reason", length = 300)
        private String cancellationReason;

        @Column(name = "recurring_series_id")
        private UUID recurringSeriesId;

        @Column(name = "queue_token_number")
        private Integer queueTokenNumber;

        @Column(name = "checked_in_at")
        private Instant checkedInAt;

        @Column(name = "created_by", nullable = false, length = 120)
        private String createdBy;

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt = Instant.now();

        public UUID getId() { return id; }
        public void setId(UUID id) { this.id = id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getCalendarId() { return calendarId; }
        public void setCalendarId(UUID calendarId) { this.calendarId = calendarId; }
        public UUID getRoomCalendarId() { return roomCalendarId; }
        public void setRoomCalendarId(UUID roomCalendarId) { this.roomCalendarId = roomCalendarId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getAppointmentTypeCode() { return appointmentTypeCode; }
        public void setAppointmentTypeCode(String appointmentTypeCode) { this.appointmentTypeCode = appointmentTypeCode; }
        public String getPractitionerRef() { return practitionerRef; }
        public void setPractitionerRef(String practitionerRef) { this.practitionerRef = practitionerRef; }
        public String getDepartmentCode() { return departmentCode; }
        public void setDepartmentCode(String departmentCode) { this.departmentCode = departmentCode; }
        public Instant getScheduledStart() { return scheduledStart; }
        public void setScheduledStart(Instant scheduledStart) { this.scheduledStart = scheduledStart; }
        public Instant getScheduledEnd() { return scheduledEnd; }
        public void setScheduledEnd(Instant scheduledEnd) { this.scheduledEnd = scheduledEnd; }
        public AppointmentStatus getStatus() { return status; }
        public void setStatus(AppointmentStatus status) { this.status = status; }
        public String getVisitReason() { return visitReason; }
        public void setVisitReason(String visitReason) { this.visitReason = visitReason; }
        public String getCancellationReason() { return cancellationReason; }
        public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }
        public UUID getRecurringSeriesId() { return recurringSeriesId; }
        public void setRecurringSeriesId(UUID recurringSeriesId) { this.recurringSeriesId = recurringSeriesId; }
        public Integer getQueueTokenNumber() { return queueTokenNumber; }
        public void setQueueTokenNumber(Integer queueTokenNumber) { this.queueTokenNumber = queueTokenNumber; }
        public Instant getCheckedInAt() { return checkedInAt; }
        public void setCheckedInAt(Instant checkedInAt) { this.checkedInAt = checkedInAt; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
        public long getVersion() { return version; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    @Entity
    @Table(name = "hc_appointment_waitlist")
    @BatchSize(size = 32)
    @Filter(name = "facilityFilter", condition = "facility_id = :facilityId")
    public static class AppointmentWaitlistEntity {
        @Id
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id = UuidV7Generator.nextUuid();

        @Column(name = "facility_id", nullable = false)
        private UUID facilityId;

        @Column(name = "calendar_id", nullable = false)
        private UUID calendarId;

        @Column(name = "patient_id", nullable = false)
        private UUID patientId;

        @Column(name = "appointment_type_code", nullable = false, length = 64)
        private String appointmentTypeCode;

        @Column(name = "priority_rank", nullable = false)
        private int priorityRank = 3; // 1 = urgent, 5 = routine

        @Column(name = "preferred_from", nullable = false)
        private Instant preferredFrom;

        @Column(name = "preferred_until", nullable = false)
        private Instant preferredUntil;

        @Enumerated(EnumType.STRING)
        @Column(name = "status", nullable = false, length = 24)
        private WaitlistStatus status = WaitlistStatus.WAITING;

        @Column(name = "notes", length = 300)
        private String notes;

        @Column(name = "created_at", nullable = false)
        private Instant createdAt = Instant.now();

        @Version
        @Column(name = "version", nullable = false)
        private long version;

        public UUID getId() { return id; }
        public UUID getFacilityId() { return facilityId; }
        public void setFacilityId(UUID facilityId) { this.facilityId = facilityId; }
        public UUID getCalendarId() { return calendarId; }
        public void setCalendarId(UUID calendarId) { this.calendarId = calendarId; }
        public UUID getPatientId() { return patientId; }
        public void setPatientId(UUID patientId) { this.patientId = patientId; }
        public String getAppointmentTypeCode() { return appointmentTypeCode; }
        public void setAppointmentTypeCode(String appointmentTypeCode) { this.appointmentTypeCode = appointmentTypeCode; }
        public int getPriorityRank() { return priorityRank; }
        public void setPriorityRank(int priorityRank) { this.priorityRank = priorityRank; }
        public Instant getPreferredFrom() { return preferredFrom; }
        public void setPreferredFrom(Instant preferredFrom) { this.preferredFrom = preferredFrom; }
        public Instant getPreferredUntil() { return preferredUntil; }
        public void setPreferredUntil(Instant preferredUntil) { this.preferredUntil = preferredUntil; }
        public WaitlistStatus getStatus() { return status; }
        public void setStatus(WaitlistStatus status) { this.status = status; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public Instant getCreatedAt() { return createdAt; }
        public long getVersion() { return version; }
    }
}
