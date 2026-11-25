package com.personal.devicememory.pipeline.brain

import android.content.Context
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.BrainInsightEntity
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.NeuralPolicyStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

data class OfflineLunaReply(
    val query: String,
    val learned: Boolean,
    val totalMemories: Int,
    val totalTokens: Int,
    val simulationPasses: Int = 0,
    val associationsCount: Int = 0,
    val neuralVectorsCount: Int = 0,
    val selfPlayAccuracyPct: Int = 0,
    val jepaAccuracyPct: Int = 0,
    val curiosityTarget: String = "",
    val response: String
)

enum class QueryIntent {
    SUMMARY_PROFILE,
    TEMPORAL,
    SOCIAL,
    CONCEPT_RECALL
}

/**
 * 100% On-Device, Zero-Prebuilt-Knowledge 2026 AI Reasoning Engine.
 * Combines:
 *  - Continuous simulated associative graph (`learned_associations`)
 *  - Google Titans Surprise + Skip-Gram EWC Neural Vectors (`neural_vectors`)
 *  - DeepSeek-style GRPO Self-Play Policy Weights (`neural_policy_state`)
 *  - Test-Time Training (TTT) inner-loop session adaptation during live inference
 *  - Fuzzy typo matching (Levenshtein edit distance) & burst deduplication
 */
object OnDeviceLunaBrain {

    private val TOKEN_REGEX = Regex("""[\p{L}\p{M}\p{N}_]{2,40}""")

    private val STOP_QUERY_WORDS = setOf(
        "what", "when", "where", "which", "who", "whom", "how", "why", "about",
        "tell", "show", "did", "does", "have", "been", "from", "with", "this",
        "that", "you", "your", "are", "the", "and", "for", "eppo", "enna",
        "yaar", "enga", "pati", "patti", "sollu", "kaatu", "pannen", "use"
    )

    fun tokenize(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return TOKEN_REGEX.findAll(text.lowercase())
            .map { it.value }
            .distinct()
            .take(30)
            .toList()
    }

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    fun classifyIntent(queryLower: String): QueryIntent = when {
        listOf("summary", "pattern", "habit", "learned", "profile", "overview", "ella", "kathukitta", "theriyum")
            .any { queryLower.contains(it) } && queryLower.split(" ").size >= 2 -> QueryIntent.SUMMARY_PROFILE
        listOf("when", "time", "eppo", "morning", "afternoon", "evening", "night")
            .any { queryLower.contains(it) } -> QueryIntent.TEMPORAL
        listOf("who", "yaar", "message", "contact", "friend")
            .any { queryLower.contains(it) } -> QueryIntent.SOCIAL
        else -> QueryIntent.CONCEPT_RECALL
    }

    suspend fun learnFromRecords(db: DeviceMemoryDatabase, records: List<CollectedRecordEntity>) {
        if (records.isEmpty()) return
        val tokenDao = db.learnedTokenDao()
        for (entity in records) {
            val safeText = SensitiveContentFilter.redact(entity.textContent)
            for (tok in tokenize(safeText)) {
                tokenDao.recordToken(tok, entity.sourceType, entity.timestamp)
            }
        }
    }

    suspend fun askOffline(
        context: Context,
        rawQuery: String,
        recentSessionQueries: List<String> = emptyList()
    ): OfflineLunaReply = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        val queryLower = query.lowercase()
        val db = DeviceMemoryDatabase.getInstance(context)
        val recordDao = db.collectedRecordDao()
        val tokenDao = db.learnedTokenDao()
        val brainDao = db.brainSimulationDao()

        val totalMemories = recordDao.totalCount()
        val totalTokens = tokenDao.countTokens()
        var stats = brainDao.getStats()
        var assocCount = stats?.associationsCount ?: brainDao.countAssociations()

        // Ensure at least 1 full simulation + neural training pass runs when data exists
        if (totalMemories > 0 && (stats == null || stats.totalPasses == 0)) {
            val fresh = LunaMemorySimulator.runSinglePass(context)
            stats = brainDao.getStats()
            assocCount = fresh.associationsCount
        }
        val simPasses = stats?.totalPasses ?: 0
        val policy = brainDao.getPolicyState() ?: NeuralPolicyStateEntity()
        val allVectors = brainDao.getAllVectors(900)
        val worldState = brainDao.getWorldModelState()
        val allFutureCells = brainDao.getAllFutureCells(600)
        val jepaAcc = worldState?.jepaAccuracyPct ?: 0
        val curiosity = worldState?.curiosityTarget ?: ""
        val selfPlayAccPct = if (policy.selfPlayTotal > 0) {
            ((policy.selfPlayCorrect.toDouble() / policy.selfPlayTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        if (totalMemories == 0) {
            return@withContext OfflineLunaReply(
                query = query,
                learned = false,
                totalMemories = 0,
                totalTokens = totalTokens,
                simulationPasses = simPasses,
                associationsCount = assocCount,
                neuralVectorsCount = allVectors.size,
                selfPlayAccuracyPct = selfPlayAccPct,
                jepaAccuracyPct = jepaAcc,
                curiosityTarget = curiosity,
                response = buildString {
                    appendLine("⭕ [Offline • Untrained • 0 Memories]")
                    appendLine("\"$query\" பற்றி எனக்கு எதுவும் தெரியாது — போனில் இன்னும் எதுவும் கற்கப்படவில்லை.")
                    append("(⋮ → Learning Permission-ல் sensors ON செய்து 'Collect Offline' கொடுக்கவும்.)")
                }
            )
        }

        val intent = classifyIntent(queryLower)
        val topInsights = brainDao.getTopInsights(12)

        if (intent == QueryIntent.SUMMARY_PROFILE && topInsights.isNotEmpty()) {
            val summaryBody = buildString {
                appendLine("🧠 [Next-Gen Neural Profile • Pass #$simPasses • ${allVectors.size} vecs • GRPO $selfPlayAccPct% • JEPA $jepaAcc%]")
                appendLine(
                    "GRPO Policy: Exact ×${fmt(policy.weightExact)}, Graph ×${fmt(policy.weightGraph)}, Vector ×${fmt(policy.weightVector)}, Temporal ×${fmt(policy.weightTemporal)}"
                )
                if (curiosity.isNotBlank()) {
                    appendLine("Active Inference Focus: exploring \"$curiosity\" next")
                }
                appendLine()
                for (ins in topInsights.take(6)) {
                    appendLine("• ${ins.concept.replaceFirstChar { it.uppercase() }} (${ins.category}): ${ins.summaryFact}")
                }
            }.trimEnd()
            return@withContext OfflineLunaReply(
                query = query,
                learned = true,
                totalMemories = totalMemories,
                totalTokens = totalTokens,
                simulationPasses = simPasses,
                associationsCount = assocCount,
                neuralVectorsCount = allVectors.size,
                selfPlayAccuracyPct = selfPlayAccPct,
                jepaAccuracyPct = jepaAcc,
                curiosityTarget = curiosity,
                response = summaryBody
            )
        }

        val rawTokens = tokenize(query)
        val meaningfulTokens = rawTokens.filter { it !in STOP_QUERY_WORDS }.ifEmpty { rawTokens }

        val expandedTerms = LinkedHashSet<String>()
        expandedTerms.addAll(meaningfulTokens)

        val topVocab = tokenDao.getTopTokens(250).map { it.token }
        val vocabPool = (topInsights.map { it.concept } + allVectors.map { it.concept } + topVocab).distinct()
        val fuzzyCorrected = ArrayList<String>()

        for (t in meaningfulTokens) {
            if (t.length < 4) continue
            val maxDist = if (t.length >= 6) 2 else 1
            if (vocabPool.none { it == t }) {
                val closest = vocabPool
                    .asSequence()
                    .filter { kotlin.math.abs(it.length - t.length) <= maxDist }
                    .map { it to levenshteinDistance(t, it) }
                    .filter { it.second in 1..maxDist }
                    .minByOrNull { it.second }
                if (closest != null) {
                    expandedTerms.add(closest.first)
                    fuzzyCorrected.add("$t → ${closest.first}")
                }
            }
        }

        // Gather matching insights & 1-hop graph neighbors
        val matchedInsights = LinkedHashMap<String, BrainInsightEntity>()
        val graphNeighbors = LinkedHashSet<String>()
        for (term in expandedTerms) {
            for (ins in brainDao.searchInsights(term, limit = 4)) {
                matchedInsights[ins.concept] = ins
                ins.linkedConcepts.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != ins.concept }
                    .forEach { graphNeighbors.add(it) }
            }
            for (edge in brainDao.getAssociationsFor(term, limit = 6)) {
                val neighbor = if (edge.tokenA == term) edge.tokenB else edge.tokenA
                if (neighbor.isNotBlank() && neighbor !in expandedTerms) {
                    graphNeighbors.add(neighbor)
                }
            }
        }

        // Test-Time Training (TTT) inner-loop vector adaptation using recent conversation context
        val recentSessionConcepts = recentSessionQueries
            .flatMap { LunaMemorySimulator.extractSemanticConcepts("SESSION", it) }
            .filter { it !in STOP_QUERY_WORDS }
            .distinct()
            .take(6)

        val tttSemanticNeighbors = FrontierNeuralTrainer.adaptAndQueryVectors(
            queryConcepts = expandedTerms.toList(),
            recentSessionConcepts = recentSessionConcepts,
            allVectors = allVectors,
            topK = 5
        )
        val anchorTerm = expandedTerms.firstOrNull()
        val poincareHierarchy = if (anchorTerm != null) {
            FutureNeuralArchitectures.queryPoincareHierarchy(anchorTerm, allFutureCells, topK = 4)
        } else emptyList()
        val jepaForecast = if (anchorTerm != null) {
            FutureNeuralArchitectures.predictNextWorldStates(anchorTerm, allFutureCells, worldState, topK = 3)
        } else emptyList()

        // Score candidate memories dynamically using GRPO learned policy weights!
        val scored = linkedMapOf<String, Pair<CollectedRecordEntity, Double>>()
        val exactScore = 10.0 * policy.weightExact
        for (rec in recordDao.searchByText(query, limit = 10)) {
            scored[rec.id] = rec to exactScore
        }
        for (tok in expandedTerms) {
            val baseW = (if (tok in meaningfulTokens) 4.0 else 2.2) * policy.weightExact
            for (rec in recordDao.searchByText(tok, limit = 12)) {
                val existing = scored[rec.id]
                val bonus = if (intent == QueryIntent.TEMPORAL) policy.weightTemporal * 0.6 else 0.0
                if (existing != null) {
                    scored[rec.id] = existing.first to (existing.second + baseW + bonus)
                } else {
                    scored[rec.id] = rec to (baseW + bonus)
                }
            }
        }
        for (hop in graphNeighbors.take(3)) {
            val hopBoost = 1.5 * policy.weightGraph
            for (rec in recordDao.searchByText(hop, limit = 3)) {
                val existing = scored[rec.id]
                if (existing != null) {
                    scored[rec.id] = existing.first to (existing.second + hopBoost)
                }
            }
        }
        for ((vecConcept, sim) in tttSemanticNeighbors.take(3)) {
            val vecBoost = sim * 3.0 * policy.weightVector
            for (rec in recordDao.searchByText(vecConcept, limit = 4)) {
                val existing = scored[rec.id]
                if (existing != null) {
                    scored[rec.id] = existing.first to (existing.second + vecBoost)
                } else {
                    scored[rec.id] = rec to vecBoost
                }
            }
        }

        val ranked = deduplicateEvidence(
            scored.values
                .sortedWith(
                    compareByDescending<Pair<CollectedRecordEntity, Double>> { it.second }
                        .thenByDescending { it.first.timestamp }
                )
        ).take(4)

        val matchedTokens = if (expandedTerms.isNotEmpty()) {
            expandedTerms.flatMap { tokenDao.searchTokens(it, limit = 3) }
                .filterNot { it.token.length > 26 && it.token.contains("_") }
                .distinctBy { it.token }
                .sortedByDescending { it.frequency }
                .take(4)
        } else {
            emptyList()
        }

        if (ranked.isEmpty() && matchedInsights.isEmpty() && matchedTokens.isEmpty() && tttSemanticNeighbors.isEmpty()) {
            val realTopics = topInsights.take(4).joinToString(", ") { it.concept }
            return@withContext OfflineLunaReply(
                query = query,
                learned = false,
                totalMemories = totalMemories,
                totalTokens = totalTokens,
                simulationPasses = simPasses,
                associationsCount = assocCount,
                neuralVectorsCount = allVectors.size,
                selfPlayAccuracyPct = selfPlayAccPct,
                jepaAccuracyPct = jepaAcc,
                curiosityTarget = curiosity,
                response = buildString {
                    appendLine("⚠️ [Offline • Untrained for \"$query\"]")
                    appendLine("Checked $totalMemories memories, $assocCount links & ${allVectors.size} vectors (Pass #$simPasses • GRPO $selfPlayAccPct% • JEPA $jepaAcc%) — \"$query\" never appeared in your data.")
                    if (realTopics.isNotBlank()) {
                        append("Trained concepts ready in vault: $realTopics")
                    }
                }
            )
        }

        val surpriseHint = if (policy.avgSurprise > 0.0) {
            " • Surprise ${(policy.avgSurprise * 100).roundToInt()}%"
        } else ""

        val response = buildString {
            appendLine("🧠 [Titans+TTT+GRPO+JEPA+Poincaré • Pass #$simPasses • ${allVectors.size} vecs • GRPO ${selfPlayAccPct}% • JEPA ${jepaAcc}%$surpriseHint]")
            if (fuzzyCorrected.isNotEmpty()) {
                appendLine("Fuzzy Match: ${fuzzyCorrected.joinToString(", ")}")
            }
            if (matchedInsights.isNotEmpty()) {
                val primary = matchedInsights.values.first()
                appendLine("Habit (${primary.concept}): ${primary.summaryFact}")
            }
            if (jepaForecast.isNotEmpty()) {
                appendLine("JEPA World Forecast (Often follows): ${jepaForecast.joinToString(" → ") { it.first }}")
            }
            if (poincareHierarchy.isNotEmpty()) {
                appendLine("Poincaré Hierarchy Tree: ${poincareHierarchy.joinToString(", ") { "${it.first} (d=${fmt(it.second)})" }}")
            }
            if (tttSemanticNeighbors.isNotEmpty()) {
                val vecText = tttSemanticNeighbors.take(4).joinToString(", ") { "${it.first} (${fmt(it.second)})" }
                appendLine("TTT Semantic Neighbors: $vecText")
            }
            if (graphNeighbors.isNotEmpty()) {
                appendLine("Graph Links: ${graphNeighbors.take(4).joinToString(" ↔ ")}")
            }
            if (matchedTokens.isNotEmpty()) {
                appendLine("Key Signals: ${matchedTokens.joinToString(", ") { "${it.token} (×${it.frequency})" }}")
            }
            if (ranked.isNotEmpty()) {
                appendLine()
                appendLine("Top Evidence:")
                for ((rec, _) in ranked) {
                    val ts = TimestampUtil.formatForDisplay(rec.timestamp)
                    appendLine("• [${rec.sourceType} • $ts] ${cleanEvidenceLine(rec.textContent)}")
                }
            }
        }.trimEnd()

        OfflineLunaReply(
            query = query,
            learned = true,
            totalMemories = totalMemories,
            totalTokens = totalTokens,
            simulationPasses = simPasses,
            associationsCount = assocCount,
            neuralVectorsCount = allVectors.size,
            selfPlayAccuracyPct = selfPlayAccPct,
            jepaAccuracyPct = jepaAcc,
            curiosityTarget = curiosity,
            response = response
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun cleanEvidenceLine(text: String?): String {
        if (text.isNullOrBlank()) return "Metadata"
        return (SensitiveContentFilter.redact(text.replace('\n', ' ').trim()) ?: "").take(140)
    }

    private fun deduplicateEvidence(sorted: List<Pair<CollectedRecordEntity, Double>>): List<Pair<CollectedRecordEntity, Double>> {
        val seenNorm = HashSet<String>()
        val result = ArrayList<Pair<CollectedRecordEntity, Double>>()
        for (pair in sorted) {
            val raw = (pair.first.textContent ?: "").trim()
            if (raw.endsWith("Sending...", ignoreCase = true)) continue
            val norm = raw.lowercase().replace(Regex("""\d{6,}"""), "#")
            if (seenNorm.add(norm)) {
                result.add(pair)
            }
        }
        return if (result.isNotEmpty()) result else sorted
    }
}
