package com.personal.devicememory.collector.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMapperTest {

    @Test
    fun testNotificationRecordMapping() {
        val parsed = NotificationMapper.ParsedNotification(
            packageName = "com.whatsapp",
            title = "அம்மா",
            text = "சாப்பிட்டியா? Take care!",
            postTime = 1700000000000L,
            channelId = "messages",
            category = "msg",
            isOngoing = false,
            contentHash = "mockhash123"
        )

        val record = NotificationMapper.mapToRecord(parsed)

        assertEquals(1700000000000L, record.timestamp)
        assertEquals("அம்மா: சாப்பிட்டியா? Take care!", record.textContent)
        assertTrue(record.metadataJson.contains("com.whatsapp"))
        assertTrue(record.metadataJson.contains("messages"))
    }

    @Test
    fun testOnlyTitleNotification() {
        val parsed = NotificationMapper.ParsedNotification(
            packageName = "com.google.android.calendar",
            title = "Design Review Meeting",
            text = null,
            postTime = 1700000000000L,
            channelId = "reminders",
            category = "event",
            isOngoing = false,
            contentHash = "mockhash456"
        )

        val record = NotificationMapper.mapToRecord(parsed)
        assertEquals("Design Review Meeting", record.textContent)
    }
}
