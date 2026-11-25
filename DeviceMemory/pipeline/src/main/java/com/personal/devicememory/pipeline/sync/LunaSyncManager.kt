package com.personal.devicememory.pipeline.sync

import android.content.Context
import android.util.Log
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class LunaSyncRecordDto(
    val id: String,
    val source_type: String,
    val timestamp: Long,
    val text_content: String? = null,
    val metadata_json: String? = "{}"
)

@Serializable
data class LunaTokenDto(
    val token: String,
    val frequency: Int,
    val last_source: String,
    val last_seen: Long
)

@Serializable
data class LunaSyncPayloadDto(
    val client_version: String = "Shadow-0.3.0",
    val records: List<LunaSyncRecordDto>,
    val tokens: List<LunaTokenDto> = emptyList()
)

@Serializable
data class LunaSyncResponseDto(
    val status: String = "",
    val synced_records: Int = 0,
    val synced_tokens: Int = 0,
    val total_received: Int = 0
)

@Serializable
data class HandshakeStatusDto(
    val laptop_ready: Boolean = false,
    val mobile_ready: Boolean = false,
    val phase: String = "IDLE",
    val total_records: Int = 0,
    val received_records: Int = 0,
    val received_tokens: Int = 0,
    val last_message: String = ""
)

@Serializable
data class HandshakeProgressDto(
    val total_records: Int,
    val received_records: Int,
    val received_tokens: Int
)

data class SyncResult(
    val success: Boolean,
    val syncedCount: Int,
    val syncedTokens: Int = 0,
    val remainingUnsynced: Int,
    val message: String
)

/**
 * Handles explicit 2-way Handshake transfer from Mobile Shadow Vault -> Laptop Luna Server Studio.
 * Zero background/automatic uploads occur outside explicit user handshake.
 */
object LunaSyncManager {

    private const val TAG = "LunaSyncManager"
    private const val BATCH_SIZE = 50
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun signalMobileReady(context: Context): Result<HandshakeStatusDto> = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Non-local URL blocked"))
        }
        try {
            val raw = requestJson("$serverUrl/api/handshake/mobile-ready", "POST", "{}")
            Result.success(json.decodeFromString<HandshakeStatusDto>(raw))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun fetchHandshakeStatus(context: Context): Result<HandshakeStatusDto> = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Non-local URL blocked"))
        }
        try {
            val raw = requestJson("$serverUrl/api/handshake/status", "GET", null)
            Result.success(json.decodeFromString<HandshakeStatusDto>(raw))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun cancelHandshake(context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) return@withContext
        try {
            requestJson("$serverUrl/api/handshake/cancel", "POST", "{}")
        } catch (_: Throwable) {}
    }

    suspend fun uploadLearningProgressWithHandshake(
        context: Context,
        onProgress: (uploadedRecords: Int, totalRecords: Int, uploadedTokens: Int) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
            return@withContext SyncResult(false, 0, 0, 0, "Blocked non-local URL")
        }

        val db = DeviceMemoryDatabase.getInstance(context)
        val recordDao = db.collectedRecordDao()
        val tokenDao = db.learnedTokenDao()

        try {
            val totalUnsynced = recordDao.countUnsynced()
            val allTokens = tokenDao.getTopTokens(500).map {
                LunaTokenDto(it.token, it.frequency, it.lastSource, it.lastSeen)
            }

            // Initialize handshake progress on server
            val initProg = json.encodeToString(HandshakeProgressDto(totalUnsynced, 0, 0))
            requestJson("$serverUrl/api/handshake/progress", "POST", initProg)
            onProgress(0, totalUnsynced, 0)

            var uploaded = 0
            var tokensSent = 0
            var tokensIncluded = false

            while (true) {
                val batch = recordDao.getUnsynced(BATCH_SIZE)
                if (batch.isEmpty()) {
                    // Still upload learned tokens snapshot even if all raw records were already sent
                    if (!tokensIncluded && allTokens.isNotEmpty()) {
                        val tokPayload = json.encodeToString(
                            LunaSyncPayloadDto(records = emptyList(), tokens = allTokens)
                        )
                        val raw = requestJson("$serverUrl/api/sync", "POST", tokPayload)
                        val resp = json.decodeFromString<LunaSyncResponseDto>(raw)
                        tokensSent = resp.synced_tokens.coerceAtLeast(allTokens.size)
                        tokensIncluded = true
                        onProgress(uploaded, totalUnsynced, tokensSent)
                    }
                    break
                }

                val dtoList = batch.map { entity ->
                    LunaSyncRecordDto(
                        id = entity.id,
                        source_type = entity.sourceType,
                        timestamp = entity.timestamp,
                        text_content = SensitiveContentFilter.redact(entity.textContent),
                        metadata_json = entity.metadataJson
                    )
                }

                val payloadJson = json.encodeToString(
                    LunaSyncPayloadDto(
                        records = dtoList,
                        tokens = if (!tokensIncluded) allTokens else emptyList()
                    )
                )
                val rawResp = requestJson("$serverUrl/api/sync", "POST", payloadJson)
                val resp = json.decodeFromString<LunaSyncResponseDto>(rawResp)

                if (resp.status == "SUCCESS") {
                    recordDao.markSynced(batch.map { it.id })
                    uploaded += batch.size
                    if (!tokensIncluded) {
                        tokensSent = allTokens.size
                        tokensIncluded = true
                    }
                    val progJson = json.encodeToString(HandshakeProgressDto(totalUnsynced, uploaded, tokensSent))
                    requestJson("$serverUrl/api/handshake/progress", "POST", progJson)
                    onProgress(uploaded, totalUnsynced, tokensSent)
                } else {
                    val remaining = recordDao.countUnsynced()
                    return@withContext SyncResult(false, uploaded, tokensSent, remaining, "Sync error: ${resp.status}")
                }
            }

            // Finalize session on laptop
            val finalJson = json.encodeToString(HandshakeProgressDto(totalUnsynced, uploaded, tokensSent))
            requestJson("$serverUrl/api/handshake/complete", "POST", finalJson)

            val remaining = recordDao.countUnsynced()
            SyncResult(
                success = true,
                syncedCount = uploaded,
                syncedTokens = tokensSent,
                remainingUnsynced = remaining,
                message = "Uploaded $uploaded memories & $tokensSent tokens to Laptop permanently"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Handshake upload failed", e)
            val remaining = try { recordDao.countUnsynced() } catch (_: Throwable) { 0 }
            SyncResult(false, 0, 0, remaining, e.message ?: "Upload interrupted")
        }
    }

    private fun requestJson(endpointUrl: String, method: String, body: String?): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 2500
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
            }

            if (body != null) {
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                    writer.flush()
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $text")
            }
            return text
        } finally {
            conn?.disconnect()
        }
    }
}
