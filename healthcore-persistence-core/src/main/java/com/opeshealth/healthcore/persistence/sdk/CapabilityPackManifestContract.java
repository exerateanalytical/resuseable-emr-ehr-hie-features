/*
 * =============================================================================
 * Opes HealthCore SDK — Industry Capability Pack Manifest & Conflict Detector
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.util.*;

/**
 * Runtime and test-time contract for validating OPES HealthCore Industry Capability Pack
 * dependency graphs, detecting cross-industry coupling, and checking module version compatibility.
 */
public final class CapabilityPackManifestContract {

    private CapabilityPackManifestContract() {}

    public record PackManifestMetadata(
            String packId,
            String version,
            String industryDomain,
            List<String> requiredModules,
            List<String> optionalModules,
            Set<String> forbiddenTransitiveModules
    ) {}

    public record PackCompatibilityReport(
            String packId,
            boolean compatible,
            List<String> violations
    ) {}

    public static PackCompatibilityReport verifyIsolation(
            PackManifestMetadata manifest,
            Set<String> resolvedModuleArtifacts) {
        List<String> violations = new ArrayList<>();
        if (manifest.requiredModules() != null) {
            for (String req : manifest.requiredModules()) {
                if (!resolvedModuleArtifacts.contains(req)) {
                    violations.add("Missing required module: " + req);
                }
            }
        }
        if (manifest.forbiddenTransitiveModules() != null) {
            for (String forbidden : manifest.forbiddenTransitiveModules()) {
                if (resolvedModuleArtifacts.contains(forbidden)) {
                    violations.add("Forbidden cross-industry dependency detected: " + forbidden);
                }
            }
        }
        return new PackCompatibilityReport(manifest.packId(), violations.isEmpty(), List.copyOf(violations));
    }

    /**
     * Detects conflicting major versions or mutually exclusive module compositions when developers
     * assemble Mode C custom compositions across multiple HealthCore starters.
     */
    public static PackCompatibilityReport detectDependencyConflicts(
            String compositionName,
            Map<String, String> installedModuleVersions) {
        List<String> violations = new ArrayList<>();
        Set<String> majorVersions = new HashSet<>();
        for (var entry : installedModuleVersions.entrySet()) {
            String ver = entry.getValue();
            if (ver == null || ver.isBlank()) {
                violations.add("Module '" + entry.getKey() + "' has missing semantic version.");
                continue;
            }
            String major = ver.split("\\.")[0];
            majorVersions.add(major);
        }
        if (majorVersions.size() > 1) {
            violations.add("Incompatible HealthCore major versions detected across modules: " + majorVersions);
        }
        if (installedModuleVersions.containsKey("healthcore-starter-pharmacy-commerce")
                && installedModuleVersions.containsKey("healthcore-starter-pharmacy-lis")) {
            violations.add("Conflicting pharmacy modules installed simultaneously: choose standalone 'healthcore-starter-pharmacy-commerce' OR hospital 'healthcore-starter-pharmacy-lis'.");
        }
        return new PackCompatibilityReport(compositionName, violations.isEmpty(), List.copyOf(violations));
    }
}
