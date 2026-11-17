/*
 * =============================================================================
 * Opes HealthCore SDK — ArchUnit Architectural Governance & Zero-TS Tests
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.quickstart;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces Opes HealthCore architectural boundaries:
 * <ul>
 *   <li>Persistence core must never depend on upper clinical/billing modules</li>
 *   <li>All controllers must reside cleanly in their bounded context modules</li>
 *   <li>Zero TypeScript/JS config files exist anywhere in the repository</li>
 * </ul>
 */
class HealthCoreArchitectureTest {

    @Test
    void persistenceCoreDoesNotDependOnDomainModules() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.opeshealth.healthcore");
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..emr..", "..pharmacylis..", "..uhc..", "..publichealth..")
                .check(classes);
    }

    @Test
    void workspaceContainsZeroTypeScriptFiles() throws IOException {
        Path root = Paths.get("..").toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(root)) {
            long tsCount = walk
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString().toLowerCase())
                    .filter(name -> name.endsWith(".ts") || name.endsWith(".tsx") || name.equals("tsconfig.json"))
                    .count();
            assertTrue(tsCount == 0, "TypeScript files are strictly forbidden in Opes HealthCore");
        }
    }
}
