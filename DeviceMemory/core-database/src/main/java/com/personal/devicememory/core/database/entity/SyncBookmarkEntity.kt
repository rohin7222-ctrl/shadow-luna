package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_bookmarks")
data class SyncBookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "last_timestamp")
    val lastTimestamp: Long,

    @ColumnInfo(name = "last_item_hash")
    val lastItemHash: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
