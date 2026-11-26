package com.personal.devicememory.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules battery-aware background collection and expedited manual/widget learning runs.
 */
object WorkScheduler {

    const val KEY_MANUAL_TRIGGER = "manual"

    /**
     * Schedules adaptive 6-hour background learning with battery-not-low protection
     * and exponential backoff (replacing strict 24h charging+idle starvation).
     */
    fun scheduleNightly(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<CollectionWorker>(
            6, TimeUnit.HOURS,
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(20, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(CollectionWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CollectionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Triggers an immediate one-time expedited collection (for "Collect & Learn Now" / Home Widget).
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CollectionWorker>()
            .setInputData(workDataOf(KEY_MANUAL_TRIGGER to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
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
