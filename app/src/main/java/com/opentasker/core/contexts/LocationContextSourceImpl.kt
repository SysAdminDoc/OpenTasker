package com.opentasker.core.contexts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * FOSS platform location source for Location contexts.
 *
 * This deliberately uses Android's framework LocationManager instead of Play
 * Services. It emits raw location samples; geofence matching and dwell logic
 * stay in [com.opentasker.core.location.FossGeofenceEvaluator].
 */
class LocationContextSourceImpl(
    private val requestPolicy: LocationProviderRequestPolicy = LocationProviderRequestPolicy.balanced(),
    private val setupRecheckMs: Long = DEFAULT_SETUP_RECHECK_MS,
) : ContextSource {
    override val type = LocationContextEvents.TYPE

    override fun events(app: Context): Flow<ContextEvent> = callbackFlow {
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            trySend(
                LocationContextEvents.setupBlocked(
                    reason = "location_service_unavailable",
                    detail = "Android location service is unavailable on this device.",
                ),
            )
            awaitClose { }
            return@callbackFlow
        }

        val registration = LocationListenerRegistrationState { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }

        fun stopUpdates() {
            registration.stop()
        }

        fun emitBlocked(reason: String, detail: String) {
            trySend(LocationContextEvents.setupBlocked(reason, detail))
        }

        @SuppressLint("MissingPermission")
        fun emitBestLastKnown(providers: List<String>): Boolean {
            val best = providers
                .mapNotNull { provider ->
                    runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
                ?: return false
            trySend(LocationContextEvents.location(best.toPlatformSample()))
            return true
        }

        @SuppressLint("MissingPermission")
        fun startUpdatesIfReady() {
            if (!hasLocationPermission(app)) {
                stopUpdates()
                emitBlocked(
                    reason = "missing_location_permission",
                    detail = "Foreground location permission is required before OpenTasker can emit live location context values.",
                )
                return
            }

            val providers = enabledLocationProviders(locationManager)
            if (providers.isEmpty()) {
                stopUpdates()
                emitBlocked(
                    reason = "location_provider_disabled",
                    detail = "No GPS or network location provider is enabled for live location context checks.",
                )
                return
            }

            val providerSet = providers.toSet()
            if (registration.isActiveFor(providerSet)) return

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    trySend(LocationContextEvents.location(location.toPlatformSample()))
                }

                override fun onProviderEnabled(provider: String) {
                    emitBlocked(
                        reason = "location_provider_enabled",
                        detail = "$provider location provider is available; waiting for the next location fix.",
                    )
                }

                override fun onProviderDisabled(provider: String) {
                    emitBlocked(
                        reason = "location_provider_disabled",
                        detail = "$provider location provider was disabled.",
                    )
                }

                @Deprecated("Deprecated in Android framework")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }

            registration.replaceProviders(providerSet, listener) { provider, registeredListener ->
                val cadence = requestPolicy.cadenceFor(provider)
                locationManager.requestLocationUpdates(
                    provider,
                    cadence.minTimeMs,
                    cadence.minDistanceMeters,
                    registeredListener,
                    Looper.getMainLooper(),
                )
            }

            if (!emitBestLastKnown(providers)) {
                trySend(
                    ContextEvent(
                        type,
                        false,
                        mapOf(
                            "setup" to "waiting_for_location",
                            "reason" to "Live location source is registered and waiting for the first GPS or network fix.",
                            "providers" to providers.joinToString(","),
                            "cadence" to providers.joinToString(";") { provider ->
                                requestPolicy.cadenceFor(provider).toMetadata(provider)
                            },
                        ),
                    ),
                )
            }
        }

        val monitorJob = launch {
            while (isActive) {
                runCatching { startUpdatesIfReady() }
                    .onFailure { error ->
                        stopUpdates()
                        emitBlocked(
                            reason = "location_source_error",
                            detail = error.message ?: error::class.java.simpleName,
                        )
                }
                delay(setupRecheckMs)
            }
        }

        awaitClose {
            monitorJob.cancel()
            stopUpdates()
        }
    }

    private fun Location.toPlatformSample(): PlatformLocationSample =
        PlatformLocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
            observedAtEpochMs = time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            provider = provider,
        )

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun enabledLocationProviders(locationManager: LocationManager): List<String> {
        val active = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }
        val passive = if (runCatching { locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false))
            listOf(LocationManager.PASSIVE_PROVIDER) else emptyList()
        return active + passive
    }

    companion object {
        private const val DEFAULT_SETUP_RECHECK_MS = 60_000L
    }
}

internal class LocationListenerRegistrationState(
    private val removeUpdates: (LocationListener) -> Unit,
) {
    @Volatile
    private var activeListener: LocationListener? = null
    private var activeProviders: Set<String> = emptySet()

    fun isActiveFor(providers: Set<String>): Boolean =
        activeListener != null && activeProviders == providers

    /** Whether [listener] is the one registered now, not one that came off. */
    fun isCurrent(listener: LocationListener): Boolean = activeListener === listener

    fun replace(
        providers: Set<String>,
        listener: LocationListener,
        register: () -> Unit,
    ) {
        stop()
        activeListener = listener
        activeProviders = providers
        try {
            register()
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        activeListener?.let(removeUpdates)
        activeListener = null
        activeProviders = emptySet()
    }
}

internal fun LocationListenerRegistrationState.replaceProviders(
    providers: Set<String>,
    listener: LocationListener,
    registerProvider: (provider: String, listener: LocationListener) -> Unit,
) {
    replace(providers, listener) {
        providers.forEach { provider -> registerProvider(provider, listener) }
    }
}

data class LocationProviderCadence(
    val minTimeMs: Long,
    val minDistanceMeters: Float,
) {
    init {
        require(minTimeMs > 0L) { "minTimeMs must be positive" }
        require(minDistanceMeters >= 0f) { "minDistanceMeters must be non-negative" }
    }

    fun toMetadata(provider: String): String =
        "$provider:${minTimeMs}ms/${minDistanceMeters.toInt()}m"
}

data class LocationProviderRequestPolicy(
    private val gps: LocationProviderCadence,
    private val network: LocationProviderCadence,
    private val passive: LocationProviderCadence,
    private val fallback: LocationProviderCadence,
) {
    fun cadenceFor(provider: String): LocationProviderCadence =
        when (provider) {
            LocationManager.GPS_PROVIDER -> gps
            LocationManager.NETWORK_PROVIDER -> network
            LocationManager.PASSIVE_PROVIDER -> passive
            else -> fallback
        }

    companion object {
        fun balanced(): LocationProviderRequestPolicy =
            LocationProviderRequestPolicy(
                gps = LocationProviderCadence(minTimeMs = 180_000L, minDistanceMeters = 100f),
                network = LocationProviderCadence(minTimeMs = 90_000L, minDistanceMeters = 150f),
                passive = LocationProviderCadence(minTimeMs = 1L, minDistanceMeters = 0f),
                fallback = LocationProviderCadence(minTimeMs = 180_000L, minDistanceMeters = 150f),
            )
    }
}
