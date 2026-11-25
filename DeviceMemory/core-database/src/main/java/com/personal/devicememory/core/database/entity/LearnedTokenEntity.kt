package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "learned_tokens")
data class LearnedTokenEntity(
    @PrimaryKey
    val token: String,

    val frequency: Int = 1,

    @ColumnInfo(name = "last_source")
    val lastSource: String,

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long
)
