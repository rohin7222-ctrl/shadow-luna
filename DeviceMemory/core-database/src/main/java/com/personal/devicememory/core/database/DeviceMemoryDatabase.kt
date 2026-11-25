package com.personal.devicememory.core.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.devicememory.core.database.crypto.DatabaseKeyManager
import com.personal.devicememory.core.database.dao.BrainSimulationDao
import com.personal.devicememory.core.database.dao.CollectedRecordDao
import com.personal.devicememory.core.database.dao.CollectorConfigDao
import com.personal.devicememory.core.database.dao.LearnedTokenDao
import com.personal.devicememory.core.database.dao.SyncBookmarkDao
import com.personal.devicememory.core.database.entity.BrainInsightEntity
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.CollectorConfigEntity
import com.personal.devicememory.core.database.entity.LearnedAssociationEntity
import com.personal.devicememory.core.database.entity.LearnedTokenEntity
import com.personal.devicememory.core.database.entity.SimulationStatsEntity
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CollectedRecordEntity::class,
        SyncBookmarkEntity::class,
        CollectorConfigEntity::class,
        LearnedTokenEntity::class,
        LearnedAssociationEntity::class,
        BrainInsightEntity::class,
        SimulationStatsEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class DeviceMemoryDatabase : RoomDatabase() {

    abstract fun collectedRecordDao(): CollectedRecordDao
    abstract fun syncBookmarkDao(): SyncBookmarkDao
    abstract fun collectorConfigDao(): CollectorConfigDao
    abstract fun learnedTokenDao(): LearnedTokenDao
    abstract fun brainSimulationDao(): BrainSimulationDao

    companion object {
        private const val DB_NAME = "devicememory.db"
        private const val TAG = "DeviceMemoryDatabase"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `learned_tokens` (
                        `token` TEXT NOT NULL,
                        `frequency` INTEGER NOT NULL,
                        `last_source` TEXT NOT NULL,
                        `last_seen` INTEGER NOT NULL,
                        PRIMARY KEY(`token`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `learned_associations` (
                        `pair_key` TEXT NOT NULL,
                        `token_a` TEXT NOT NULL,
                        `token_b` TEXT NOT NULL,
                        `weight` REAL NOT NULL,
                        `co_count` INTEGER NOT NULL,
                        `context_source` TEXT NOT NULL,
                        `time_bucket` TEXT NOT NULL,
                        `last_seen` INTEGER NOT NULL,
                        PRIMARY KEY(`pair_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_associations_token_a` ON `learned_associations` (`token_a`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_associations_token_b` ON `learned_associations` (`token_b`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_associations_weight` ON `learned_associations` (`weight`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `brain_insights` (
                        `concept` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `peak_time_bucket` TEXT NOT NULL,
                        `total_hits` INTEGER NOT NULL,
                        `linked_concepts` TEXT NOT NULL,
                        `summary_fact` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`concept`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `simulation_stats` (
                        `id` INTEGER NOT NULL,
                        `total_passes` INTEGER NOT NULL,
                        `last_simulated_at` INTEGER NOT NULL,
                        `last_batch_records` INTEGER NOT NULL,
                        `insights_count` INTEGER NOT NULL,
                        `associations_count` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        init {
            try {
                System.loadLibrary("sqlcipher")
                Log.i(TAG, "SQLCipher native library loaded via companion init")
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load sqlcipher in companion init", e)
            }
        }

        @Volatile
        private var INSTANCE: DeviceMemoryDatabase? = null

        fun getInstance(context: Context): DeviceMemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        private fun buildDatabase(context: Context): DeviceMemoryDatabase {
            try {
                System.loadLibrary("sqlcipher")
            } catch (_: Throwable) {}

            val passphrase = try {
                DatabaseKeyManager.getPassphrase(context)
            } catch (e: Throwable) {
                Log.e(TAG, "Error getting passphrase, fallback to app-bound key", e)
                "shadow_fallback_key_${context.packageName}".toByteArray(Charsets.UTF_8)
            }

            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context,
                DeviceMemoryDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Destroys the database and its encryption key.
         */
        fun destroyAll(context: Context) {
            INSTANCE?.close()
            INSTANCE = null
            context.deleteDatabase(DB_NAME)
            DatabaseKeyManager.destroyKey(context)
        }
    }
}
