package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores 32-dimensional continuous neural concept representations trained offline via:
 * - Contrastive Skip-Gram SGD with Negative Sampling
 * - Google Titans surprise-driven momentum updates
 * - EWC-inspired Fisher importance regularization against catastrophic forgetting
 */
@Entity(tableName = "neural_vectors")
data class NeuralVectorEntity(
    @PrimaryKey
    val concept: String,

    @ColumnInfo(name = "vector_csv")
    val vectorCsv: String,

    @ColumnInfo(name = "momentum_csv")
    val momentumCsv: String,

    @ColumnInfo(name = "fisher_importance")
    val fisherImportance: Double = 0.0,

    @ColumnInfo(name = "surprise_score")
    val surpriseScore: Double = 0.0,

    @ColumnInfo(name = "update_steps")
    val updateSteps: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
