package com.personal.devicememory.pipeline.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections

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
    val client_version: String = "Shadow-0.3.1",
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

data class HandshakeProbeResult(
    val status: HandshakeStatusDto?,
    val resolvedUrl: String,
    val diagnosticError: String? = null
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
 * Binds local LAN traffic to active Wi-Fi transport so 5G/cellular data never hijacks LAN IPs,
 * and auto-discovers the Laptop on the same Wi-Fi subnet or hotspot if DHCP IP changed.
 */
object LunaSyncManager {

    private const val TAG = "LunaSyncManager"
    private const val BATCH_SIZE = 50
    private const val DISCOVERY_UDP_PORT = 8800
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Suppress("DEPRECATION")
    private fun findWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return active
        }
        return cm.allNetworks.firstOrNull { net ->
            cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun openLocalConnection(context: Context, endpointUrl: String): HttpURLConnection {
        val url = URL(endpointUrl)
        val host = url.host?.lowercase() ?: ""
        // Never bind loopback / USB ADB reverse traffic through Wi-Fi transport
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") {
            val wifiNet = findWifiNetwork(context)
            if (wifiNet != null) {
                try {
                    return wifiNet.openConnection(url) as HttpURLConnection
                } catch (_: Throwable) {
                    // Fall back to default OS routing (e.g. Hotspot AP interface)
                }
            }
        }
        return url.openConnection() as HttpURLConnection
    }

    private fun getLocalLanIpsAndPrefixes(): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val parts = ip.split(".")
                        if (parts.size == 4) {
                            val a = parts[0].toIntOrNull() ?: continue
                            val b = parts[1].toIntOrNull() ?: continue
                            if (a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)) {
                                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                                result.add(ip to prefix)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return result
    }

    fun pingServer(context: Context, baseUrl: String, timeoutMs: Int = 900): Boolean {
        if (!LunaServerConfig.isLocalPrivateUrl(baseUrl)) return false
        var conn: HttpURLConnection? = null
        return try {
            conn = openLocalConnection(context, "$baseUrl/api/ping").apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).readText()
                body.contains("shadow-luna")
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Automatically locates Shadow • Luna Laptop Server on active Wi-Fi or Hotspot:
     * 1) Checks saved URL
     * 2) Races UDP discovery broadcast (:8800) + parallel TCP /24 subnet sweep (:8000/api/ping)
     */
    suspend fun discoverServerOnLan(context: Context): String? = withContext(Dispatchers.IO) {
        val current = LunaServerConfig.getServerUrl(context)
        if (pingServer(context, current, 900)) {
            return@withContext current
        }

        // Fast-check USB ADB reverse loopback (bypasses router AP isolation completely)
        if (current != "http://127.0.0.1:8000" && pingServer(context, "http://127.0.0.1:8000", 250)) {
            LunaServerConfig.setServerUrl(context, "http://127.0.0.1:8000")
            return@withContext "http://127.0.0.1:8000"
        }

        val ipsAndPrefixes = getLocalLanIpsAndPrefixes()
        val ownIps = ipsAndPrefixes.map { it.first }.toSet()
        val prefixes = ipsAndPrefixes.map { it.second }.distinct()
        if (prefixes.isEmpty()) return@withContext null

        val found = CompletableDeferred<String?>()

        val discovered = coroutineScope {
            val searchJob = launch {
                // Path 1: UDP broadcast beacon on port 8800
                val udpJob = async {
                    var sock: DatagramSocket? = null
                    try {
                        sock = DatagramSocket()
                        findWifiNetwork(context)?.bindSocket(sock)
                        sock.broadcast = true
                        sock.soTimeout = 1000
                        val probe = "SHADOW_DISCOVER_V1".toByteArray(Charsets.UTF_8)
                        val targets = (listOf("255.255.255.255") + prefixes.map { "$it.255" }).distinct()
                        for (t in targets) {
                            try {
                                sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName(t), DISCOVERY_UDP_PORT))
                            } catch (_: Throwable) {}
                        }
                        val buf = ByteArray(512)
                        val respPkt = DatagramPacket(buf, buf.size)
                        sock.receive(respPkt)
                        val msg = String(respPkt.data, 0, respPkt.length, Charsets.UTF_8)
                        if (msg.contains("shadow-luna")) {
                            val ip = respPkt.address.hostAddress
                            if (!ip.isNullOrBlank()) {
                                val candidate = "http://$ip:8000"
                                if (pingServer(context, candidate, 800)) {
                                    found.complete(candidate)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        try { sock?.close() } catch (_: Throwable) {}
                    }
                }

                // Path 2: Parallel bounded /24 TCP ping sweep
                val sem = Semaphore(48)
                val scanJobs = prefixes.flatMap { prefix ->
                    (1..254).map { host ->
                        val ip = "$prefix.$host"
                        async {
                            if (found.isCompleted || ip in ownIps) return@async
                            sem.withPermit {
                                if (found.isCompleted) return@withPermit
                                val candidate = "http://$ip:8000"
                                if (pingServer(context, candidate, 650)) {
                                    found.complete(candidate)
                                }
                            }
                        }
                    }
                }

                awaitAll(udpJob, *scanJobs.toTypedArray())
                if (!found.isCompleted) found.complete(null)
            }

            // Return immediately on first discovery and cancel any remaining socket waits
            found.await().also { if (it != null) searchJob.cancel() }
        }
        if (discovered != null) {
            LunaServerConfig.setServerUrl(context, discovered)
        }
        discovered
    }

    suspend fun discoverAndSignalMobileReady(context: Context): HandshakeProbeResult = withContext(Dispatchers.IO) {
        var serverUrl = LunaServerConfig.getServerUrl(context)
        val firstTry = signalMobileReady(context)
        firstTry.getOrNull()?.let { dto ->
            return@withContext HandshakeProbeResult(dto, serverUrl, null)
        }

        // First attempt failed -> scan LAN via UDP + /24 subnet sweep bound to Wi-Fi
        val discovered = discoverServerOnLan(context)
        if (discovered != null) {
            serverUrl = discovered
            val retry = signalMobileReady(context)
            retry.getOrNull()?.let { dto ->
                return@withContext HandshakeProbeResult(dto, serverUrl, null)
            }
        }

        val ips = getLocalLanIpsAndPrefixes().map { it.first }
        val phoneNetHint = if (ips.isEmpty()) {
            "Phone is not connected to Wi-Fi or Hotspot."
        } else {
            "Phone Wi-Fi IP: ${ips.joinToString(", ")}. Laptop blocked or isolated at $serverUrl — approve Firewall on Laptop, or use Mobile Hotspot / USB cable if your router has AP Isolation."
        }
        HandshakeProbeResult(null, serverUrl, phoneNetHint)
    }

    suspend fun signalMobileReady(context: Context): Result<HandshakeStatusDto> = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) {
            return@withContext Result.failure(IllegalArgumentException("Non-local URL blocked"))
        }
        try {
            val raw = requestJson(context, "$serverUrl/api/handshake/mobile-ready", "POST", "{}")
            Result.success(json.decodeFromString<HandshakeStatusDto>(raw))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun cancelHandshake(context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = LunaServerConfig.getServerUrl(context)
        if (!LunaServerConfig.isLocalPrivateUrl(serverUrl)) return@withContext
        try {
            requestJson(context, "$serverUrl/api/handshake/cancel", "POST", "{}")
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

        var uploaded = 0
        var tokensSent = 0
        var tokensIncluded = false

        try {
            val totalUnsynced = recordDao.countUnsynced()
            val allTokens = tokenDao.getTopTokens(2000).map {
                LunaTokenDto(it.token, it.frequency, it.lastSource, it.lastSeen)
            }

            val initProg = json.encodeToString(HandshakeProgressDto(totalUnsynced, 0, 0))
            requestJson(context, "$serverUrl/api/handshake/progress", "POST", initProg)
            onProgress(0, totalUnsynced, 0)

            while (true) {
                val batch = recordDao.getUnsynced(BATCH_SIZE)
                if (batch.isEmpty()) {
                    if (!tokensIncluded && allTokens.isNotEmpty()) {
                        val tokPayload = json.encodeToString(
                            LunaSyncPayloadDto(records = emptyList(), tokens = allTokens)
                        )
                        val raw = requestJson(context, "$serverUrl/api/sync", "POST", tokPayload)
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
                val rawResp = requestJson(context, "$serverUrl/api/sync", "POST", payloadJson)
                val resp = json.decodeFromString<LunaSyncResponseDto>(rawResp)

                if (resp.status == "SUCCESS") {
                    recordDao.markSynced(batch.map { it.id })
                    uploaded += batch.size
                    if (!tokensIncluded) {
                        tokensSent = allTokens.size
                        tokensIncluded = true
                    }
                    val progJson = json.encodeToString(HandshakeProgressDto(totalUnsynced, uploaded, tokensSent))
                    requestJson(context, "$serverUrl/api/handshake/progress", "POST", progJson)
                    onProgress(uploaded, totalUnsynced, tokensSent)
                } else {
                    val remaining = recordDao.countUnsynced()
                    return@withContext SyncResult(false, uploaded, tokensSent, remaining, "Sync error: ${resp.status}")
                }
            }

            val finalJson = json.encodeToString(HandshakeProgressDto(totalUnsynced, uploaded, tokensSent))
            requestJson(context, "$serverUrl/api/handshake/complete", "POST", finalJson)

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
            SyncResult(false, uploaded, tokensSent, remaining, e.message ?: "Upload interrupted")
        }
    }

    private fun requestJson(context: Context, endpointUrl: String, method: String, body: String?): String {
        var conn: HttpURLConnection? = null
        try {
            conn = openLocalConnection(context, endpointUrl).apply {
                requestMethod = method
                connectTimeout = 2000
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
