package com.example.tryhearingaid.emergency

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds the user's position for an SOS message (Requirement 10.3, 10.4).
 *
 * Uses the platform [LocationManager], so it needs no extra Gradle dependency. Both functions
 * return `null` when the `ACCESS_FINE_LOCATION` permission is missing or no position is
 * available, and never throw for those reasons.
 *
 * Typical use: try [current]; if it returns `null`, fall back to [lastKnown]. Pass both to
 * [SosMessageBuilder.build], which adds the "may be outdated" note for an old fix.
 */
class SosLocationProvider(private val context: Context) : SosLocationSource {

    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** The newest position any enabled provider has stored, or `null` if there is none. */
    @SuppressLint("MissingPermission")
    override fun lastKnown(): SosLocation? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        return try {
            m.getProviders(true)
                .mapNotNull { m.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.toSosLocation()
        } catch (e: SecurityException) {
            null
        }
    }

    /** A fresh fix within [DEFAULT_TIMEOUT_MS], or `null`. See [currentWithin]. */
    override suspend fun current(): SosLocation? = currentWithin(DEFAULT_TIMEOUT_MS)

    /**
     * Request a fresh fix and wait up to [timeoutMs] for it. Prefers GPS and falls back to
     * the network provider. Returns `null` on timeout, when no provider is enabled, or
     * when the permission is missing.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentWithin(timeoutMs: Long): SosLocation? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        val provider = when {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            m.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(
                        m,
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context)
                    ) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
            }?.toSosLocation()
        } catch (e: SecurityException) {
            null
        }
    }

    private fun Location.toSosLocation() = SosLocation(latitude, longitude, time)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
