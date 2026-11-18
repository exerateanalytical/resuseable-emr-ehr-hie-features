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
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Enforces Opes HealthCore architectural boundaries:
 * <ul>
 *   <li>Persistence core and Security/Audit modules must never depend on upper clinical/billing modules</li>
 *   <li>Zero standard output streams ({@code System.out}/{@code System.err}) in production classes</li>
 *   <li>Zero TypeScript/JavaScript/Node files exist anywhere in the repository</li>
 * </ul>
 */
class HealthCoreArchitectureTest {

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.opeshealth.healthcore");

    @Test
    void persistenceCoreDoesNotDependOnDomainModules() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..emr..", "..pharmacylis..", "..uhc..", "..publichealth..",
                        "..hie..", "..ops..", "..templates..", "..apiproduct..")
                .check(MAIN_CLASSES);
    }

    @Test
    void securityAuditDoesNotDependOnDomainModules() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage("..security..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..emr..", "..pharmacylis..", "..uhc..", "..publichealth..",
                        "..hie..", "..ops..", "..templates..")
                .check(MAIN_CLASSES);
    }

    @Test
    void productionClassesNeverWriteToSystemOutOrErr() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(MAIN_CLASSES);
    }

    @Test
    void workspaceContainsZeroTypeScriptOrNodeFiles() throws IOException {
        Path root = Paths.get("..").toAbsolutePath().normalize();
        try (Stream<Path> walk = Files.walk(root)) {
            long jsTsCount = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("\\target\\"))
                    .map(p -> p.getFileName().toString().toLowerCase())
                    .filter(name -> name.endsWith(".ts")
                            || name.endsWith(".tsx")
                            || name.endsWith(".js")
                            || name.endsWith(".jsx")
                            || name.equals("package.json")
                            || name.equals("tsconfig.json")
                            || name.equals("vite.config.ts"))
                    .count();
            assertEquals(0, jsTsCount, "TypeScript/JavaScript/Node files are strictly forbidden in Opes HealthCore");
        }
    }
}
