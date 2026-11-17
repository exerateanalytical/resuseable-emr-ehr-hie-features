/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — KMP Android / iPhone / iPadOS Build Script
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("desktop")
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

sqldelight {
    databases {
        create("HealthCoreOfflineDatabase") {
            packageName.set("com.opeshealth.healthcore.mobile.db")
        }
    }
}
