/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — Android WorkManager Background Sync Worker
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import android.content.Context
import androidx.work.*
import com.opeshealth.healthcore.mobile.sync.OfflineVaultSyncRepository
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.util.concurrent.TimeUnit

/**
 * Periodically flushes queued offline clinical notes, vitals, and CHW field visits
 * to the HealthCore Edge/Cloud gateway whenever network connectivity is restored.
 */
class WorkManagerSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val baseUrl = inputData.getString("baseUrl") ?: "http://10.0.2.2:3000"
        val facilityId = inputData.getString("facilityId") ?: "01950000-0000-7000-8000-000000000001"
        val token = inputData.getString("bearerToken") ?: ""
        val http = HttpClient(OkHttp)
        return try {
            val repo = OfflineVaultSyncRepository(PlatformSqlDriverFactory(applicationContext), baseUrl, http)
            val fhirSyncResult = repo.dataSyncManager.processBackgroundSync(
                facilityId = facilityId,
                bearerToken = token,
                wallClockMs = System.currentTimeMillis()
            )
            if (fhirSyncResult.isFailure) {
                Result.retry()
            } else {
                repo.syncWithServer(facilityId, token).fold(
                    onSuccess = { Result.success() },
                    onFailure = { Result.retry() }
                )
            }
        } finally {
            http.close()
        }
    }

    companion object {
        fun schedulePeriodicSync(context: Context, baseUrl: String, facilityId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<WorkManagerSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInputData(workDataOf("baseUrl" to baseUrl, "facilityId" to facilityId))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "opes_healthcore_delta_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
