package com.personal.devicememory.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the nightly collection worker.
 */
object WorkScheduler {

    /**
     * Schedules the periodic nightly collection.
     * Runs every 24 hours when the device is charging and idle.
     */
    fun scheduleNightly(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()

        val request = PeriodicWorkRequestBuilder<CollectionWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag(CollectionWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CollectionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Triggers an immediate one-time collection (ignoring constraints).
     * Used by the "Run Now" button in the UI.
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CollectionWorker>()
            .addTag(CollectionWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "${CollectionWorker.WORK_NAME}_manual",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /**
     * Cancels all scheduled collection work.
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CollectionWorker.WORK_NAME)
    }
}
