package com.personal.devicememory.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.personal.devicememory.pipeline.brain.LunaMemorySimulator

/**
 * Tracks whether Shadow remains open in Android Recents.
 * Runs cleanly without foreground-notification clutter, and calls
 * `stopSimulationLoop()` immediately in `onTaskRemoved` when the user
 * closes / swipes away the Shadow tab.
 */
class ShadowTabWatcherService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LunaMemorySimulator.startSimulationLoop(applicationContext)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LunaMemorySimulator.stopSimulationLoop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LunaMemorySimulator.setForegroundActive(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
