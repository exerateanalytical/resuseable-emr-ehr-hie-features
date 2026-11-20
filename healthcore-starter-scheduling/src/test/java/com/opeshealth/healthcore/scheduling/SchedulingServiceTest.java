package com.opeshealth.healthcore.scheduling;

import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.*;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.scheduling.service.SchedulingService.*;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SchedulingServiceTest {

    private ResourceCalendarRepository calendarRepo;
    private AppointmentTypeRepository typeRepo;
    private ScheduledAppointmentRepository apptRepo;
    private WaitlistRepository waitlistRepo;
    private SchedulingService service;

    @BeforeEach
    void setUp() {
        calendarRepo = mock(ResourceCalendarRepository.class);
        typeRepo = mock(AppointmentTypeRepository.class);
        apptRepo = mock(ScheduledAppointmentRepository.class);
        waitlistRepo = mock(WaitlistRepository.class);
        when(calendarRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(typeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(apptRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(waitlistRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new SchedulingService(calendarRepo, typeRepo, apptRepo, waitlistRepo, mock(ClinicalAuditService.class));
    }

    @Test
    void booksChecksInCancelsAndSurfacesWaitlistCandidates() {
        UUID facId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();

        ResourceCalendarEntity cal = service.createCalendar(facId, new CreateCalendarRequest(
                "OPD", ResourceKind.PROVIDER, "DR-01", "Dr. Mballa Clinic", 20, 1));
        when(calendarRepo.findByIdAndFacilityId(cal.getId(), facId)).thenReturn(Optional.of(cal));
        when(apptRepo.findOverlappingBookings(eq(facId), eq(cal.getId()), anyCollection(), any(), any())).thenReturn(List.of());

        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        Instant end = start.plus(20, ChronoUnit.MINUTES);
        ScheduledAppointmentEntity appt = service.bookAppointment(facId,
                new BookAppointmentRequest(cal.getId(), null, patientId, "CONSULT", start, end, "Follow-up"),
                "clerk.1");
        assertEquals(AppointmentStatus.BOOKED, appt.getStatus());

        // Double booking conflict rejected when maxConcurrentBookings=1
        when(apptRepo.findOverlappingBookings(eq(facId), eq(cal.getId()), anyCollection(), any(), any())).thenReturn(List.of(appt));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.bookAppointment(facId,
                new BookAppointmentRequest(cal.getId(), null, UUID.randomUUID(), "CONSULT", start, end, "Overlap"),
                "clerk.1"));

        // Check-in issues queue token
        when(apptRepo.findByIdAndFacilityId(appt.getId(), facId)).thenReturn(Optional.of(appt));
        ScheduledAppointmentEntity checkedIn = service.checkIn(facId, appt.getId());
        assertEquals(AppointmentStatus.CHECKED_IN, checkedIn.getStatus());
        assertNotNull(checkedIn.getQueueTokenNumber());

        // Cancel surfaces waitlist candidate
        AppointmentWaitlistEntity wait = new AppointmentWaitlistEntity();
        wait.setFacilityId(facId);
        wait.setCalendarId(cal.getId());
        when(waitlistRepo.findByFacilityIdAndCalendarIdAndStatusOrderByPriorityRankAscCreatedAtAsc(
                facId, cal.getId(), WaitlistStatus.WAITING)).thenReturn(List.of(wait));
        CancellationOutcome outcome = service.cancelAppointment(facId, appt.getId(), "Patient rescheduled", "clerk.1");
        assertEquals(AppointmentStatus.CANCELLED, outcome.cancelledAppointment().getStatus());
        assertEquals(1, outcome.waitlistCandidates().size());
    }
}
