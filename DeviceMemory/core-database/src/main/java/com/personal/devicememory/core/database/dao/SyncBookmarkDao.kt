package com.personal.devicememory.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity

@Dao
interface SyncBookmarkDao {

    @Query("SELECT * FROM sync_bookmarks WHERE source_type = :sourceType")
    suspend fun getBookmark(sourceType: String): SyncBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: SyncBookmarkEntity)

    @Query("DELETE FROM sync_bookmarks")
    suspend fun deleteAll()
}
