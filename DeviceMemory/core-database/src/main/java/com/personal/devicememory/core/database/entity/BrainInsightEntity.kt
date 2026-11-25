package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "brain_insights")
data class BrainInsightEntity(
    @PrimaryKey
    val concept: String,

    val category: String = "TOPIC",

    @ColumnInfo(name = "peak_time_bucket")
    val peakTimeBucket: String = "Anytime",

    @ColumnInfo(name = "total_hits")
    val totalHits: Int = 1,

    @ColumnInfo(name = "linked_concepts")
    val linkedConcepts: String = "",

    @ColumnInfo(name = "summary_fact")
    val summaryFact: String = "",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
