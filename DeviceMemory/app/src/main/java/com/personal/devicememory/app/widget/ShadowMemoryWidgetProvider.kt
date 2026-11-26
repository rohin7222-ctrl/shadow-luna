package com.personal.devicememory.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.personal.devicememory.R
import com.personal.devicememory.app.MainActivity
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.pipeline.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 2026 Home Screen Quick Memory Widget for Shadow • Luna.
 * Displays live encrypted-vault metrics (Memories, Stitched Moments, Concepts)
 * and offers one-tap "Learn Now" & "Ask Luna" shortcuts.
 */
class ShadowMemoryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        if (appWidgetIds.isEmpty()) return
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                val snap = queryWidgetStats(context.applicationContext)
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, buildRemoteViews(context.applicationContext, snap))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_LEARN_NOW) {
            val appContext = context.applicationContext
            val pendingResult = goAsync()
            widgetScope.launch {
                try {
                    WorkScheduler.runNow(appContext)
                    val snap = queryWidgetStats(appContext).copy(
                        subtitleOverride = "Collecting & learning locally..."
                    )
                    pushViewsToAll(appContext, snap)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private data class WidgetStats(
        val totalMemories: Int = 0,
        val episodesCount: Int = 0,
        val tokensCount: Int = 0,
        val latestMomentText: String = "Blank-slate vault ready. Open Shadow to enable sensors.",
        val subtitleOverride: String? = null
    )

    companion object {
        const val ACTION_LEARN_NOW = "com.personal.devicememory.widget.ACTION_LEARN_NOW"
        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun refreshAllWidgets(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                try {
                    val mgr = AppWidgetManager.getInstance(appContext)
                    val ids = mgr.getAppWidgetIds(ComponentName(appContext, ShadowMemoryWidgetProvider::class.java))
                    if (ids.isEmpty()) return@launch
                    val snap = queryWidgetStats(appContext)
                    val rv = buildRemoteViews(appContext, snap)
                    for (id in ids) {
                        mgr.updateAppWidget(id, rv)
                    }
                } catch (_: Throwable) {}
            }
        }

        private suspend fun queryWidgetStats(context: Context): WidgetStats {
            return try {
                val db = DeviceMemoryDatabase.getInstance(context)
                val memories = db.collectedRecordDao().totalCount()
                val tokens = db.learnedTokenDao().countTokens()
                val episodes = db.brainSimulationDao().countEpisodes()
                val latestEp = db.brainSimulationDao().getRecentEpisodes(1).firstOrNull()
                val summary = when {
                    latestEp != null -> latestEp.narrativeSummary
                    memories > 0 -> "$memories memories stored offline. Open Moments or tap Learn Now to stitch stories."
                    else -> "Blank-slate AI • Zero pre-built knowledge. Enable sensors to start learning."
                }
                WidgetStats(
                    totalMemories = memories,
                    episodesCount = episodes,
                    tokensCount = tokens,
                    latestMomentText = summary
                )
            } catch (_: Throwable) {
                WidgetStats()
            }
        }

        private fun pushViewsToAll(context: Context, stats: WidgetStats) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ShadowMemoryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val rv = buildRemoteViews(context, stats)
            for (id in ids) {
                mgr.updateAppWidget(id, rv)
            }
        }

        private fun buildRemoteViews(context: Context, stats: WidgetStats): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_shadow_memory)
            views.setTextViewText(R.id.widget_memories_count, stats.totalMemories.toString())
            views.setTextViewText(R.id.widget_moments_count, stats.episodesCount.toString())
            views.setTextViewText(R.id.widget_tokens_count, stats.tokensCount.toString())
            views.setTextViewText(R.id.widget_latest_moment, stats.latestMomentText)
            views.setTextViewText(
                R.id.widget_badge,
                if (stats.totalMemories == 0) "Blank Slate" else "${stats.totalMemories} learned"
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                stats.subtitleOverride ?: if (stats.totalMemories == 0) "Blank-Slate Offline Brain" else "On-Device Encrypted Vault"
            )

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val askPending = PendingIntent.getActivity(
                context,
                1001,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, askPending)
            views.setOnClickPendingIntent(R.id.widget_btn_ask, askPending)

            val learnIntent = Intent(context, ShadowMemoryWidgetProvider::class.java).apply {
                action = ACTION_LEARN_NOW
            }
            val learnPending = PendingIntent.getBroadcast(
                context,
                1002,
                learnIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_learn, learnPending)
            return views
        }
    }
}
