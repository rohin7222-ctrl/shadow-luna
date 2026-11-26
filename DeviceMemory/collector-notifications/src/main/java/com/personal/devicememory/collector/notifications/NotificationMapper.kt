package com.personal.devicememory.collector.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.toJsonString

/**
 * Maps incoming [StatusBarNotification] events to normalized [CollectedRecord] entities.
 */
object NotificationMapper {

    data class ParsedNotification(
        val packageName: String,
        val title: String?,
        val text: String?,
        val postTime: Long,
        val channelId: String?,
        val category: String?,
        val isOngoing: Boolean,
        val contentHash: String
    )

    /**
     * Extracts readable details from [StatusBarNotification].
     * Returns null for system/empty notifications that have no text or title.
     */
    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()

        // Skip empty notifications that carry no readable user content
        if (title.isNullOrBlank() && text.isNullOrBlank()) {
            return null
        }

        val channelId = notification.channelId
        val category = notification.category
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

        // Compute a signature hash to deduplicate continuous notifications (e.g. progress bar updates)
        val signature = "${sbn.packageName}|$title|$text"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return ParsedNotification(
            packageName = sbn.packageName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            channelId = channelId,
            category = category,
            isOngoing = isOngoing,
            contentHash = hash
        )
    }

    /**
     * Converts a [ParsedNotification] to a [CollectedRecord].
     */
    fun mapToRecord(parsed: ParsedNotification): CollectedRecord {
        val redactedTitle = SensitiveContentFilter.redact(parsed.title)
        val redactedText = SensitiveContentFilter.redact(parsed.text)
        val combinedText = buildString {
            if (!redactedTitle.isNullOrBlank()) {
                append(redactedTitle)
            }
            if (!redactedText.isNullOrBlank()) {
                if (isNotEmpty()) append(": ")
                append(redactedText)
            }
        }

        val metadata = mapOf<String, Any?>(
            "package" to parsed.packageName,
            "title" to redactedTitle,
            "channel_id" to parsed.channelId,
            "category" to parsed.category,
            "is_ongoing" to parsed.isOngoing,
            "content_hash" to parsed.contentHash
        )

        return CollectedRecord(
            sourceType = SourceType.NOTIFICATION,
            timestamp = parsed.postTime,
            textContent = combinedText,
            metadataJson = metadata.toJsonString()
        )
    }
}
