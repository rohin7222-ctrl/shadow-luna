package com.personal.devicememory.pipeline.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection

@Serializable
data class LunaUpdateItemDto(
    val version: String,
    val title: String,
    val changelog: String,
    val released_at: Long,
    val released_at_display: String = "",
    val estimated_seconds: Int = 12,
    val code_hash: String? = null
)

@Serializable
data class LunaUpdatesResponseDto(
    val installed_version: String = "",
    val latest_version: String = "",
    val has_update: Boolean = false,
    val latest_update: LunaUpdateItemDto? = null,
    val updates: List<LunaUpdateItemDto> = emptyList()
)

@Serializable
data class LunaServerStateDto(
    val total_memories: Int = 0,
    val learned_vocab_size: Int = 0
)

/**
 * Fetches server-published updates, downloads OTA bundles, checks Luna learned count,
 * and triggers server brain reset when requested — bound cleanly over active Wi-Fi.
 */
object LunaUpdatesClient {

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun resolveUrl(context: Context): String {
        val saved = LunaServerConfig.getServerUrl(context)
        if (LunaSyncManager.pingServer(context, saved, 800)) return saved
        return LunaSyncManager.discoverServerOnLan(context) ?: saved
    }

    suspend fun fetchUpdates(context: Context, installedVersion: String): Result<LunaUpdatesResponseDto> =
        withContext(Dispatchers.IO) {
            val serverUrl = resolveUrl(context)
            if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
                return@withContext Result.failure(IllegalStateException("Non-local server blocked"))
            }
            var conn: HttpURLConnection? = null
            try {
                val url = "$serverUrl/api/updates?installed_version=${java.net.URLEncoder.encode(installedVersion, "UTF-8")}"
                conn = LunaSyncManager.openLocalConnection(context, url).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
                if (code !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("HTTP $code: $text"))
                }
                Result.success(json.decodeFromString<LunaUpdatesResponseDto>(text))
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun downloadUpdateBundle(context: Context, version: String): Result<File> =
        withContext(Dispatchers.IO) {
            val serverUrl = resolveUrl(context)
            if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
                return@withContext Result.failure(IllegalStateException("Non-local server blocked"))
            }
            var conn: HttpURLConnection? = null
            try {
                val url = "$serverUrl/api/updates/bundle/${java.net.URLEncoder.encode(version, "UTF-8")}"
                conn = LunaSyncManager.openLocalConnection(context, url).apply {
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 8000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } } ?: ""
                if (code !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("HTTP $code: $text"))
                }
                val safeVersion = version.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
                val dir = File(context.filesDir, "ota_bundles").apply { mkdirs() }
                val outFile = File(dir, "bundle_$safeVersion.json")
                outFile.writeText(text)
                Result.success(outFile)
            } catch (e: Throwable) {
                Result.failure(e)
            } finally {
                conn?.disconnect()
            }
        }

    suspend fun fetchServerMemoryCount(context: Context): Int? = withContext(Dispatchers.IO) {
        val serverUrl = resolveUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            conn = LunaSyncManager.openLocalConnection(context, "$serverUrl/api/state").apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 3000
            }
            if (conn.responseCode in 200..299) {
                val text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
                json.decodeFromString<LunaServerStateDto>(text).total_memories
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun resetServerBrain(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        val serverUrl = resolveUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
            return@withContext Result.failure(IllegalStateException("Non-local server blocked"))
        }
        var conn: HttpURLConnection? = null
        try {
            conn = LunaSyncManager.openLocalConnection(context, "$serverUrl/api/reset-brain").apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
            }
            conn.outputStream.close()
            if (conn.responseCode in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException("HTTP ${conn.responseCode}"))
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
