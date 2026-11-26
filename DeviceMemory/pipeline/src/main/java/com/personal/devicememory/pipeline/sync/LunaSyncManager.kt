package com.personal.devicememory.pipeline.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.personal.devicememory.core.common.crypto.DerivedSyncKeys
import com.personal.devicememory.core.common.crypto.SyncCryptoUtil
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
data class LunaVectorDto(
    val concept: String,
    val vector_csv: String,
    val fisher_importance: Double = 1.0,
    val surprise_score: Double = 0.2
)

@Serializable
data class LunaTransitionDto(
    val from_concept: String,
    val to_concept: String,
    val stdp_weight: Double,
    val transition_count: Int
)

@Serializable
data class LunaEpisodeDto(
    val episode_id: String,
    val title_label: String,
    val start_millis: Long,
    val end_millis: Long,
    val primary_place: String = "",
    val dominant_sources: String = "",
    val key_concepts: String = "",
    val record_count: Int = 1,
    val narrative_summary: String
)

@Serializable
data class LunaSyncPayloadDto(
    val client_version: String = "Shadow-0.8.0",
    val records: List<LunaSyncRecordDto>,
    val tokens: List<LunaTokenDto> = emptyList(),
    val vectors: List<LunaVectorDto> = emptyList(),
    val transitions: List<LunaTransitionDto> = emptyList(),
    val episodes: List<LunaEpisodeDto> = emptyList()
)

@Serializable
data class EncryptedSyncWrapperDto(
    val encrypted_payload: String,
    val iv: String,
    val hmac_tag: String,
    val mobile_pubkey: String
)

@Serializable
data class HandshakeMobileReadyDto(
    val mobile_pubkey: String? = null
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
    val last_message: String = "",
    val laptop_pubkey: String? = null,
    val session_salt: String? = null
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

    @Volatile private var activeKeyPair: java.security.KeyPair? = null
    @Volatile private var activeMobilePubB64: String? = null
    @Volatile private var activeE2eeKeys: DerivedSyncKeys? = null

    private fun ensureSessionKeys() {
        if (activeKeyPair == null) {
            try {
                val kp = SyncCryptoUtil.generateEphemeralKeyPair()
                activeKeyPair = kp
                activeMobilePubB64 = SyncCryptoUtil.encodePublicKeyB64(kp.public)
            } catch (e: Throwable) {
                Log.w(TAG, "ECDH keypair generation unavailable: ${e.message}")
            }
        }
    }

    private fun maybeDerivePeerKeys(dto: HandshakeStatusDto) {
        val kp = activeKeyPair ?: return
        val peerB64 = dto.laptop_pubkey?.takeIf { it.isNotBlank() } ?: return
        val salt = dto.session_salt?.takeIf { it.isNotBlank() } ?: return
        try {
            val peerPub = SyncCryptoUtil.decodePublicKeyB64(peerB64)
            val shared = SyncCryptoUtil.computeSharedSecret(kp.private, peerPub)
            activeE2eeKeys = SyncCryptoUtil.deriveSessionKeys(shared, salt)
        } catch (e: Throwable) {
            Log.w(TAG, "ECDH session derivation skipped: ${e.message}")
            activeE2eeKeys = null
        }
    }

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
        ensureSessionKeys()
        try {
            val reqBody = json.encodeToString(HandshakeMobileReadyDto(mobile_pubkey = activeMobilePubB64))
            val raw = requestJson(context, "$serverUrl/api/handshake/mobile-ready", "POST", reqBody)
            val dto = json.decodeFromString<HandshakeStatusDto>(raw)
            maybeDerivePeerKeys(dto)
            Result.success(dto)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun cancelHandshake(context: Context) = withContext(Dispatchers.IO) {
        activeKeyPair = null
        activeMobilePubB64 = null
        activeE2eeKeys = null
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
        val brainDao = db.brainSimulationDao()

        var uploaded = 0
        var tokensSent = 0
        var neuralIncluded = false

        fun maybeEncryptPayload(rawJson: String): String {
            val keys = activeE2eeKeys
            val pub = activeMobilePubB64
            if (keys == null || pub.isNullOrBlank()) return rawJson
            val env = SyncCryptoUtil.encrypt(rawJson.toByteArray(Charsets.UTF_8), keys)
            return json.encodeToString(
                EncryptedSyncWrapperDto(
                    encrypted_payload = env.cipherTextB64,
                    iv = env.ivB64,
                    hmac_tag = env.hmacB64,
                    mobile_pubkey = pub
                )
            )
        }

        try {
            val totalUnsynced = recordDao.countUnsynced()
            val allTokens = tokenDao.getTopTokens(2000).map {
                LunaTokenDto(it.token, it.frequency, it.lastSource, it.lastSeen)
            }
            val syncVectors = brainDao.getAllVectors(400).map {
                LunaVectorDto(it.concept, it.vectorCsv, it.fisherImportance, it.surpriseScore)
            }
            val syncTransitions = brainDao.getTopCausalTransitions(150).map {
                LunaTransitionDto(it.fromConcept, it.toConcept, it.stdpWeight, it.transitionCount)
            }
            val syncEpisodes = brainDao.getRecentEpisodes(80).map {
                LunaEpisodeDto(
                    episode_id = it.episodeId,
                    title_label = it.titleLabel,
                    start_millis = it.startMillis,
                    end_millis = it.endMillis,
                    primary_place = it.primaryPlace,
                    dominant_sources = it.dominantSources,
                    key_concepts = it.keyConcepts,
                    record_count = it.recordCount,
                    narrative_summary = it.narrativeSummary
                )
            }

            val initProg = json.encodeToString(HandshakeProgressDto(totalUnsynced, 0, 0))
            requestJson(context, "$serverUrl/api/handshake/progress", "POST", initProg)
            onProgress(0, totalUnsynced, 0)

            while (true) {
                val batch = recordDao.getUnsynced(BATCH_SIZE)
                if (batch.isEmpty()) {
                    if (!neuralIncluded && (allTokens.isNotEmpty() || syncVectors.isNotEmpty() || syncEpisodes.isNotEmpty())) {
                        val tokPayload = json.encodeToString(
                            LunaSyncPayloadDto(
                                records = emptyList(),
                                tokens = allTokens,
                                vectors = syncVectors,
                                transitions = syncTransitions,
                                episodes = syncEpisodes
                            )
                        )
                        val raw = requestJson(context, "$serverUrl/api/sync", "POST", maybeEncryptPayload(tokPayload))
                        val resp = json.decodeFromString<LunaSyncResponseDto>(raw)
                        tokensSent = resp.synced_tokens.coerceAtLeast(allTokens.size)
                        neuralIncluded = true
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
                        tokens = if (!neuralIncluded) allTokens else emptyList(),
                        vectors = if (!neuralIncluded) syncVectors else emptyList(),
                        transitions = if (!neuralIncluded) syncTransitions else emptyList(),
                        episodes = if (!neuralIncluded) syncEpisodes else emptyList()
                    )
                )
                val rawResp = requestJson(context, "$serverUrl/api/sync", "POST", maybeEncryptPayload(payloadJson))
                val resp = json.decodeFromString<LunaSyncResponseDto>(rawResp)

                if (resp.status == "SUCCESS") {
                    recordDao.markSynced(batch.map { it.id })
                    uploaded += batch.size
                    if (!neuralIncluded) {
                        tokensSent = allTokens.size
                        neuralIncluded = true
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

            val e2eeLabel = if (activeE2eeKeys != null) " (E2EE P-256+AES256)" else ""
            val remaining = recordDao.countUnsynced()
            SyncResult(
                success = true,
                syncedCount = uploaded,
                syncedTokens = tokensSent,
                remainingUnsynced = remaining,
                message = "Uploaded $uploaded memories, $tokensSent tokens, ${syncVectors.size} vecs & ${syncEpisodes.size} episodes$e2eeLabel"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Handshake upload failed", e)
            val remaining = try { recordDao.countUnsynced() } catch (_: Throwable) { 0 }
            SyncResult(false, uploaded, tokensSent, remaining, e.message ?: "Upload interrupted")
        } finally {
            activeKeyPair = null
            activeMobilePubB64 = null
            activeE2eeKeys = null
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
