package com.personal.devicememory.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.CollectedRecord
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.common.util.toJsonString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Collects on-device passive / fused location snapshot without initiating continuous GPS drains.
 * Runs completely on-device.
 */
class LocationCollector(
    private val context: Context
) : Collector {

    override val sourceName: String = "Location"

    companion object {
        const val TAG = "LocationCollector"

        fun hasPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }
    }

    override suspend fun collect(sinceTimestamp: Long): List<CollectedRecord> {
        if (!hasPermission(context)) {
            Log.w(TAG, "Missing location permission, skipping collection")
            return emptyList()
        }

        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val lastLoc: Location? = fusedClient.awaitLastLocation()

            if (lastLoc != null && lastLoc.time > sinceTimestamp) {
                val metadata = mapOf<String, Any?>(
                    "latitude" to lastLoc.latitude,
                    "longitude" to lastLoc.longitude,
                    "accuracy_m" to lastLoc.accuracy,
                    "altitude" to lastLoc.altitude,
                    "speed_mps" to lastLoc.speed,
                    "bearing" to lastLoc.bearing,
                    "provider" to (lastLoc.provider ?: "fused"),
                    "time" to lastLoc.time
                )

                val textContent = "Location: ${lastLoc.latitude}, ${lastLoc.longitude} (±${lastLoc.accuracy.toInt()}m)"

                listOf(
                    CollectedRecord(
                        sourceType = SourceType.LOCATION,
                        timestamp = lastLoc.time,
                        textContent = textContent,
                        metadataJson = metadata.toJsonString()
                    )
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read last location", e)
            emptyList()
        }
    }

    private suspend fun FusedLocationProviderClient.awaitLastLocation(): Location? =
        suspendCancellableCoroutine { continuation ->
            lastLocation
                .addOnSuccessListener { loc -> continuation.resume(loc) }
                .addOnFailureListener { _ -> continuation.resume(null) }
                .addOnCanceledListener { continuation.cancel() }
        }
}
