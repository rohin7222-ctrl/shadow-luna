package com.personal.devicememory.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personal.devicememory.core.database.entity.BrainInsightEntity
import com.personal.devicememory.core.database.entity.FutureMemoryCellEntity
import com.personal.devicememory.core.database.entity.LearnedAssociationEntity
import com.personal.devicememory.core.database.entity.NeuralPolicyStateEntity
import com.personal.devicememory.core.database.entity.NeuralVectorEntity
import com.personal.devicememory.core.database.entity.SimulationStatsEntity
import com.personal.devicememory.core.database.entity.WorldModelStateEntity

@Dao
interface BrainSimulationDao {

    @Query("""
        INSERT INTO learned_associations (pair_key, token_a, token_b, weight, co_count, context_source, time_bucket, last_seen)
        VALUES (:pairKey, :tokenA, :tokenB, :deltaWeight, 1, :source, :timeBucket, :lastSeen)
        ON CONFLICT(pair_key) DO UPDATE SET
            weight = MIN(100.0, weight + :deltaWeight),
            co_count = co_count + 1,
            context_source = :source,
            time_bucket = :timeBucket,
            last_seen = MAX(last_seen, :lastSeen)
    """)
    suspend fun reinforceAssociation(
        pairKey: String,
        tokenA: String,
        tokenB: String,
        deltaWeight: Double,
        source: String,
        timeBucket: String,
        lastSeen: Long
    )

    @Query("""
        SELECT * FROM learned_associations
        WHERE token_a = :concept OR token_b = :concept
        ORDER BY weight DESC, co_count DESC
        LIMIT :limit
    """)
    suspend fun getAssociationsFor(concept: String, limit: Int = 12): List<LearnedAssociationEntity>

    @Query("SELECT COUNT(*) FROM learned_associations")
    suspend fun countAssociations(): Int

    @Query("""
        DELETE FROM learned_associations
        WHERE pair_key NOT IN (
            SELECT pair_key FROM learned_associations ORDER BY weight DESC, last_seen DESC LIMIT :keepLimit
        )
    """)
    suspend fun trimAssociations(keepLimit: Int = 2500)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInsights(insights: List<BrainInsightEntity>)

    @Query("SELECT * FROM brain_insights WHERE concept = :concept LIMIT 1")
    suspend fun getInsight(concept: String): BrainInsightEntity?

    @Query("SELECT * FROM brain_insights WHERE concept LIKE '%' || :term || '%' ORDER BY total_hits DESC LIMIT :limit")
    suspend fun searchInsights(term: String, limit: Int = 8): List<BrainInsightEntity>

    @Query("SELECT * FROM brain_insights ORDER BY total_hits DESC, updated_at DESC LIMIT :limit")
    suspend fun getTopInsights(limit: Int = 15): List<BrainInsightEntity>

    @Query("SELECT COUNT(*) FROM brain_insights")
    suspend fun countInsights(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStats(stats: SimulationStatsEntity)

    @Query("SELECT * FROM simulation_stats WHERE id = 1 LIMIT 1")
    suspend fun getStats(): SimulationStatsEntity?

    @Query("DELETE FROM learned_associations")
    suspend fun clearAssociations()

    @Query("DELETE FROM brain_insights")
    suspend fun clearInsights()

    @Query("DELETE FROM simulation_stats")
    suspend fun clearStats()

    // ─── 2026 Neural Vectors (Titans + EWC + Skip-Gram SGD) ───
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVectors(vectors: List<NeuralVectorEntity>)

    @Query("SELECT * FROM neural_vectors WHERE concept IN (:concepts)")
    suspend fun getVectorsByConcepts(concepts: List<String>): List<NeuralVectorEntity>

    @Query("SELECT * FROM neural_vectors ORDER BY fisher_importance DESC, updated_at DESC LIMIT :limit")
    suspend fun getAllVectors(limit: Int = 1200): List<NeuralVectorEntity>

    @Query("SELECT COUNT(*) FROM neural_vectors")
    suspend fun countVectors(): Int

    @Query("""
        DELETE FROM neural_vectors
        WHERE concept NOT IN (
            SELECT concept FROM neural_vectors ORDER BY fisher_importance DESC, updated_at DESC LIMIT :keepLimit
        )
    """)
    suspend fun trimVectors(keepLimit: Int = 1200)

    // ─── GRPO Self-Play Policy Weights ───
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePolicyState(state: NeuralPolicyStateEntity)

    @Query("SELECT * FROM neural_policy_state WHERE id = 1 LIMIT 1")
    suspend fun getPolicyState(): NeuralPolicyStateEntity?

    @Query("DELETE FROM neural_vectors")
    suspend fun clearVectors()

    @Query("DELETE FROM neural_policy_state")
    suspend fun clearPolicyState()

    // ─── Future Next-Gen Architectures (Poincaré, CMS, Liquid, JEPA, Forward-Forward) ───
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFutureCells(cells: List<FutureMemoryCellEntity>)

    @Query("SELECT * FROM future_memory_cells WHERE concept IN (:concepts)")
    suspend fun getFutureCellsByConcepts(concepts: List<String>): List<FutureMemoryCellEntity>

    @Query("SELECT * FROM future_memory_cells ORDER BY liquid_state DESC, jepa_pred_error DESC LIMIT :limit")
    suspend fun getAllFutureCells(limit: Int = 1200): List<FutureMemoryCellEntity>

    @Query("SELECT COUNT(*) FROM future_memory_cells")
    suspend fun countFutureCells(): Int

    @Query("""
        DELETE FROM future_memory_cells
        WHERE concept NOT IN (
            SELECT concept FROM future_memory_cells ORDER BY liquid_state DESC, updated_at DESC LIMIT :keepLimit
        )
    """)
    suspend fun trimFutureCells(keepLimit: Int = 1200)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorldModelState(state: WorldModelStateEntity)

    @Query("SELECT * FROM world_model_state WHERE id = 1 LIMIT 1")
    suspend fun getWorldModelState(): WorldModelStateEntity?

    @Query("DELETE FROM future_memory_cells")
    suspend fun clearFutureCells()

    @Query("DELETE FROM world_model_state")
    suspend fun clearWorldModelState()
}
