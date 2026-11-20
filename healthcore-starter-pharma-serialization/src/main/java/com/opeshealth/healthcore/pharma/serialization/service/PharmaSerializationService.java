/*
 * =============================================================================
 * Opes HealthCore SDK — Pharmaceutical Serialization, Cold-Chain & Distribution Service
 * =============================================================================
 */
package com.opeshealth.healthcore.pharma.serialization.service;

import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureCommand;
import com.opeshealth.healthcore.persistence.sdk.ElectronicSignatureSpi.ElectronicSignatureProof;
import com.opeshealth.healthcore.persistence.sdk.TraceableLotPrimitive;
import com.opeshealth.healthcore.pharma.serialization.domain.PharmaSerializationEntities.*;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class PharmaSerializationService {

    /**
     * Extension point allowing national serialization hubs (EU FMD, US DSCSA, GS1 EPCIS)
     * to generate or validate unit serial numbers and SSCC codes.
     */
    public interface PharmaSerializationProviderSpi {
        String generateSerial(String gtin14, String batchNumber);
    }

    public interface SerializedUnitRepository extends JpaRepository<SerializedUnitEntity, UUID> {
        List<SerializedUnitEntity> findByFacilityIdAndBatchNumber(UUID facilityId, String batchNumber);
        Optional<SerializedUnitEntity> findByFacilityIdAndSerialNumber(UUID facilityId, String serialNumber);
    }

    public interface WarehouseLotRepository extends JpaRepository<WarehouseLotEntity, UUID> {
        Optional<WarehouseLotEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        Optional<WarehouseLotEntity> findByFacilityIdAndBatchNumber(UUID facilityId, String batchNumber);
        List<WarehouseLotEntity> findByFacilityIdOrderByUpdatedAtDesc(UUID facilityId);
    }

    public interface DistributionShipmentRepository extends JpaRepository<DistributionShipmentEntity, UUID> {
        List<DistributionShipmentEntity> findByFacilityIdOrderByDispatchedAtDesc(UUID facilityId);
    }

    public record CommissionUnitRequest(
            String gtin14,
            String batchNumber,
            String serialNumber,
            LocalDate expiryDate,
            String packagingLevel,
            String commissionedBy
    ) {}

    public record ReceiveWarehouseLotRequest(
            String gtin14,
            String batchNumber,
            LocalDate expiryDate,
            String warehouseZone,
            int quantityUnits,
            BigDecimal minTempC,
            BigDecimal maxTempC
    ) {}

    public record DispatchShipmentRequest(
            String shipmentNumber,
            String ssccPalletCode,
            String batchNumber,
            String destinationPartner,
            int unitCount,
            String dispatchedBy
    ) {}

    private final SerializedUnitRepository unitRepo;
    private final WarehouseLotRepository lotRepo;
    private final DistributionShipmentRepository shipmentRepo;
    private final ElectronicSignatureSpi signatureSpi;
    private final PharmaSerializationProviderSpi serialProviderSpi;

    public PharmaSerializationService(
            SerializedUnitRepository unitRepo,
            WarehouseLotRepository lotRepo,
            DistributionShipmentRepository shipmentRepo,
            ObjectProvider<ElectronicSignatureSpi> signatureSpiProvider,
            ObjectProvider<PharmaSerializationProviderSpi> serialProvider) {
        this.unitRepo = unitRepo;
        this.lotRepo = lotRepo;
        this.shipmentRepo = shipmentRepo;
        this.signatureSpi = signatureSpiProvider != null
                ? signatureSpiProvider.getIfAvailable(() -> ElectronicSignatureSpi::defaultSign)
                : ElectronicSignatureSpi::defaultSign;
        this.serialProviderSpi = serialProvider != null
                ? serialProvider.getIfAvailable(() -> (g, b) -> "SN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT))
                : (g, b) -> "SN-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SERIAL_COMMISSION", resourceType = "SerializedUnit")
    public SerializedUnitEntity commissionUnit(CommissionUnitRequest req) {
        UUID facId = requireFacility();
        String serial = (req.serialNumber() == null || req.serialNumber().isBlank())
                ? serialProviderSpi.generateSerial(req.gtin14(), req.batchNumber())
                : req.serialNumber().trim();
        TraceableLotPrimitive primitive = new TraceableLotPrimitive(
                req.gtin14(), req.batchNumber(), req.expiryDate(), serial, false);

        SerializedUnitEntity unit = new SerializedUnitEntity();
        unit.setFacilityId(facId);
        unit.setGtin14(primitive.gtin14());
        unit.setBatchNumber(primitive.lotOrBatchNumber());
        unit.setSerialNumber(primitive.serialNumber());
        unit.setExpiryDate(primitive.expiryDate());
        unit.setPackagingLevel(req.packagingLevel() == null ? "UNIT" : req.packagingLevel().trim().toUpperCase(Locale.ROOT));
        unit.setGs1AiString(primitive.toGs1ApplicationIdentifierString());
        unit.setCommissionedBy(req.commissionedBy().trim());
        unit.setStatus("COMMISSIONED");
        return unitRepo.save(unit);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SERIAL_AGGREGATE", resourceType = "SerializedUnit")
    public List<SerializedUnitEntity> aggregateBatchToSscc(String batchNumber, String parentSscc) {
        UUID facId = requireFacility();
        if (parentSscc == null || !parentSscc.trim().matches("^\\d{18}$")) {
            throw new IllegalArgumentException("SSCC code must be an 18-digit GS1 Serial Shipping Container Code.");
        }
        List<SerializedUnitEntity> units = unitRepo.findByFacilityIdAndBatchNumber(facId, batchNumber.trim());
        for (SerializedUnitEntity u : units) {
            if ("RECALLED".equals(u.getStatus())) {
                throw new IllegalStateException("Cannot aggregate recalled serialized unit: " + u.getSerialNumber());
            }
            u.setParentSscc(parentSscc.trim());
            u.setStatus("AGGREGATED");
            unitRepo.save(u);
        }
        return units;
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_WAREHOUSE_RECEIVE", resourceType = "WarehouseLot")
    public WarehouseLotEntity receiveWarehouseLot(ReceiveWarehouseLotRequest req) {
        UUID facId = requireFacility();
        new TraceableLotPrimitive(req.gtin14(), req.batchNumber(), req.expiryDate(), null, true);
        WarehouseLotEntity lot = new WarehouseLotEntity();
        lot.setFacilityId(facId);
        lot.setGtin14(req.gtin14().trim());
        lot.setBatchNumber(req.batchNumber().trim());
        lot.setExpiryDate(req.expiryDate());
        lot.setWarehouseZone(req.warehouseZone().trim());
        lot.setQuantityUnits(req.quantityUnits());
        lot.setMinTempC(req.minTempC() != null ? req.minTempC() : BigDecimal.valueOf(2));
        lot.setMaxTempC(req.maxTempC() != null ? req.maxTempC() : BigDecimal.valueOf(25));
        lot.setLotStatus("QUARANTINE");
        return lotRepo.save(lot);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_COLDCHAIN_READ", resourceType = "WarehouseLot")
    public WarehouseLotEntity recordTemperatureReading(UUID lotId, BigDecimal tempCelsius) {
        UUID facId = requireFacility();
        WarehouseLotEntity lot = lotRepo.findByIdAndFacilityId(lotId, facId)
                .orElseThrow(() -> new NoSuchElementException("Warehouse lot not found: " + lotId));
        lot.setLastRecordedTempC(tempCelsius);
        lot.setUpdatedAt(Instant.now());
        if (tempCelsius.compareTo(lot.getMinTempC()) < 0 || tempCelsius.compareTo(lot.getMaxTempC()) > 0) {
            lot.setColdChainExcursion(true);
            lot.setLotStatus("EXCURSION_HOLD");
        }
        return lotRepo.save(lot);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_WAREHOUSE_RELEASE", resourceType = "WarehouseLot")
    public WarehouseLotEntity releaseWarehouseLot(UUID lotId, String qaApproverId) {
        UUID facId = requireFacility();
        WarehouseLotEntity lot = lotRepo.findByIdAndFacilityId(lotId, facId)
                .orElseThrow(() -> new NoSuchElementException("Warehouse lot not found: " + lotId));
        if (lot.isColdChainExcursion() || "EXCURSION_HOLD".equals(lot.getLotStatus())) {
            throw new IllegalStateException("Cannot release warehouse lot while unresolved cold-chain excursion hold is active.");
        }
        if ("RECALLED".equals(lot.getLotStatus())) {
            throw new IllegalStateException("Cannot release a recalled warehouse lot.");
        }
        ElectronicSignatureProof proof = signatureSpi.sign(new ElectronicSignatureCommand(
                qaApproverId, "QA_DISTRIBUTION_RELEASE", "WAREHOUSE_LOT_RELEASED",
                "WarehouseLot", lotId.toString(),
                lot.getGtin14() + "|" + lot.getBatchNumber() + "|" + lot.getQuantityUnits(),
                null, false
        ));
        lot.setLotStatus("RELEASED");
        lot.setQaReleaseSigSha256(proof.signatureDigestSha256());
        lot.setUpdatedAt(proof.signedAt());
        return lotRepo.save(lot);
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_BATCH_RECALL", resourceType = "WarehouseLot")
    public WarehouseLotEntity recallBatch(String batchNumber) {
        UUID facId = requireFacility();
        WarehouseLotEntity lot = lotRepo.findByFacilityIdAndBatchNumber(facId, batchNumber.trim())
                .orElseThrow(() -> new NoSuchElementException("Warehouse lot not found for batch: " + batchNumber));
        lot.setLotStatus("RECALLED");
        lot.setUpdatedAt(Instant.now());
        lotRepo.save(lot);
        for (SerializedUnitEntity u : unitRepo.findByFacilityIdAndBatchNumber(facId, batchNumber.trim())) {
            u.setStatus("RECALLED");
            unitRepo.save(u);
        }
        return lot;
    }

    @Transactional
    @ClinicalAudit(action = "PHARMA_SHIPMENT_DISPATCH", resourceType = "DistributionShipment")
    public DistributionShipmentEntity dispatchShipment(DispatchShipmentRequest req) {
        UUID facId = requireFacility();
        WarehouseLotEntity lot = lotRepo.findByFacilityIdAndBatchNumber(facId, req.batchNumber().trim())
                .orElseThrow(() -> new NoSuchElementException("Warehouse lot not found for batch: " + req.batchNumber()));
        if (!"RELEASED".equals(lot.getLotStatus())) {
            throw new IllegalStateException("Distribution dispatch blocked: batch '" + req.batchNumber()
                    + "' status is " + lot.getLotStatus() + " (must be RELEASED).");
        }
        TraceableLotPrimitive lotCheck = new TraceableLotPrimitive(
                lot.getGtin14(), lot.getBatchNumber(), lot.getExpiryDate(), null, false);
        if (!lotCheck.isEligibleForUse(LocalDate.now())) {
            throw new IllegalStateException("Distribution dispatch blocked: batch '" + req.batchNumber() + "' is expired.");
        }
        if (req.unitCount() <= 0 || req.unitCount() > lot.getQuantityUnits()) {
            throw new IllegalArgumentException("Invalid dispatch unitCount=" + req.unitCount() + " against stock=" + lot.getQuantityUnits());
        }
        lot.setQuantityUnits(lot.getQuantityUnits() - req.unitCount());
        lot.setUpdatedAt(Instant.now());
        lotRepo.save(lot);

        DistributionShipmentEntity shipment = new DistributionShipmentEntity();
        shipment.setFacilityId(facId);
        shipment.setShipmentNumber(req.shipmentNumber().trim());
        shipment.setSsccPalletCode(req.ssccPalletCode().trim());
        shipment.setBatchNumber(req.batchNumber().trim());
        shipment.setDestinationPartner(req.destinationPartner().trim());
        shipment.setUnitCount(req.unitCount());
        shipment.setColdChainVerified(!lot.isColdChainExcursion());
        shipment.setDispatchedBy(req.dispatchedBy().trim());
        shipment.setStatus("DISPATCHED");
        return shipmentRepo.save(shipment);
    }

    @Transactional(readOnly = true)
    public List<WarehouseLotEntity> listWarehouseLots() {
        return lotRepo.findByFacilityIdOrderByUpdatedAtDesc(requireFacility());
    }

    @Transactional(readOnly = true)
    public List<DistributionShipmentEntity> listShipments() {
        return shipmentRepo.findByFacilityIdOrderByDispatchedAtDesc(requireFacility());
    }

    private UUID requireFacility() {
        return FacilityContext.getFacilityId()
                .orElseThrow(() -> new AccessDeniedException("FacilityContext is required for pharmaceutical serialization operations."));
    }

    @RestController
    @RequestMapping("/api/v1/pharma/serialization")
    public static class PharmaSerializationController {
        private final PharmaSerializationService service;

        public PharmaSerializationController(PharmaSerializationService service) {
            this.service = service;
        }

        @PostMapping("/units/commission")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public SerializedUnitEntity commission(@RequestBody CommissionUnitRequest req) {
            return service.commissionUnit(req);
        }

        @PostMapping("/units/{batchNumber}/aggregate")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public List<SerializedUnitEntity> aggregate(@PathVariable String batchNumber, @RequestParam String sscc) {
            return service.aggregateBatchToSscc(batchNumber, sscc);
        }

        @GetMapping("/warehouse-lots")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:read')")
        public List<WarehouseLotEntity> listLots() {
            return service.listWarehouseLots();
        }

        @PostMapping("/warehouse-lots")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public WarehouseLotEntity receiveLot(@RequestBody ReceiveWarehouseLotRequest req) {
            return service.receiveWarehouseLot(req);
        }

        @PostMapping("/warehouse-lots/{id}/temperature")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public WarehouseLotEntity recordTemp(@PathVariable UUID id, @RequestParam BigDecimal tempC) {
            return service.recordTemperatureReading(id, tempC);
        }

        @PostMapping("/warehouse-lots/{id}/release")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public WarehouseLotEntity releaseLot(@PathVariable UUID id, @RequestParam String qaApproverId) {
            return service.releaseWarehouseLot(id, qaApproverId);
        }

        @PostMapping("/recall/{batchNumber}")
        @PreAuthorize("hasAuthority('PERM_facility:manage')")
        public WarehouseLotEntity recall(@PathVariable String batchNumber) {
            return service.recallBatch(batchNumber);
        }

        @GetMapping("/shipments")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:read')")
        public List<DistributionShipmentEntity> listShipments() {
            return service.listShipments();
        }

        @PostMapping("/shipments/dispatch")
        @PreAuthorize("hasAuthority('PERM_facility:manage') or hasAuthority('PERM_inventory:adjust')")
        public DistributionShipmentEntity dispatch(@RequestBody DispatchShipmentRequest req) {
            return service.dispatchShipment(req);
        }
    }
}
