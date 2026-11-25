package com.personal.devicememory.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.personal.devicememory.core.database.entity.LearnedTokenEntity

@Dao
interface LearnedTokenDao {

    @Query("""
        INSERT INTO learned_tokens (token, frequency, last_source, last_seen)
        VALUES (:token, 1, :source, :timestamp)
        ON CONFLICT(token) DO UPDATE SET
            frequency = frequency + 1,
            last_source = :source,
            last_seen = MAX(last_seen, :timestamp)
    """)
    suspend fun recordToken(token: String, source: String, timestamp: Long)

    @Query("SELECT COUNT(*) FROM learned_tokens")
    suspend fun countTokens(): Int

    @Query("SELECT * FROM learned_tokens ORDER BY frequency DESC, last_seen DESC LIMIT :limit")
    suspend fun getTopTokens(limit: Int = 200): List<LearnedTokenEntity>

    @Query("SELECT * FROM learned_tokens WHERE token LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY frequency DESC LIMIT :limit")
    suspend fun searchTokens(query: String, limit: Int = 20): List<LearnedTokenEntity>

    @Query("DELETE FROM learned_tokens")
    suspend fun deleteAll()
}
