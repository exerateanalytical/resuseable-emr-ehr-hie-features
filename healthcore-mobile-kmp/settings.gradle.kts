/*
 * =============================================================================
 * Opes HealthCore SDK — Kotlin Multiplatform Mobile & iPad Suite Settings
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 * Pure Kotlin + Compose Multiplatform (Android, iOS, iPadOS). Zero TypeScript.
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "healthcore-mobile-kmp"
include(":healthcore-mobile-sdk")
