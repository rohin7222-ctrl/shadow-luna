package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "collected_records",
    indices = [
        Index(value = ["source_type", "timestamp"]),
        Index(value = ["processed_flag"])
    ]
)
data class CollectedRecordEntity(
    @PrimaryKey
    val id: String,                             // UUID

    @ColumnInfo(name = "source_type")
    val sourceType: String,                     // SourceType.name

    val timestamp: Long,                        // epoch millis UTC

    @ColumnInfo(name = "text_content")
    val textContent: String? = null,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String,

    @ColumnInfo(name = "processed_flag", defaultValue = "0")
    val processedFlag: Int = 0,                 // 0=raw, 1=processed

    @ColumnInfo(name = "created_at")
    val createdAt: Long                         // insertion timestamp
)
