package com.healthcore.consent;

import com.healthcore.audit.AuditLogEntity;
import com.healthcore.audit.AuditWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsentEnforcementServiceTest {

    @Mock
    private ConsentRepository consentRepository;

    @Mock
    private AuditWriter auditWriter;

    @InjectMocks
    private ConsentEnforcementService service;

    @Test
    void allowsAccessWhenActiveConsentExists() {
        UUID patientId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        when(consentRepository.findActiveConsentsForFacility(eq(patientId), eq(facilityId), any(Instant.class)))
                .thenReturn(List.of(new PatientConsentEntity()));

        assertDoesNotThrow(() -> service.assertHieAccessOrBreakGlass(patientId, facilityId, null));
        verifyNoInteractions(auditWriter);
    }

    @Test
    void deniesAccessWithoutConsentOrBreakGlass() {
        UUID patientId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        when(consentRepository.findActiveConsentsForFacility(eq(patientId), eq(facilityId), any(Instant.class)))
                .thenReturn(List.of());

        assertThrows(AccessDeniedException.class,
                () -> service.assertHieAccessOrBreakGlass(patientId, facilityId, null));
    }

    @Test
    void allowsEmergencyBreakGlassAndAudits() {
        UUID patientId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        when(consentRepository.findActiveConsentsForFacility(eq(patientId), eq(facilityId), any(Instant.class)))
                .thenReturn(List.of());

        assertDoesNotThrow(() -> service.assertHieAccessOrBreakGlass(patientId, facilityId, "Unconscious ER patient"));
        verify(auditWriter, times(1)).write(any(AuditLogEntity.class));
    }
}
