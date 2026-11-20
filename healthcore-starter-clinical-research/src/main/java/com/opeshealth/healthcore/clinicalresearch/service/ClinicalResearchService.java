/*
 * =============================================================================
 * Opes HealthCore SDK — Clinical Research & EDC Service (ICH E6(R3), CDISC & OMOP)
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

    public interface InvestigationalProductRepository extends JpaRepository<InvestigationalProductLedgerEntity, UUID> {
        Optional<InvestigationalProductLedgerEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<InvestigationalProductLedgerEntity> findByFacilityIdAndProtocolCode(UUID facilityId, String protocolCode);
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

    public record DispenseIpRequest(
            UUID subjectId,
            String kitNumber,
            String lotNumber,
            int unitsDispensed,
            String dispensedBy
    ) {}

    public record ReconcileIpRequest(
            int returnedUnits,
            int destroyedUnits,
            String reconciledBy,
            String destructionWitnessId
    ) {}

    private final TrialSubjectRepository subjectRepo;
    private final EcrfEntryRepository ecrfRepo;
    private final SafetyEventRepository safetyRepo;
    private final InvestigationalProductRepository ipRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public ClinicalResearchService(
            TrialSubjectRepository subjectRepo,
            EcrfEntryRepository ecrfRepo,
            SafetyEventRepository safetyRepo,
            InvestigationalProductRepository ipRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.subjectRepo = subjectRepo;
        this.ecrfRepo = ecrfRepo;
        this.safetyRepo = safetyRepo;
        this.ipRepo = ipRepo;
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

    @Transactional
    @ClinicalAudit(action = "CLINRES_IP_DISPENSE", resourceType = "InvestigationalProduct")
    public InvestigationalProductLedgerEntity dispenseInvestigationalProduct(DispenseIpRequest req) {
        UUID facId = requireFacility();
        if (req.unitsDispensed() <= 0) {
            throw new IllegalArgumentException("unitsDispensed must be positive.");
        }
        TrialSubjectEntity subject = subjectRepo.findByIdAndFacilityId(req.subjectId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Trial subject not found: " + req.subjectId()));
        if (!"ENROLLED".equals(subject.getStatus())) {
            throw new IllegalStateException("Cannot dispense Investigational Product to non-enrolled subject.");
        }
        InvestigationalProductLedgerEntity ip = new InvestigationalProductLedgerEntity();
        ip.setFacilityId(facId);
        ip.setProtocolCode(subject.getProtocolCode());
        ip.setSiteCode(subject.getSiteCode());
        ip.setSubjectId(subject.getId());
        ip.setKitNumber(req.kitNumber().trim().toUpperCase(Locale.ROOT));
        ip.setLotNumber(req.lotNumber().trim().toUpperCase(Locale.ROOT));
        ip.setDispensedUnits(req.unitsDispensed());
        ip.setDispensedBy(req.dispensedBy().trim());
        ip.setStatus("DISPENSED");
        ip.setUpdatedAt(Instant.now());
        return ipRepo.save(ip);
    }

    @Transactional
    @ClinicalAudit(action = "CLINRES_IP_RECONCILE", resourceType = "InvestigationalProduct")
    public InvestigationalProductLedgerEntity reconcileInvestigationalProduct(UUID ipId, ReconcileIpRequest req) {
        UUID facId = requireFacility();
        InvestigationalProductLedgerEntity ip = ipRepo.findByIdAndFacilityId(ipId, facId)
                .orElseThrow(() -> new NoSuchElementException("Investigational product kit not found: " + ipId));
        if (req.returnedUnits() < 0 || req.destroyedUnits() < 0) {
            throw new IllegalArgumentException("Returned and destroyed units cannot be negative.");
        }
        if (req.returnedUnits() + req.destroyedUnits() > ip.getDispensedUnits()) {
            throw new IllegalStateException("Returned + destroyed units exceed dispensed quantity for kit " + ip.getKitNumber());
        }
        ip.setReturnedUnits(req.returnedUnits());
        ip.setDestroyedUnits(req.destroyedUnits());
        ip.setReconciledBy(req.reconciledBy().trim());
        if (req.destroyedUnits() > 0) {
            ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                    req.destructionWitnessId(), "IP_DESTRUCTION_WITNESS", "IP_DESTRUCTION_WITNESSED",
                    "InvestigationalProduct", ipId.toString(),
                    ip.getKitNumber() + "|" + ip.getLotNumber() + "|" + req.destroyedUnits(),
                    req.reconciledBy(), true
            ));
            ip.setDestructionWitnessId(proof.signerId());
            ip.setWitnessSigSha256(proof.signatureDigestSha256());
            ip.setStatus("DESTROYED");
        } else {
            ip.setStatus("RETURNED");
        }
        ip.setUpdatedAt(Instant.now());
        return ipRepo.save(ip);
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

    @Transactional(readOnly = true)
    public Map<String, Object> exportDeidentifiedCdiscCdashAndAdam(String protocolCode) {
        UUID facId = requireFacility();
        String code = protocolCode.trim().toUpperCase(Locale.ROOT);
        List<TrialSubjectEntity> subjects = subjectRepo.findByFacilityIdAndProtocolCode(facId, code);
        List<Map<String, Object>> adsl = new ArrayList<>();
        for (TrialSubjectEntity s : subjects) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("STUDYID", s.getProtocolCode());
            row.put("USUBJID", s.getProtocolCode() + "-" + s.getSiteCode() + "-" + s.getSubjectPseudonym());
            row.put("TRT01P", s.getArmCode());
            row.put("ENRLFL", "ENROLLED".equals(s.getStatus()) ? "Y" : "N");
            row.put("ICFVER", s.getEconsentVersion());
            adsl.add(row);
        }
        return Map.of(
                "standards", List.of("CDISC-CDASH-2.2-COMPATIBLE", "CDISC-ADAM-ADSL-1.3-COMPATIBLE"),
                "deidentified", true,
                "protocolCode", code,
                "adslRecordCount", adsl.size(),
                "adslRecords", adsl
        );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportDeidentifiedOmopCdm(String protocolCode) {
        UUID facId = requireFacility();
        String code = protocolCode.trim().toUpperCase(Locale.ROOT);
        List<TrialSubjectEntity> subjects = subjectRepo.findByFacilityIdAndProtocolCode(facId, code);
        List<Map<String, Object>> persons = new ArrayList<>();
        for (TrialSubjectEntity s : subjects) {
            Map<String, Object> person = new LinkedHashMap<>();
            person.put("person_source_value", ElectronicSignatureSpi.sha256Hex(s.getSubjectPseudonym()).substring(0, 16));
            person.put("care_site_source_value", s.getSiteCode());
            person.put("cohort_definition_code", s.getProtocolCode() + ":" + s.getArmCode());
            person.put("cohort_start_datetime", s.getEnrolledAt().toString());
            persons.add(person);
        }
        return Map.of(
                "standard", "OMOP-CDM-5.4-DEIDENTIFIED-PROJECTION",
                "deidentified", true,
                "protocolCode", code,
                "personCount", persons.size(),
                "persons", persons
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

        @PostMapping("/ip/dispense")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_pharmacy:dispense')")
        public InvestigationalProductLedgerEntity dispenseIp(@RequestBody DispenseIpRequest req) {
            return service.dispenseInvestigationalProduct(req);
        }

        @PostMapping("/ip/{id}/reconcile")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_pharmacy:dispense')")
        public InvestigationalProductLedgerEntity reconcileIp(@PathVariable UUID id, @RequestBody ReconcileIpRequest req) {
            return service.reconcileInvestigationalProduct(id, req);
        }

        @GetMapping("/protocols/{protocolCode}/export/cdisc-sdtm")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public Map<String, Object> exportSdtm(@PathVariable String protocolCode) {
            return service.exportDeidentifiedCdiscSdtm(protocolCode);
        }

        @GetMapping("/protocols/{protocolCode}/export/cdisc-cdash-adam")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public Map<String, Object> exportCdashAdam(@PathVariable String protocolCode) {
            return service.exportDeidentifiedCdiscCdashAndAdam(protocolCode);
        }

        @GetMapping("/protocols/{protocolCode}/export/omop-cdm")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_audit:read')")
        public Map<String, Object> exportOmop(@PathVariable String protocolCode) {
            return service.exportDeidentifiedOmopCdm(protocolCode);
        }
    }
}
