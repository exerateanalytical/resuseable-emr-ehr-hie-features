package com.opeshealth.healthcore.pharma.serialization;

import com.opeshealth.healthcore.pharma.serialization.domain.PharmaSerializationEntities.*;
import com.opeshealth.healthcore.pharma.serialization.service.PharmaSerializationService;
import com.opeshealth.healthcore.pharma.serialization.service.PharmaSerializationService.*;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PharmaSerializationServiceTest {

    @AfterEach
    void clearTenant() {
        FacilityContext.clear();
    }

    @Test
    void verifiesGs1CommissioningColdChainExcursionBlockQaReleaseAndRecallStop() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);

        SerializedUnitRepository unitRepo = mock(SerializedUnitRepository.class);
        WarehouseLotRepository lotRepo = mock(WarehouseLotRepository.class);
        DistributionShipmentRepository shipRepo = mock(DistributionShipmentRepository.class);
        when(unitRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lotRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(shipRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        PharmaSerializationService service = new PharmaSerializationService(unitRepo, lotRepo, shipRepo, null, null);

        SerializedUnitEntity unit = service.commissionUnit(new CommissionUnitRequest(
                "03400930000012", "LOT-2026-A", "SER0001", LocalDate.now().plusYears(2), "UNIT", "line.op1"));
        assertTrue(unit.getGs1AiString().contains("(01)03400930000012"));
        assertTrue(unit.getGs1AiString().contains("(21)SER0001"));

        when(unitRepo.findByFacilityIdAndBatchNumber(facId, "LOT-2026-A")).thenReturn(List.of(unit));
        service.aggregateBatchToSscc("LOT-2026-A", "001234567890123456");
        assertEquals("AGGREGATED", unit.getStatus());

        WarehouseLotEntity excursionLot = service.receiveWarehouseLot(new ReceiveWarehouseLotRequest(
                "03400930000012", "LOT-COLD-BAD", LocalDate.now().plusYears(1), "COLD-ROOM-1",
                500, BigDecimal.valueOf(2), BigDecimal.valueOf(8)));
        when(lotRepo.findByIdAndFacilityId(excursionLot.getId(), facId)).thenReturn(Optional.of(excursionLot));
        service.recordTemperatureReading(excursionLot.getId(), BigDecimal.valueOf(14.5));
        assertEquals("EXCURSION_HOLD", excursionLot.getLotStatus());
        assertThrows(IllegalStateException.class, () -> service.releaseWarehouseLot(excursionLot.getId(), "qa.lead"));

        WarehouseLotEntity goodLot = service.receiveWarehouseLot(new ReceiveWarehouseLotRequest(
                "03400930000012", "LOT-2026-A", LocalDate.now().plusYears(2), "AMBIENT-A",
                1000, BigDecimal.valueOf(15), BigDecimal.valueOf(25)));
        when(lotRepo.findByIdAndFacilityId(goodLot.getId(), facId)).thenReturn(Optional.of(goodLot));
        when(lotRepo.findByFacilityIdAndBatchNumber(facId, "LOT-2026-A")).thenReturn(Optional.of(goodLot));

        // Blocked while still in QUARANTINE
        assertThrows(IllegalStateException.class, () -> service.dispatchShipment(new DispatchShipmentRequest(
                "SHP-01", "001234567890123456", "LOT-2026-A", "Regional Depot", 200, "wh.mgr")));

        service.releaseWarehouseLot(goodLot.getId(), "qa.lead");
        assertEquals("RELEASED", goodLot.getLotStatus());

        DistributionShipmentEntity shp = service.dispatchShipment(new DispatchShipmentRequest(
                "SHP-01", "001234567890123456", "LOT-2026-A", "Regional Depot", 200, "wh.mgr"));
        assertEquals("DISPATCHED", shp.getStatus());
        assertEquals(800, goodLot.getQuantityUnits());

        // Recall stops further shipping and marks serialized units recalled
        service.recallBatch("LOT-2026-A");
        assertEquals("RECALLED", goodLot.getLotStatus());
        assertEquals("RECALLED", unit.getStatus());
        assertThrows(IllegalStateException.class, () -> service.dispatchShipment(new DispatchShipmentRequest(
                "SHP-02", "001234567890123456", "LOT-2026-A", "Regional Depot", 50, "wh.mgr")));
    }
}
