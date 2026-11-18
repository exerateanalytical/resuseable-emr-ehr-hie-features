/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — iPhone & iPadOS Compose UIViewController
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile

import androidx.compose.ui.window.ComposeUIViewController
import com.opeshealth.healthcore.mobile.platform.BgTaskSyncScheduler
import com.opeshealth.healthcore.mobile.platform.PlatformSqlDriverFactory
import com.opeshealth.healthcore.mobile.sync.OfflineVaultSyncRepository
import com.opeshealth.healthcore.mobile.ui.HealthCoreSuiteHost
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIViewController

/**
 * SwiftUI / UIKit entry point rendering the adaptive Opes HealthCore experience:
 * single-hand thumb layout on iPhone and 3-Column Clinical Cockpit + Apple Pencil canvas on iPadOS.
 */
fun MainViewController(baseUrl: String = "http://localhost:3000"): UIViewController {
    val facilityId = "01950000-0000-7000-8000-000000000001"
    val vault = OfflineVaultSyncRepository(
        driverFactory = PlatformSqlDriverFactory(),
        baseUrl = baseUrl,
        httpClient = HttpClient(Darwin),
        nodeId = "ipad-ward-cockpit"
    )
    BgTaskSyncScheduler.registerAndSchedule {}

    return ComposeUIViewController {
        HealthCoreSuiteHost(
            vault = vault,
            facilityId = facilityId,
            wallClockMs = { (NSDate().timeIntervalSince1970 * 1000.0).toLong() }
        )
    }
}
