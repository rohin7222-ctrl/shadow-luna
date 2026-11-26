package com.personal.devicememory.core.common.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonUtilTest {

    @Test
    fun nestedMapsListsAndNonFiniteNumbersSerializeAsValidJson() {
        val payload = mapOf<String, Any?>(
            "media_id" to 42L,
            "av_analysis" to mapOf(
                "motion_scene" to "Fast Motion",
                "v_jepa_novelty" to Double.NaN,
                "visual_latent" to listOf(0.25, 0.75)
            ),
            "tags" to listOf("வணக்கம்", "audio")
        )

        val rawJson = payload.toJsonString()
        val parsed = Json.parseToJsonElement(rawJson).jsonObject
        val avObj = parsed["av_analysis"]!!.jsonObject
        assertEquals("Fast Motion", avObj["motion_scene"]!!.jsonPrimitive.content)
        assertTrue(avObj["v_jepa_novelty"] is JsonNull)
        assertTrue(rawJson.contains("வணக்கம்"))
    }
}
