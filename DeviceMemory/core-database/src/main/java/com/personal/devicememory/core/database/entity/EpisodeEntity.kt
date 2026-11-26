package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Synthesized spatio-temporal cross-modal Episode ("Moment" / Story Cluster),
 * stitching related Photos, Videos, Audio, Notifications, and Location within ±20 mins & 500m.
 */
@Entity(
    tableName = "memory_episodes",
    indices = [
        Index(value = ["end_millis"]),
        Index(value = ["key_concepts"])
    ]
)
data class EpisodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "episode_id")
    val episodeId: String,

    @ColumnInfo(name = "title_label")
    val titleLabel: String,

    @ColumnInfo(name = "start_millis")
    val startMillis: Long,

    @ColumnInfo(name = "end_millis")
    val endMillis: Long,

    @ColumnInfo(name = "primary_place")
    val primaryPlace: String = "",

    @ColumnInfo(name = "dominant_sources")
    val dominantSources: String = "",

    @ColumnInfo(name = "key_concepts")
    val keyConcepts: String = "",

    @ColumnInfo(name = "record_count")
    val recordCount: Int = 1,

    @ColumnInfo(name = "narrative_summary")
    val narrativeSummary: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
