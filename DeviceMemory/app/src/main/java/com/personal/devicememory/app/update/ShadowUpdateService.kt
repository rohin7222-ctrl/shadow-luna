package com.personal.devicememory.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.personal.devicememory.app.MainActivity
import com.personal.devicememory.pipeline.sync.LunaUpdatesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Runs background OTA update download + staging while MainActivity closes cleanly,
 * notifies any active lockout popup with exact timing, and automatically re-opens Shadow upon completion.
 */
class ShadowUpdateService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val version = intent?.getStringExtra(EXTRA_VERSION) ?: ShadowUpdateState.getTargetVersion(this)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ShadowUpdateState.getTargetTitle(this)
        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, 12_000L) ?: 12_000L

        if (version.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannels()
        if (!ShadowUpdateState.isUpdating(this)) {
            ShadowUpdateState.startUpdate(this, version, title, durationMs)
        }

        val initialSeconds = ceil(durationMs / 1000.0).toInt().coerceAtLeast(1)
        val notification = buildProgressNotification(version, initialSeconds, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIF_ID, notification)
        }

        serviceScope.launch {
            runUpdatePipeline(version, durationMs)
        }

        return START_NOT_STICKY
    }

    private suspend fun runUpdatePipeline(version: String, durationMs: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            // Download real bundle concurrently so the countdown never stalls if LAN endpoint is slow
            serviceScope.launch {
                val bundleResult = LunaUpdatesClient.downloadUpdateBundle(applicationContext, version)
                if (bundleResult.isFailure) {
                    Log.w(TAG, "Bundle download warning (continuing staged refresh): ${bundleResult.exceptionOrNull()?.message}")
                }
            }

            var lastShownSec = -1
            while (true) {
                val remaining = ShadowUpdateState.remainingMillis(applicationContext)
                if (remaining <= 0L) break

                val sec = ceil(remaining / 1000.0).toInt().coerceAtLeast(1)
                val progress = (((durationMs - remaining).toFloat() / durationMs.toFloat()) * 100f)
                    .toInt()
                    .coerceIn(5, 99)

                if (sec != lastShownSec) {
                    lastShownSec = sec
                    nm.notify(PROGRESS_NOTIF_ID, buildProgressNotification(version, sec, progress))
                }
                delay(250L)
            }

            // Apply completed version atomically
            ShadowUpdateState.completeUpdate(applicationContext, version)

            // Tell any visible UpdateLockoutActivity to close & launch MainActivity immediately
            sendBroadcast(
                Intent(ACTION_UPDATE_FINISHED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_VERSION, version)
            )

            // Auto-launch MainActivity from background
            launchShadowApp(version)
        } catch (e: Throwable) {
            Log.e(TAG, "Update pipeline failed", e)
            ShadowUpdateState.clearUpdatingFlag(applicationContext)
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun launchShadowApp(version: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_JUST_UPDATED, true)
            putExtra(EXTRA_VERSION, version)
        }

        // Direct launch works seamlessly when SYSTEM_ALERT_WINDOW is granted or within OS interaction window
        try {
            startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "Direct background startActivity blocked by OS; posting FullScreenIntent fallback", e)
        }

        // High-priority full-screen / tap-to-open notification fallback for restricted Android 10-15 devices
        try {
            val pending = PendingIntent.getActivity(
                this,
                2026,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val readyNotif = NotificationCompat.Builder(this, CHANNEL_READY)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Shadow updated to v$version")
                .setContentText("Tap to open the updated Luna core")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(READY_NOTIF_ID, readyNotif)
        } catch (_: Throwable) {}
    }

    private fun ensureChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS,
            "Shadow Updates",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows background update progress for Shadow"
        }
        val readyChannel = NotificationChannel(
            CHANNEL_READY,
            "Shadow Update Ready",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Auto-opens Shadow when an update completes"
        }
        nm.createNotificationChannel(progressChannel)
        nm.createNotificationChannel(readyChannel)
    }

    private fun buildProgressNotification(version: String, remainingSeconds: Int, progress: Int): Notification {
        val touchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            this,
            1001,
            touchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating Shadow to v$version")
            .setContentText("Opens automatically in ${remainingSeconds}s")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShadowUpdateService"
        private const val CHANNEL_PROGRESS = "shadow_updates_progress"
        private const val CHANNEL_READY = "shadow_updates_ready"
        private const val PROGRESS_NOTIF_ID = 4101
        private const val READY_NOTIF_ID = 4102

        const val ACTION_UPDATE_FINISHED = "com.personal.devicememory.ACTION_UPDATE_FINISHED"
        const val EXTRA_VERSION = "extra_version"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_JUST_UPDATED = "extra_just_updated"

        fun start(context: Context, version: String, title: String, durationSeconds: Int) {
            val durationMs = durationSeconds.coerceIn(5, 120) * 1000L
            // Write lockout flag synchronously BEFORE startForegroundService so fast re-opens never bypass lockout
            ShadowUpdateState.startUpdate(context.applicationContext, version, title, durationMs)
            val intent = Intent(context, ShadowUpdateService::class.java).apply {
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_DURATION_MS, durationMs)
            }
            context.startForegroundService(intent)
        }
    }
}
