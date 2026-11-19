/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — iOS / iPadOS BGTaskScheduler Delta Sync
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import com.opeshealth.healthcore.mobile.sync.DataSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Registers background refresh sync tasks with Apple iOS/iPadOS {@code BGTaskScheduler}
 * matching {@code BGTaskSchedulerPermittedIdentifiers} in {@code Info.plist}.
 */
object BgTaskSyncScheduler {
    const val TASK_ID = "com.opeshealth.healthcore.mobile.sync"
    private val backgroundScope = CoroutineScope(Dispatchers.Default)

    fun registerAndSchedule(onSyncTriggered: () -> Unit) {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_ID,
            usingQueue = null
        ) { task ->
            onSyncTriggered()
            task?.setTaskCompletedWithSuccess(true)
        }

        val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(900.0) // 15 minutes
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
    }

    fun registerDataSyncManager(
        dataSyncManager: DataSyncManager,
        facilityId: String,
        bearerTokenProvider: () -> String
    ) {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_ID,
            usingQueue = null
        ) { task ->
            backgroundScope.launch {
                val nowMs = (NSDate().dateWithTimeIntervalSince1970 * 1000.0).toLong()
                val outcome = dataSyncManager.processBackgroundSync(
                    facilityId = facilityId,
                    bearerToken = bearerTokenProvider(),
                    wallClockMs = nowMs
                )
                task?.setTaskCompletedWithSuccess(outcome.isSuccess)
            }
        }
        val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(900.0)
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
    }
}
