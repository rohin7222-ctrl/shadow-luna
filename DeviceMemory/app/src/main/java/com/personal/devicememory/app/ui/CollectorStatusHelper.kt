package com.personal.devicememory.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.personal.devicememory.collector.appusage.AppUsageCollector
import com.personal.devicememory.collector.audio.AudioCollector
import com.personal.devicememory.collector.contactscalendar.CalendarCollector
import com.personal.devicememory.collector.contactscalendar.ContactCollector
import com.personal.devicememory.collector.location.ActivityCollector
import com.personal.devicememory.collector.location.LocationCollector
import com.personal.devicememory.collector.notifications.NotificationCollector
import com.personal.devicememory.collector.photos.PhotoCollector
import com.personal.devicememory.collector.videos.VideoCollector
import com.personal.devicememory.core.common.model.SourceType

/**
 * Shared helper for collector display labels, permission checking, and Settings intents
 * so Native Compose UI and WebView JS Bridge stay consistent without duplicate logic.
 */
object CollectorStatusHelper {

    fun hasPermission(context: Context, sourceType: SourceType): Boolean {
        return try {
            when (sourceType) {
                SourceType.APP_USAGE -> AppUsageCollector(context).hasPermission()
                SourceType.NOTIFICATION -> NotificationCollector.hasPermission(context)
                SourceType.PHOTO -> PhotoCollector.hasPermission(context)
                SourceType.VIDEO -> VideoCollector.hasPermission(context)
                SourceType.AUDIO -> AudioCollector.hasPermission(context)
                SourceType.LOCATION -> LocationCollector.hasPermission(context)
                SourceType.ACTIVITY -> ActivityCollector.hasPermission(context)
                SourceType.CONTACT -> ContactCollector.hasPermission(context)
                SourceType.CALENDAR_EVENT -> CalendarCollector.hasPermission(context)
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun displayName(sourceType: SourceType): String = when (sourceType) {
        SourceType.APP_USAGE -> "App Usage"
        SourceType.NOTIFICATION -> "Notifications"
        SourceType.PHOTO -> "Photos & Screenshots"
        SourceType.VIDEO -> "Videos"
        SourceType.AUDIO -> "Audio & Voice Recordings"
        SourceType.LOCATION -> "Location"
        SourceType.ACTIVITY -> "Activity Recognition"
        SourceType.CONTACT -> "Contacts"
        SourceType.CALENDAR_EVENT -> "Calendar Events"
    }

    fun openPermissionOrRequest(
        context: Context,
        sourceType: SourceType,
        onRequestRuntimePermissions: () -> Unit
    ) {
        try {
            when (sourceType) {
                SourceType.APP_USAGE -> {
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                SourceType.NOTIFICATION -> {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }

                else -> onRequestRuntimePermissions()
            }
        } catch (_: Throwable) {
            onRequestRuntimePermissions()
        }
    }
}
