package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BiomedMaintenanceEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BiomedMaintenanceRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.ColdChainRepository;
import com.opeshealth.healthcore.starters.BiomedicalColdChainStarter.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.opeshealth.healthcore.security.tenant.FacilityContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiomedicalColdChainStarterTest {

    @AfterEach
    void clearFacility() { FacilityContext.clear(); }

    @Test
    void detectsThermalExcursionAndOverdueBiomedicalCalibration() {
        UUID facId = UUID.randomUUID();
        FacilityContext.setFacilityId(facId);
        ColdChainRepository coldRepo = mock(ColdChainRepository.class);
        BiomedMaintenanceRepository biomedRepo = mock(BiomedMaintenanceRepository.class);

        ColdChainTelemetryEntity reading = new ColdChainTelemetryEntity();
        reading.setFacilityId(facId);
        reading.setEquipmentTag("FRIDGE-EPI-01");
        reading.setTempCelsius(new BigDecimal("11.4"));
        reading.setExcursionAlert(true);
        when(coldRepo.findByFacilityIdAndEquipmentTagOrderByRecordedAtAsc(facId, "FRIDGE-EPI-01"))
                .thenReturn(List.of(reading));

        BiomedMaintenanceEntity asset = new BiomedMaintenanceEntity();
        asset.setFacilityId(facId);
        asset.setEquipmentTag("VENT-ICU-01");
        asset.setEquipmentName("ICU ventilator");
        asset.setCalibrationDueOn(LocalDate.now().minusDays(5));
        when(biomedRepo.findByFacilityId(facId)).thenReturn(List.of(asset));

        BiomedicalColdChainGovernanceService service = new BiomedicalColdChainGovernanceService(
                mock(HospitalOpsService.class), coldRepo, biomedRepo);

        ColdChainStabilityReport report = service.analyzeSensorThermalStability("FRIDGE-EPI-01");
        assertTrue(report.heatExcursionReadingsAbove8C() >= 1);

        BiomedicalComplianceSummary audit = service.auditFacilityBiomedicalCompliance(facId);
        assertEquals(1, audit.overdueCalibrationAssets());
        assertTrue(audit.overdueAssetTags().contains("VENT-ICU-01"));
    }
}
