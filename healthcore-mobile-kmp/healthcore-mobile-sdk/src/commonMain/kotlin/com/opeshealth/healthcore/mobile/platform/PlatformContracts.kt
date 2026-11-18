/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Expect/Actual Platform Driver & Auth Contracts
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import app.cash.sqldelight.db.SqlDriver

/**
 * Multiplatform factory creating an encrypted local SQLite driver
 * (Android Keystore + SQLCipher on Android; Keychain-protected SQLite on iOS/iPadOS).
 */
expect class PlatformSqlDriverFactory {
    fun createDriver(databaseName: String = "healthcore_offline.db"): SqlDriver
}

/**
 * Cross-platform camera/optical scanner abstraction for patient wristbands,
 * laboratory specimen accession barcodes, and Ed25519 QR IPS/UHC Health Cards.
 */
interface QrAndBarcodeScanner {
    fun startScan(onDecoded: (rawPayload: String) -> Unit)
}

/**
 * Cross-platform stylus / Apple Pencil stroke capture contract used by Dental odontogram,
 * Ophthalmology fundus sketches, and Dermatology body-map drawings on tablets/iPads.
 */
data class StylusStrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val isStylus: Boolean
)
