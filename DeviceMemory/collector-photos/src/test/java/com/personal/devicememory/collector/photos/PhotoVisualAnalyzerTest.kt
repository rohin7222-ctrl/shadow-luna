package com.personal.devicememory.collector.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoVisualAnalyzerTest {

    @Test
    fun `detects document high contrast grid vs outdoor uniform brightness and maps json cleanly`() {
        // High-contrast striped document pattern
        val docGrid = FloatArray(32 * 32) { idx -> if ((idx % 4) < 2) 0.95f else 0.05f }
        val docRes = PhotoVisualAnalyzer.analyzeGrid(32, 32, docGrid, 0.5, 0.5, 0.5)
        assertNotNull(docRes)
        assertTrue(docRes!!.isDocumentOrReceipt)
        assertTrue(docRes.visualCode.matches(Regex("vis_\\d{3}")))
        assertEquals(16, docRes.visualLatent16.size)

        // Bright nature scene
        val brightGrid = FloatArray(32 * 32) { 0.78f }
        val brightRes = PhotoVisualAnalyzer.analyzeGrid(32, 32, brightGrid, 0.4, 0.78, 0.45)
        assertNotNull(brightRes)
        assertEquals("Bright / Outdoor", brightRes!!.lightingScene)
        assertEquals("Green / Nature", brightRes.colorTone)

        val record = PhotoMapper.map(
            PhotoItem(
                mediaId = 55L,
                displayName = "Screenshot_20261126_GPay.png",
                bucketName = "Screenshots",
                mimeType = "image/png",
                dateTaken = 1_760_000_000_000L,
                dateAdded = 1_760_000_000_000L,
                sizeBytes = 120_000L,
                width = 1080,
                height = 2400,
                isScreenshot = true,
                visual = docRes,
                ocrText = "GPay Electricity Bill Paid [AMOUNT]"
            )
        )
        val txt = record.textContent ?: ""
        assertTrue(txt.contains("Document/Text"))
        assertTrue(txt.contains("OCR: GPay Electricity Bill Paid"))
        assertTrue(record.metadataJson?.contains("\"visual_analysis\":{") == true)
    }
}
