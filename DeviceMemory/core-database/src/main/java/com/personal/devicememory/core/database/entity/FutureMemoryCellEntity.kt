package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Next-generation non-backprop continual-learning cell per concept:
 * - Hyperbolic Poincaré Ball geometry (`poincareCsv`, 16D) for hierarchy without crowding
 * - Nested Learning Continuum Memory System (`cmsMidCsv`) multi-timescale trace
 * - Liquid Neural Network continuous-time activation (`liquidState`)
 * - Hinton Forward-Forward local goodness (`ffGoodness`)
 * - JEPA / Active Inference epistemic prediction error (`jepaPredError`)
 */
@Entity(tableName = "future_memory_cells")
data class FutureMemoryCellEntity(
    @PrimaryKey
    val concept: String,

    @ColumnInfo(name = "poincare_csv")
    val poincareCsv: String,

    @ColumnInfo(name = "cms_mid_csv")
    val cmsMidCsv: String,

    @ColumnInfo(name = "liquid_state")
    val liquidState: Double = 0.0,

    @ColumnInfo(name = "ff_goodness")
    val ffGoodness: Double = 0.0,

    @ColumnInfo(name = "jepa_pred_error")
    val jepaPredError: Double = 0.5,

    @ColumnInfo(name = "last_timestamp_ms")
    val lastTimestampMs: Long = 0L,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
