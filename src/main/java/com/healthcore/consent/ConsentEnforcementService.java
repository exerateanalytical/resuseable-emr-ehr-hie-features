package com.healthcore.consent;

import com.healthcore.audit.AuditLogEntity;
import com.healthcore.audit.AuditWriter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ConsentEnforcementService {

    private final ConsentRepository consentRepository;
    private final AuditWriter auditWriter;

    public ConsentEnforcementService(ConsentRepository consentRepository, AuditWriter auditWriter) {
        this.consentRepository = consentRepository;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public PatientConsentEntity recordConsent(UUID patientId, UUID grantingFacilityId, UUID targetFacilityId,
                                              String scope, String purposeOfUse, Instant expiresAt, String recordedBy) {
        PatientConsentEntity consent = new PatientConsentEntity();
        consent.setPatientId(patientId);
        consent.setGrantingFacilityId(grantingFacilityId);
        consent.setTargetFacilityId(targetFacilityId);
        consent.setStatus("ACTIVE");
        consent.setScope(scope);
        consent.setPurposeOfUse(purposeOfUse);
        consent.setExpiresAt(expiresAt);
        consent.setRecordedBy(recordedBy);
        return consentRepository.save(consent);
    }

    public void assertHieAccessOrBreakGlass(UUID patientId, UUID requestingFacilityId, String emergencyBreakGlassReason) {
        var active = consentRepository.findActiveConsentsForFacility(patientId, requestingFacilityId, Instant.now());
        if (!active.isEmpty()) {
            return;
        }

        if (emergencyBreakGlassReason != null && !emergencyBreakGlassReason.isBlank()) {
            AuditLogEntity btg = new AuditLogEntity();
            var auth = SecurityContextHolder.getContext().getAuthentication();
            btg.setActorId(auth != null ? auth.getName() : "unknown");
            btg.setFacilityId(requestingFacilityId);
            btg.setAction("BREAK_THE_GLASS");
            btg.setResourceType("HIE_PATIENT_SUMMARY");
            btg.setPatientId(patientId);
            btg.setResourceId(patientId.toString());
            btg.setOutcome("SUCCESS");
            btg.setReason(emergencyBreakGlassReason);
            auditWriter.write(btg);
            return;
        }

        throw new AccessDeniedException("No active HIE consent for patient " + patientId + " at facility " + requestingFacilityId);
    }
}
