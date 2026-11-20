/*
 * =============================================================================
 * Opes HealthCore SDK — Industry Capability Pack Manifest & Isolation Contract
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.persistence.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Runtime and test-time contract for validating OPES HealthCore Industry Capability Pack
 * dependency graphs, preventing forbidden cross-industry coupling (for example, ensuring
 * {@code healthcore-pack-pharma-manufacturing} never pulls in MPI/EMR/HIS modules).
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
}
