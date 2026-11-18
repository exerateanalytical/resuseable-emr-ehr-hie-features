/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Android Keystore + SQLCipher Encrypted SQLite Driver
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.opeshealth.healthcore.mobile.db.HealthCoreOfflineDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates and stores a 256-bit SQLCipher vault key wrapped inside Android Hardware Keystore
 * via {@link EncryptedSharedPreferences}, ensuring offline PHI on disk is AES-256 encrypted.
 */
actual class PlatformSqlDriverFactory(private val context: Context) {

    actual fun createDriver(databaseName: String): SqlDriver {
        System.loadLibrary("sqlcipher")
        val passphrase = resolveOrCreateVaultPassphrase(context)
        val sqlCipherFactory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
        return AndroidSqliteDriver(
            schema = HealthCoreOfflineDatabase.Schema,
            context = context,
            name = databaseName,
            factory = sqlCipherFactory
        )
    }

    private fun resolveOrCreateVaultPassphrase(ctx: Context): String {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            ctx,
            "opes_healthcore_vault_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val existing = prefs.getString("sqlcipher_key", null)
        if (!existing.isNullOrBlank()) return existing
        val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val created = Base64.getEncoder().encodeToString(randomBytes)
        prefs.edit().putString("sqlcipher_key", created).apply()
        return created
    }
}
