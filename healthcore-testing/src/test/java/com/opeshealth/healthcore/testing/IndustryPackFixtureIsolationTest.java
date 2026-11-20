/*
 * =============================================================================
 * Opes HealthCore SDK — 8 Industry Capability Packs Isolated Fixture Verification
 * =============================================================================
 */
package com.opeshealth.healthcore.testing;

import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.ClinicalQualityMeasureEntity;
import com.opeshealth.healthcore.analytics.domain.AnalyticsEntities.CohortExportRequestEntity;
import com.opeshealth.healthcore.analytics.service.HospitalAnalyticsService;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.IntegrationMessageLedgerEntity;
import com.opeshealth.healthcore.integrationops.domain.IntegrationOpsEntities.PartnerConformanceProfileEntity;
import com.opeshealth.healthcore.integrationops.service.IntegrationOpsService;
import com.opeshealth.healthcore.persistence.sdk.CapabilityPackManifestContract;
import com.opeshealth.healthcore.persistence.sdk.CapabilityPackManifestContract.PackCompatibilityReport;
import com.opeshealth.healthcore.persistence.sdk.CapabilityPackManifestContract.PackManifestMetadata;
import com.opeshealth.healthcore.portal.service.PatientPortalService;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.ModalityWorklistEntity;
import com.opeshealth.healthcore.radiology.domain.RadiologyRisEntities.RadiologyReportEntity;
import com.opeshealth.healthcore.radiology.service.RadiologyRisService;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.ClinicalCodingRecordEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.FacilityTariffEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.InsuranceAuthorizationEntity;
import com.opeshealth.healthcore.revenueclaims.domain.RevenueCodingClaimsEntities.PayerClaimEntity;
import com.opeshealth.healthcore.revenueclaims.service.RevenueCodingClaimsService;
import com.opeshealth.healthcore.security.tenant.FacilityContext;
import com.opeshealth.healthcore.testing.HealthCoreDataFixture.ConsoleSeedSummary;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that each of the 8 OPES HealthCore Industry Capability Packs can be initialized
 * in complete isolation with {@link HealthCoreDataFixture} without pulling forbidden
 * cross-industry modules into its transitive Maven dependency graph.
 *
 * <p>Supports filtering to a single pack in CI matrix jobs via
 * {@code -Dhealthcore.pack=healthcore-pack-<name>}.</p>
 */
class IndustryPackFixtureIsolationTest {

    private static final List<String> ALL_EIGHT_PACKS = List.of(
            "healthcore-pack-pharmacy",
            "healthcore-pack-diagnostics",
            "healthcore-pack-pharma-manufacturing",
            "healthcore-pack-life-sciences",
            "healthcore-pack-emr",
            "healthcore-pack-ehr",
            "healthcore-pack-his",
            "healthcore-pack-hie"
    );

    @Test
    void verifiesSelectedOrAllIndustryPacksInitializeInIsolationWithHealthCoreDataFixture() throws Exception {
        String requestedPack = System.getProperty("healthcore.pack", "").trim();
        List<String> targetPacks = requestedPack.isEmpty() ? ALL_EIGHT_PACKS : List.of(requestedPack);

        for (String pack : targetPacks) {
            assertTrue(ALL_EIGHT_PACKS.contains(pack), "Unknown industry pack: " + pack);
            verifyPackTransitiveIsolation(pack);
            verifyPackFixtureInitialization(pack);
            assertTrue(FacilityContext.getFacilityId().isEmpty(),
                    "FacilityContext must be clean after isolated initialization of " + pack);
        }
    }

    private void verifyPackTransitiveIsolation(String pack) throws Exception {
        Path repoRoot = resolveRepoRoot();
        Map<String, Set<String>> directGraph = new HashMap<>();

        for (String p : ALL_EIGHT_PACKS) {
            Path packPom = repoRoot.resolve("packs").resolve(p).resolve("pom.xml");
            assertTrue(Files.exists(packPom), "Missing pack POM: " + packPom);
            directGraph.put(p, parseRequiredHealthCoreDependencies(packPom));
        }

        try (var stream = Files.list(repoRoot)) {
            for (Path dir : stream.toList()) {
                Path pom = dir.resolve("pom.xml");
                if (Files.isDirectory(dir) && Files.exists(pom) && dir.getFileName().toString().startsWith("healthcore-")) {
                    directGraph.put(dir.getFileName().toString(), parseRequiredHealthCoreDependencies(pom));
                }
            }
        }

        // Confirm healthcore-testing never injects non-optional domain starters when added in test scope
        Set<String> testingNonOptional = directGraph.getOrDefault("healthcore-testing", Set.of());
        for (String dep : testingNonOptional) {
            assertFalse(dep.startsWith("healthcore-starter-"),
                    "healthcore-testing must declare domain starter as <optional>true</optional>: " + dep);
        }

        Path manifestPath = repoRoot.resolve("packs").resolve(pack)
                .resolve("src/main/resources/META-INF/healthcore/pack-manifest.json");
        assertTrue(Files.exists(manifestPath), "Missing pack-manifest.json for " + pack);
        String json = Files.readString(manifestPath);

        List<String> required = extractJsonStringArray(json, "requiredModules");
        Set<String> forbidden = new HashSet<>(extractJsonStringArray(json, "forbiddenTransitiveModules"));

        // Include healthcore-testing non-optional deps to prove combining pack + healthcore-testing stays isolated
        Set<String> combinedRoots = new HashSet<>(directGraph.getOrDefault(pack, Set.of()));
        combinedRoots.addAll(testingNonOptional);
        Set<String> closure = resolveTransitive(combinedRoots, directGraph);

        PackManifestMetadata metadata = new PackManifestMetadata(pack, "1.0.0", pack, required, List.of(), forbidden);
        PackCompatibilityReport report = CapabilityPackManifestContract.verifyIsolation(metadata, closure);
        assertTrue(report.compatible(),
                "Isolated pack + healthcore-testing violation for " + pack + ": " + report.violations());
    }

    private void verifyPackFixtureInitialization(String pack) {
        UUID facilityId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();

        switch (pack) {
            case "healthcore-pack-pharmacy", "healthcore-pack-pharma-manufacturing", "healthcore-pack-emr" -> {
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder().build();
                ConsoleSeedSummary base = fixture.initializeIsolatedPackContext(pack, facilityId);
                assertEquals(pack, base.consoleKey());
                assertEquals(1, base.operationsExecuted());
                assertThrows(NullPointerException.class,
                        () -> fixture.seedBloodBankAndOtConsole(facilityId, patientId),
                        "Unwired HIS consoles must fail fast inside " + pack);
            }
            case "healthcore-pack-diagnostics" -> {
                RadiologyRisService rad = mock(RadiologyRisService.class);
                when(rad.scheduleImagingExam(any())).thenAnswer(i -> {
                    ModalityWorklistEntity w = mock(ModalityWorklistEntity.class);
                    when(w.getId()).thenReturn(UUID.randomUUID());
                    return w;
                });
                when(rad.signReport(any())).thenAnswer(i -> {
                    RadiologyReportEntity r = mock(RadiologyReportEntity.class);
                    when(r.getId()).thenReturn(UUID.randomUUID());
                    return r;
                });
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                        .radiologyService(rad)
                        .build();
                assertEquals(1, fixture.initializeIsolatedPackContext(pack, facilityId).operationsExecuted());
                ConsoleSeedSummary radSummary = fixture.seedRadiologyConsole(facilityId, patientId, encounterId);
                assertEquals("radiology", radSummary.consoleKey());
                assertEquals(2, radSummary.operationsExecuted());
                assertThrows(NullPointerException.class,
                        () -> fixture.seedRevenueCodingClaimsStarter(facilityId, patientId, encounterId));
            }
            case "healthcore-pack-life-sciences" -> {
                HospitalAnalyticsService analytics = mock(HospitalAnalyticsService.class);
                when(analytics.recordQualityMeasure(any())).thenAnswer(i -> {
                    ClinicalQualityMeasureEntity m = mock(ClinicalQualityMeasureEntity.class);
                    when(m.getId()).thenReturn(UUID.randomUUID());
                    return m;
                });
                when(analytics.requestCohortExport(any())).thenAnswer(i -> {
                    CohortExportRequestEntity e = mock(CohortExportRequestEntity.class);
                    when(e.getId()).thenReturn(UUID.randomUUID());
                    return e;
                });
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                        .analyticsService(analytics)
                        .build();
                assertEquals(1, fixture.initializeIsolatedPackContext(pack, facilityId).operationsExecuted());
                ConsoleSeedSummary summary = fixture.seedAnalyticsConsole(facilityId);
                assertEquals("analytics", summary.consoleKey());
                assertThrows(NullPointerException.class,
                        () -> fixture.seedEmergencyNursingConsole(facilityId, patientId, encounterId));
            }
            case "healthcore-pack-ehr" -> {
                PatientPortalService portal = mock(PatientPortalService.class);
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                        .portalService(portal)
                        .build();
                assertEquals(1, fixture.initializeIsolatedPackContext(pack, facilityId).operationsExecuted());
                ConsoleSeedSummary summary = fixture.seedPatientPortalConsole(facilityId, patientId);
                assertEquals("portal", summary.consoleKey());
                assertEquals(3, summary.operationsExecuted());
                assertThrows(NullPointerException.class,
                        () -> fixture.seedBloodBankAndOtConsole(facilityId, patientId));
            }
            case "healthcore-pack-his" -> {
                RevenueCodingClaimsService rev = mock(RevenueCodingClaimsService.class);
                when(rev.upsertTariff(any(), anyString())).thenAnswer(i -> {
                    FacilityTariffEntity t = mock(FacilityTariffEntity.class);
                    when(t.getId()).thenReturn(UUID.randomUUID());
                    return t;
                });
                when(rev.createCodingAssignment(any())).thenAnswer(i -> {
                    ClinicalCodingRecordEntity c = mock(ClinicalCodingRecordEntity.class);
                    when(c.getId()).thenReturn(UUID.randomUUID());
                    return c;
                });
                when(rev.verifyEligibilityAndRequestAuth(any(), anyString())).thenAnswer(i -> {
                    InsuranceAuthorizationEntity a = mock(InsuranceAuthorizationEntity.class);
                    when(a.getId()).thenReturn(UUID.randomUUID());
                    return a;
                });
                when(rev.submitPayerClaim(any(), anyString())).thenAnswer(i -> {
                    PayerClaimEntity cl = mock(PayerClaimEntity.class);
                    when(cl.getId()).thenReturn(UUID.randomUUID());
                    return cl;
                });
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                        .revenueClaimsService(rev)
                        .build();
                assertEquals(1, fixture.initializeIsolatedPackContext(pack, facilityId).operationsExecuted());
                ConsoleSeedSummary revSummary = fixture.seedRevenueCodingClaimsStarter(facilityId, patientId, encounterId);
                assertEquals("revenue-coding-claims", revSummary.consoleKey());
                assertEquals(5, revSummary.operationsExecuted());
            }
            case "healthcore-pack-hie" -> {
                IntegrationOpsService intOps = mock(IntegrationOpsService.class);
                when(intOps.upsertPartnerProfile(any(), anyString())).thenAnswer(i -> {
                    PartnerConformanceProfileEntity p = mock(PartnerConformanceProfileEntity.class);
                    when(p.getId()).thenReturn(UUID.randomUUID());
                    return p;
                });
                when(intOps.enqueueOutbound(any(), anyString())).thenAnswer(i -> {
                    IntegrationMessageLedgerEntity m = mock(IntegrationMessageLedgerEntity.class);
                    when(m.getId()).thenReturn(UUID.randomUUID());
                    return m;
                });
                HealthCoreDataFixture fixture = HealthCoreDataFixture.builder()
                        .integrationOpsService(intOps)
                        .build();
                assertEquals(1, fixture.initializeIsolatedPackContext(pack, facilityId).operationsExecuted());
                ConsoleSeedSummary summary = fixture.seedIntegrationOpsStarter(facilityId);
                assertEquals("integration-ops", summary.consoleKey());
                assertEquals(4, summary.operationsExecuted());
                assertThrows(NullPointerException.class,
                        () -> fixture.seedEmergencyNursingConsole(facilityId, patientId, encounterId));
            }
            default -> fail("Unhandled industry pack: " + pack);
        }
    }

    private static Path resolveRepoRoot() {
        Path candidate = Path.of("..").toAbsolutePath().normalize();
        if (Files.exists(candidate.resolve("packs"))) {
            return candidate;
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static Set<String> resolveTransitive(Set<String> roots, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String dep = queue.poll();
            if (visited.add(dep)) {
                queue.addAll(graph.getOrDefault(dep, Set.of()));
            }
        }
        return visited;
    }

    private static Set<String> parseRequiredHealthCoreDependencies(Path pomFile) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.toFile());
        Set<String> deps = new HashSet<>();
        NodeList nodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            if (isInsideTag(node, "profiles") || isInsideTag(node, "dependencyManagement")) {
                continue;
            }
            String groupId = childText(node, "groupId");
            String artifactId = childText(node, "artifactId");
            String scope = childText(node, "scope");
            String optional = childText(node, "optional");
            if ("com.opeshealth.healthcore".equals(groupId)
                    && artifactId != null
                    && !artifactId.equals("healthcore-bom")
                    && !"test".equals(scope)
                    && !"true".equalsIgnoreCase(optional)) {
                deps.add(artifactId);
            }
        }
        return deps;
    }

    private static boolean isInsideTag(org.w3c.dom.Node node, String tagName) {
        var parent = node.getParentNode();
        while (parent != null) {
            if (tagName.equals(parent.getNodeName())) {
                return true;
            }
            parent = parent.getParentNode();
        }
        return false;
    }

    private static String childText(org.w3c.dom.Node node, String tag) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (tag.equals(children.item(i).getNodeName())) {
                return children.item(i).getTextContent().trim();
            }
        }
        return null;
    }

    private static List<String> extractJsonStringArray(String json, String field) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher itemMatcher = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (itemMatcher.find()) {
            out.add(itemMatcher.group(1));
        }
        return out;
    }
}
