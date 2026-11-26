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
import com.personal.devicememory.core.database.entity.CausalTransitionEntity
import com.personal.devicememory.core.database.entity.CollectedRecordEntity
import com.personal.devicememory.core.database.entity.CollectorConfigEntity
import com.personal.devicememory.core.database.entity.FutureMemoryCellEntity
import com.personal.devicememory.core.database.entity.LearnedAssociationEntity
import com.personal.devicememory.core.database.entity.LearnedTokenEntity
import com.personal.devicememory.core.database.entity.NeuralPolicyStateEntity
import com.personal.devicememory.core.database.entity.NeuralVectorEntity
import com.personal.devicememory.core.database.entity.SimulationStatsEntity
import com.personal.devicememory.core.database.entity.SyncBookmarkEntity
import com.personal.devicememory.core.database.entity.WorldModelStateEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CollectedRecordEntity::class,
        SyncBookmarkEntity::class,
        CollectorConfigEntity::class,
        LearnedTokenEntity::class,
        LearnedAssociationEntity::class,
        BrainInsightEntity::class,
        SimulationStatsEntity::class,
        NeuralVectorEntity::class,
        NeuralPolicyStateEntity::class,
        FutureMemoryCellEntity::class,
        WorldModelStateEntity::class,
        CausalTransitionEntity::class
    ],
    version = 6,
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `neural_vectors` (
                        `concept` TEXT NOT NULL,
                        `vector_csv` TEXT NOT NULL,
                        `momentum_csv` TEXT NOT NULL,
                        `fisher_importance` REAL NOT NULL,
                        `surprise_score` REAL NOT NULL,
                        `update_steps` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`concept`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `neural_policy_state` (
                        `id` INTEGER NOT NULL,
                        `weight_exact` REAL NOT NULL,
                        `weight_graph` REAL NOT NULL,
                        `weight_vector` REAL NOT NULL,
                        `weight_temporal` REAL NOT NULL,
                        `last_grpo_reward` REAL NOT NULL,
                        `self_play_correct` INTEGER NOT NULL,
                        `self_play_total` INTEGER NOT NULL,
                        `avg_surprise` REAL NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `future_memory_cells` (
                        `concept` TEXT NOT NULL,
                        `poincare_csv` TEXT NOT NULL,
                        `cms_mid_csv` TEXT NOT NULL,
                        `liquid_state` REAL NOT NULL,
                        `ff_goodness` REAL NOT NULL,
                        `jepa_pred_error` REAL NOT NULL,
                        `last_timestamp_ms` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`concept`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `world_model_state` (
                        `id` INTEGER NOT NULL,
                        `jepa_matrix_csv` TEXT NOT NULL,
                        `ff_weights_csv` TEXT NOT NULL,
                        `jepa_accuracy_pct` INTEGER NOT NULL,
                        `jepa_correct` INTEGER NOT NULL,
                        `jepa_total` INTEGER NOT NULL,
                        `avg_free_energy` REAL NOT NULL,
                        `avg_ff_goodness` REAL NOT NULL,
                        `curiosity_target` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `causal_transitions` (
                        `transition_key` TEXT NOT NULL,
                        `from_concept` TEXT NOT NULL,
                        `to_concept` TEXT NOT NULL,
                        `stdp_weight` REAL NOT NULL,
                        `transition_count` INTEGER NOT NULL,
                        `avg_delta_ms` INTEGER NOT NULL,
                        `last_seen_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`transition_key`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_causal_transitions_from_concept` ON `causal_transitions` (`from_concept`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_causal_transitions_stdp_weight` ON `causal_transitions` (`stdp_weight`)")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Destroys the database and its encryption key.
         */
        fun destroyAll(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                context.deleteDatabase(DB_NAME)
                DatabaseKeyManager.destroyKey(context)
            }
        }
    }
}
