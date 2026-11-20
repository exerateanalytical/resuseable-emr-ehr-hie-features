/*
 * =============================================================================
 * Opes HealthCore SDK — Biobanking Service & REST API (ISO 20387-Aligned)
 * =============================================================================
 */
package com.opeshealth.healthcore.biobanking.service;

import com.opeshealth.healthcore.biobanking.domain.BiobankingEntities.*;
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

import java.math.BigDecimal;
import java.util.*;

@Service
public class BiobankingService {

    public interface BiospecimenRepository extends JpaRepository<BiospecimenEntity, UUID> {
        Optional<BiospecimenEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        List<BiospecimenEntity> findByFacilityIdAndFreezerId(UUID facilityId, String freezerId);
        List<BiospecimenEntity> findByFacilityIdAndDonorPseudonym(UUID facilityId, String donorPseudonym);
        List<BiospecimenEntity> findByFacilityIdOrderByCollectedAtDesc(UUID facilityId);
    }

    public interface MtaRequestRepository extends JpaRepository<MtaRequestEntity, UUID> {
        Optional<MtaRequestEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
    }

    public record StoreSpecimenRequest(
            String specimenBarcode,
            String donorPseudonym,
            String consentScope,
            boolean consentVerified,
            String specimenType,
            BigDecimal volumeUl,
            String freezerId,
            String rackBoxSlot,
            BigDecimal targetTempC
    ) {}

    public record CreateAliquotRequest(
            UUID parentSpecimenId,
            String aliquotBarcode,
            BigDecimal aliquotVolumeUl,
            String freezerId,
            String rackBoxSlot
    ) {}

    public record RequestMtaShipmentRequest(
            String mtaReference,
            String recipientInstitution,
            UUID specimenId,
            String requestedPurpose,
            boolean ethicsVerified
    ) {}

    private final BiospecimenRepository specimenRepo;
    private final MtaRequestRepository mtaRepo;
    private final ElectronicSignatureSpi signatureSpi;

    public BiobankingService(
            BiospecimenRepository specimenRepo,
            MtaRequestRepository mtaRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider) {
        this.specimenRepo = specimenRepo;
        this.mtaRepo = mtaRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_SPECIMEN_STORE", resourceType = "Biospecimen")
    public BiospecimenEntity storeSpecimen(StoreSpecimenRequest req) {
        UUID facId = requireFacility();
        if (!req.consentVerified()) {
            throw new IllegalStateException("ISO 20387 governance rule: donor consent must be verified before biobank intake.");
        }
        BiospecimenEntity entity = new BiospecimenEntity();
        entity.setFacilityId(facId);
        entity.setSpecimenBarcode(req.specimenBarcode().trim().toUpperCase(Locale.ROOT));
        entity.setDonorPseudonym(req.donorPseudonym().trim());
        entity.setConsentScope(req.consentScope().trim().toUpperCase(Locale.ROOT));
        entity.setConsentActive(true);
        entity.setSpecimenType(req.specimenType().trim().toUpperCase(Locale.ROOT));
        entity.setVolumeUl(req.volumeUl());
        entity.setFreezerId(req.freezerId().trim().toUpperCase(Locale.ROOT));
        entity.setRackBoxSlot(req.rackBoxSlot().trim().toUpperCase(Locale.ROOT));
        entity.setTargetTempC(req.targetTempC() != null ? req.targetTempC() : BigDecimal.valueOf(-80));
        entity.setStatus("STORED");
        return specimenRepo.save(entity);
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_ALIQUOT_CREATE", resourceType = "Biospecimen")
    public BiospecimenEntity createAliquot(CreateAliquotRequest req) {
        UUID facId = requireFacility();
        BiospecimenEntity parent = specimenRepo.findByIdAndFacilityId(req.parentSpecimenId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Parent biospecimen not found: " + req.parentSpecimenId()));
        if (!parent.isConsentActive() || !"STORED".equals(parent.getStatus())) {
            throw new IllegalStateException("Cannot aliquot specimen with status=" + parent.getStatus() + " or withdrawn consent.");
        }
        if (req.aliquotVolumeUl() == null || req.aliquotVolumeUl().compareTo(BigDecimal.ZERO) <= 0
                || req.aliquotVolumeUl().compareTo(parent.getVolumeUl()) > 0) {
            throw new IllegalArgumentException("Invalid aliquot volume: " + req.aliquotVolumeUl() + " uL (parent has " + parent.getVolumeUl() + " uL).");
        }
        parent.setVolumeUl(parent.getVolumeUl().subtract(req.aliquotVolumeUl()));
        parent.setFreezeThawCycles(parent.getFreezeThawCycles() + 1);
        specimenRepo.save(parent);

        BiospecimenEntity child = new BiospecimenEntity();
        child.setFacilityId(facId);
        child.setSpecimenBarcode(req.aliquotBarcode().trim().toUpperCase(Locale.ROOT));
        child.setDonorPseudonym(parent.getDonorPseudonym());
        child.setConsentScope(parent.getConsentScope());
        child.setConsentActive(parent.isConsentActive());
        child.setSpecimenType(parent.getSpecimenType());
        child.setVolumeUl(req.aliquotVolumeUl());
        child.setParentAliquotId(parent.getId());
        child.setFreezerId(req.freezerId().trim().toUpperCase(Locale.ROOT));
        child.setRackBoxSlot(req.rackBoxSlot().trim().toUpperCase(Locale.ROOT));
        child.setTargetTempC(parent.getTargetTempC());
        child.setFreezeThawCycles(parent.getFreezeThawCycles());
        child.setStatus("STORED");
        return specimenRepo.save(child);
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_FREEZER_EXCURSION", resourceType = "Biospecimen")
    public List<BiospecimenEntity> flagFreezerExcursion(String freezerId, BigDecimal observedTempC) {
        UUID facId = requireFacility();
        List<BiospecimenEntity> affected = specimenRepo.findByFacilityIdAndFreezerId(facId, freezerId.trim().toUpperCase(Locale.ROOT));
        List<BiospecimenEntity> quarantined = new ArrayList<>();
        for (BiospecimenEntity sp : affected) {
            if (observedTempC.compareTo(sp.getTargetTempC().add(BigDecimal.TEN)) > 0) {
                sp.setTempExcursionFlagged(true);
                sp.setStatus("QUARANTINED_EXCURSION");
                quarantined.add(specimenRepo.save(sp));
            }
        }
        return quarantined;
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_CONSENT_WITHDRAW", resourceType = "Biospecimen")
    public List<BiospecimenEntity> withdrawDonorConsent(String donorPseudonym) {
        UUID facId = requireFacility();
        List<BiospecimenEntity> specimens = specimenRepo.findByFacilityIdAndDonorPseudonym(facId, donorPseudonym.trim());
        for (BiospecimenEntity sp : specimens) {
            sp.setConsentActive(false);
            sp.setStatus("CONSENT_WITHDRAWN");
            specimenRepo.save(sp);
        }
        return specimens;
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_MTA_FULFILL", resourceType = "MtaRequest")
    public MtaRequestEntity fulfillMtaShipment(RequestMtaShipmentRequest req, String biobankDirectorId) {
        UUID facId = requireFacility();
        BiospecimenEntity sp = specimenRepo.findByIdAndFacilityId(req.specimenId(), facId)
                .orElseThrow(() -> new NoSuchElementException("Biospecimen not found: " + req.specimenId()));
        if (!sp.isConsentActive() || !"STORED".equals(sp.getStatus())) {
            throw new IllegalStateException("MTA shipment blocked: specimen consent inactive or status=" + sp.getStatus());
        }
        if (!req.ethicsVerified()) {
            throw new IllegalStateException("MTA shipment blocked: recipient ethics & MTA verification required.");
        }
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                biobankDirectorId, "BIOBANK_DIRECTOR", "MTA_SPECIMEN_RELEASE",
                "MtaRequest", req.mtaReference(),
                req.mtaReference() + "|" + sp.getSpecimenBarcode() + "|" + req.recipientInstitution(),
                null, false
        ));
        sp.setStatus("SHIPPED_MTA");
        specimenRepo.save(sp);

        MtaRequestEntity mta = new MtaRequestEntity();
        mta.setFacilityId(facId);
        mta.setMtaReference(req.mtaReference().trim().toUpperCase(Locale.ROOT));
        mta.setRecipientInstitution(req.recipientInstitution().trim());
        mta.setSpecimenId(sp.getId());
        mta.setRequestedPurpose(req.requestedPurpose().trim());
        mta.setEthicsVerified(true);
        mta.setStatus("APPROVED_SHIPPED");
        mta.setApprovedBy(proof.signerId());
        mta.setApprovalSigSha256(proof.signatureDigestSha256());
        return mtaRepo.save(mta);
    }

    @Transactional
    @ClinicalAudit(action = "BIOBANK_SPECIMEN_DISPOSE", resourceType = "Biospecimen")
    public BiospecimenEntity disposeSpecimen(UUID specimenId) {
        UUID facId = requireFacility();
        BiospecimenEntity sp = specimenRepo.findByIdAndFacilityId(specimenId, facId)
                .orElseThrow(() -> new NoSuchElementException("Biospecimen not found: " + specimenId));
        sp.setStatus("DISPOSED");
        return specimenRepo.save(sp);
    }

    @Transactional(readOnly = true)
    public List<BiospecimenEntity> listSpecimens() {
        return specimenRepo.findByFacilityIdOrderByCollectedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for Biobanking operations."));
    }

    @RestController
    @RequestMapping("/api/v1/lifesciences/biobank")
    public static class BiobankingController {
        private final BiobankingService service;

        public BiobankingController(BiobankingService service) {
            this.service = service;
        }

        @GetMapping("/specimens")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:read')")
        public List<BiospecimenEntity> list() {
            return service.listSpecimens();
        }

        @PostMapping("/specimens")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public BiospecimenEntity store(@RequestBody StoreSpecimenRequest req) {
            return service.storeSpecimen(req);
        }

        @PostMapping("/specimens/aliquot")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_lab:result:enter')")
        public BiospecimenEntity aliquot(@RequestBody CreateAliquotRequest req) {
            return service.createAliquot(req);
        }

        @PostMapping("/freezers/{freezerId}/excursion")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public List<BiospecimenEntity> excursion(@PathVariable String freezerId, @RequestParam BigDecimal observedTempC) {
            return service.flagFreezerExcursion(freezerId, observedTempC);
        }

        @PostMapping("/donors/{donorPseudonym}/withdraw-consent")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public List<BiospecimenEntity> withdraw(@PathVariable String donorPseudonym) {
            return service.withdrawDonorConsent(donorPseudonym);
        }

        @PostMapping("/mta/fulfill")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public MtaRequestEntity fulfillMta(@RequestBody RequestMtaShipmentRequest req, @RequestParam String directorId) {
            return service.fulfillMtaShipment(req, directorId);
        }

        @PostMapping("/specimens/{id}/dispose")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public BiospecimenEntity dispose(@PathVariable UUID id) {
            return service.disposeSpecimen(id);
        }
    }
}
