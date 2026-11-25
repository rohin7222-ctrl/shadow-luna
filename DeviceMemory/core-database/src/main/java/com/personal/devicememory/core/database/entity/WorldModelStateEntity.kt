package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores global weights for next-generation on-device AI modules:
 * - JEPA Latent World-Model Transition Matrix (16x16 predictor)
 * - Forward-Forward Local Plasticity Weights (16D)
 * - Active Inference Free Energy & Epistemic Curiosity Target
 */
@Entity(tableName = "world_model_state")
data class WorldModelStateEntity(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "jepa_matrix_csv")
    val jepaMatrixCsv: String = "",

    @ColumnInfo(name = "ff_weights_csv")
    val ffWeightsCsv: String = "",

    @ColumnInfo(name = "jepa_accuracy_pct")
    val jepaAccuracyPct: Int = 0,

    @ColumnInfo(name = "jepa_correct")
    val jepaCorrect: Int = 0,

    @ColumnInfo(name = "jepa_total")
    val jepaTotal: Int = 0,

    @ColumnInfo(name = "avg_free_energy")
    val avgFreeEnergy: Double = 0.0,

    @ColumnInfo(name = "avg_ff_goodness")
    val avgFfGoodness: Double = 0.0,

    @ColumnInfo(name = "curiosity_target")
    val curiosityTarget: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
