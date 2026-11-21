/*
 * =============================================================================
 * Opes HealthCore SDK — Patient & Proxy Portal Service and REST Controller
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.portal.service;

import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.ClinicalResultEntity;
import com.opeshealth.healthcore.orders.domain.OrdersResultsEntities.ResultStatus;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.portal.domain.PatientPortalEntities.*;
import com.opeshealth.healthcore.scheduling.domain.SchedulingEntities.ScheduledAppointmentEntity;
import com.opeshealth.healthcore.scheduling.service.SchedulingService;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.audit.ClinicalAuditService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides strictly scoped patient and proxy-authorized portal access to verified lab
 * results (never exposing unreleased preliminary values), upcoming appointments,
 * multilingual locale preferences, and secure patient-to-care-team messaging.
 */
public class PatientPortalService {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "fr", "es", "ar");

    public interface PortalAccountRepository extends JpaRepository<PatientPortalAccountEntity, UUID> {
        Optional<PatientPortalAccountEntity> findByFacilityIdAndIdentitySubjectAndActiveTrue(
                UUID facilityId, String identitySubject);
        Optional<PatientPortalAccountEntity> findByFacilityIdAndPatientIdAndActiveTrue(
                UUID facilityId, UUID patientId);
        List<PatientPortalAccountEntity> findByFacilityIdAndActiveTrueOrderByCreatedAtDesc(UUID facilityId);
    }

    public interface ProxyGrantRepository extends JpaRepository<PatientProxyGrantEntity, UUID> {
        List<PatientProxyGrantEntity> findByFacilityIdAndPatientIdAndProxySubjectAndRevokedFalse(
                UUID facilityId, UUID patientId, String proxySubject);
        Optional<PatientProxyGrantEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface MessageThreadRepository extends JpaRepository<PortalMessageThreadEntity, UUID> {
        List<PortalMessageThreadEntity> findByFacilityIdAndPatientIdOrderByUpdatedAtDesc(UUID facilityId, UUID patientId);
        Optional<PortalMessageThreadEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public interface PortalMessageRepository extends JpaRepository<PortalMessageEntity, UUID> {
        List<PortalMessageEntity> findByFacilityIdAndThreadIdOrderBySentAtAsc(UUID facilityId, UUID threadId);
    }

    public record EnrollPortalAccountRequest(
            UUID patientId, String identitySubject, String preferredLocale, String contactEmail, String contactPhone) {}

    public record GrantProxyRequest(
            UUID patientId, String proxySubject, String relationship, Set<PortalScope> scopes, Instant expiresAt) {}

    public record OpenThreadRequest(UUID patientId, String subject, String careTeamDepartment, String initialMessage) {}

    public record SendMessageRequest(String body) {}

    public record PortalReleasedResultView(
            UUID resultId,
            String resultCode,
            String resultDisplay,
            String valueDisplay,
            String unit,
            String referenceRange,
            String abnormalFlag,
            String status,
            Instant verifiedAt) {}

    private final PortalAccountRepository accountRepo;
    private final ProxyGrantRepository proxyRepo;
    private final MessageThreadRepository threadRepo;
    private final PortalMessageRepository messageRepo;
    private final SchedulingService schedulingService;
    private final UnifiedOrdersResultsService ordersResultsService;
    private final ClinicalAuditService auditService;

    public PatientPortalService(
            PortalAccountRepository accountRepo,
            ProxyGrantRepository proxyRepo,
            MessageThreadRepository threadRepo,
            PortalMessageRepository messageRepo,
            SchedulingService schedulingService,
            UnifiedOrdersResultsService ordersResultsService,
            ClinicalAuditService auditService) {
        this.accountRepo = accountRepo;
        this.proxyRepo = proxyRepo;
        this.threadRepo = threadRepo;
        this.messageRepo = messageRepo;
        this.schedulingService = schedulingService;
        this.ordersResultsService = ordersResultsService;
        this.auditService = auditService;
    }

    @Transactional
    @ClinicalAudit(action = "PORTAL_ENROLL_ACCOUNT", resourceType = "Patient")
    public PatientPortalAccountEntity enrollAccount(UUID facilityId, EnrollPortalAccountRequest req) {
        if (facilityId == null || req == null || req.patientId() == null || isBlank(req.identitySubject())) {
            throw new IllegalArgumentException("facilityId, patientId, and identitySubject are required.");
        }
        String locale = normalizeLocale(req.preferredLocale());
        PatientPortalAccountEntity account = accountRepo.findByFacilityIdAndPatientIdAndActiveTrue(facilityId, req.patientId())
                .orElseGet(PatientPortalAccountEntity::new);
        account.setFacilityId(facilityId);
        account.setPatientId(req.patientId());
        account.setIdentitySubject(req.identitySubject().trim());
        account.setPreferredLocale(locale);
        account.setContactEmail(req.contactEmail());
        account.setContactPhone(req.contactPhone());
        account.setActive(true);
        return accountRepo.save(account);
    }

    @Transactional
    public PatientPortalAccountEntity updatePreferredLocale(
            UUID facilityId, String callerSubject, UUID patientId, String preferredLocale) {
        assertPortalAccess(facilityId, callerSubject, patientId, PortalScope.CONSENT);
        PatientPortalAccountEntity account = accountRepo.findByFacilityIdAndPatientIdAndActiveTrue(facilityId, patientId)
                .orElseThrow(() -> new IllegalArgumentException("Active portal account not found for patient."));
        account.setPreferredLocale(normalizeLocale(preferredLocale));
        return accountRepo.save(account);
    }

    @Transactional
    @ClinicalAudit(action = "PORTAL_GRANT_PROXY", resourceType = "Consent")
    public PatientProxyGrantEntity grantProxyAccess(
            UUID facilityId, String callerSubject, boolean callerIsFacilityAdmin, GrantProxyRequest req) {
        if (facilityId == null || req == null || req.patientId() == null || isBlank(req.proxySubject())
                || req.scopes() == null || req.scopes().isEmpty() || req.expiresAt() == null
                || !req.expiresAt().isAfter(Instant.now())) {
            throw new IllegalArgumentException("Valid patientId, proxySubject, non-empty scopes, and future expiresAt are required.");
        }
        if (!callerIsFacilityAdmin) {
            assertPatientSelfOnly(facilityId, callerSubject, req.patientId());
        }
        PatientProxyGrantEntity grant = new PatientProxyGrantEntity();
        grant.setFacilityId(facilityId);
        grant.setPatientId(req.patientId());
        grant.setProxySubject(req.proxySubject().trim());
        grant.setRelationship(isBlank(req.relationship()) ? "GUARDIAN" : req.relationship().trim().toUpperCase(Locale.ROOT));
        grant.setAllowedScopesCsv(req.scopes().stream().map(Enum::name).sorted().collect(Collectors.joining(",")));
        grant.setExpiresAt(req.expiresAt());
        grant.setGrantedBy(callerSubject != null ? callerSubject : "SYSTEM");
        return proxyRepo.save(grant);
    }

    @Transactional
    @ClinicalAudit(action = "PORTAL_REVOKE_PROXY", resourceType = "Consent")
    public PatientProxyGrantEntity revokeProxyAccess(
            UUID facilityId, String callerSubject, boolean callerIsFacilityAdmin, UUID grantId) {
        PatientProxyGrantEntity grant = proxyRepo.findByIdAndFacilityId(grantId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Proxy grant not found in active facility."));
        if (!callerIsFacilityAdmin) {
            assertPatientSelfOnly(facilityId, callerSubject, grant.getPatientId());
        }
        grant.setRevoked(true);
        return proxyRepo.save(grant);
    }

    @Transactional(readOnly = true)
    public List<ScheduledAppointmentEntity> getPatientAppointments(
            UUID facilityId, String callerSubject, UUID patientId) {
        assertPortalAccess(facilityId, callerSubject, patientId, PortalScope.APPOINTMENTS);
        return schedulingService.listPatientAppointments(facilityId, patientId);
    }

    /**
     * Returns only FINAL or AMENDED verified diagnostic results to the patient or authorized proxy.
     * Unverified PRELIMINARY results and CANCELLED entries are strictly excluded.
     */
    @Transactional(readOnly = true)
    public List<PortalReleasedResultView> getReleasedResults(
            UUID facilityId, String callerSubject, UUID patientId) {
        assertPortalAccess(facilityId, callerSubject, patientId, PortalScope.RESULTS);
        return ordersResultsService.listPatientResults(facilityId, patientId).stream()
                .filter(r -> (r.getStatus() == ResultStatus.FINAL || r.getStatus() == ResultStatus.AMENDED)
                        && r.getVerifiedAt() != null)
                .map(this::toPortalResultView)
                .toList();
    }

    @Transactional
    @ClinicalAudit(action = "PORTAL_OPEN_THREAD", resourceType = "Communication")
    public PortalMessageThreadEntity openMessageThread(
            UUID facilityId, String callerSubject, OpenThreadRequest req) {
        if (req == null || req.patientId() == null || isBlank(req.subject()) || isBlank(req.initialMessage())) {
            throw new IllegalArgumentException("patientId, subject, and initialMessage are required.");
        }
        String senderRole = assertPortalAccess(facilityId, callerSubject, req.patientId(), PortalScope.MESSAGING);
        PortalMessageThreadEntity thread = new PortalMessageThreadEntity();
        thread.setFacilityId(facilityId);
        thread.setPatientId(req.patientId());
        thread.setSubject(req.subject().trim());
        thread.setCareTeamDepartment(isBlank(req.careTeamDepartment()) ? "OPD-GENERAL" : req.careTeamDepartment().trim().toUpperCase(Locale.ROOT));
        thread.setCreatedBySubject(callerSubject);
        PortalMessageThreadEntity savedThread = threadRepo.save(thread);

        PortalMessageEntity first = new PortalMessageEntity();
        first.setFacilityId(facilityId);
        first.setThreadId(savedThread.getId());
        first.setSenderSubject(callerSubject);
        first.setSenderRole(senderRole);
        first.setBody(req.initialMessage().trim());
        messageRepo.save(first);
        return savedThread;
    }

    @Transactional
    @ClinicalAudit(action = "PORTAL_SEND_MESSAGE", resourceType = "Communication")
    public PortalMessageEntity sendMessage(
            UUID facilityId, String callerSubject, boolean callerIsClinician, UUID threadId, String body) {
        if (isBlank(body)) {
            throw new IllegalArgumentException("Message body cannot be empty.");
        }
        PortalMessageThreadEntity thread = threadRepo.findByIdAndFacilityId(threadId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Message thread not found in active facility."));
        if (thread.getStatus() != ThreadStatus.OPEN) {
            throw new IllegalStateException("Cannot post messages to a closed thread.");
        }
        String senderRole = callerIsClinician
                ? "CLINICIAN"
                : assertPortalAccess(facilityId, callerSubject, thread.getPatientId(), PortalScope.MESSAGING);

        PortalMessageEntity msg = new PortalMessageEntity();
        msg.setFacilityId(facilityId);
        msg.setThreadId(thread.getId());
        msg.setSenderSubject(callerSubject);
        msg.setSenderRole(senderRole);
        msg.setBody(body.trim());
        thread.setUpdatedAt(Instant.now());
        threadRepo.save(thread);
        return messageRepo.save(msg);
    }

    @Transactional(readOnly = true)
    public List<PortalMessageThreadEntity> listPatientThreads(UUID facilityId, String callerSubject, UUID patientId) {
        assertPortalAccess(facilityId, callerSubject, patientId, PortalScope.MESSAGING);
        return threadRepo.findByFacilityIdAndPatientIdOrderByUpdatedAtDesc(facilityId, patientId);
    }

    @Transactional(readOnly = true)
    public List<PortalMessageEntity> listThreadMessages(
            UUID facilityId, String callerSubject, boolean callerIsClinician, UUID threadId) {
        PortalMessageThreadEntity thread = threadRepo.findByIdAndFacilityId(threadId, facilityId)
                .orElseThrow(() -> new AccessDeniedException("Message thread not found in active facility."));
        if (!callerIsClinician) {
            assertPortalAccess(facilityId, callerSubject, thread.getPatientId(), PortalScope.MESSAGING);
        }
        return messageRepo.findByFacilityIdAndThreadIdOrderBySentAtAsc(facilityId, threadId);
    }

    /**
     * Enforces strict patient-self or active scoped proxy authorization within the active facility.
     * Returns {@code "PATIENT"} or {@code "PROXY"} on success, or throws {@link AccessDeniedException}.
     */
    public String assertPortalAccess(
            UUID facilityId, String callerSubject, UUID targetPatientId, PortalScope requiredScope) {
        if (facilityId == null || isBlank(callerSubject) || targetPatientId == null || requiredScope == null) {
            throw new AccessDeniedException("Missing facility, identity, patient, or scope for portal access.");
        }
        Optional<PatientPortalAccountEntity> self =
                accountRepo.findByFacilityIdAndIdentitySubjectAndActiveTrue(facilityId, callerSubject.trim());
        if (self.isPresent() && self.get().getPatientId().equals(targetPatientId)) {
            return "PATIENT";
        }
        Instant now = Instant.now();
        List<PatientProxyGrantEntity> grants =
                proxyRepo.findByFacilityIdAndPatientIdAndProxySubjectAndRevokedFalse(
                        facilityId, targetPatientId, callerSubject.trim());
        for (PatientProxyGrantEntity grant : grants) {
            if (grant.getExpiresAt().isAfter(now) && hasScope(grant.getAllowedScopesCsv(), requiredScope)) {
                return "PROXY";
            }
        }
        if (auditService != null) {
            auditService.recordEvent(
                    facilityId.toString(),
                    callerSubject,
                    "PORTAL",
                    "PORTAL_ACCESS_DENIED_" + requiredScope.name(),
                    "Patient",
                    targetPatientId.toString(),
                    targetPatientId,
                    "DENIED",
                    "Caller lacks patient ownership or active proxy grant for scope " + requiredScope.name(),
                    "internal");
        }
        throw new AccessDeniedException(
                "Portal access denied: caller is neither the patient nor an active authorized proxy for scope "
                        + requiredScope.name());
    }

    private void assertPatientSelfOnly(UUID facilityId, String callerSubject, UUID targetPatientId) {
        if (facilityId == null || isBlank(callerSubject) || targetPatientId == null) {
            throw new AccessDeniedException("Authenticated patient identity and facility are required.");
        }
        Optional<PatientPortalAccountEntity> self =
                accountRepo.findByFacilityIdAndIdentitySubjectAndActiveTrue(facilityId, callerSubject.trim());
        if (self.isEmpty() || !self.get().getPatientId().equals(targetPatientId)) {
            throw new AccessDeniedException("Only the patient or a facility administrator may manage proxy grants.");
        }
    }

    private static boolean hasScope(String csv, PortalScope requiredScope) {
        if (isBlank(csv)) return false;
        for (String token : csv.split(",")) {
            if (requiredScope.name().equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private PortalReleasedResultView toPortalResultView(ClinicalResultEntity r) {
        String value = r.getNumericValue() != null
                ? r.getNumericValue().toPlainString()
                : Objects.toString(r.getTextValue(), "");
        return new PortalReleasedResultView(
                r.getId(), r.getResultCode(), r.getResultDisplay(), value,
                r.getUnit(), r.getReferenceRange(),
                r.getAbnormalFlag() != null ? r.getAbnormalFlag().name() : "UNKNOWN",
                r.getStatus().name(), r.getVerifiedAt());
    }

    private static String normalizeLocale(String locale) {
        if (isBlank(locale)) return "en";
        String lower = locale.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_LOCALES.contains(lower) ? lower : "en";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @RestController
    @RequestMapping("/api/v1/portal")
    public static class PatientPortalController {
        private final PatientPortalService service;

        public PatientPortalController(PatientPortalService service) {
            this.service = service;
        }

        @PostMapping("/accounts")
        @PreAuthorize("hasAuthority('PERM_patient:write') or hasAuthority('PERM_facility:manage')")
        public PatientPortalAccountEntity enrollAccount(@RequestBody EnrollPortalAccountRequest req) {
            return service.enrollAccount(requireFacility(), req);
        }

        @PostMapping("/proxies")
        @PreAuthorize("hasAuthority('PERM_self:consent:manage') or hasAuthority('PERM_facility:manage')")
        public PatientProxyGrantEntity grantProxy(@RequestBody GrantProxyRequest req, Authentication auth) {
            return service.grantProxyAccess(requireFacility(), requireSubject(auth), hasAuthority(auth, "PERM_facility:manage"), req);
        }

        @DeleteMapping("/proxies/{grantId}")
        @PreAuthorize("hasAuthority('PERM_self:consent:manage') or hasAuthority('PERM_facility:manage')")
        public PatientProxyGrantEntity revokeProxy(@PathVariable UUID grantId, Authentication auth) {
            return service.revokeProxyAccess(requireFacility(), requireSubject(auth), hasAuthority(auth, "PERM_facility:manage"), grantId);
        }

        @GetMapping("/patients/{patientId}/appointments")
        @PreAuthorize("hasAuthority('PERM_self:record:read') or hasAuthority('PERM_self:appointment:request')")
        public List<ScheduledAppointmentEntity> myAppointments(@PathVariable UUID patientId, Authentication auth) {
            return service.getPatientAppointments(requireFacility(), requireSubject(auth), patientId);
        }

        @GetMapping("/patients/{patientId}/results")
        @PreAuthorize("hasAuthority('PERM_self:record:read')")
        public List<PortalReleasedResultView> myResults(@PathVariable UUID patientId, Authentication auth) {
            return service.getReleasedResults(requireFacility(), requireSubject(auth), patientId);
        }

        @PostMapping("/threads")
        @PreAuthorize("hasAuthority('PERM_self:record:read')")
        public PortalMessageThreadEntity openThread(@RequestBody OpenThreadRequest req, Authentication auth) {
            return service.openMessageThread(requireFacility(), requireSubject(auth), req);
        }

        @PostMapping("/threads/{threadId}/messages")
        @PreAuthorize("hasAuthority('PERM_self:record:read') or hasAuthority('PERM_patient:read')")
        public PortalMessageEntity sendMessage(
                @PathVariable UUID threadId, @RequestBody SendMessageRequest req, Authentication auth) {
            return service.sendMessage(
                    requireFacility(), requireSubject(auth), hasAuthority(auth, "PERM_patient:read"),
                    threadId, req != null ? req.body() : null);
        }

        @GetMapping("/patients/{patientId}/threads")
        @PreAuthorize("hasAuthority('PERM_self:record:read')")
        public List<PortalMessageThreadEntity> listThreads(@PathVariable UUID patientId, Authentication auth) {
            return service.listPatientThreads(requireFacility(), requireSubject(auth), patientId);
        }

        @GetMapping("/threads/{threadId}/messages")
        @PreAuthorize("hasAuthority('PERM_self:record:read') or hasAuthority('PERM_patient:read')")
        public List<PortalMessageEntity> listMessages(@PathVariable UUID threadId, Authentication auth) {
            return service.listThreadMessages(
                    requireFacility(), requireSubject(auth), hasAuthority(auth, "PERM_patient:read"), threadId);
        }

        private UUID requireFacility() {
            return FacilityContext.getFacilityId()
                    .orElseThrow(() -> new AccessDeniedException("Active facility tenant context is required."));
        }

        private String requireSubject(Authentication auth) {
            if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
                throw new AccessDeniedException("Authenticated subject is required.");
            }
            return auth.getName();
        }

        private boolean hasAuthority(Authentication auth, String authority) {
            return auth != null && auth.getAuthorities().stream()
                    .anyMatch(a -> authority.equals(a.getAuthority()));
        }
    }
}
