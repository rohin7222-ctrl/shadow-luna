package com.personal.devicememory.pipeline.brain

import com.personal.devicememory.core.database.dao.BrainSimulationDao
import com.personal.devicememory.core.database.entity.FutureMemoryCellEntity
import com.personal.devicememory.core.database.entity.WorldModelStateEntity
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

data class FuturePassMetrics(
    val futureCellsCount: Int = 0,
    val jepaAccuracyPct: Int = 0,
    val avgFreeEnergy: Double = 0.0,
    val avgFfGoodness: Double = 0.0,
    val curiosityTarget: String = ""
)

/**
 * Next-Generation (Post-2026) Continual & World-Model On-Device Training Engine:
 *
 * 1) Hyperbolic Geometry Memory (Poincaré Ball B^16): preserves hierarchical tree structure
 *    (hubs near origin, fine-grained leaf memories outward) without Euclidean crowding.
 * 2) Nested Learning Continuum Memory System (CMS): coordinates multi-speed memory timescales
 *    (fast Titans vectors -> mid-frequency CMS trace) to eliminate catastrophic forgetting.
 * 3) Joint-Embedding Predictive Architecture (JEPA): latent 16x16 world model forecasting
 *    what user activity / concept follows next without wasteful token generation.
 * 4) Hinton Forward-Forward (FF) Local Plasticity: backprop-free local positive vs. negative
 *    goodness adaptation (`G = mean((w * h)^2)`).
 * 5) MIT Liquid Time-Constant Decay + Active Inference Free Energy: continuous-time decay
 *    over real elapsed timestamps + curiosity-driven epistemic replay selection.
 */
object FutureNeuralArchitectures {

    const val FUTURE_DIM = 16
    private const val POINCARE_MAX_R = 0.92f
    private const val CMS_MID_GAMMA = 0.16f
    private const val FF_THRESHOLD = 0.45
    private const val FF_LR = 0.055
    private const val JEPA_LR = 0.065
    private const val MAX_CELLS = 1200

    fun initPoincareCoords(concept: String): FloatArray {
        val raw = FloatArray(FUTURE_DIM)
        var state = (concept.hashCode().toLong() * 2862933555777941757L) xor 3037000493L
        for (i in 0 until FUTURE_DIM) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val u = ((state ushr 33) and 0x7FFF_FFFFL).toDouble() / 0x7FFF_FFFFL.toDouble()
            raw[i] = ((u - 0.5) * 0.45).toFloat()
        }
        return projectToPoincareBall(raw, 0.55f)
    }

    fun projectToPoincareBall(v: FloatArray, maxRadius: Float = POINCARE_MAX_R): FloatArray {
        var sq = 0.0
        for (x in v) sq += x * x
        val norm = sqrt(sq).toFloat()
        if (norm <= maxRadius || norm < 1e-6f) return v.copyOf()
        val scale = maxRadius / norm
        return FloatArray(v.size) { i -> v[i] * scale }
    }

    fun normSquared(v: FloatArray): Double {
        var s = 0.0
        for (x in v) s += x * x
        return s
    }

    /** Exact hyperbolic distance d_B(u, v) on the Poincaré Ball with curvature c = 1. */
    fun poincareDistance(u: FloatArray, v: FloatArray): Double {
        val uu = normSquared(u).coerceAtMost(0.96)
        val vv = normSquared(v).coerceAtMost(0.96)
        var diffSq = 0.0
        val n = min(u.size, v.size)
        for (i in 0 until n) {
            val d = (u[i] - v[i]).toDouble()
            diffSq += d * d
        }
        val denom = max(1e-4, (1.0 - uu) * (1.0 - vv))
        val arg = max(1.0, 1.0 + 2.0 * diffSq / denom)
        return ln(arg + sqrt(max(0.0, arg * arg - 1.0)))
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var sq = 0.0
        for (x in v) sq += x * x
        val n = sqrt(sq).toFloat()
        if (n < 1e-6f) return v.copyOf()
        return FloatArray(v.size) { i -> v[i] / n }
    }

    fun fold32To16(vec32: FloatArray): FloatArray {
        val out = FloatArray(FUTURE_DIM)
        for (i in 0 until FUTURE_DIM) {
            val a = if (i < vec32.size) vec32[i] else 0f
            val b = if (i + FUTURE_DIM < vec32.size) vec32[i + FUTURE_DIM] else 0f
            out[i] = (a + b) * 0.5f
        }
        return l2Normalize(out)
    }

    fun decode16(csv: String?): FloatArray {
        if (csv.isNullOrBlank()) return FloatArray(FUTURE_DIM)
        val parts = csv.split(",")
        return FloatArray(FUTURE_DIM) { i -> parts.getOrNull(i)?.toFloatOrNull() ?: 0f }
    }

    fun encode16(v: FloatArray): String =
        v.joinToString(",") { String.format(Locale.US, "%.4f", it) }

    private fun decodeMatrix(csv: String?): FloatArray {
        val size = FUTURE_DIM * FUTURE_DIM
        if (csv.isNullOrBlank()) {
            // Initialize near-identity JEPA world-model transition matrix
            return FloatArray(size) { idx -> if (idx / FUTURE_DIM == idx % FUTURE_DIM) 0.75f else 0.02f }
        }
        val parts = csv.split(",")
        return FloatArray(size) { i -> parts.getOrNull(i)?.toFloatOrNull() ?: 0f }
    }

    private fun encodeMatrix(m: FloatArray): String =
        m.joinToString(",") { String.format(Locale.US, "%.4f", it) }

    private fun decodeFfWeights(csv: String?): FloatArray {
        if (csv.isNullOrBlank()) return FloatArray(FUTURE_DIM) { 0.55f }
        val parts = csv.split(",")
        return FloatArray(FUTURE_DIM) { i -> parts.getOrNull(i)?.toFloatOrNull() ?: 0.55f }
    }

    /** Forward-Forward local layer goodness G(a, b) using local Hadamard pairing. */
    fun forwardForwardGoodness(a: FloatArray, b: FloatArray, ffWeights: FloatArray): Double {
        var sqSum = 0.0
        for (i in 0 until FUTURE_DIM) {
            val h = tanh(((a[i] + b[i]) * 0.75 * ffWeights[i]).toDouble())
            sqSum += h * h
        }
        return sqSum / FUTURE_DIM.toDouble()
    }

    /** Liquid Neural Network continuous-time integration over real elapsed milliseconds deltaMs. */
    fun liquidTimeUpdate(prevState: Double, inputSignal: Double, deltaMs: Long, ffGoodness: Double): Double {
        val deltaHours = (deltaMs.coerceAtLeast(0L) / 3_600_000.0).coerceIn(0.0, 240.0)
        // High Forward-Forward goodness lengthens memory time constant tau!
        val tauHours = 12.0 + 36.0 * ffGoodness.coerceIn(0.0, 1.0)
        val forgetGate = 1.0 - exp(-deltaHours / tauHours)
        val blended = prevState * (1.0 - forgetGate * 0.45) + inputSignal * 0.35
        return tanh(blended * 0.85).coerceIn(0.05, 1.0)
    }

    /** JEPA latent forward transition: z_hat = l2Normalize(tanh(W_jepa * z_t)). */
    fun jepaPredict(matrix: FloatArray, zCurr: FloatArray): FloatArray {
        val out = FloatArray(FUTURE_DIM)
        for (r in 0 until FUTURE_DIM) {
            var acc = 0.0
            val rowOff = r * FUTURE_DIM
            for (c in 0 until FUTURE_DIM) {
                acc += matrix[rowOff + c] * zCurr[c]
            }
            out[r] = tanh(acc).toFloat()
        }
        return l2Normalize(out)
    }

    /**
     * Runs one unified Future AI Continual Training Pass:
     * Hyperbolic Poincaré Riemannian SGD + Nested CMS + Forward-Forward + JEPA World Model + Active Inference.
     */
    suspend fun trainFuturePass(
        brainDao: BrainSimulationDao,
        pairs: List<PairSignal>,
        timelineTransitions: List<Triple<String, String, Long>>, // (currConcept, nextConcept, timestampMs)
        conceptHits: Map<String, Int>,
        passIndex: Int
    ): FuturePassMetrics {
        val concepts = (conceptHits.keys + pairs.flatMap { listOf(it.tokenA, it.tokenB) }).distinct()
        if (concepts.isEmpty()) {
            val prevWorld = brainDao.getWorldModelState() ?: WorldModelStateEntity()
            return FuturePassMetrics(
                futureCellsCount = brainDao.countFutureCells(),
                jepaAccuracyPct = prevWorld.jepaAccuracyPct,
                avgFreeEnergy = prevWorld.avgFreeEnergy,
                avgFfGoodness = prevWorld.avgFfGoodness,
                curiosityTarget = prevWorld.curiosityTarget
            )
        }

        val nowMs = System.currentTimeMillis()
        val fastVecMap = brainDao.getVectorsByConcepts(concepts).associate {
            it.concept to FrontierNeuralTrainer.decodeVector(it.vectorCsv)
        }
        val prevCellsMap = brainDao.getFutureCellsByConcepts(concepts).associateBy { it.concept }
        val prevWorld = brainDao.getWorldModelState() ?: WorldModelStateEntity()

        val jepaMatrix = decodeMatrix(prevWorld.jepaMatrixCsv)
        val ffWeights = decodeFfWeights(prevWorld.ffWeightsCsv)

        val poincare = HashMap<String, FloatArray>(concepts.size)
        val cmsMid = HashMap<String, FloatArray>(concepts.size)
        val liquid = HashMap<String, Double>(concepts.size)
        val ffScores = HashMap<String, Double>(concepts.size)
        val jepaErrors = HashMap<String, Double>(concepts.size)
        val lastTsMap = HashMap<String, Long>(concepts.size)

        // 1) Initialize + Nested Learning CMS Mid-Speed consolidation from Fast 32D Titans vectors
        for (c in concepts) {
            val cell = prevCellsMap[c]
            val fast16 = fastVecMap[c]?.let { fold32To16(it) } ?: initPoincareCoords(c)

            if (cell != null) {
                poincare[c] = projectToPoincareBall(decode16(cell.poincareCsv))
                val prevMid = decode16(cell.cmsMidCsv)
                val updatedMid = FloatArray(FUTURE_DIM) { i ->
                    (1f - CMS_MID_GAMMA) * prevMid[i] + CMS_MID_GAMMA * fast16[i]
                }
                cmsMid[c] = l2Normalize(updatedMid)
                liquid[c] = cell.liquidState
                ffScores[c] = cell.ffGoodness
                jepaErrors[c] = cell.jepaPredError
                lastTsMap[c] = cell.lastTimestampMs
            } else {
                poincare[c] = initPoincareCoords(c)
                cmsMid[c] = fast16
                liquid[c] = 0.35
                ffScores[c] = 0.5
                jepaErrors[c] = 0.65
                lastTsMap[c] = nowMs
            }
        }

        val meanHits = if (conceptHits.isNotEmpty()) conceptHits.values.average() else 1.0

        // 2) Hyperbolic Poincaré Riemannian SGD + Forward-Forward Local Plasticity
        var ffGoodSum = 0.0
        var ffTrials = 0

        for ((idx, pair) in pairs.take(65).withIndex()) {
            val pA = poincare[pair.tokenA] ?: continue
            val pB = poincare[pair.tokenB] ?: continue
            val mA = cmsMid[pair.tokenA] ?: continue
            val mB = cmsMid[pair.tokenB] ?: continue

            // Forward-Forward Positive Pass (real observed co-occurring pair)
            val goodPos = forwardForwardGoodness(mA, mB, ffWeights)
            ffGoodSum += goodPos
            ffTrials++
            if (goodPos < FF_THRESHOLD + 0.15) {
                for (i in 0 until FUTURE_DIM) {
                    val push = (FF_LR * (1.0 - goodPos) * kotlin.math.abs(mA[i] + mB[i])).toFloat()
                    ffWeights[i] = (ffWeights[i] + push).coerceIn(0.15f, 2.2f)
                }
            }
            ffScores[pair.tokenA] = 0.7 * (ffScores[pair.tokenA] ?: 0.5) + 0.3 * goodPos

            // Forward-Forward Negative Pass (synthetic mismatched negative sample)
            if (concepts.size >= 4) {
                val negC = concepts[(idx * 7 + passIndex * 3 + 1) % concepts.size]
                if (negC != pair.tokenA && negC != pair.tokenB) {
                    val mNeg = cmsMid[negC]
                    if (mNeg != null) {
                        val goodNeg = forwardForwardGoodness(mA, mNeg, ffWeights)
                        if (goodNeg > FF_THRESHOLD - 0.10) {
                            for (i in 0 until FUTURE_DIM) {
                                val pushDown = (FF_LR * goodNeg * 0.65).toFloat()
                                ffWeights[i] = (ffWeights[i] - pushDown).coerceIn(0.15f, 2.2f)
                            }
                        }
                    }
                }
            }

            // Riemannian Gradient Pull in Poincaré Ball B^16
            val rA2 = normSquared(pA).coerceAtMost(0.88)
            val rB2 = normSquared(pB).coerceAtMost(0.88)
            val metricA = (((1.0 - rA2) * (1.0 - rA2)) / 4.0 * 0.10).toFloat()
            val metricB = (((1.0 - rB2) * (1.0 - rB2)) / 4.0 * 0.10).toFloat()

            val snapA = pA.copyOf()
            for (i in 0 until FUTURE_DIM) {
                val diff = pB[i] - snapA[i]
                pA[i] += metricA * diff
                pB[i] -= metricB * diff
            }

            // Hierarchy Geometry: Hubs (high frequency) gravitate inward; fine leaves branch outward
            val radialTargetA = if ((conceptHits[pair.tokenA] ?: 1) >= meanHits) 0.95f else 1.03f
            val radialTargetB = if ((conceptHits[pair.tokenB] ?: 1) >= meanHits) 0.95f else 1.03f
            for (i in 0 until FUTURE_DIM) {
                pA[i] *= radialTargetA
                pB[i] *= radialTargetB
            }
            poincare[pair.tokenA] = projectToPoincareBall(pA)
            poincare[pair.tokenB] = projectToPoincareBall(pB)
        }

        // 3) JEPA World Model Transition Training (z_t -> z_{t+1} latent EMA predictor)
        var jepaHits = 0
        var jepaTested = 0
        var freeEnergySum = 0.0

        for ((currC, nextC, ts) in timelineTransitions.take(40)) {
            if (currC == nextC) continue
            val zCurr = cmsMid[currC] ?: continue
            val zNextTarget = cmsMid[nextC] ?: continue

            val predNext = jepaPredict(jepaMatrix, zCurr)

            // Evaluate latent JEPA prediction hit against top-3 nearest concepts in batch
            val topPreds = cmsMid.entries
                .asSequence()
                .filter { it.key != currC }
                .map { it.key to FrontierNeuralTrainer.cosineSimilarity(predNext, it.value) }
                .sortedByDescending { it.second }
                .take(3)
                .map { it.first }
                .toList()
            jepaTested++
            if (nextC in topPreds) jepaHits++

            val cosMatch = FrontierNeuralTrainer.cosineSimilarity(predNext, zNextTarget)
            val predError = (1.0 - cosMatch).coerceIn(0.0, 2.0) * 0.5
            freeEnergySum += predError
            jepaErrors[currC] = 0.6 * (jepaErrors[currC] ?: 0.5) + 0.4 * predError

            // Update Liquid Time-Constant continuous activation
            val prevT = lastTsMap[currC] ?: ts
            val delta = kotlin.math.abs(ts - prevT)
            liquid[currC] = liquidTimeUpdate(
                prevState = liquid[currC] ?: 0.35,
                inputSignal = 0.7 + 0.3 * predError,
                deltaMs = delta,
                ffGoodness = ffScores[currC] ?: 0.5
            )
            lastTsMap[currC] = max(prevT, ts)

            // Latent JEPA matrix gradient step towards EMA target encoder (no pixel/word decoding!)
            for (r in 0 until FUTURE_DIM) {
                val errR = (zNextTarget[r] - predNext[r])
                val rowOff = r * FUTURE_DIM
                for (col in 0 until FUTURE_DIM) {
                    val deltaW = (JEPA_LR * errR * zCurr[col]).toFloat()
                    jepaMatrix[rowOff + col] = (jepaMatrix[rowOff + col] * 0.996f + deltaW).coerceIn(-2.0f, 2.0f)
                }
            }
        }

        // 4) Persist Future Memory Cells & Select Active Inference Epistemic Curiosity Target
        var highestFreeEnergy = -1.0
        var curiosityConcept = prevWorld.curiosityTarget
        val toSave = ArrayList<FutureMemoryCellEntity>(concepts.size)
        for (c in concepts) {
            val liq = liquid[c] ?: 0.35
            val err = jepaErrors[c] ?: 0.5
            val ff = ffScores[c] ?: 0.5
            val expectedFreeEnergy = err + 0.35 * (1.0 - liq)
            if (expectedFreeEnergy > highestFreeEnergy) {
                highestFreeEnergy = expectedFreeEnergy
                curiosityConcept = c
            }
            toSave.add(
                FutureMemoryCellEntity(
                    concept = c,
                    poincareCsv = encode16(poincare[c]!!),
                    cmsMidCsv = encode16(cmsMid[c]!!),
                    liquidState = liq,
                    ffGoodness = ff,
                    jepaPredError = err,
                    lastTimestampMs = lastTsMap[c] ?: nowMs,
                    updatedAt = nowMs
                )
            )
        }
        brainDao.upsertFutureCells(toSave)
        if (passIndex % 6 == 0) {
            brainDao.trimFutureCells(MAX_CELLS)
        }

        val cumCorrect = prevWorld.jepaCorrect + jepaHits
        val cumTotal = prevWorld.jepaTotal + jepaTested
        val jepaAcc = if (cumTotal > 0) ((cumCorrect.toDouble() / cumTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100) else 0
        val avgFE = if (jepaTested > 0) freeEnergySum / jepaTested else prevWorld.avgFreeEnergy
        val avgFF = if (ffTrials > 0) ffGoodSum / ffTrials else prevWorld.avgFfGoodness

        val updatedWorld = WorldModelStateEntity(
            id = 1,
            jepaMatrixCsv = encodeMatrix(jepaMatrix),
            ffWeightsCsv = encode16(ffWeights),
            jepaAccuracyPct = jepaAcc,
            jepaCorrect = cumCorrect,
            jepaTotal = cumTotal,
            avgFreeEnergy = avgFE,
            avgFfGoodness = avgFF,
            curiosityTarget = curiosityConcept,
            updatedAt = nowMs
        )
        brainDao.saveWorldModelState(updatedWorld)

        return FuturePassMetrics(
            futureCellsCount = brainDao.countFutureCells(),
            jepaAccuracyPct = jepaAcc,
            avgFreeEnergy = avgFE,
            avgFfGoodness = avgFF,
            curiosityTarget = curiosityConcept
        )
    }

    /**
     * Queries Hyperbolic Poincaré hierarchy neighbors (clean tree-like semantic proximity).
     */
    fun queryPoincareHierarchy(
        anchorConcept: String,
        allCells: List<FutureMemoryCellEntity>,
        topK: Int = 4
    ): List<Pair<String, Double>> {
        if (allCells.isEmpty()) return emptyList()
        val cellMap = allCells.associateBy { it.concept }
        val anchor = cellMap[anchorConcept]
            ?: allCells.firstOrNull { it.concept.contains(anchorConcept) || anchorConcept.contains(it.concept) }
            ?: return emptyList()
        val pAnchor = decode16(anchor.poincareCsv)
        return allCells
            .asSequence()
            .filter { it.concept != anchor.concept }
            .map { it.concept to poincareDistance(pAnchor, decode16(it.poincareCsv)) }
            .sortedBy { it.second }
            .take(topK)
            .toList()
    }

    /**
     * Uses JEPA World Model (`W_jepa * z_cms`) to predict what activity / concept typically follows next!
     */
    fun predictNextWorldStates(
        anchorConcept: String,
        allCells: List<FutureMemoryCellEntity>,
        worldState: WorldModelStateEntity?,
        topK: Int = 3
    ): List<Pair<String, Double>> {
        if (allCells.isEmpty() || worldState == null || worldState.jepaMatrixCsv.isBlank()) return emptyList()
        val cellMap = allCells.associateBy { it.concept }
        val anchor = cellMap[anchorConcept]
            ?: allCells.firstOrNull { it.concept.contains(anchorConcept) || anchorConcept.contains(it.concept) }
            ?: return emptyList()
        val zCurr = decode16(anchor.cmsMidCsv)
        val matrix = decodeMatrix(worldState.jepaMatrixCsv)
        val predNext = jepaPredict(matrix, zCurr)

        return allCells
            .asSequence()
            .filter { it.concept != anchor.concept }
            .map { it.concept to FrontierNeuralTrainer.cosineSimilarity(predNext, decode16(it.cmsMidCsv)) }
            .filter { it.second > 0.20 }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
    }
}
