package com.personal.devicememory.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectedRecordDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<CollectedRecordEntity>)

    @Query("SELECT COUNT(*) FROM collected_records WHERE source_type = :sourceType")
    suspend fun countBySource(sourceType: String): Int

    @Query("SELECT COUNT(*) FROM collected_records")
    suspend fun totalCount(): Int

    @Query("SELECT COUNT(*) FROM collected_records WHERE source_type = :sourceType")
    fun countBySourceFlow(sourceType: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM collected_records")
    fun totalCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM collected_records WHERE processed_flag = 0")
    suspend fun countUnsynced(): Int

    @Query("SELECT * FROM collected_records WHERE processed_flag = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getUnsynced(limit: Int = 100): List<CollectedRecordEntity>

    @Query("UPDATE collected_records SET processed_flag = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM collected_records WHERE text_content LIKE '%' || :term || '%' ESCAPE '\\' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun searchByText(term: String, limit: Int = 10): List<CollectedRecordEntity>

    @Query("SELECT * FROM collected_records ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentAll(limit: Int = 15): List<CollectedRecordEntity>

    @Query("SELECT * FROM collected_records ORDER BY timestamp ASC LIMIT :limit OFFSET :offset")
    suspend fun getRecordsWindow(limit: Int, offset: Int): List<CollectedRecordEntity>

    @Query("SELECT * FROM collected_records WHERE source_type = :sourceType ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatest(sourceType: String, limit: Int = 20): List<CollectedRecordEntity>

    @Query("DELETE FROM collected_records")
    suspend fun deleteAll()

    @Query("DELETE FROM collected_records WHERE source_type = :sourceType")
    suspend fun deleteBySource(sourceType: String)
}
