package com.personal.devicememory.app

import android.app.Application
import android.util.Log
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectorConfigEntity
import com.personal.devicememory.pipeline.WorkScheduler
import com.personal.devicememory.pipeline.brain.LunaMemorySimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class DeviceMemoryApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. Explicitly load SQLCipher native library before any database access
        try {
            System.loadLibrary("sqlcipher")
            Log.i(TAG, "SQLCipher native library loaded successfully")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load sqlcipher native library", e)
        }

        // 2. Global uncaught exception logger (stores crash log to disk for diagnosis)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashFile = File(filesDir, "crash.log")
                val logContent = "CRASH on thread '${thread.name}' at ${System.currentTimeMillis()}:\n" +
                    Log.getStackTraceString(throwable)
                crashFile.writeText(logContent)
                Log.e(TAG, logContent)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 3. Initialize database and seed default configs safely in background
        appScope.launch {
            try {
                val db = DeviceMemoryDatabase.getInstance(this@DeviceMemoryApp)
                seedDefaultConfigs(db)
                LunaMemorySimulator.startSimulationLoop(this@DeviceMemoryApp)
            } catch (e: Throwable) {
                Log.e(TAG, "Database init failed in Application.onCreate", e)
            }

            // 4. Schedule the nightly worker safely
            try {
                WorkScheduler.scheduleNightly(this@DeviceMemoryApp)
            } catch (e: Throwable) {
                Log.e(TAG, "WorkScheduler failed to schedule nightly work", e)
            }
        }
    }

    private suspend fun seedDefaultConfigs(db: DeviceMemoryDatabase) {
        try {
            val defaults = SourceType.entries.map { sourceType ->
                CollectorConfigEntity(
                    sourceType = sourceType.name,
                    enabled = 0,
                    lastRunStatus = "NEVER"
                )
            }
            db.collectorConfigDao().insertIfMissing(defaults)
        } catch (e: Throwable) {
            Log.e(TAG, "Error seeding default configs", e)
        }
    }

    companion object {
        private const val TAG = "DeviceMemoryApp"
    }
}
