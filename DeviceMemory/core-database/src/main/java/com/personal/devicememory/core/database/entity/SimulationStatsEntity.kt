package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "simulation_stats")
data class SimulationStatsEntity(
    @PrimaryKey
    val id: Int = 1,

    @ColumnInfo(name = "total_passes")
    val totalPasses: Int = 0,

    @ColumnInfo(name = "last_simulated_at")
    val lastSimulatedAt: Long = 0L,

    @ColumnInfo(name = "last_batch_records")
    val lastBatchRecords: Int = 0,

    @ColumnInfo(name = "insights_count")
    val insightsCount: Int = 0,

    @ColumnInfo(name = "associations_count")
    val associationsCount: Int = 0
)
