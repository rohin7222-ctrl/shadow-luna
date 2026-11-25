package com.personal.devicememory.pipeline

import android.content.Context
import com.personal.devicememory.collector.appusage.AppUsageCollector
import com.personal.devicememory.collector.notifications.NotificationCollector
import com.personal.devicememory.core.common.model.SourceType

/**
 * Provides a fully-wired [CollectorRegistry].
 * New collectors are added here as they are implemented.
 */
object CollectorRegistryProvider {

    @Volatile
    private var registry: CollectorRegistry? = null

    fun getRegistry(context: Context): CollectorRegistry {
        return registry ?: synchronized(this) {
            registry ?: buildRegistry(context.applicationContext).also {
                registry = it
            }
        }
    }

    private fun buildRegistry(context: Context): CollectorRegistry {
        return CollectorRegistry().apply {
            register(SourceType.APP_USAGE, AppUsageCollector(context))
            register(SourceType.NOTIFICATION, NotificationCollector(context))
            register(SourceType.PHOTO, com.personal.devicememory.collector.photos.PhotoCollector(context))
            register(SourceType.VIDEO, com.personal.devicememory.collector.videos.VideoCollector(context))
            register(SourceType.AUDIO, com.personal.devicememory.collector.audio.AudioCollector(context))
            register(SourceType.LOCATION, com.personal.devicememory.collector.location.LocationCollector(context))
            register(SourceType.ACTIVITY, com.personal.devicememory.collector.location.ActivityCollector(context))
            register(SourceType.CONTACT, com.personal.devicememory.collector.contactscalendar.ContactCollector(context))
            register(SourceType.CALENDAR_EVENT, com.personal.devicememory.collector.contactscalendar.CalendarCollector(context))
        }
    }
}
