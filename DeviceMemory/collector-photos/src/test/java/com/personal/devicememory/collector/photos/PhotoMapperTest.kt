package com.personal.devicememory.collector.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMapperTest {

    @Test
    fun testDetectScreenshotByName() {
        assertTrue(PhotoMapper.isScreenshot("Screenshot_20261124_103015.png", "DCIM"))
        assertTrue(PhotoMapper.isScreenshot("screencap_01.jpg", null))
        assertFalse(PhotoMapper.isScreenshot("IMG_20261124_103015.jpg", "Camera"))
    }

    @Test
    fun testDetectScreenshotByBucket() {
        assertTrue(PhotoMapper.isScreenshot("image_123.jpg", "Screenshots"))
        assertTrue(PhotoMapper.isScreenshot("capture.png", "ScreenCapture"))
        assertFalse(PhotoMapper.isScreenshot("photo.jpg", "FamilyTrip"))
    }

    @Test
    fun testPhotoRecordMapping() {
        val item = PhotoItem(
            mediaId = 101L,
            displayName = "IMG_2026.jpg",
            bucketName = "Camera",
            mimeType = "image/jpeg",
            dateTaken = 1700000000000L,
            dateAdded = 1700000001000L,
            sizeBytes = 4500000L,
            width = 4000,
            height = 3000,
            isScreenshot = false
        )

        val record = PhotoMapper.map(item)

        assertEquals("Photo: Camera - IMG_2026.jpg", record.textContent)
        assertEquals(1700000000000L, record.timestamp)
        assertTrue(record.metadataJson.contains("\"media_id\":101"))
        assertTrue(record.metadataJson.contains("\"is_screenshot\":false"))
    }

    @Test
    fun testScreenshotRecordMapping() {
        val item = PhotoItem(
            mediaId = 202L,
            displayName = "Screenshot_receipt.png",
            bucketName = "Screenshots",
            mimeType = "image/png",
            dateTaken = 0L, // fallback to dateAdded
            dateAdded = 1700005000000L,
            sizeBytes = 1200000L,
            width = 1080,
            height = 2400,
            isScreenshot = true
        )

        val record = PhotoMapper.map(item)

        assertEquals("Screenshot: Screenshot_receipt.png", record.textContent)
        assertEquals(1700005000000L, record.timestamp)
        assertTrue(record.metadataJson.contains("\"is_screenshot\":true"))
    }
}
