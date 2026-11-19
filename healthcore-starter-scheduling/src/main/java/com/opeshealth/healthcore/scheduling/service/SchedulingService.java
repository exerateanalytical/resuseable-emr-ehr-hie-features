/*
 * =============================================================================
 * Opes HealthCore SDK — Enterprise Scheduling Service, Conflict Engine & FHIR API
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.scheduling.service;

import ca.uhn.fhir.context.FhirContext;
import com.opeshealth.healthcore.persistence.id.UuidV7Generator;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages facility-scoped provider/room/equipment calendars, appointment booking with
 * deterministic overlap and overbooking enforcement, recurring series generation,
 * outpatient queue check-in tokens, waitlist promotion candidates, and FHIR R4 projections.
 */
public class SchedulingService {

    private static final FhirContext FHIR_R4 = FhirContext.forR4();
    private static final Set<AppointmentStatus> ACTIVE_SLOT_STATUSES = Set.of(
            AppointmentStatus.BOOKED,
            AppointmentStatus.CHECKED_IN,
            AppointmentStatus.IN_PROGRESS
    );

    public interface ResourceCalendarRepository extends JpaRepository<ResourceCalendarEntity, UUID> {
        List<ResourceCalendarEntity> findByFacilityIdAndActiveTrueOrderByDisplayNameAsc(UUID facilityId);
        Optional<ResourceCalendarEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface AppointmentTypeRepository extends JpaRepository<AppointmentTypeEntity, UUID> {
        List<AppointmentTypeEntity> findByFacilityIdAndActiveTrueOrderByDisplayNameAsc(UUID facilityId);
        Optional<AppointmentTypeEntity> findByFacilityIdAndTypeCode(UUID facilityId, String typeCode);
    }

    public interface ScheduledAppointmentRepository extends JpaRepository<ScheduledAppointmentEntity, UUID> {
        Optional<ScheduledAppointmentEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<ScheduledAppointmentEntity> findByFacilityIdOrderByScheduledStartAsc(UUID facilityId);
        List<ScheduledAppointmentEntity> findByFacilityIdAndPatientIdOrderByScheduledStartDesc(UUID facilityId, UUID patientId);

        @Query("""
                SELECT a FROM ScheduledAppointmentEntity a
                WHERE a.facilityId = :facilityId
                  AND (a.calendarId = :calendarId OR a.roomCalendarId = :calendarId)
                  AND a.status IN :activeStatuses
                  AND a.scheduledStart < :windowEnd
                  AND a.scheduledEnd > :windowStart
                """)
        List<ScheduledAppointmentEntity> findOverlappingBookings(
                @Param("facilityId") UUID facilityId,
                @Param("calendarId") UUID calendarId,
                @Param("activeStatuses") Collection<AppointmentStatus> activeStatuses,
                @Param("windowStart") Instant windowStart,
                @Param("windowEnd") Instant windowEnd);

        @Query("""
                SELECT COALESCE(MAX(a.queueTokenNumber), 0) FROM ScheduledAppointmentEntity a
                WHERE a.facilityId = :facilityId
                  AND a.departmentCode = :departmentCode
                  AND a.checkedInAt >= :dayStart
                """)
        int findMaxQueueTokenForDepartmentSince(
                @Param("facilityId") UUID facilityId,
                @Param("departmentCode") String departmentCode,
                @Param("dayStart") Instant dayStart);
    }

    public interface WaitlistRepository extends JpaRepository<AppointmentWaitlistEntity, UUID> {
        List<AppointmentWaitlistEntity> findByFacilityIdAndCalendarIdAndStatusOrderByPriorityRankAscCreatedAtAsc(
                UUID facilityId, UUID calendarId, WaitlistStatus status);
        Optional<AppointmentWaitlistEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public record CreateCalendarRequest(
            String departmentCode,
            ResourceKind resourceKind,
            String resourceRef,
            String displayName,
            int slotDurationMinutes,
            int maxConcurrentBookings) {}

    public record CreateAppointmentTypeRequest(
            String typeCode,
            String displayName,
            String specialtyCode,
            int defaultDurationMinutes,
            int bufferMinutes) {}

    public record BookAppointmentRequest(
            UUID calendarId,
            UUID roomCalendarId,
            UUID patientId,
            String appointmentTypeCode,
            Instant scheduledStart,
            Instant scheduledEnd,
            String visitReason) {}

    public record BookRecurringSeriesRequest(
            BookAppointmentRequest baseRequest,
            int occurrences,
            int intervalDays) {}

    public record RescheduleRequest(Instant newStart, Instant newEnd, String reason) {}

    public record CancelAppointmentRequest(String reason) {}

    public record WaitlistRequest(
            UUID calendarId,
            UUID patientId,
            String appointmentTypeCode,
            int priorityRank,
            Instant preferredFrom,
            Instant preferredUntil,
            String notes) {}

    public record CancellationOutcome(
            ScheduledAppointmentEntity cancelledAppointment,
            List<AppointmentWaitlistEntity> waitlistCandidates) {}

    private final ResourceCalendarRepository calendarRepo;
    private final AppointmentTypeRepository typeRepo;
    private final ScheduledAppointmentRepository appointmentRepo;
    private final WaitlistRepository waitlistRepo;
    private final ClinicalAuditService auditService;

    public SchedulingService(
            ResourceCalendarRepository calendarRepo,
            AppointmentTypeRepository typeRepo,
            ScheduledAppointmentRepository appointmentRepo,
            WaitlistRepository waitlistRepo,
            ClinicalAuditService auditService) {
        this.calendarRepo = calendarRepo;
        this.typeRepo = typeRepo;
        this.appointmentRepo = appointmentRepo;
        this.waitlistRepo = waitlistRepo;
        this.auditService = auditService;
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_CREATE_CALENDAR", resourceType = "Schedule")
    public ResourceCalendarEntity createCalendar(UUID facilityId, CreateCalendarRequest req) {
        if (facilityId == null || req == null || isBlank(req.departmentCode()) || isBlank(req.resourceRef()) || isBlank(req.displayName())) {
            throw new IllegalArgumentException("facilityId, departmentCode, resourceRef, and displayName are required.");
        }
        ResourceCalendarEntity calendar = new ResourceCalendarEntity();
        calendar.setFacilityId(facilityId);
        calendar.setDepartmentCode(req.departmentCode().trim().toUpperCase(Locale.ROOT));
        calendar.setResourceKind(req.resourceKind() != null ? req.resourceKind() : ResourceKind.PROVIDER);
        calendar.setResourceRef(req.resourceRef().trim());
        calendar.setDisplayName(req.displayName().trim());
        calendar.setSlotDurationMinutes(req.slotDurationMinutes() > 0 ? req.slotDurationMinutes() : 20);
        calendar.setMaxConcurrentBookings(Math.max(1, req.maxConcurrentBookings()));
        return calendarRepo.save(calendar);
    }

    @Transactional(readOnly = true)
    public List<ResourceCalendarEntity> listCalendars(UUID facilityId) {
        return calendarRepo.findByFacilityIdAndActiveTrueOrderByDisplayNameAsc(facilityId);
    }

    @Transactional
    public AppointmentTypeEntity createAppointmentType(UUID facilityId, CreateAppointmentTypeRequest req) {
        if (facilityId == null || req == null || isBlank(req.typeCode()) || isBlank(req.displayName())) {
            throw new IllegalArgumentException("facilityId, typeCode, and displayName are required.");
        }
        String code = req.typeCode().trim().toUpperCase(Locale.ROOT);
        typeRepo.findByFacilityIdAndTypeCode(facilityId, code).ifPresent(existing -> {
            throw new IllegalStateException("Appointment type code already exists: " + code);
        });
        AppointmentTypeEntity type = new AppointmentTypeEntity();
        type.setFacilityId(facilityId);
        type.setTypeCode(code);
        type.setDisplayName(req.displayName().trim());
        type.setSpecialtyCode(req.specialtyCode());
        type.setDefaultDurationMinutes(req.defaultDurationMinutes() > 0 ? req.defaultDurationMinutes() : 20);
        type.setBufferMinutes(Math.max(0, req.bufferMinutes()));
        return typeRepo.save(type);
    }

    @Transactional(readOnly = true)
    public List<AppointmentTypeEntity> listAppointmentTypes(UUID facilityId) {
        return typeRepo.findByFacilityIdAndActiveTrueOrderByDisplayNameAsc(facilityId);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_BOOK_APPOINTMENT", resourceType = "Appointment")
    public ScheduledAppointmentEntity bookAppointment(UUID facilityId, BookAppointmentRequest req, String actorId) {
        return bookInternal(facilityId, req, null, actorId);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_BOOK_RECURRING_SERIES", resourceType = "Appointment")
    public List<ScheduledAppointmentEntity> bookRecurringSeries(
            UUID facilityId, BookRecurringSeriesRequest req, String actorId) {
        if (req == null || req.baseRequest() == null || req.occurrences() < 2 || req.occurrences() > 52) {
            throw new IllegalArgumentException("occurrences must be between 2 and 52.");
        }
        if (req.intervalDays() < 1 || req.intervalDays() > 90) {
            throw new IllegalArgumentException("intervalDays must be between 1 and 90.");
        }
        UUID seriesId = UuidV7Generator.nextUuid();
        List<ScheduledAppointmentEntity> created = new ArrayList<>();
        BookAppointmentRequest base = req.baseRequest();
        Duration slotLength = resolveEnd(facilityId, base).isAfter(base.scheduledStart())
                ? Duration.between(base.scheduledStart(), resolveEnd(facilityId, base))
                : Duration.ofMinutes(20);

        for (int i = 0; i < req.occurrences(); i++) {
            Instant start = base.scheduledStart().plus(Duration.ofDays((long) i * req.intervalDays()));
            Instant end = start.plus(slotLength);
            BookAppointmentRequest occurrence = new BookAppointmentRequest(
                    base.calendarId(), base.roomCalendarId(), base.patientId(),
                    base.appointmentTypeCode(), start, end, base.visitReason());
            created.add(bookInternal(facilityId, occurrence, seriesId, actorId));
        }
        return created;
    }

    private ScheduledAppointmentEntity bookInternal(
            UUID facilityId, BookAppointmentRequest req, UUID seriesId, String actorId) {
        if (facilityId == null || req == null || req.calendarId() == null || req.patientId() == null || req.scheduledStart() == null) {
            throw new IllegalArgumentException("facilityId, calendarId, patientId, and scheduledStart are required.");
        }
        ResourceCalendarEntity primaryCalendar = calendarRepo.findByIdAndFacilityId(req.calendarId(), facilityId)
                .orElseThrow(() -> new AccessDeniedException("Calendar not found in active facility."));
        if (!primaryCalendar.isActive()) {
            throw new IllegalStateException("Selected calendar is inactive.");
        }

        Instant end = resolveEnd(facilityId, req);
        if (!end.isAfter(req.scheduledStart())) {
            throw new IllegalArgumentException("scheduledEnd must be after scheduledStart.");
        }

        enforceCapacity(facilityId, primaryCalendar, req.scheduledStart(), end, null);

        if (req.roomCalendarId() != null) {
            ResourceCalendarEntity roomCalendar = calendarRepo.findByIdAndFacilityId(req.roomCalendarId(), facilityId)
                    .orElseThrow(() -> new AccessDeniedException("Room calendar not found in active facility."));
            enforceCapacity(facilityId, roomCalendar, req.scheduledStart(), end, null);
        }

        ScheduledAppointmentEntity entity = new ScheduledAppointmentEntity();
        entity.setFacilityId(facilityId);
        entity.setCalendarId(primaryCalendar.getId());
        entity.setRoomCalendarId(req.roomCalendarId());
        entity.setPatientId(req.patientId());
        entity.setAppointmentTypeCode(isBlank(req.appointmentTypeCode()) ? "CONSULT" : req.appointmentTypeCode().trim().toUpperCase(Locale.ROOT));
        entity.setPractitionerRef(primaryCalendar.getResourceRef());
        entity.setDepartmentCode(primaryCalendar.getDepartmentCode());
        entity.setScheduledStart(req.scheduledStart());
        entity.setScheduledEnd(end);
        entity.setStatus(AppointmentStatus.BOOKED);
        entity.setVisitReason(req.visitReason());
        entity.setRecurringSeriesId(seriesId);
        entity.setCreatedBy(actorId != null ? actorId : "SYSTEM");
        return appointmentRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_CHECK_IN", resourceType = "Appointment")
    public ScheduledAppointmentEntity checkIn(UUID facilityId, UUID appointmentId) {
        ScheduledAppointmentEntity appt = requireFacilityAppointment(facilityId, appointmentId);
        if (appt.getStatus() != AppointmentStatus.BOOKED) {
            throw new IllegalStateException("Only BOOKED appointments can be checked in; current status=" + appt.getStatus());
        }
        Instant now = Instant.now();
        Instant dayStart = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        int nextToken = appointmentRepo.findMaxQueueTokenForDepartmentSince(
                facilityId, appt.getDepartmentCode(), dayStart) + 1;
        appt.setStatus(AppointmentStatus.CHECKED_IN);
        appt.setCheckedInAt(now);
        appt.setQueueTokenNumber(nextToken);
        appt.setUpdatedAt(now);
        return appointmentRepo.save(appt);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_MARK_NO_SHOW", resourceType = "Appointment")
    public ScheduledAppointmentEntity markNoShow(UUID facilityId, UUID appointmentId) {
        ScheduledAppointmentEntity appt = requireFacilityAppointment(facilityId, appointmentId);
        if (appt.getStatus() != AppointmentStatus.BOOKED) {
            throw new IllegalStateException("Only BOOKED appointments can be marked NO_SHOW.");
        }
        appt.setStatus(AppointmentStatus.NO_SHOW);
        appt.setUpdatedAt(Instant.now());
        return appointmentRepo.save(appt);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_RESCHEDULE", resourceType = "Appointment")
    public ScheduledAppointmentEntity reschedule(UUID facilityId, UUID appointmentId, RescheduleRequest req) {
        if (req == null || req.newStart() == null || req.newEnd() == null || !req.newEnd().isAfter(req.newStart())) {
            throw new IllegalArgumentException("Valid newStart and newEnd are required.");
        }
        ScheduledAppointmentEntity appt = requireFacilityAppointment(facilityId, appointmentId);
        if (appt.getStatus() == AppointmentStatus.COMPLETED || appt.getStatus() == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException("Cannot reschedule an appointment in status " + appt.getStatus());
        }
        ResourceCalendarEntity primary = calendarRepo.findByIdAndFacilityId(appt.getCalendarId(), facilityId)
                .orElseThrow(() -> new IllegalStateException("Calendar no longer exists."));
        enforceCapacity(facilityId, primary, req.newStart(), req.newEnd(), appt.getId());
        if (appt.getRoomCalendarId() != null) {
            ResourceCalendarEntity room = calendarRepo.findByIdAndFacilityId(appt.getRoomCalendarId(), facilityId)
                    .orElseThrow(() -> new IllegalStateException("Room calendar no longer exists."));
            enforceCapacity(facilityId, room, req.newStart(), req.newEnd(), appt.getId());
        }
        appt.setScheduledStart(req.newStart());
        appt.setScheduledEnd(req.newEnd());
        appt.setStatus(AppointmentStatus.BOOKED);
        appt.setUpdatedAt(Instant.now());
        return appointmentRepo.save(appt);
    }

    @Transactional
    @ClinicalAudit(action = "SCHEDULING_CANCEL", resourceType = "Appointment")
    public CancellationOutcome cancelAppointment(UUID facilityId, UUID appointmentId, String reason, String actorId) {
        ScheduledAppointmentEntity appt = requireFacilityAppointment(facilityId, appointmentId);
        if (appt.getStatus() == AppointmentStatus.COMPLETED) {
            throw new IllegalStateException("Completed appointments cannot be cancelled.");
        }
        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setCancellationReason(reason);
        appt.setUpdatedAt(Instant.now());
        ScheduledAppointmentEntity saved = appointmentRepo.save(appt);

        List<AppointmentWaitlistEntity> candidates = waitlistRepo
                .findByFacilityIdAndCalendarIdAndStatusOrderByPriorityRankAscCreatedAtAsc(
                        facilityId, appt.getCalendarId(), WaitlistStatus.WAITING)
                .stream()
                .filter(w -> !appt.getScheduledStart().isBefore(w.getPreferredFrom())
                        && !appt.getScheduledStart().isAfter(w.getPreferredUntil()))
                .toList();

        if (auditService != null) {
            auditService.recordEvent(
                    facilityId.toString(),
                    actorId != null ? actorId : "SYSTEM",
                    "SCHEDULING",
                    "APPOINTMENT_CANCELLED",
                    "Appointment",
                    appt.getId().toString(),
                    appt.getPatientId(),
                    "SUCCESS",
                    reason,
                    "internal");
        }
        return new CancellationOutcome(saved, candidates);
    }

    @Transactional
    public AppointmentWaitlistEntity enqueueWaitlist(UUID facilityId, WaitlistRequest req) {
        if (facilityId == null || req == null || req.calendarId() == null || req.patientId() == null
                || req.preferredFrom() == null || req.preferredUntil() == null
                || !req.preferredUntil().isAfter(req.preferredFrom())) {
            throw new IllegalArgumentException("Valid calendarId, patientId, and preferred time window are required.");
        }
        calendarRepo.findByIdAndFacilityId(req.calendarId(), facilityId)
                .orElseThrow(() -> new AccessDeniedException("Calendar not found in active facility."));
        AppointmentWaitlistEntity entry = new AppointmentWaitlistEntity();
        entry.setFacilityId(facilityId);
        entry.setCalendarId(req.calendarId());
        entry.setPatientId(req.patientId());
        entry.setAppointmentTypeCode(isBlank(req.appointmentTypeCode()) ? "CONSULT" : req.appointmentTypeCode().trim().toUpperCase(Locale.ROOT));
        entry.setPriorityRank(Math.max(1, Math.min(5, req.priorityRank() == 0 ? 3 : req.priorityRank())));
        entry.setPreferredFrom(req.preferredFrom());
        entry.setPreferredUntil(req.preferredUntil());
        entry.setNotes(req.notes());
        return waitlistRepo.save(entry);
    }

    @Transactional(readOnly = true)
    public List<ScheduledAppointmentEntity> listFacilityAppointments(UUID facilityId) {
        return appointmentRepo.findByFacilityIdOrderByScheduledStartAsc(facilityId);
    }

    @Transactional(readOnly = true)
    public List<ScheduledAppointmentEntity> listPatientAppointments(UUID facilityId, UUID patientId) {
        return appointmentRepo.findByFacilityIdAndPatientIdOrderByScheduledStartDesc(facilityId, patientId);
    }

    public Appointment toFhirAppointment(ScheduledAppointmentEntity entity) {
        Appointment fhir = new Appointment();
        fhir.setId(entity.getId().toString());
        fhir.setStatus(switch (entity.getStatus()) {
            case BOOKED, RESCHEDULED -> Appointment.AppointmentStatus.BOOKED;
            case CHECKED_IN -> Appointment.AppointmentStatus.ARRIVED;
            case IN_PROGRESS -> Appointment.AppointmentStatus.CHECKEDIN;
            case COMPLETED -> Appointment.AppointmentStatus.FULFILLED;
            case CANCELLED -> Appointment.AppointmentStatus.CANCELLED;
            case NO_SHOW -> Appointment.AppointmentStatus.NOSHOW;
        });
        fhir.setDescription(entity.getVisitReason());
        fhir.setStart(Date.from(entity.getScheduledStart()));
        fhir.setEnd(Date.from(entity.getScheduledEnd()));
        fhir.addParticipant()
                .setActor(new Reference("Patient/" + entity.getPatientId()))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        fhir.addParticipant()
                .setActor(new Reference("Practitioner/" + entity.getPractitionerRef()))
                .setStatus(Appointment.ParticipationStatus.ACCEPTED);
        return fhir;
    }

    public Schedule toFhirSchedule(ResourceCalendarEntity calendar) {
        Schedule schedule = new Schedule();
        schedule.setId(calendar.getId().toString());
        schedule.setActive(calendar.isActive());
        schedule.addActor(new Reference(calendar.getResourceKind().name() + "/" + calendar.getResourceRef())
                .setDisplay(calendar.getDisplayName()));
        schedule.setComment("Department: " + calendar.getDepartmentCode()
                + ", slotDurationMinutes=" + calendar.getSlotDurationMinutes()
                + ", maxConcurrentBookings=" + calendar.getMaxConcurrentBookings());
        return schedule;
    }

    private void enforceCapacity(
            UUID facilityId, ResourceCalendarEntity calendar, Instant start, Instant end, UUID excludeAppointmentId) {
        List<ScheduledAppointmentEntity> overlapping = appointmentRepo.findOverlappingBookings(
                facilityId, calendar.getId(), ACTIVE_SLOT_STATUSES, start, end);
        long count = overlapping.stream()
                .filter(a -> excludeAppointmentId == null || !a.getId().equals(excludeAppointmentId))
                .count();
        if (count >= calendar.getMaxConcurrentBookings()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Scheduling conflict on resource '" + calendar.getDisplayName()
                            + "': slot capacity (" + calendar.getMaxConcurrentBookings() + ") reached.");
        }
    }

    private Instant resolveEnd(UUID facilityId, BookAppointmentRequest req) {
        if (req.scheduledEnd() != null) {
            return req.scheduledEnd();
        }
        int minutes = 20;
        if (!isBlank(req.appointmentTypeCode())) {
            Optional<AppointmentTypeEntity> type = typeRepo.findByFacilityIdAndTypeCode(
                    facilityId, req.appointmentTypeCode().trim().toUpperCase(Locale.ROOT));
            if (type.isPresent()) {
                minutes = type.get().getDefaultDurationMinutes() + type.get().getBufferMinutes();
            }
        }
        return req.scheduledStart().plus(Duration.ofMinutes(minutes));
    }

    private ScheduledAppointmentEntity requireFacilityAppointment(UUID facilityId, UUID appointmentId) {
        return appointmentRepo.findByIdAndFacilityId(appointmentId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Appointment not found in active facility."));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @RestController
    @RequestMapping("/api/v1/scheduling")
    public static class SchedulingController {
        private final SchedulingService service;

        public SchedulingController(SchedulingService service) {
            this.service = service;
        }

        @PostMapping("/calendars")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin') or hasAuthority('PERM_encounter:sign')")
        public ResourceCalendarEntity createCalendar(@RequestBody CreateCalendarRequest req) {
            return service.createCalendar(requireFacility(), req);
        }

        @GetMapping("/calendars")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
        public List<ResourceCalendarEntity> listCalendars() {
            return service.listCalendars(requireFacility());
        }

        @PostMapping("/appointment-types")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_system:admin')")
        public AppointmentTypeEntity createType(@RequestBody CreateAppointmentTypeRequest req) {
            return service.createAppointmentType(requireFacility(), req);
        }

        @GetMapping("/appointment-types")
        @PreAuthorize("hasAuthority('PERM_patient:read') or hasAuthority('PERM_facility:manage')")
        public List<AppointmentTypeEntity> listTypes() {
            return service.listAppointmentTypes(requireFacility());
        }

        @PostMapping("/appointments")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign') or hasAuthority('PERM_triage:write')")
        public ScheduledAppointmentEntity book(@RequestBody BookAppointmentRequest req, Authentication auth) {
            return service.bookAppointment(requireFacility(), req, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/appointments/recurring")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        public List<ScheduledAppointmentEntity> bookRecurring(
                @RequestBody BookRecurringSeriesRequest req, Authentication auth) {
            return service.bookRecurringSeries(requireFacility(), req, auth != null ? auth.getName() : "SYSTEM");
        }

        @GetMapping("/appointments")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public List<ScheduledAppointmentEntity> listAppointments(@RequestParam(required = false) UUID patientId) {
            UUID fac = requireFacility();
            return patientId != null ? service.listPatientAppointments(fac, patientId) : service.listFacilityAppointments(fac);
        }

        @PostMapping("/appointments/{id}/check-in")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_triage:write')")
        public ScheduledAppointmentEntity checkIn(@PathVariable UUID id) {
            return service.checkIn(requireFacility(), id);
        }

        @PostMapping("/appointments/{id}/no-show")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        public ScheduledAppointmentEntity markNoShow(@PathVariable UUID id) {
            return service.markNoShow(requireFacility(), id);
        }

        @PutMapping("/appointments/{id}/reschedule")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        public ScheduledAppointmentEntity reschedule(@PathVariable UUID id, @RequestBody RescheduleRequest req) {
            return service.reschedule(requireFacility(), id, req);
        }

        @PostMapping("/appointments/{id}/cancel")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_encounter:sign')")
        public CancellationOutcome cancel(
                @PathVariable UUID id, @RequestBody CancelAppointmentRequest req, Authentication auth) {
            return service.cancelAppointment(
                    requireFacility(), id, req != null ? req.reason() : null, auth != null ? auth.getName() : "SYSTEM");
        }

        @PostMapping("/waitlist")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_triage:write')")
        public AppointmentWaitlistEntity addWaitlist(@RequestBody WaitlistRequest req) {
            return service.enqueueWaitlist(requireFacility(), req);
        }

        @GetMapping(value = "/fhir/Appointment", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public ResponseEntity<String> searchFhirAppointments(@RequestParam(required = false) UUID patientId) {
            UUID fac = requireFacility();
            List<ScheduledAppointmentEntity> rows = patientId != null
                    ? service.listPatientAppointments(fac, patientId)
                    : service.listFacilityAppointments(fac);
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(rows.size());
            rows.forEach(r -> bundle.addEntry().setResource(service.toFhirAppointment(r)));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body(FHIR_R4.newJsonParser().encodeResourceToString(bundle));
        }

        @GetMapping(value = "/fhir/Schedule", produces = "application/fhir+json")
        @PreAuthorize("hasAuthority('PERM_patient:read')")
        public ResponseEntity<String> searchFhirSchedules() {
            List<ResourceCalendarEntity> rows = service.listCalendars(requireFacility());
            Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(rows.size());
            rows.forEach(r -> bundle.addEntry().setResource(service.toFhirSchedule(r)));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .body(FHIR_R4.newJsonParser().encodeResourceToString(bundle));
        }

        private UUID requireFacility() {
            return FacilityContext.getFacilityId()
                    .orElseThrow(() -> new AccessDeniedException("Active facility tenant context is required."));
        }
    }
}
