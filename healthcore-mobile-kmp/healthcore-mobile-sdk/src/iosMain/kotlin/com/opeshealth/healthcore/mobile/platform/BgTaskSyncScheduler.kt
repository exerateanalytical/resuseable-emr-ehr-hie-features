/*
 * =============================================================================
 * Opes HealthCore Mobile SDK — iOS / iPadOS BGTaskScheduler Delta Sync
 * Author / Architect : Jude Nshome — Opes Health Systems (nshome@opesware.com)
 * Website            : https://www.opeshealthsystems.com | Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.mobile.platform

import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * Registers background refresh sync tasks with Apple iOS/iPadOS {@code BGTaskScheduler}
 * matching {@code BGTaskSchedulerPermittedIdentifiers} in {@code Info.plist}.
 */
object BgTaskSyncScheduler {
    const val TASK_ID = "com.opeshealth.healthcore.mobile.sync"

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
}
