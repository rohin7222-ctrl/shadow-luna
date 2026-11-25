package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores Group Relative Policy Optimization (GRPO) learned reasoning weights
 * updated continuously via Self-Play verification against stored phone memories.
 */
@Entity(tableName = "neural_policy_state")
data class NeuralPolicyStateEntity(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "weight_exact")
    val weightExact: Double = 1.0,

    @ColumnInfo(name = "weight_graph")
    val weightGraph: Double = 1.0,

    @ColumnInfo(name = "weight_vector")
    val weightVector: Double = 1.0,

    @ColumnInfo(name = "weight_temporal")
    val weightTemporal: Double = 1.0,

    @ColumnInfo(name = "last_grpo_reward")
    val lastGrpoReward: Double = 0.0,

    @ColumnInfo(name = "self_play_correct")
    val selfPlayCorrect: Int = 0,

    @ColumnInfo(name = "self_play_total")
    val selfPlayTotal: Int = 0,

    @ColumnInfo(name = "avg_surprise")
    val avgSurprise: Double = 0.0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
