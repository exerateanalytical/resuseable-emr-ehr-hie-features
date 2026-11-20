/*
 * =============================================================================
 * Opes HealthCore SDK — Extended Consoles, Liquibase Master & DR Verification Test
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.opeshealth.healthcore.ai.governance.ClinicalAiGovernanceService;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.emergency.service.EmergencyNursingService;
import com.opeshealth.healthcore.governance.domain.TenantGovernanceEntities.TenantFacilityEntity;
import com.opeshealth.healthcore.governance.service.TenantGovernanceService;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.mpi.service.MpiCommandService.PatientRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.BloodUnitRepository;
import com.opeshealth.healthcore.ops.service.HospitalOpsService.SurgicalScheduleRepository;
import com.opeshealth.healthcore.orders.service.UnifiedOrdersResultsService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService;
import com.opeshealth.healthcore.pharmacylis.service.PharmacyLisService.LabSpecimenRepository;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.quickstart.seed.DemoHospitalDataSeeder;
import com.opeshealth.healthcore.quickstart.ui.ExtendedHospitalWorkstationController;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.starters.BloodBankStarter;
import com.opeshealth.healthcore.starters.SurgeryOtStarter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies all 9 extended Thymeleaf workstation console routes, Liquibase master changelog
 * completeness across all 38+ starter migrations, and presence of container/DR ops artifacts.
 */
class ExtendedWorkstationAndDrVerificationTest {

    @Test
    void allNineExtendedWorkstationRoutesRenderExpectedTemplatesAndAttributes() {
        UUID facId = UUID.randomUUID();
        TenantFacilityEntity fac = new TenantFacilityEntity();
        fac.setFacilityCode("DLA-REF-001");
        fac.setName("Douala Reference Hospital");

        TenantGovernanceService gov = mock(TenantGovernanceService.class);
        TenantGovernanceService.FacilityRepository facRepo = mock(TenantGovernanceService.FacilityRepository.class);
        PatientRepository patientRepo = mock(PatientRepository.class);
        RadiologyRisService radService = mock(RadiologyRisService.class);
        EmergencyNursingService edService = mock(EmergencyNursingService.class);
        PharmacyLisService pharmService = mock(PharmacyLisService.class);
        LabSpecimenRepository specRepo = mock(LabSpecimenRepository.class);
        UnifiedOrdersResultsService ordersService = mock(UnifiedOrdersResultsService.class);
        BloodBankStarter.BloodBankHemovigilanceService bloodService = mock(BloodBankStarter.BloodBankHemovigilanceService.class);
        BloodUnitRepository bloodUnitRepo = mock(BloodUnitRepository.class);
        SurgeryOtStarter.SurgicalPerioperativeService surgeryService = mock(SurgeryOtStarter.SurgicalPerioperativeService.class);
        SurgicalScheduleRepository surgRepo = mock(SurgicalScheduleRepository.class);
        PatientPortalService portalService = mock(PatientPortalService.class);
        IntegrationOpsService intOpsService = mock(IntegrationOpsService.class);
        RevenueCodingClaimsService revService = mock(RevenueCodingClaimsService.class);
        ClinicalAiGovernanceService aiService = mock(ClinicalAiGovernanceService.class);
        HospitalAnalyticsService analyticsService = mock(HospitalAnalyticsService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DemoHospitalDataSeeder> seederProvider = mock(ObjectProvider.class);

        when(seederProvider.getIfAvailable()).thenReturn(null);
        when(facRepo.findAll()).thenReturn(List.of(fac));
        when(gov.findFacility(any())).thenReturn(Optional.of(fac));
        when(patientRepo.findByFacilityIdAndActiveTrueOrderByUpdatedAtDesc(any())).thenReturn(List.of());
        when(radService.listWorklist(any())).thenReturn(List.of());
        when(radService.listUnacknowledgedCriticalFindings()).thenReturn(List.of());
        when(edService.getActiveEdBoard()).thenReturn(List.of());
        when(edService.listActiveEscalations()).thenReturn(List.of());
        when(specRepo.findAll()).thenReturn(List.of());
        when(ordersService.listUnacknowledgedCriticalResults(any())).thenReturn(List.of());
        when(bloodUnitRepo.findAll()).thenReturn(List.of());
        when(surgRepo.findAll()).thenReturn(List.of());
        when(intOpsService.listPartners()).thenReturn(List.of());
        when(intOpsService.getPartnerHealthSummaries()).thenReturn(List.of());
        when(intOpsService.listMessages(any())).thenReturn(List.of());
        when(aiService.isTenantAiEnabled(any())).thenReturn(false);
        when(aiService.listDrafts(any(), any())).thenReturn(List.of());
        when(analyticsService.listQualityMeasures()).thenReturn(List.of());
        when(analyticsService.listCohortExports()).thenReturn(List.of());
        when(revService.listTariffs()).thenReturn(List.of());
        when(revService.listCodingRecords()).thenReturn(List.of());
        when(revService.listAuthorizations()).thenReturn(List.of());
        when(revService.listClaims()).thenReturn(List.of());

        ExtendedHospitalWorkstationController controller = new ExtendedHospitalWorkstationController(
                gov, facRepo, patientRepo, radService, edService, pharmService, specRepo,
                ordersService, bloodService, bloodUnitRepo, surgeryService, surgRepo,
                portalService, intOpsService, revService, aiService, analyticsService, seederProvider);

        assertEquals("workstation/radiology", controller.radiology(null, new ConcurrentModel()));
        assertEquals("workstation/emergency-nursing", controller.emergencyNursing(new ConcurrentModel()));
        assertEquals("workstation/laboratory", controller.laboratory(new ConcurrentModel()));
        assertEquals("workstation/blood-ot", controller.bloodAndOt(new ConcurrentModel()));
        assertEquals("workstation/portal", controller.patientPortal(null, new ConcurrentModel()));
        assertEquals("workstation/integration-ops", controller.integrationOps(null, new ConcurrentModel()));
        assertEquals("workstation/clinical-ai", controller.clinicalAi(new ConcurrentModel()));
        assertEquals("workstation/analytics", controller.analytics(new ConcurrentModel()));
        assertEquals("workstation/revenue-claims", controller.revenueClaims(new ConcurrentModel()));

        Model navModel = new ConcurrentModel();
        controller.revenueClaims(navModel);
        assertEquals("revenue-claims", navModel.getAttribute("activeNav"));
        assertNotNull(facId);
    }

    @Test
    void liquibaseMasterChangelogIncludesAll38StarterChangelogsAndValidXml() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/changelog/db.changelog-master.xml")) {
            assertNotNull(in, "db.changelog-master.xml must be on classpath");
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            NodeList includes = doc.getElementsByTagName("include");
            assertTrue(includes.getLength() >= 38,
                    "Expected at least 38 Liquibase changelog includes, found " + includes.getLength());

            boolean hasIntegrationOps = false;
            boolean hasRevenueClaims = false;
            for (int i = 0; i < includes.getLength(); i++) {
                Element el = (Element) includes.item(i);
                String file = el.getAttribute("file");
                assertNotNull(file);
                if (file.contains("001-integration-ops.xml")) hasIntegrationOps = true;
                if (file.contains("001-revenue-coding-claims.xml")) hasRevenueClaims = true;
            }
            assertTrue(hasIntegrationOps, "Master changelog must include integration-ops migration");
            assertTrue(hasRevenueClaims, "Master changelog must include revenue-coding-claims migration");
        }
    }

    @Test
    void containerAndDisasterRecoveryArtifactsExistWhenRunningFromRepositoryRoot() {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        if (Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
            assertTrue(Files.isRegularFile(repoRoot.resolve("Dockerfile")), "Root Dockerfile must exist");
            assertTrue(Files.isRegularFile(repoRoot.resolve("scripts/backup-postgres.sh")), "PostgreSQL backup script must exist");
            assertTrue(Files.isRegularFile(repoRoot.resolve("scripts/restore-drill-verify.sh")), "DR restore drill script must exist");
            assertTrue(Files.isRegularFile(repoRoot.resolve("scripts/ci-verify-docs-and-sign.sh")), "Release signing script must exist");
        }
    }
}
