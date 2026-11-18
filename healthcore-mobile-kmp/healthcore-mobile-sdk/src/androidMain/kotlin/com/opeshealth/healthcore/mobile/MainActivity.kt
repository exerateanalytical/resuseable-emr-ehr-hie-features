/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Android Compose Entry Activity
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.opeshealth.healthcore.mobile.platform.CameraQrScannerBridge
import com.opeshealth.healthcore.mobile.platform.PlatformSqlDriverFactory
import com.opeshealth.healthcore.mobile.platform.WorkManagerSyncWorker
import com.opeshealth.healthcore.mobile.sync.OfflineVaultSyncRepository
import com.opeshealth.healthcore.mobile.ui.HealthCoreSuiteHost
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val facilityId = "01950000-0000-7000-8000-000000000001"
        val baseUrl = "http://10.0.2.2:3000"
        val vault = OfflineVaultSyncRepository(
            driverFactory = PlatformSqlDriverFactory(applicationContext),
            baseUrl = baseUrl,
            httpClient = HttpClient(OkHttp),
            nodeId = "android-bedside"
        )
        val scanner = CameraQrScannerBridge(this)
        WorkManagerSyncWorker.schedulePeriodicSync(applicationContext, baseUrl, facilityId)

        setContent {
            HealthCoreSuiteHost(
                vault = vault,
                facilityId = facilityId,
                wallClockMs = { System.currentTimeMillis() },
                onRequestBarcodeScan = { callback -> scanner.startScan(callback) }
            )
        }
    }
}
