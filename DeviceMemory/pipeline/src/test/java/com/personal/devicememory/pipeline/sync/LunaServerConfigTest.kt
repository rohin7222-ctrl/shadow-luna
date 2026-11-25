package com.personal.devicememory.pipeline.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaServerConfigTest {

    @Test
    fun normalizeUrlAddsHttpAndStripsTrailingSlash() {
        assertEquals("http://192.168.31.91:8000", LunaServerConfig.normalizeUrl("192.168.31.91:8000/"))
        assertEquals("http://localhost:8000", LunaServerConfig.normalizeUrl(" http://localhost:8000// "))
        assertEquals(LunaServerConfig.DEFAULT_SERVER_URL, LunaServerConfig.normalizeUrl("   "))
    }

    @Test
    fun isLocalPrivateUrlAllowsOnlyLanAndLoopback() {
        assertTrue(LunaServerConfig.isLocalPrivateUrl("http://192.168.31.91:8000"))
        assertTrue(LunaServerConfig.isLocalPrivateUrl("http://10.0.0.15:8000"))
        assertTrue(LunaServerConfig.isLocalPrivateUrl("http://172.16.5.20:8000"))
        assertTrue(LunaServerConfig.isLocalPrivateUrl("http://127.0.0.1:8000"))
        assertTrue(LunaServerConfig.isLocalPrivateUrl("http://localhost:8000"))

        assertFalse(LunaServerConfig.isLocalPrivateUrl("https://evil.example.com"))
        assertFalse(LunaServerConfig.isLocalPrivateUrl("http://8.8.8.8:8000"))
        assertFalse(LunaServerConfig.isLocalPrivateUrl("http://172.32.0.1:8000"))
        assertFalse(LunaServerConfig.isLocalPrivateUrl("not-a-url"))
    }
}
