package com.personal.devicememory.pipeline.brain

import com.personal.devicememory.core.database.dao.BrainSimulationDao
import com.personal.devicememory.core.database.entity.NeuralPolicyStateEntity
import com.personal.devicememory.core.database.entity.NeuralVectorEntity
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PairSignal(
    val tokenA: String,
    val tokenB: String,
    val strength: Double
)

data class TrainingStepMetrics(
    val updatedVectors: Int = 0,
    val avgSurprise: Double = 0.0,
    val grpoReward: Double = 0.0,
    val selfPlayAccuracyPct: Int = 0,
    val policyState: NeuralPolicyStateEntity = NeuralPolicyStateEntity()
)

/**
 * Pure Kotlin 2026 On-Device Neural Continual Training Engine:
 *
 * 1) Contrastive Skip-Gram SGD with Negative Sampling (32-dim continuous concept embeddings)
 * 2) Google Titans Neural Long-Term Memory (Surprise-driven gradient momentum + adaptive decay)
 * 3) EWC Fisher Importance Penalty (prevents catastrophic forgetting of established habits)
 * 4) DeepSeek-style GRPO Self-Play Policy Optimization (learns optimal Exact/Graph/Vector/Temporal weights)
 * 5) Test-Time Training (TTT) inner-loop query adaptation over conversational session context
 */
object FrontierNeuralTrainer {

    const val EMBEDDING_DIM = 32
    private const val BASE_LR = 0.085
    private const val TITANS_MOMENTUM_BETA = 0.72
    private const val TITANS_FORGET_DECAY = 0.012
    private const val EWC_LAMBDA = 0.35
    private const val GRPO_CLIP_EPS = 0.18
    private const val GRPO_LR = 0.09
    private const val MAX_VECTORS_STORED = 1200

    fun l2Normalize(vec: FloatArray): FloatArray {
        var sq = 0.0
        for (v in vec) sq += v * v
        val norm = sqrt(sq).toFloat()
        if (norm < 1e-6f) return vec
        val out = FloatArray(vec.size)
        for (i in vec.indices) out[i] = vec[i] / norm
        return out
    }

    fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        val n = min(a.size, b.size)
        for (i in 0 until n) sum += a[i] * b[i]
        return sum
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var d = 0.0
        var na = 0.0
        var nb = 0.0
        val n = min(a.size, b.size)
        for (i in 0 until n) {
            d += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom < 1e-8) 0.0 else (d / denom).coerceIn(-1.0, 1.0)
    }

    fun sigmoid(x: Double): Double = when {
        x > 16.0 -> 0.9999999
        x < -16.0 -> 1e-7
        else -> 1.0 / (1.0 + exp(-x))
    }

    /** Deterministic zero-knowledge hashed vector initialization (never pre-seeded). */
    fun initConceptVector(concept: String): FloatArray {
        val out = FloatArray(EMBEDDING_DIM)
        var state = concept.hashCode().toLong() * 6364136223846793005L + 1442695040888963407L
        for (i in 0 until EMBEDDING_DIM) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val bits = ((state ushr 33) and 0x7FFF_FFFFL).toDouble() / 0x7FFF_FFFFL.toDouble()
            out[i] = (bits - 0.5).toFloat()
        }
        return l2Normalize(out)
    }

    fun decodeVector(csv: String?): FloatArray {
        if (csv.isNullOrBlank()) return FloatArray(EMBEDDING_DIM)
        val parts = csv.split(",")
        val out = FloatArray(EMBEDDING_DIM)
        for (i in 0 until min(parts.size, EMBEDDING_DIM)) {
            out[i] = parts[i].toFloatOrNull() ?: 0f
        }
        return out
    }

    fun encodeVector(vec: FloatArray): String =
        vec.joinToString(",") { String.format(Locale.US, "%.4f", it) }

    /**
     * Google Titans surprise metric: S_t = 1 - sigmoid(v_a . v_b).
     * Unexpected or newly-linked events yield high surprise & strong plasticity;
     * repetitive bursts yield low surprise & avoid overwriting memory.
     */
    fun computeTitansSurprise(vecA: FloatArray, vecB: FloatArray): Double {
        val p = sigmoid(dot(vecA, vecB) * 2.5)
        return (1.0 - p).coerceIn(0.02, 0.98)
    }

    /**
     * Runs one complete continual-learning & GRPO self-play pass over a batch of user co-occurrences.
     */
    suspend fun trainPass(
        brainDao: BrainSimulationDao,
        pairs: List<PairSignal>,
        recordConceptsList: List<Pair<String, List<String>>>, // (timeBucket, concepts)
        allBatchConcepts: List<String>,
        passIndex: Int
    ): TrainingStepMetrics {
        if (allBatchConcepts.isEmpty()) {
            val ps = brainDao.getPolicyState() ?: NeuralPolicyStateEntity()
            return TrainingStepMetrics(policyState = ps)
        }

        val nowMs = System.currentTimeMillis()
        val distinctConcepts = allBatchConcepts.distinct()

        // 1. Load existing SQLCipher neural states for concepts in this pass
        val existingMap = brainDao.getVectorsByConcepts(distinctConcepts).associateBy { it.concept }
        val vecs = HashMap<String, FloatArray>(distinctConcepts.size)
        val moms = HashMap<String, FloatArray>(distinctConcepts.size)
        val fishers = HashMap<String, Double>(distinctConcepts.size)
        val surprises = HashMap<String, Double>(distinctConcepts.size)
        val steps = HashMap<String, Int>(distinctConcepts.size)

        for (c in distinctConcepts) {
            val ent = existingMap[c]
            if (ent != null) {
                vecs[c] = l2Normalize(decodeVector(ent.vectorCsv))
                moms[c] = decodeVector(ent.momentumCsv)
                fishers[c] = ent.fisherImportance
                surprises[c] = ent.surpriseScore
                steps[c] = ent.updateSteps
            } else {
                vecs[c] = initConceptVector(c)
                moms[c] = FloatArray(EMBEDDING_DIM)
                fishers[c] = 0.0
                surprises[c] = 0.5
                steps[c] = 0
            }
        }

        // Cosine learning rate decay over simulation passes while preserving floor plasticity
        val cosineSchedule = 0.35 + 0.65 * (0.5 * (1.0 + kotlin.math.cos(min(passIndex, 100) / 100.0 * Math.PI)))
        val effectiveLr = BASE_LR * cosineSchedule

        var surpriseSum = 0.0
        var pairUpdates = 0

        fun applyTitansStep(target: String, gradDir: FloatArray, scalarGrad: Double, surprise: Double) {
            val v = vecs[target] ?: return
            val m = moms[target] ?: return
            val fisher = fishers[target] ?: 0.0

            // EWC damping protects high-importance long-term concepts from catastrophic forgetting
            val ewcDamp = 1.0 / (1.0 + EWC_LAMBDA * fisher)
            val lrEff = (effectiveLr * (0.45 + 0.9 * surprise) * ewcDamp).toFloat()

            var gradNormSq = 0.0
            for (d in 0 until EMBEDDING_DIM) {
                val g = scalarGrad * gradDir[d]
                gradNormSq += g * g
                val newM = (TITANS_MOMENTUM_BETA * m[d] + (1.0 - TITANS_MOMENTUM_BETA) * g).toFloat()
                m[d] = newM
                v[d] = (v[d] * (1.0f - TITANS_FORGET_DECAY.toFloat())) + lrEff * newM
            }
            vecs[target] = l2Normalize(v)
            fishers[target] = (fisher * 0.96 + 0.04 * surprise * min(4.0, gradNormSq)).coerceIn(0.0, 25.0)
            surprises[target] = (0.65 * (surprises[target] ?: surprise) + 0.35 * surprise)
            steps[target] = (steps[target] ?: 0) + 1
        }

        // 2. Positive Skip-Gram + Titans Momentum + Contrastive Negative Sampling
        val rngSeedBase = passIndex * 31 + 7
        for ((idx, pair) in pairs.take(90).withIndex()) {
            val va = vecs[pair.tokenA] ?: continue
            val vb = vecs[pair.tokenB] ?: continue

            val surprise = computeTitansSurprise(va, vb)
            surpriseSum += surprise
            pairUpdates++

            // Positive contrastive gradient pulls co-occurring concepts together
            val posError = (1.0 - sigmoid(dot(va, vb) * 2.5)) * pair.strength.coerceAtMost(2.5)
            val snapA = va.copyOf()
            val snapB = vb.copyOf()
            applyTitansStep(pair.tokenA, snapB, posError, surprise)
            applyTitansStep(pair.tokenB, snapA, posError, surprise)

            // Contrastive Negative Sampling (K = 3 non-matching concepts pushed apart)
            if (distinctConcepts.size >= 5) {
                for (negK in 1..3) {
                    val negIdx = (idx * 7 + negK * 13 + rngSeedBase) % distinctConcepts.size
                    val negConcept = distinctConcepts[negIdx]
                    if (negConcept == pair.tokenA || negConcept == pair.tokenB) continue
                    val vn = vecs[negConcept] ?: continue
                    val negScore = sigmoid(dot(snapA, vn) * 2.5)
                    if (negScore > 0.25) {
                        val negGrad = -0.65 * negScore
                        applyTitansStep(pair.tokenA, vn, negGrad, surprise * 0.5)
                    }
                }
            }
        }

        // 3. Persist updated neural weights to encrypted Room DB
        val updatedEntities = distinctConcepts.map { c ->
            NeuralVectorEntity(
                concept = c,
                vectorCsv = encodeVector(vecs[c]!!),
                momentumCsv = encodeVector(moms[c]!!),
                fisherImportance = fishers[c] ?: 0.0,
                surpriseScore = surprises[c] ?: 0.0,
                updateSteps = steps[c] ?: 1,
                updatedAt = nowMs
            )
        }
        brainDao.upsertVectors(updatedEntities)
        if (passIndex % 6 == 0) {
            brainDao.trimVectors(MAX_VECTORS_STORED)
        }

        val avgSurprise = if (pairUpdates > 0) surpriseSum / pairUpdates else 0.0

        // 4. GRPO Self-Play Policy Verification over held-out multi-concept records
        val prevPolicy = brainDao.getPolicyState() ?: NeuralPolicyStateEntity()
        val updatedPolicy = runGrpoSelfPlay(
            prevPolicy = prevPolicy,
            recordsWithConcepts = recordConceptsList,
            vecs = vecs,
            pairs = pairs,
            avgSurprise = avgSurprise,
            nowMs = nowMs
        )
        brainDao.savePolicyState(updatedPolicy)

        val accPct = if (updatedPolicy.selfPlayTotal > 0) {
            ((updatedPolicy.selfPlayCorrect.toDouble() / updatedPolicy.selfPlayTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else 0

        return TrainingStepMetrics(
            updatedVectors = updatedEntities.size,
            avgSurprise = avgSurprise,
            grpoReward = updatedPolicy.lastGrpoReward,
            selfPlayAccuracyPct = accPct,
            policyState = updatedPolicy
        )
    }

    /**
     * DeepSeek-style Group Relative Policy Optimization (GRPO) without a critic network:
     * Generates masked-concept self-play tests on real user memories, scores 4 reasoning
     * hypotheses (`Exact`, `Graph`, `Vector Cosine`, `Temporal`), computes normalized
     * group advantages, and updates retrieval policy weights cleanly.
     */
    private fun runGrpoSelfPlay(
        prevPolicy: NeuralPolicyStateEntity,
        recordsWithConcepts: List<Pair<String, List<String>>>,
        vecs: Map<String, FloatArray>,
        pairs: List<PairSignal>,
        avgSurprise: Double,
        nowMs: Long
    ): NeuralPolicyStateEntity {
        val multiConcept = recordsWithConcepts.filter { it.second.size >= 2 }.take(8)
        if (multiConcept.isEmpty()) {
            return prevPolicy.copy(avgSurprise = avgSurprise, updatedAt = nowMs)
        }

        // Build fast adjacency list for Graph hypothesis scoring
        val adj = HashMap<String, MutableList<Pair<String, Double>>>()
        for (p in pairs) {
            adj.getOrPut(p.tokenA) { mutableListOf() }.add(p.tokenB to p.strength)
            adj.getOrPut(p.tokenB) { mutableListOf() }.add(p.tokenA to p.strength)
        }

        // Rewards per strategy across this self-play episode: [Exact, Graph, Vector, Temporal]
        val rewards = DoubleArray(4)
        var trials = 0
        var totalHits = 0

        for ((bucket, concepts) in multiConcept) {
            val anchor = concepts.first()
            val groundTruth = concepts[1]
            val anchorVec = vecs[anchor] ?: continue

            // Hypothesis 0: Exact string / prefix match score
            val rExact = if (groundTruth.contains(anchor) || anchor.contains(groundTruth)) 1.0 else 0.15

            // Hypothesis 1: Graph co-occurrence top-3 recall
            val graphTop = adj[anchor]?.sortedByDescending { it.second }?.take(3)?.map { it.first } ?: emptyList()
            val rGraph = if (groundTruth in graphTop) 1.0 else 0.0

            // Hypothesis 2: Neural Embedding Cosine Similarity top-3 semantic prediction
            val vecTop = vecs.entries
                .asSequence()
                .filter { it.key != anchor }
                .map { it.key to cosineSimilarity(anchorVec, it.value) }
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
                .toList()
            val rVector = if (groundTruth in vecTop) 1.0 else 0.0

            // Hypothesis 3: Temporal session sibling prediction
            val sameBucketNeighbors = recordsWithConcepts
                .filter { it.first == bucket && anchor in it.second }
                .flatMap { it.second }
                .filter { it != anchor }
            val rTemporal = if (groundTruth in sameBucketNeighbors) 0.9 else 0.0

            rewards[0] += rExact
            rewards[1] += rGraph
            rewards[2] += rVector
            rewards[3] += rTemporal
            trials++

            if (rGraph > 0.5 || rVector > 0.5 || rExact > 0.5) {
                totalHits++
            }
        }

        if (trials == 0) return prevPolicy.copy(avgSurprise = avgSurprise, updatedAt = nowMs)
        for (k in 0..3) rewards[k] /= trials.toDouble()

        val groupMean = rewards.average()
        var varSum = 0.0
        for (r in rewards) varSum += (r - groupMean) * (r - groupMean)
        val groupStd = sqrt(varSum / 4.0) + 1e-4

        val adv = DoubleArray(4) { k -> ((rewards[k] - groupMean) / groupStd).coerceIn(-2.0, 2.0) }

        fun clipUpdate(oldW: Double, a: Double): Double {
            val step = (GRPO_LR * a).coerceIn(-GRPO_CLIP_EPS, GRPO_CLIP_EPS)
            return (oldW * (1.0 + step)).coerceIn(0.35, 2.8)
        }

        var wExact = clipUpdate(prevPolicy.weightExact, adv[0])
        var wGraph = clipUpdate(prevPolicy.weightGraph, adv[1])
        var wVector = clipUpdate(prevPolicy.weightVector, adv[2])
        var wTemp = clipUpdate(prevPolicy.weightTemporal, adv[3])

        // Re-center mean weight to 1.0 to keep policy scale bounded
        val avgW = max(0.01, (wExact + wGraph + wVector + wTemp) / 4.0)
        wExact /= avgW
        wGraph /= avgW
        wVector /= avgW
        wTemp /= avgW

        return NeuralPolicyStateEntity(
            id = 1,
            weightExact = wExact,
            weightGraph = wGraph,
            weightVector = wVector,
            weightTemporal = wTemp,
            lastGrpoReward = groupMean,
            selfPlayCorrect = prevPolicy.selfPlayCorrect + totalHits,
            selfPlayTotal = prevPolicy.selfPlayTotal + trials,
            avgSurprise = avgSurprise,
            updatedAt = nowMs
        )
    }

    /**
     * Test-Time Training (TTT) Inner Loop executed live on inference!
     * Adapts an ephemeral query tensor via mini-gradient updates over the current
     * query terms + recent session turns ("context-as-training-dataset") and returns
     * the top semantically nearest neural concepts learned on-device.
     */
    fun adaptAndQueryVectors(
        queryConcepts: List<String>,
        recentSessionConcepts: List<String>,
        allVectors: List<NeuralVectorEntity>,
        topK: Int = 5
    ): List<Pair<String, Double>> {
        if (allVectors.isEmpty() || queryConcepts.isEmpty()) return emptyList()
        val vecMap = allVectors.associate { it.concept to decodeVector(it.vectorCsv) }

        // Initialize query context state from matched query concepts
        val state = FloatArray(EMBEDDING_DIM)
        var foundDirect = false
        for (qc in queryConcepts) {
            val v = vecMap[qc]
            if (v != null) {
                foundDirect = true
                for (i in 0 until EMBEDDING_DIM) state[i] += v[i]
            } else {
                // Prefix fallback if user typed root word
                val prefixHit = vecMap.entries.firstOrNull { it.key.contains(qc) || qc.contains(it.key) }?.value
                if (prefixHit != null) {
                    foundDirect = true
                    for (i in 0 until EMBEDDING_DIM) state[i] += prefixHit[i] * 0.85f
                }
            }
        }
        if (!foundDirect) return emptyList()
        var adapted = l2Normalize(state)

        // TTT Inner-Loop SGD steps pulling slightly toward recent chat context without mutating DB weights
        val contextVecs = recentSessionConcepts.mapNotNull { vecMap[it] }
        if (contextVecs.isNotEmpty()) {
            val tttLr = 0.16f
            repeat(3) {
                val grad = FloatArray(EMBEDDING_DIM)
                for (cv in contextVecs) {
                    val sim = cosineSimilarity(adapted, cv).coerceAtLeast(0.0)
                    val stepScale = ((1.0 - sim) * 0.5).toFloat()
                    for (i in 0 until EMBEDDING_DIM) grad[i] += cv[i] * stepScale
                }
                for (i in 0 until EMBEDDING_DIM) {
                    adapted[i] += tttLr * (grad[i] / contextVecs.size)
                }
                adapted = l2Normalize(adapted)
            }
        }

        val querySet = queryConcepts.toSet()
        return vecMap.entries
            .asSequence()
            .filter { it.key !in querySet }
            .map { it.key to cosineSimilarity(adapted, it.value) }
            .filter { it.second > 0.22 }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
    }
}
