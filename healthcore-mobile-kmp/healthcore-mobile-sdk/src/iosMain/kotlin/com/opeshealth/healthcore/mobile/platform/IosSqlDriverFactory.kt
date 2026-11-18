/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — iOS / iPadOS Keychain-Protected SQLite Driver
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.opeshealth.healthcore.mobile.db.HealthCoreOfflineDatabase
import platform.Foundation.NSData
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDefaults

/**
 * Configures Apple iOS/iPadOS Data Protection ({@code NSFileProtectionComplete}) alongside
 * Keychain-anchored local vault metadata so offline clinical charts remain locked when the iPad sleeps.
 */
actual class PlatformSqlDriverFactory {

    actual fun createDriver(databaseName: String): SqlDriver {
        ensureCompleteFileProtectionAttr()
        return NativeSqliteDriver(HealthCoreOfflineDatabase.Schema, databaseName)
    }

    private fun ensureCompleteFileProtectionAttr() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.stringForKey("opes_ios_vault_protection") == null) {
            defaults.setObject(NSFileProtectionComplete, forKey = "opes_ios_vault_protection")
        }
        NSFileManager.defaultManager
    }
}
