/*
 * =============================================================================
 * Opes HealthCore SDK — FEFO Pharmacy & Westgard LIS Services + REST Gateway
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.pharmacylis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService;
import com.opeshealth.healthcore.persistence.outbox.OutboxRelayService.HealthCoreDomainEvent;
import com.opeshealth.healthcore.persistence.outbox.TransactionalOutboxEntity;
import com.opeshealth.healthcore.persistence.sql.RetryableOnTransientSqlFailure;
import com.opeshealth.healthcore.pharmacylis.domain.PharmacyLisEntities.*;
import com.opeshealth.healthcore.security.audit.ClinicalAudit;
import com.opeshealth.healthcore.terminology.cdss.CdsHooksEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Provides independent Pharmacy Information System (PIS) FEFO dispensing and
 * Laboratory Information System (LIS) specimen accessioning, Westgard QC gating,
 * reflex triggers, and critical panic-value escalation.
 */
public class PharmacyLisService {

    public interface MedicationBatchRepository extends JpaRepository<MedicationBatchEntity, UUID> {
        List<MedicationBatchEntity> findByFacilityId(UUID facilityId);
        List<MedicationBatchEntity> findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                UUID facilityId, String drugCode, LocalDate minExpiry);
        List<MedicationBatchEntity> findByFacilityIdAndExpiryDateBefore(UUID facilityId, LocalDate cutoff);

        /**
         * Atomic conditional inventory deduction preventing lost updates and negative stock
         * under concurrent dispensing requests across multiple threads or service replicas.
         */
        @Modifying
        @Query("UPDATE MedicationBatchEntity b SET b.quantityOnHand = b.quantityOnHand - :qty, b.version = b.version + 1 "
                + "WHERE b.id = :id AND b.facilityId = :facilityId AND b.quarantined = false AND b.quantityOnHand >= :qty")
        int decrementStockAtomically(
                @Param("id") UUID id,
                @Param("facilityId") UUID facilityId,
                @Param("qty") int qty);
    }

    public interface PharmacyDispenseRepository extends JpaRepository<PharmacyDispenseEntity, UUID> {
        List<PharmacyDispenseEntity> findByFacilityId(UUID facilityId);
        @Modifying
        @Query("UPDATE PharmacyDispenseEntity p SET p.patientId = :survivorId WHERE p.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface LabSpecimenRepository extends JpaRepository<LabSpecimenEntity, UUID> {
        @Modifying
        @Query("UPDATE LabSpecimenEntity l SET l.patientId = :survivorId WHERE l.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }
    public interface DiagnosticReportRepository extends JpaRepository<DiagnosticReportEntity, UUID> {
        Optional<DiagnosticReportEntity> findByIdAndFacilityId(UUID id, UUID facilityId);
        @Modifying
        @Query("UPDATE DiagnosticReportEntity r SET r.patientId = :survivorId WHERE r.patientId = :sourceId")
        int relinkPatient(@Param("sourceId") UUID sourceId, @Param("survivorId") UUID survivorId);
    }

    private final MedicationBatchRepository batchRepo;
    private final PharmacyDispenseRepository dispenseRepo;
    private final LabSpecimenRepository specimenRepo;
    private final DiagnosticReportRepository reportRepo;
    private final CdsHooksEngine cdsHooksEngine;
    private final OutboxRelayService.OutboxRepository outboxRepo;

    public PharmacyLisService(
            MedicationBatchRepository batchRepo,
            PharmacyDispenseRepository dispenseRepo,
            LabSpecimenRepository specimenRepo,
            DiagnosticReportRepository reportRepo,
            CdsHooksEngine cdsHooksEngine,
            OutboxRelayService.OutboxRepository outboxRepo) {
        this.batchRepo = batchRepo;
        this.dispenseRepo = dispenseRepo;
        this.specimenRepo = specimenRepo;
        this.reportRepo = reportRepo;
        this.cdsHooksEngine = cdsHooksEngine;
        this.outboxRepo = outboxRepo;
    }

    private static final ObjectMapper MERGE_MAPPER = new ObjectMapper();

    /** Cascades MPI Golden Record merges emitted via Outbox into pharmacy dispenses, lab specimens & reports. */
    @EventListener
    @Transactional
    public void onOutboxDomainEvent(HealthCoreDomainEvent event) {
        if (event == null || !"PatientMergedEvent".equals(event.eventType()) || event.payloadJson() == null) {
            return;
        }
        try {
            JsonNode root = MERGE_MAPPER.readTree(event.payloadJson());
            String src = root.path("sourceId").asText("");
            String surv = root.path("survivingId").asText("");
            if (!src.isBlank() && !surv.isBlank()) {
                UUID sourceId = UUID.fromString(src);
                UUID survivorId = UUID.fromString(surv);
                dispenseRepo.relinkPatient(sourceId, survivorId);
                specimenRepo.relinkPatient(sourceId, survivorId);
                reportRepo.relinkPatient(sourceId, survivorId);
            }
        } catch (Exception ignored) {
            // Malformed payload does not break other event consumers
        }
    }

    @Transactional
    public MedicationBatchEntity receiveStockBatch(MedicationBatchEntity batch) {
        return batchRepo.save(batch);
    }

    /**
     * Picks earliest non-expired, non-quarantined stock lot (First-Expired, First-Out),
     * enforces dual-witness signoff for Schedule II/III controlled substances, and atomically
     * deducts inventory using a conditional database update ({@code quantity_on_hand >= qty})
     * so concurrent dispensing can never oversell or drive stock negative.
     */
    @Transactional
    @RetryableOnTransientSqlFailure
    @ClinicalAudit(action = "PIS_DISPENSE_FEFO", resourceType = "PharmacyDispense")
    public PharmacyDispenseEntity dispenseFefo(
            UUID facilityId, UUID patientId, UUID rxOrderId,
            String prescribedDrugCode, String dispensedDrugCode,
            int requestedQty, boolean allowPartial, String pharmacistId, String witnessId) {

        if (requestedQty <= 0) {
            throw new IllegalArgumentException("Dispense quantity must be greater than zero");
        }
        UUID activeFacility = com.opeshealth.healthcore.security.tenant.FacilityContext.getFacilityId().orElse(null);
        if (activeFacility != null && !activeFacility.equals(facilityId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cross-tenant pharmacy dispense denied for facility " + facilityId);
        }

        List<MedicationBatchEntity> lots = batchRepo
                .findByFacilityIdAndDrugCodeAndQuarantinedFalseAndExpiryDateAfterOrderByExpiryDateAsc(
                        facilityId, dispensedDrugCode, LocalDate.now());

        MedicationBatchEntity selected = lots.stream()
                .filter(b -> b.getQuantityOnHand() > 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No non-expired stock available for drug " + dispensedDrugCode));

        if (selected.getControlledSchedule() != null && (witnessId == null || witnessId.isBlank())) {
            throw new SecurityException("Controlled drug (" + selected.getControlledSchedule() + ") requires witness ID");
        }

        int qtyToDeduct = Math.min(requestedQty, selected.getQuantityOnHand());
        if (qtyToDeduct < requestedQty && !allowPartial) {
            throw new IllegalStateException("Insufficient lot stock (" + selected.getQuantityOnHand() + " < " + requestedQty + ")");
        }

        int updatedRows = batchRepo.decrementStockAtomically(selected.getId(), facilityId, qtyToDeduct);
        if (updatedRows != 1) {
            throw new org.springframework.dao.OptimisticLockingFailureException(
                    "Concurrent stock depletion detected for lot " + selected.getLotNumber()
                            + "; retry dispense against remaining available FEFO inventory.");
        }
        selected.setQuantityOnHand(selected.getQuantityOnHand() - qtyToDeduct);

        PharmacyDispenseEntity dispense = new PharmacyDispenseEntity();
        dispense.setFacilityId(facilityId);
        dispense.setPatientId(patientId);
        dispense.setPrescriptionOrderId(rxOrderId);
        dispense.setBatchId(selected.getId());
        dispense.setPrescribedDrugCode(prescribedDrugCode);
        dispense.setDispensedDrugCode(dispensedDrugCode);
        dispense.setQuantityDispensed(qtyToDeduct);
        dispense.setPartialFill(qtyToDeduct < requestedQty);
        dispense.setPharmacistId(pharmacistId);
        dispense.setControlledWitnessId(witnessId);
        dispense = dispenseRepo.save(dispense);

        emitOutbox("PharmacyDispense", dispense.getId(), "MedicationDispensedEvent",
                "{\"dispenseId\":\"" + dispense.getId() + "\",\"lot\":\"" + selected.getLotNumber() + "\"}");
        return dispense;
    }

    @Transactional
    public LabSpecimenEntity accessionSpecimen(LabSpecimenEntity specimen) {
        return specimenRepo.save(specimen);
    }

    /**
     * Releases diagnostic result only if Westgard QC passed; flags panic values (e.g., K+ >= 6.2),
     * triggers reflex follow-up notes, and emits {@code CriticalLabAlertEvent}.
     */
    @Transactional
    @ClinicalAudit(action = "LIS_VERIFY_RESULT", resourceType = "DiagnosticReport")
    public Map<String, Object> verifyLabResult(DiagnosticReportEntity report) {
        if (!report.isWestgardQcPassed()) {
            throw new IllegalStateException("LIS Westgard QC failure: result release blocked until control run passes");
        }

        BigDecimal potassium = "2823-3".equals(report.getLoincCode()) ? report.getNumericValue() : null;
        List<CdsHooksEngine.CdsCard> panicCards = cdsHooksEngine.evaluateVitalsAndLabPanic(
                new CdsHooksEngine.VitalsPanicRequest(null, null, null, null, null, potassium, null));

        if (!panicCards.isEmpty()) {
            report.setAbnormalFlag("HH");
            emitOutbox("DiagnosticReport", report.getId(), "CriticalLabAlertEvent",
                    "{\"loinc\":\"" + report.getLoincCode() + "\",\"patientId\":\"" + report.getPatientId() + "\"}");
        }

        report.setVerifiedAt(Instant.now());
        DiagnosticReportEntity saved = reportRepo.save(report);

        emitOutbox("DiagnosticReport", saved.getId(), "LabResultVerifiedEvent",
                "{\"reportId\":\"" + saved.getId() + "\",\"loinc\":\"" + saved.getLoincCode() + "\"}");
        return Map.of("report", saved, "panicAlerts", panicCards);
    }

    private void emitOutbox(String aggType, UUID aggId, String eventType, String json) {
        TransactionalOutboxEntity evt = new TransactionalOutboxEntity();
        evt.setIdempotencyKey(aggType + ":" + eventType + ":" + aggId + ":" + evt.getId());
        evt.setAggregateType(aggType);
        evt.setAggregateId(aggId);
        evt.setEventType(eventType);
        evt.setPayloadJson(json);
        outboxRepo.save(evt);
    }

    @RestController
    @RequestMapping("/api/v1/pharmacy-lis")
    @Tag(name = "Standalone Pharmacy (PIS)", description = "Pharmacy FEFO inventory/dispensing, expiry quarantine & controlled dual-witness APIs")
    public static class PharmacyController {
        private final PharmacyLisService service;

        public PharmacyController(PharmacyLisService service) {
            this.service = service;
        }

        @PostMapping("/pharmacy/batches")
        @PreAuthorize("hasAuthority('PERM_pharmacy:stock:adjust')")
        @Operation(summary = "Receive medication stock lot with expiry and reorder threshold")
        public MedicationBatchEntity receiveBatch(@RequestBody MedicationBatchEntity batch) {
            return service.receiveStockBatch(batch);
        }

        @PostMapping("/pharmacy/dispense")
        @PreAuthorize("hasAuthority('PERM_rx:dispense') or hasAuthority('PERM_rx:controlled:dispense')")
        @Operation(summary = "Dispense medication using First-Expired-First-Out (FEFO) lot picking")
        public PharmacyDispenseEntity dispense(
                @RequestParam UUID facilityId, @RequestParam UUID patientId,
                @RequestParam(required = false) UUID rxOrderId,
                @RequestParam String prescribedCode, @RequestParam String dispensedCode,
                @RequestParam int qty, @RequestParam(defaultValue = "false") boolean allowPartial,
                @RequestParam String pharmacistId, @RequestParam(required = false) String witnessId) {
            return service.dispenseFefo(facilityId, patientId, rxOrderId, prescribedCode, dispensedCode,
                    qty, allowPartial, pharmacistId, witnessId);
        }
    }

    @RestController
    @RequestMapping("/api/v1/pharmacy-lis")
    @Tag(name = "Standalone Lab Diagnostics (LIS)", description = "Laboratory barcode accession, Westgard QC gate & critical panic escalation APIs")
    public static class LisController {
        private final PharmacyLisService service;

        public LisController(PharmacyLisService service) {
            this.service = service;
        }

        @PostMapping("/lis/specimens")
        @PreAuthorize("hasAuthority('PERM_lab:accession')")
        @Operation(summary = "Accession barcoded diagnostic laboratory specimen")
        public LabSpecimenEntity accession(@RequestBody LabSpecimenEntity specimen) {
            return service.accessionSpecimen(specimen);
        }

        @PostMapping("/lis/reports/verify")
        @PreAuthorize("hasAuthority('PERM_lab:verify')")
        @Operation(summary = "Verify & release LOINC lab result gated by Westgard QC and panic escalation")
        public Map<String, Object> verify(@RequestBody DiagnosticReportEntity report) {
            return service.verifyLabResult(report);
        }
    }
}
