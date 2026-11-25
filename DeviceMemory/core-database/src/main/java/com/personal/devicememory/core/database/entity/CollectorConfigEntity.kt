package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collector_config")
data class CollectorConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    val enabled: Int = 0,                       // 0=off, 1=on

    @ColumnInfo(name = "last_run_at")
    val lastRunAt: Long? = null,

    @ColumnInfo(name = "last_run_status")
    val lastRunStatus: String = "NEVER",        // SUCCESS, PARTIAL, FAILED, NEVER

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
