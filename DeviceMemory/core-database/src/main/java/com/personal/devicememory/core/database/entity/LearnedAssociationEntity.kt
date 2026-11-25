package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "learned_associations",
    indices = [
        Index(value = ["token_a"]),
        Index(value = ["token_b"]),
        Index(value = ["weight"])
    ]
)
data class LearnedAssociationEntity(
    @PrimaryKey
    @ColumnInfo(name = "pair_key")
    val pairKey: String,

    @ColumnInfo(name = "token_a")
    val tokenA: String,

    @ColumnInfo(name = "token_b")
    val tokenB: String,

    val weight: Double = 1.0,

    @ColumnInfo(name = "co_count")
    val coCount: Int = 1,

    @ColumnInfo(name = "context_source")
    val contextSource: String = "DEVICE",

    @ColumnInfo(name = "time_bucket")
    val timeBucket: String = "ANY",

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long = 0L
)
