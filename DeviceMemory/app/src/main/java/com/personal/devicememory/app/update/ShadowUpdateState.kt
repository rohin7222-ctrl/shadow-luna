package com.personal.devicememory.app.update

import android.content.Context

/**
 * Synchronous SharedPreferences-backed state for active OTA updates.
 * Uses commit() on state transitions so Activity <-> Service handoffs have zero race conditions.
 */
object ShadowUpdateState {

    private const val PREFS_NAME = "shadow_ota_state"
    private const val KEY_INSTALLED_VERSION = "installed_version"
    private const val KEY_IS_UPDATING = "is_updating"
    private const val KEY_TARGET_VERSION = "target_version"
    private const val KEY_TARGET_TITLE = "target_title"
    private const val KEY_STARTED_AT_MS = "started_at_ms"
    private const val KEY_END_AT_MS = "end_at_ms"

    // Initial baseline prior to applying server v2.2.0 so user can immediately test OTA flow once installed,
    // after which installedVersion persists cleanly across upgrades and future server code bumps.
    const val INITIAL_CLIENT_VERSION = "2.1.9"

    private const val SAFETY_TTL_GRACE_MS = 10_000L

    fun getInstalledVersion(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INSTALLED_VERSION, INITIAL_CLIENT_VERSION)
            ?: INITIAL_CLIENT_VERSION
    }

    fun startUpdate(context: Context, version: String, title: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        val end = now + durationMs.coerceAtLeast(3000L)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_UPDATING, true)
            .putString(KEY_TARGET_VERSION, version)
            .putString(KEY_TARGET_TITLE, title)
            .putLong(KEY_STARTED_AT_MS, now)
            .putLong(KEY_END_AT_MS, end)
            .commit()
    }

    fun isUpdating(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_IS_UPDATING, false)) return false
        val endAt = prefs.getLong(KEY_END_AT_MS, 0L)
        // Safety guard: never permanently lock out the app if Android OS killed the service
        if (System.currentTimeMillis() > endAt + SAFETY_TTL_GRACE_MS) {
            prefs.edit().putBoolean(KEY_IS_UPDATING, false).commit()
            return false
        }
        return true
    }

    fun getTargetVersion(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_VERSION, "") ?: ""
    }

    fun getTargetTitle(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TARGET_TITLE, "Updating Shadow...") ?: "Updating Shadow..."
    }

    fun remainingMillis(context: Context): Long {
        val endAt = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_END_AT_MS, 0L)
        return (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun totalDurationMillis(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val start = prefs.getLong(KEY_STARTED_AT_MS, 0L)
        val end = prefs.getLong(KEY_END_AT_MS, 0L)
        return (end - start).coerceAtLeast(1000L)
    }

    fun completeUpdate(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_UPDATING, false)
            .putString(KEY_INSTALLED_VERSION, version)
            .commit()
    }

    fun clearUpdatingFlag(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_IS_UPDATING, false)
            .commit()
    }
}
