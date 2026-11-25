package com.personal.devicememory.pipeline

import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionWorkerIdentityTest {

    @Test
    fun deterministicUuidDeduplicatesIdenticalRecords() {
        val recA = CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = 1_750_000_000_000L,
            textContent = "Photo: Camera - IMG_1.jpg",
            metadataJson = """{"media_id":42,"date_added":1795610000000}"""
        )
        val recB = CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = 1_750_000_000_000L,
            textContent = "Photo: Camera - IMG_1.jpg",
            metadataJson = """{"media_id":42,"date_added":1795610000000}"""
        )
        val recOther = recA.copy(metadataJson = """{"media_id":43,"date_added":1795610000000}""")

        assertEquals(CollectionWorker.stableRecordUuid(recA), CollectionWorker.stableRecordUuid(recB))
        assertNotEquals(CollectionWorker.stableRecordUuid(recA), CollectionWorker.stableRecordUuid(recOther))
    }

    @Test
    fun bookmarkTimestampHandlesOldExifFutureExifAndUpcomingCalendar() {
        val now = 1_795_612_000_000L
        val downloadedOldExif = CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = now - 30L * 86_400_000L,
            textContent = "Screenshot: old_exif.jpg",
            metadataJson = """{"media_id":1,"date_added":${now - 10_000L}}"""
        )
        val bogusFutureExif = CollectedRecord(
            sourceType = SourceType.PHOTO,
            timestamp = 4_070_908_800_000L,
            textContent = "Photo: bad_exif.jpg",
            metadataJson = """{"media_id":2,"date_added":$now}"""
        )

        val photoBookmarkOld = CollectionWorker.computeSafeBookmarkTimestamp(
            SourceType.PHOTO, listOf(downloadedOldExif), 0L, now
        )
        assertEquals(now - 10_000L, photoBookmarkOld)

        val photoBookmarkFuture = CollectionWorker.computeSafeBookmarkTimestamp(
            SourceType.PHOTO, listOf(bogusFutureExif), 0L, now
        )
        assertTrue(photoBookmarkFuture <= now + 60_000L)

        val farFutureEvent = CollectedRecord(
            sourceType = SourceType.CALENDAR_EVENT,
            timestamp = now + 30L * 86_400_000L,
            textContent = "Event: Next Month",
            metadataJson = """{"event_id":99}"""
        )
        val calBookmark = CollectionWorker.computeSafeBookmarkTimestamp(
            SourceType.CALENDAR_EVENT, listOf(farFutureEvent), 0L, now
        )
        assertEquals(now - 86_400_000L, calBookmark)
    }
}
