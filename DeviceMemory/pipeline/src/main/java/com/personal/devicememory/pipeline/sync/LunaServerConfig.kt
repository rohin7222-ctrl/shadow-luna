package com.personal.devicememory.pipeline.sync

import android.content.Context
import java.net.URI

/**
 * Manages the local Luna Server URL configuration.
 * Enforces local LAN / loopback privacy boundaries without performing main-thread DNS lookups.
 */
object LunaServerConfig {

    private const val PREFS_NAME = "shadow_luna_server"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_AUTO_SYNC = "auto_sync_enabled"

    // Default local PC Wi-Fi address for instant setup
    const val DEFAULT_SERVER_URL = "http://192.168.31.91:8000"

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        return normalizeUrl(raw)
    }

    fun setServerUrl(context: Context, url: String): Result<String> {
        val normalized = normalizeUrl(url)
        if (!isLocalPrivateUrl(normalized)) {
            return Result.failure(
                IllegalArgumentException("Privacy lock: Only local Wi-Fi / LAN URLs (e.g. 192.168.x.x or localhost) are allowed.")
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, normalized)
            .apply()
        return Result.success(normalized)
    }

    fun isAutoSyncEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_SYNC, enabled)
            .apply()
    }

    fun normalizeUrl(raw: String): String {
        var trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return trimmed
    }

    /**
     * Checks purely in-memory (safe on UI thread) that the URL targets
     * only private LAN / loopback addresses so Shadow never leaks data externally.
     */
    fun isLocalPrivateUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2" || host.endsWith(".local")) {
                return true
            }
            val parts = host.split(".")
            if (parts.size == 4) {
                val octets = parts.map { it.toIntOrNull() ?: return false }
                if (octets.any { it !in 0..255 }) return false
                val a = octets[0]
                val b = octets[1]
                // RFC 1918 private + 127.0.0.0/8 loopback + 169.254.0.0/16 link-local
                return a == 10 ||
                    a == 127 ||
                    (a == 192 && b == 168) ||
                    (a == 172 && b in 16..31) ||
                    (a == 169 && b == 254)
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}
