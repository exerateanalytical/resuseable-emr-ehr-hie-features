package com.opeshealth.healthcore.starters;

import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.BiomedMaintenanceEntity;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import com.opeshealth.healthcore.ops.service.HospitalOpsService;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BiomedMaintenanceRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.ColdChainRepository;
import com.opeshealth.healthcore.starters.BiomedicalColdChainStarter.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BiomedicalColdChainStarterTest {

    @Test
    void detectsThermalExcursionAndOverdueBiomedicalCalibration() {
        UUID facId = UUID.randomUUID();
        ColdChainRepository coldRepo = mock(ColdChainRepository.class);
        BiomedMaintenanceRepository biomedRepo = mock(BiomedMaintenanceRepository.class);

        ColdChainTelemetryEntity reading = new ColdChainTelemetryEntity();
        reading.setSensorId("FRIDGE-EPI-01");
        reading.setTemperatureCelsius(new BigDecimal("11.4"));
        reading.setExcursionAlert(true);
        when(coldRepo.findAll()).thenReturn(List.of(reading));

        BiomedMaintenanceEntity asset = new BiomedMaintenanceEntity();
        asset.setFacilityId(facId);
        asset.setAssetTag("VENT-ICU-01");
        asset.setNextCalibrationDue(LocalDate.now().minusDays(5));
        when(biomedRepo.findAll()).thenReturn(List.of(asset));

        BiomedicalColdChainGovernanceService service = new BiomedicalColdChainGovernanceService(
                mock(HospitalOpsService.class), coldRepo, biomedRepo);

        ColdChainStabilityReport report = service.analyzeSensorThermalStability("FRIDGE-EPI-01");
        assertTrue(report.excursionCount() >= 1);

        BiomedicalComplianceSummary audit = service.auditFacilityBiomedicalCompliance(facId);
        assertEquals(1, audit.overdueCalibrationAssets());
        assertTrue(audit.overdueAssetTags().contains("VENT-ICU-01"));
    }
}
