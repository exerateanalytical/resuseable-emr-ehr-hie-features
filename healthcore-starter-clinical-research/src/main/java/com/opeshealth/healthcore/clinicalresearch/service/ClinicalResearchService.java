/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical Research & EDC Service (ICH E6(R3) & CDISC)
 * =============================================================================
 */
package com.opeshealth.healthcore.clinicalresearch.service;

import com.opeshealth.healthcore.clinicalresearch.domain.ClinicalResearchEntities.*;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureCommand;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureProof;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Service
public class ClinicalResearchService {

    public interface TrialSubjectRepository extends JpaRepository<TrialSubjectEntity, UUID> {
        Optional<TrialSubjectEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<TrialSubjectEntity> findByFacilityIdAndProtocolCode(UUID facilityId, String protocolCode);
    }

    public interface EcrfEntryRepository extends JpaRepository<EcrfEntryEntity, UUID> {
        Optional<EcrfEntryEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<EcrfEntryEntity> findByFacilityIdAndSubjectId(UUID facilityId, UUID subjectId);
    }

    public interface SafetyEventRepository extends JpaRepository<SafetyEventEntity, UUID> {
        List<SafetyEventEntity> findByFacilityIdAndSubjectId(UUID facilityId, UUID subjectId);
    }

    public record EnrollSubjectRequest(
            String protocolCode,
            String siteCode,
            String subjectPseudonym,
            String armCode,
            boolean eligibilityPassed,
            String econsentVersion
    ) {}

    public record CaptureEcrfRequest(
            UUID subjectId,
            String visitCode,
            String sdtmDomain,
            String formPayloadJson
    ) {}

    public record ReportSafetyEventRequest(
            UUID subjectId,
            String eventCategory,
            String meddraTerm,
            boolean serious,
            String causality,
            String reportedBy
    ) {}

    private final TrialSubjectRepository subjectRepo;
    private final EcrfEntryRepository ecrfRepo;
    private final SafetyEventRepository safetyRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public ClinicalResearchService(
            TrialSubjectRepository subjectRepo,
            EcrfEntryRepository ecrfRepo,
            SafetyEventRepository safetyRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.subjectRepo = subjectRepo;
        this.ecrfRepo = ecrfRepo;
        this.safetyRepo = safetyRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_SUBJECT_ENROLL", resourceType = "TrialSubject")
    public TrialSubjectEntity enrollSubject(EnrollSubjectRequest req) {
        UUID facId = requireFacility();
        if (!req.eligibilityPassed()) {
            throw new IllegalStateException("ICH E6(R3) gate: subject failed protocol eligibility screening.");
        }
        if (req.econsentVersion() == null || req.econsentVersion().isBlank()) {
            throw new IllegalStateException("ICH E6(R3) gate: documented eConsent version is mandatory before enrollment.");
        }
        TrialSubjectEntity subject = new TrialSubjectEntity();
        subject.setFacilityId(facId);
        subject.setProtocolCode(req.protocolCode().trim().toUpperCase(Locale.ROOT));
        subject.setSiteCode(req.siteCode().trim().toUpperCase(Locale.ROOT));
        subject.setSubjectPseudonym(req.subjectPseudonym().trim().toUpperCase(Locale.ROOT));
        subject.setArmCode(req.armCode());
        subject.setEligibilityPassed(true);
        subject.setEconsentVersion(req.econsentVersion().trim());
        subject.setEconsentSignedAt(Instant.now());
        subject.setStatus("ENROLLED");
        return subjectRepo.save(subject);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_ECRF_CAPTURE", resourceType = "EcrfEntry")
    public EcrfEntryEntity captureEcrfVisit(CaptureEcrfRequest req) {
        UUID facId = requireFacility();
        TrialSubjectEntity subject = subjectRepo.findByIdAndFacilityId(req.subjectId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Trial subject not found: " + req.subjectId()));
        if (!"ENROLLED".equals(subject.getStatus())) {
            throw new IllegalStateException("Cannot capture eCRF for subject with status=" + subject.getStatus());
        }
        EcrfEntryEntity ecrf = new EcrfEntryEntity();
        ecrf.setFacilityId(facId);
        ecrf.setSubjectId(subject.getId());
        ecrf.setVisitCode(req.visitCode().trim().toUpperCase(Locale.ROOT));
        ecrf.setSdtmDomain(req.sdtmDomain().trim().toUpperCase(Locale.ROOT));
        ecrf.setFormPayloadJson(req.formPayloadJson());
        ecrf.setStatus("DRAFT");
        return ecrfRepo.save(ecrf);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_ECRF_QUERY_OPEN", resourceType = "EcrfEntry")
    public EcrfEntryEntity raiseDataQuery(UUID ecrfId) {
        UUID facId = requireFacility();
        EcrfEntryEntity ecrf = ecrfRepo.findByIdAndFacilityId(ecrfId, facId)
                .orElseThrow(() -> new NoSuchElementException("eCRF not found: " + ecrfId));
        if ("LOCKED".equals(ecrf.getStatus())) {
            throw new IllegalStateException("Cannot raise query on locked eCRF.");
        }
        ecrf.setOpenQueryCount(ecrf.getOpenQueryCount() + 1);
        ecrf.setStatus("QUERIED");
        return ecrfRepo.save(ecrf);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_ECRF_QUERY_RESOLVE", resourceType = "EcrfEntry")
    public EcrfEntryEntity resolveDataQuery(UUID ecrfId, String correctedPayloadJson) {
        UUID facId = requireFacility();
        EcrfEntryEntity ecrf = ecrfRepo.findByIdAndFacilityId(ecrfId, facId)
                .orElseThrow(() -> new NoSuchElementException("eCRF not found: " + ecrfId));
        if (correctedPayloadJson != null && !correctedPayloadJson.isBlank()) {
            ecrf.setFormPayloadJson(correctedPayloadJson);
        }
        ecrf.setOpenQueryCount(Math.max(0, ecrf.getOpenQueryCount() - 1));
        if (ecrf.getOpenQueryCount() == 0) {
            ecrf.setStatus("SDV_VERIFIED");
        }
        return ecrfRepo.save(ecrf);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_ECRF_LOCK", resourceType = "EcrfEntry")
    public EcrfEntryEntity signAndLockEcrf(UUID ecrfId, String investigatorId) {
        UUID facId = requireFacility();
        EcrfEntryEntity ecrf = ecrfRepo.findByIdAndFacilityId(ecrfId, facId)
                .orElseThrow(() -> new NoSuchElementException("eCRF not found: " + ecrfId));
        if (ecrf.getOpenQueryCount() > 0) {
            throw new IllegalStateException("Database lock blocked: " + ecrf.getOpenQueryCount() + " open data queries remain unresolved.");
        }
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                investigatorId, "PRINCIPAL_INVESTIGATOR", "ECRF_INVESTIGATOR_SIGNOFF",
                "EcrfEntry", ecrfId.toString(),
                ecrf.getVisitCode() + "|" + ecrf.getSdtmDomain() + "|" + ecrf.getFormPayloadJson(),
                null, false
        ));
        ecrf.setInvestigatorId(proof.signerId());
        ecrf.setPiSigSha256(proof.signatureDigestSha256());
        ecrf.setStatus("LOCKED");
        return ecrfRepo.save(ecrf);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_SAFETY_REPORT", resourceType = "SafetyEvent")
    public SafetyEventEntity reportSafetyEvent(ReportSafetyEventRequest req) {
        UUID facId = requireFacility();
        subjectRepo.findByIdAndFacilityId(req.subjectId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Trial subject not found: " + req.subjectId()));
        SafetyEventEntity ev = new SafetyEventEntity();
        ev.setFacilityId(facId);
        ev.setSubjectId(req.subjectId());
        ev.setEventCategory(req.eventCategory().trim().toUpperCase(Locale.ROOT));
        ev.setMeddraTerm(req.meddraTerm().trim());
        ev.setSerious(req.serious());
        ev.setCausality(req.causality().trim().toUpperCase(Locale.ROOT));
        ev.setExpeditedReportRequired(req.serious() && !"UNRELATED".equalsIgnoreCase(req.causality()));
        ev.setReportedBy(req.reportedBy().trim());
        return safetyRepo.save(ev);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportDeidentifiedCdiscSdtm(String protocolCode) {
        UUID facId = requireFacility();
        List<TrialSubjectEntity> subjects = subjectRepo.findByFacilityIdAndProtocolCode(facId, protocolCode.trim().toUpperCase(Locale.ROOT));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TrialSubjectEntity s : subjects) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("STUDYID", s.getProtocolCode());
            dm.put("DOMAIN", "DM");
            dm.put("USUBJID", s.getProtocolCode() + "-" + s.getSiteCode() + "-" + s.getSubjectPseudonym());
            dm.put("SITEID", s.getSiteCode());
            dm.put("ARMCD", s.getArmCode());
            dm.put("RFSTDTC", s.getEnrolledAt().toString());
            rows.add(dm);
        }
        return Map.of(
                "standard", "CDISC-SDTM-IG-3.4-COMPATIBLE",
                "deidentified", true,
                "protocolCode", protocolCode.trim().toUpperCase(Locale.ROOT),
                "subjectCount", rows.size(),
                "records", rows
        );
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Clinical Research operations."));
    }

    @RestController
    @RequestMapping("/api/v1/lifesciences/clinical-research")
    public static class ClinicalResearchController {
        private final ClinicalResearchService service;

        public ClinicalResearchController(ClinicalResearchService service) {
            this.service = service;
        }

        @PostMapping("/subjects/enroll")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public TrialSubjectEntity enroll(@RequestBody EnrollSubjectRequest req) {
            return service.enrollSubject(req);
        }

        @PostMapping("/ecrf")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public EcrfEntryEntity captureEcrf(@RequestBody CaptureEcrfRequest req) {
            return service.captureEcrfVisit(req);
        }

        @PostMapping("/ecrf/{id}/lock")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:verify')")
        public EcrfEntryEntity lockEcrf(@PathVariable UUID id, @RequestParam String investigatorId) {
            return service.signAndLockEcrf(id, investigatorId);
        }

        @PostMapping("/safety-events")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public SafetyEventEntity reportSafety(@RequestBody ReportSafetyEventRequest req) {
            return service.reportSafetyEvent(req);
        }

        @GetMapping("/protocols/{protocolCode}/export/cdisc-sdtm")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public Map<String, Object> exportSdtm(@PathVariable String protocolCode) {
            return service.exportDeidentifiedCdiscSdtm(protocolCode);
        }
    }
}
