package com.opeshealth.healthcore.persistence.sdk;

import com.opeshealth.healthcore.persistence.sdk.CapabilityPackManifestContract.PackCompatibilityReport;
import com.opeshealth.healthcore.persistence.sdk.CapabilityPackManifestContract.PackManifestMetadata;
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

/**
 * Architectural acceptance test verifying that every OPES HealthCore Industry Capability Pack
 * preserves strict transitive module isolation (e.g., Pharmacy never pulls EMR/LIS/HIS;
 * Pharma Manufacturing & Life Sciences never pull MPI/EMR/HIS; HIE never pulls inpatient/billing).
 */
class CapabilityPackIsolationTest {

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
    void verifiesAllEightCapabilityPacksHonorIsolationManifestsTransitively() throws Exception {
        Path repoRoot = Path.of("..").toAbsolutePath().normalize();
        if (!Files.exists(repoRoot.resolve("packs"))) {
            repoRoot = Path.of(".").toAbsolutePath().normalize();
        }
        assertTrue(Files.exists(repoRoot.resolve("packs")), "Expected /packs directory in repository root");

        Map<String, Set<String>> directGraph = new HashMap<>();
        for (String pack : ALL_EIGHT_PACKS) {
            Path packPom = repoRoot.resolve("packs").resolve(pack).resolve("pom.xml");
            assertTrue(Files.exists(packPom), "Missing pack pom.xml: " + packPom);
            directGraph.put(pack, parseDirectHealthCoreDependencies(packPom));
        }

        // Index all starter POMs so transitive resolution is complete
        try (var stream = Files.list(repoRoot)) {
            for (Path p : stream.toList()) {
                Path pom = p.resolve("pom.xml");
                if (Files.isDirectory(p) && Files.exists(pom) && p.getFileName().toString().startsWith("healthcore-")) {
                    directGraph.put(p.getFileName().toString(), parseDirectHealthCoreDependencies(pom));
                }
            }
        }

        List<String> requiredManifestKeys = List.of(
                "packId", "version", "description", "intendedDeveloperAudience",
                "requiredModules", "optionalModules", "dependencyConstraints",
                "forbiddenTransitiveModules", "supportedDatabasePlatforms",
                "providedCapabilities", "exposedApisAndEvents", "requiredConfiguration",
                "defaultRolesAndPermissions", "relevantStandardsAndApplicability",
                "referenceIntegrations", "installationExamples", "knownLimitations"
        );

        for (String pack : ALL_EIGHT_PACKS) {
            Path manifestPath = repoRoot.resolve("packs").resolve(pack)
                    .resolve("src/main/resources/META-INF/healthcore/pack-manifest.json");
            assertTrue(Files.exists(manifestPath), "Missing pack-manifest.json for " + pack);
            String json = Files.readString(manifestPath);

            for (String key : requiredManifestKeys) {
                assertTrue(json.contains("\"" + key + "\""),
                        "Pack manifest " + pack + " missing mandatory Section 5 field: " + key);
            }

            List<String> required = extractJsonStringArray(json, "requiredModules");
            Set<String> forbidden = new HashSet<>(extractJsonStringArray(json, "forbiddenTransitiveModules"));
            Set<String> transitiveClosure = resolveTransitive(pack, directGraph);

            PackManifestMetadata metadata = new PackManifestMetadata(
                    pack, "1.0.0", pack, required, List.of(), forbidden);
            PackCompatibilityReport report = CapabilityPackManifestContract.verifyIsolation(metadata, transitiveClosure);

            assertTrue(report.compatible(),
                    "Pack isolation failure for " + pack + ": " + report.violations() + " (resolved=" + transitiveClosure + ")");
        }
    }

    private static Set<String> resolveTransitive(String root, Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(graph.getOrDefault(root, Set.of()));
        while (!queue.isEmpty()) {
            String dep = queue.poll();
            if (visited.add(dep)) {
                queue.addAll(graph.getOrDefault(dep, Set.of()));
            }
        }
        return visited;
    }

    private static Set<String> parseDirectHealthCoreDependencies(Path pomFile) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.toFile());
        Set<String> deps = new HashSet<>();
        NodeList nodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = nodes.item(i);
            // Ignore dependencies inside <profiles> so base pack isolation is evaluated cleanly
            if (isInsideTag(node, "profiles")) {
                continue;
            }
            String groupId = childText(node, "groupId");
            String artifactId = childText(node, "artifactId");
            if ("com.opeshealth.healthcore".equals(groupId) && artifactId != null && !artifactId.equals("healthcore-bom")) {
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
