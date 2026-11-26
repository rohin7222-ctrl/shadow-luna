package com.personal.devicememory.pipeline.brain

import android.content.Context
import android.util.Log
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.text.TemporalAnchorExtractor
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.BrainInsightEntity
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.SimulationStatsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class SimulationSnapshot(
    val isSimulating: Boolean = false,
    val totalPasses: Int = 0,
    val associationsCount: Int = 0,
    val insightsCount: Int = 0,
    val lastBatchSize: Int = 0,
    val neuralVectorsCount: Int = 0,
    val selfPlayAccuracyPct: Int = 0,
    val avgSurprise: Double = 0.0,
    val topPolicyStrategy: String = "",
    val jepaAccuracyPct: Int = 0,
    val avgFreeEnergy: Double = 0.0,
    val curiosityTarget: String = "",
    val causalTransitionsCount: Int = 0,
    val topCausalChain: String = "",
    val episodesCount: Int = 0,
    val topSimulatedConcepts: List<String> = emptyList()
)

/**
 * Continuous On-Device Memory Consolidation & Self-Simulation Engine.
 * While the app remains open (until user closes the app tab from recents),
 * it repeatedly replays sliding time-windows of stored encrypted records to build:
 *   1) Cross-source co-occurrence & temporal graph weights (`learned_associations`)
 *   2) High-level behavioral habit & topic profiles (`brain_insights`)
 * Zero pre-built conversational data is ever injected; everything derives strictly from user memories.
 */
object LunaMemorySimulator {

    private const val TAG = "LunaMemorySimulator"
    private const val PASS_BATCH_SIZE = 90
    private const val TEMPORAL_WINDOW_MS = 30 * 60 * 1000L // 30 minutes cross-app context window

    private val NOISE_CONCEPTS = setOf(
        "com", "sec", "android", "app", "org", "net", "internal", "provider",
        "photo", "video", "audio", "screenshot", "screenshots", "img", "vid",
        "jpg", "jpeg", "png", "heic", "webp", "mp4", "mkv", "mp3", "m4a", "ogg",
        "sending", "file", "from", "with", "this", "that", "have", "your", "the",
        "and", "for", "are", "you", "new", "messages", "message", "chats", "chat",
        "dcim", "emulated", "storage", "download", "downloads", "metadata", "null", "true", "false",
        "vad", "hz", "kbps", "dominant", "mix", "ocr"
    )

    private val CONCEPT_WORD_REGEX = Regex("""[\p{L}\p{M}]{2,32}""")

    private val simScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simJob: Job? = null
    private val foregroundActive = AtomicBoolean(true)
    private val passMutex = Mutex()

    private val _snapshot = MutableStateFlow(SimulationSnapshot())
    val snapshot: StateFlow<SimulationSnapshot> = _snapshot.asStateFlow()

    fun setForegroundActive(active: Boolean) {
        foregroundActive.set(active)
    }

    fun startSimulationLoop(context: Context) {
        val appContext = context.applicationContext
        if (simJob?.isActive == true) return
        simJob = simScope.launch {
            // Hydrate initial persisted stats immediately
            refreshSnapshotFromDb(appContext, running = true)
            while (isActive) {
                try {
                    val updated = runSinglePass(appContext)
                    _snapshot.value = updated.copy(isSimulating = isActive)
                } catch (t: Throwable) {
                    Log.w(TAG, "Simulation pass warning", t)
                }
                val pauseMs = if (foregroundActive.get()) 6000L else 15000L
                delay(pauseMs)
            }
        }
    }

    fun stopSimulationLoop() {
        simJob?.cancel()
        simJob = null
        _snapshot.value = _snapshot.value.copy(isSimulating = false)
    }

    suspend fun stopSimulationLoopAndWait() {
        val activeJob = simJob
        simJob = null
        activeJob?.cancelAndJoin()
        passMutex.withLock {
            _snapshot.value = SimulationSnapshot(isSimulating = false)
        }
    }

    suspend fun refreshSnapshotFromDb(context: Context, running: Boolean = simJob?.isActive == true): SimulationSnapshot {
        val db = DeviceMemoryDatabase.getInstance(context)
        val brainDao = db.brainSimulationDao()
        val stats = brainDao.getStats()
        val policy = brainDao.getPolicyState()
        val world = brainDao.getWorldModelState()
        val top = brainDao.getTopInsights(5).map { it.concept }
        val acc = if (policy != null && policy.selfPlayTotal > 0) {
            ((policy.selfPlayCorrect.toDouble() / policy.selfPlayTotal) * 100.0).toInt().coerceIn(0, 100)
        } else 0
        val topCausal = brainDao.getTopCausalTransitions(1).firstOrNull()
        val causalChain = topCausal?.let { "${it.fromConcept} → ${it.toConcept}" } ?: ""
        val snap = SimulationSnapshot(
            isSimulating = running,
            totalPasses = stats?.totalPasses ?: 0,
            associationsCount = stats?.associationsCount ?: brainDao.countAssociations(),
            insightsCount = stats?.insightsCount ?: brainDao.countInsights(),
            lastBatchSize = stats?.lastBatchRecords ?: 0,
            neuralVectorsCount = brainDao.countVectors(),
            selfPlayAccuracyPct = acc,
            avgSurprise = policy?.avgSurprise ?: 0.0,
            topPolicyStrategy = policy?.let { formatTopPolicy(it) } ?: "",
            jepaAccuracyPct = world?.jepaAccuracyPct ?: 0,
            avgFreeEnergy = world?.avgFreeEnergy ?: 0.0,
            curiosityTarget = world?.curiosityTarget ?: "",
            causalTransitionsCount = brainDao.countCausalTransitions(),
            topCausalChain = causalChain,
            episodesCount = brainDao.countEpisodes(),
            topSimulatedConcepts = top
        )
        _snapshot.value = snap
        return snap
    }

    private fun formatTopPolicy(p: com.personal.devicememory.core.database.entity.NeuralPolicyStateEntity): String {
        val entries = listOf(
            "Exact" to p.weightExact,
            "Graph" to p.weightGraph,
            "Vector" to p.weightVector,
            "Temporal" to p.weightTemporal
        )
        val best = entries.maxByOrNull { it.second } ?: return ""
        return "${best.first} ×${String.format(java.util.Locale.US, "%.1f", best.second)}"
    }

    /**
     * Extracts clean, meaningful concepts (splitting package paths & underscores,
     * stripping raw numeric dates/timestamps like 20250811_150835 and boilerplate noise).
     */
    fun extractSemanticConcepts(sourceType: String, text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val safe = (SensitiveContentFilter.redact(text) ?: "").lowercase()
        val out = LinkedHashSet<String>()
        for (match in CONCEPT_WORD_REGEX.findAll(safe)) {
            val w = match.value.trim('_')
            if (w.length < 3 && !w.any { it.code in 0x0B80..0x0BFF }) continue
            if (w in NOISE_CONCEPTS) continue
            out.add(w)
            if (out.size >= 14) break
        }
        // Always include clean app/domain cue if present in package name like com.sec.android.app.camera
        if (sourceType == "APP_USAGE") {
            val lastSeg = safe.substringAfterLast('.').trim()
            if (lastSeg.length >= 2 && lastSeg !in NOISE_CONCEPTS) {
                out.add(lastSeg)
            }
        }
        return out.toList()
    }

    fun timeBucketLabel(timestampMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 6..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..20 -> "Evening"
            else -> "Night"
        }
    }

    private data class PairAccum(
        val tokenA: String,
        val tokenB: String,
        var weight: Double = 0.0,
        var source: String = "DEVICE",
        var bucket: String = "Anytime",
        var lastSeen: Long = 0L
    )

    private data class ConceptAccum(
        var hits: Int = 0,
        val sources: LinkedHashSet<String> = linkedSetOf(),
        val buckets: HashMap<String, Int> = hashMapOf(),
        val linked: LinkedHashMap<String, Double> = linkedMapOf(),
        var bestSnippet: String = "",
        var lastSeen: Long = 0L
    )

    suspend fun runSinglePass(context: Context): SimulationSnapshot = passMutex.withLock {
        val db = DeviceMemoryDatabase.getInstance(context)
        val recordDao = db.collectedRecordDao()
        val brainDao = db.brainSimulationDao()

        val totalRecords = recordDao.totalCount()
        val prevStats = brainDao.getStats()
        if (totalRecords == 0) {
            val emptySnap = SimulationSnapshot(isSimulating = true, totalPasses = 0)
            brainDao.saveStats(SimulationStatsEntity(totalPasses = 0, lastSimulatedAt = System.currentTimeMillis()))
            return emptySnap
        }

        val nextPass = (prevStats?.totalPasses ?: 0) + 1
        val windows = ((totalRecords + PASS_BATCH_SIZE - 1) / PASS_BATCH_SIZE).coerceAtLeast(1)
        val windowIdx = (nextPass - 1) % windows
        val offset = windowIdx * PASS_BATCH_SIZE
        val windowBatch = recordDao.getRecordsWindow(PASS_BATCH_SIZE, offset)

        // Active Inference epistemic curiosity replay on alternating passes
        val prevWorld = brainDao.getWorldModelState()
        val curiosityTerm = prevWorld?.curiosityTarget?.takeIf { it.isNotBlank() }
        val curiosityBatch = if (curiosityTerm != null && nextPass % 2 == 0) {
            recordDao.searchByText(OnDeviceLunaBrain.escapeLike(curiosityTerm), limit = 30)
        } else emptyList()
        val batch = (windowBatch + curiosityBatch)
            .distinctBy { it.id }
            .sortedBy { it.timestamp }

        if (batch.isEmpty()) {
            return refreshSnapshotFromDb(context)
        }

        val pairAccums = HashMap<String, PairAccum>()
        val conceptAccums = HashMap<String, ConceptAccum>()

        // Precompute clean concepts + resolved TemporalAnchor time buckets per record in batch
        val prepared: List<Triple<CollectedRecordEntity, List<String>, String>> = batch.map { rec ->
            val rawText = rec.textContent
            val anchorTs = if (!rawText.isNullOrBlank()) {
                TemporalAnchorExtractor.extractAnchors(rawText, rec.timestamp)
                    .maxByOrNull { it.confidence }?.estimatedEpochMillis ?: rec.timestamp
            } else rec.timestamp
            Triple(rec, extractSemanticConcepts(rec.sourceType, rawText), timeBucketLabel(anchorTs))
        }

        fun addPair(aRaw: String, bRaw: String, delta: Double, src: String, bucket: String, ts: Long) {
            if (aRaw == bRaw) return
            val (a, b) = if (aRaw < bRaw) aRaw to bRaw else bRaw to aRaw
            val key = "$a|$b"
            val p = pairAccums.getOrPut(key) { PairAccum(a, b, 0.0, src, bucket, ts) }
            p.weight += delta
            if (ts >= p.lastSeen) {
                p.lastSeen = ts
                p.source = src
                p.bucket = bucket
            }
            conceptAccums.getOrPut(a) { ConceptAccum() }.let {
                it.linked[b] = (it.linked[b] ?: 0.0) + delta
            }
            conceptAccums.getOrPut(b) { ConceptAccum() }.let {
                it.linked[a] = (it.linked[a] ?: 0.0) + delta
            }
        }

        for (i in prepared.indices) {
            val (rec, concepts, bucket) = prepared[i]
            if (concepts.isEmpty()) continue

            val cleanText = (rec.textContent ?: "").trim()
            val isNoisyBurst = cleanText.endsWith("Sending...", ignoreCase = true)

            for (c in concepts) {
                val acc = conceptAccums.getOrPut(c) { ConceptAccum() }
                acc.hits += 1
                acc.sources.add(rec.sourceType)
                acc.buckets[bucket] = (acc.buckets[bucket] ?: 0) + 1
                if (rec.timestamp >= acc.lastSeen) {
                    acc.lastSeen = rec.timestamp
                }
                if (cleanText.isNotBlank() && !isNoisyBurst && (acc.bestSnippet.isBlank() || cleanText.length > acc.bestSnippet.length)) {
                    acc.bestSnippet = cleanText.take(120)
                }
            }

            // 1) Within-record semantic links
            for (x in concepts.indices) {
                for (y in x + 1 until concepts.size) {
                    addPair(concepts[x], concepts[y], 1.0, rec.sourceType, bucket, rec.timestamp)
                }
            }

            // 2) Temporal session replay across nearby records (cross-source co-occurrence within 30 mins)
            var j = i + 1
            while (j < prepared.size && j <= i + 4) {
                val (nextRec, nextConcepts, _) = prepared[j]
                if (abs(nextRec.timestamp - rec.timestamp) <= TEMPORAL_WINDOW_MS) {
                    val primaryA = concepts.firstOrNull()
                    val primaryB = nextConcepts.firstOrNull()
                    if (primaryA != null && primaryB != null) {
                        val bridgeLabel = if (rec.sourceType == nextRec.sourceType) rec.sourceType else "CROSS_SENSOR"
                        addPair(primaryA, primaryB, 0.65, bridgeLabel, bucket, nextRec.timestamp)
                    }
                }
                j++
            }
        }

        // Persist top reinforced links from this simulation pass (capped batch write)
        val topPairs = pairAccums.entries.sortedByDescending { it.value.weight }.take(80)
        for ((key, p) in topPairs) {
            brainDao.reinforceAssociation(
                pairKey = key,
                tokenA = p.tokenA,
                tokenB = p.tokenB,
                deltaWeight = p.weight.coerceAtMost(5.0),
                source = p.source,
                timeBucket = p.bucket,
                lastSeen = p.lastSeen
            )
        }

        // Synthesize consolidated BrainInsights (merging incrementally with prior knowledge)
        val nowMs = System.currentTimeMillis()
        val toUpsert = ArrayList<BrainInsightEntity>()
        val topConcepts = conceptAccums.entries.sortedByDescending { it.value.hits }.take(35)
        for ((concept, acc) in topConcepts) {
            val prev = brainDao.getInsight(concept)
            val combinedHits = (prev?.totalHits ?: 0) + acc.hits
            val peakBucket = acc.buckets.maxByOrNull { it.value }?.key ?: (prev?.peakTimeBucket ?: "Anytime")

            val mergedLinked = LinkedHashMap<String, Double>()
            prev?.linkedConcepts?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEachIndexed { idx, oldLink ->
                mergedLinked[oldLink] = (mergedLinked[oldLink] ?: 0.0) + (5.0 - idx * 0.5).coerceAtLeast(0.5)
            }
            for ((k, w) in acc.linked) {
                mergedLinked[k] = (mergedLinked[k] ?: 0.0) + w
            }
            val topLinkedList = mergedLinked.entries.sortedByDescending { it.value }.map { it.key }.take(6)

            val category = inferCategory(acc.sources)
            val srcReadable = acc.sources.joinToString(" & ") { readableSource(it) }
            val linksReadable = if (topLinkedList.isNotEmpty()) topLinkedList.joinToString(", ") else "standalone activity"
            val summary = "Observed $combinedHits times via $srcReadable (peak: $peakBucket). Connected with: $linksReadable."

            toUpsert.add(
                BrainInsightEntity(
                    concept = concept,
                    category = category,
                    peakTimeBucket = peakBucket,
                    totalHits = combinedHits,
                    linkedConcepts = topLinkedList.joinToString(", "),
                    summaryFact = summary,
                    updatedAt = nowMs
                )
            )
        }
        if (toUpsert.isNotEmpty()) {
            brainDao.upsertInsights(toUpsert)
        }

        if (nextPass % 5 == 0) {
            brainDao.trimAssociations(keepLimit = 2500)
        }

        // Run 2026 continual neural training pass (Skip-Gram SGD + Titans Surprise/Momentum + EWC + GRPO Self-Play)
        val pairSignals = pairAccums.values.map { PairSignal(it.tokenA, it.tokenB, it.weight) }
        val recConcepts = prepared.mapNotNull { (_, concepts, bucket) ->
            if (concepts.isNotEmpty()) bucket to concepts else null
        }
        val allBatchConcepts = conceptAccums.keys.toList()
        val trainMetrics = FrontierNeuralTrainer.trainPass(
            brainDao = brainDao,
            pairs = pairSignals,
            recordConceptsList = recConcepts,
            allBatchConcepts = allBatchConcepts,
            passIndex = nextPass
        )

        // Build chronological timeline transitions (z_t -> z_{t+1}) for JEPA World Model + Liquid Time + Poincaré
        val transitions = ArrayList<Triple<String, String, Long>>()
        for (i in 0 until prepared.size - 1) {
            val (recA, cA, _) = prepared[i]
            val (recB, cB, _) = prepared[i + 1]
            val headA = cA.firstOrNull() ?: continue
            val headB = cB.firstOrNull() ?: continue
            if (headA != headB && abs(recB.timestamp - recA.timestamp) <= TEMPORAL_WINDOW_MS) {
                transitions.add(Triple(headA, headB, recB.timestamp))
            }
        }
        val conceptHitMap = conceptAccums.mapValues { it.value.hits }
        val futureMetrics = FutureNeuralArchitectures.trainFuturePass(
            brainDao = brainDao,
            pairs = pairSignals,
            timelineTransitions = transitions,
            conceptHits = conceptHitMap,
            passIndex = nextPass
        )

        // Cross-modal spatio-temporal Episode Stitching ("Life Moments")
        val stitchedEpisodes = EpisodeStitchingEngine.stitchEpisodes(
            records = batch,
            conceptExtractor = ::extractSemanticConcepts,
            nowMs = nowMs
        )
        if (stitchedEpisodes.isNotEmpty()) {
            brainDao.upsertEpisodes(stitchedEpisodes)
        }
        if (nextPass % 5 == 0) {
            brainDao.trimEpisodes(keepLimit = 500)
        }

        val totalLinks = brainDao.countAssociations()
        val totalInsights = brainDao.countInsights()
        val totalVecs = brainDao.countVectors()
        val topConceptsDisplay = brainDao.getTopInsights(5).map { it.concept }

        val topCausal = brainDao.getTopCausalTransitions(1).firstOrNull()
        val topChain = topCausal?.let { "${it.fromConcept} → ${it.toConcept}" } ?: ""
        val causalCount = brainDao.countCausalTransitions()
        val episodesCount = brainDao.countEpisodes()

        brainDao.saveStats(
            SimulationStatsEntity(
                id = 1,
                totalPasses = nextPass,
                lastSimulatedAt = nowMs,
                lastBatchRecords = batch.size,
                insightsCount = totalInsights,
                associationsCount = totalLinks
            )
        )

        return SimulationSnapshot(
            isSimulating = true,
            totalPasses = nextPass,
            associationsCount = totalLinks,
            insightsCount = totalInsights,
            lastBatchSize = batch.size,
            neuralVectorsCount = totalVecs,
            selfPlayAccuracyPct = trainMetrics.selfPlayAccuracyPct,
            avgSurprise = trainMetrics.avgSurprise,
            topPolicyStrategy = formatTopPolicy(trainMetrics.policyState),
            jepaAccuracyPct = futureMetrics.jepaAccuracyPct,
            avgFreeEnergy = futureMetrics.avgFreeEnergy,
            curiosityTarget = futureMetrics.curiosityTarget,
            causalTransitionsCount = causalCount,
            topCausalChain = topChain,
            episodesCount = episodesCount,
            topSimulatedConcepts = topConceptsDisplay
        )
    }

    private fun inferCategory(sources: Set<String>): String = when {
        "NOTIFICATION" in sources || "CONTACT" in sources -> "SOCIAL"
        "APP_USAGE" in sources -> "APP"
        "PHOTO" in sources || "VIDEO" in sources || "AUDIO" in sources -> "MEDIA"
        "LOCATION" in sources || "ACTIVITY" in sources -> "ROUTINE"
        else -> "TOPIC"
    }

    private fun readableSource(raw: String): String = when (raw) {
        "APP_USAGE" -> "App Usage"
        "NOTIFICATION" -> "Notifications"
        "PHOTO" -> "Photos"
        "VIDEO" -> "Videos"
        "AUDIO" -> "Audio"
        "LOCATION" -> "Location"
        "ACTIVITY" -> "Activity"
        "CONTACT" -> "Contacts"
        "CALENDAR_EVENT" -> "Calendar"
        else -> raw.lowercase()
    }
}
