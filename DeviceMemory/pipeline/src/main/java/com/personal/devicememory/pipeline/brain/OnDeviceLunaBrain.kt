package com.personal.devicememory.pipeline.brain

import android.content.Context
import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OfflineLunaReply(
    val query: String,
    val learned: Boolean,
    val totalMemories: Int,
    val totalTokens: Int,
    val response: String
)

/**
 * 100% On-Device, Zero-Prebuilt-Knowledge AI Inference & Token Learner.
 * Runs completely offline inside the encrypted SQLCipher vault on the user's phone.
 */
object OnDeviceLunaBrain {

    // Matches English + Unicode Tamil (\p{L} letters + \p{M} vowel/virama combining marks) cleanly
    private val TOKEN_REGEX = Regex("""[\p{L}\p{M}\p{N}_]{2,40}""")

    fun tokenize(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return TOKEN_REGEX.findAll(text.lowercase())
            .map { it.value }
            .distinct()
            .take(30)
            .toList()
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

    suspend fun askOffline(context: Context, rawQuery: String): OfflineLunaReply = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        val db = DeviceMemoryDatabase.getInstance(context)
        val recordDao = db.collectedRecordDao()
        val tokenDao = db.learnedTokenDao()

        val totalMemories = recordDao.totalCount()
        val totalTokens = tokenDao.countTokens()

        if (totalMemories == 0) {
            return@withContext OfflineLunaReply(
                query = query,
                learned = false,
                totalMemories = 0,
                totalTokens = totalTokens,
                response = buildString {
                    appendLine("⭕ [Offline • Untrained • 0 Memories]")
                    appendLine("\"$query\" பற்றி எனக்கு எதுவும் தெரியாது — போனில் இன்னும் எதுவும் கற்கப்படவில்லை.")
                    append("(⋮ → Learning Permission-ல் sensors ON செய்து 'Collect Now' கொடுக்கவும்.)")
                }
            )
        }

        val tokens = tokenize(query)
        val scored = linkedMapOf<String, Pair<CollectedRecordEntity, Int>>()

        // Exact phrase search
        for (rec in recordDao.searchByText(query, limit = 10)) {
            scored[rec.id] = rec to 10
        }

        // Token-level recall
        for (tok in tokens) {
            for (rec in recordDao.searchByText(tok, limit = 10)) {
                val existing = scored[rec.id]
                if (existing != null) {
                    scored[rec.id] = existing.first to (existing.second + 2)
                } else {
                    scored[rec.id] = rec to 1
                }
            }
        }

        val ranked = scored.values
            .sortedWith(compareByDescending<Pair<CollectedRecordEntity, Int>> { it.second }.thenByDescending { it.first.timestamp })
            .take(5)

        val matchedTokens = if (tokens.isNotEmpty()) {
            tokens.flatMap { tokenDao.searchTokens(it, limit = 3) }.distinctBy { it.token }.take(5)
        } else {
            tokenDao.searchTokens(query.lowercase(), limit = 5)
        }

        if (ranked.isEmpty() && matchedTokens.isEmpty()) {
            return@withContext OfflineLunaReply(
                query = query,
                learned = false,
                totalMemories = totalMemories,
                totalTokens = totalTokens,
                response = buildString {
                    appendLine("⚠️ [Offline • No Match • Checked $totalMemories memories / $totalTokens tokens]")
                    append("\"$query\" தொடர்பான எந்தத் தரவும் உங்கள் போன் நினைவுகளில் இல்லை.")
                }
            )
        }

        val reply = buildString {
            appendLine("🧠 [Offline Recall • $totalMemories memories • $totalTokens tokens]")
            if (matchedTokens.isNotEmpty()) {
                appendLine(
                    "Learned Tokens: " + matchedTokens.joinToString(", ") { "${it.token} (×${it.frequency})" }
                )
            }
            for ((rec, _) in ranked) {
                val ts = TimestampUtil.formatForDisplay(rec.timestamp)
                appendLine("• [${rec.sourceType} • $ts] ${rec.textContent ?: "Metadata"}")
            }
        }.trimEnd()

        OfflineLunaReply(
            query = query,
            learned = true,
            totalMemories = totalMemories,
            totalTokens = totalTokens,
            response = reply
        )
    }
}
