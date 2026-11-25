package com.personal.devicememory.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personal.devicememory.core.database.entity.CollectorConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectorConfigDao {

    @Query("SELECT * FROM collector_config ORDER BY source_type")
    fun getAllFlow(): Flow<List<CollectorConfigEntity>>

    @Query("SELECT * FROM collector_config WHERE source_type = :sourceType")
    suspend fun getConfig(sourceType: String): CollectorConfigEntity?

    @Query("SELECT * FROM collector_config WHERE enabled = 1")
    suspend fun getEnabled(): List<CollectorConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: CollectorConfigEntity)

    @Query("UPDATE collector_config SET enabled = :enabled WHERE source_type = :sourceType")
    suspend fun setEnabled(sourceType: String, enabled: Int)

    @Query("""
        UPDATE collector_config
        SET last_run_at = :runAt, last_run_status = :status, error_message = :error
        WHERE source_type = :sourceType
    """)
    suspend fun updateRunStatus(sourceType: String, runAt: Long, status: String, error: String? = null)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(configs: List<CollectorConfigEntity>)

    @Query("DELETE FROM collector_config")
    suspend fun deleteAll()
}
