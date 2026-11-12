package com.opentasker.core.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.opentasker.core.contexts.TaskerSunTimes
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.model.VariableNamePolicy
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tasker's Get Sunrise/Sunset Times. Args: `latitude`, `longitude`, `time` (epoch seconds),
 * `elevations` (sun elevations in degrees, one per line or comma separated) and `var`, the output
 * prefix (`ss` by default). Times come from [TaskerSunTimes] for the day `time` falls on, or today.
 *
 * Like Tasker, a coordinate that isn't a number is filled from the phone's location, and only that
 * one: a typed latitude stays when the longitude comes from the fix. A time that isn't a number means
 * now, and an elevation that isn't a number is dropped.
 */
class SunTimesAction(
    private val permitted: (Context) -> Boolean = ::hasLocationPermission,
    /** Latitude to longitude, or null when there's no fix. */
    private val locate: suspend (Context) -> Pair<Double, Double>? = { context ->
        currentFix(context)?.let { it.latitude to it.longitude }
    },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> TimeZone = TimeZone::getDefault,
) : DeclaredAction(ActionCatalog.require("sun.times")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["var"].orEmpty().ifBlank { DEFAULT_PREFIX }.let(VariableNamePolicy::normalize)
            ?.takeIf { VariableNamePolicy.normalize(it + LONGEST_SUFFIX) != null }
            ?: return ActionResult.Failure("invalid variable prefix")

        val typedLatitude = args["latitude"]?.trim()?.toDoubleOrNull()
        val typedLongitude = args["longitude"]?.trim()?.toDoubleOrNull()
        val (latitude, longitude) = if (typedLatitude != null && typedLongitude != null) {
            typedLatitude to typedLongitude
        } else {
            if (!permitted(ctx.app)) {
                return ActionResult.Failure("sun.times needs location access to fill a blank latitude or longitude")
            }
            val fix = locate(ctx.app) ?: return ActionResult.Failure("sun.times couldn't get the phone's location")
            ctx.logger("sun.times: coordinates filled from the phone's location")
            (typedLatitude ?: fix.first) to (typedLongitude ?: fix.second)
        }
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return ActionResult.Failure("latitude must be between -90 and 90")
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return ActionResult.Failure("longitude must be between -180 and 180")

        val epochMs = args["time"]?.trim()?.toLongOrNull()?.let { seconds ->
            try {
                Math.multiplyExact(seconds, 1000L)
            } catch (_: ArithmeticException) {
                return ActionResult.Failure("time is too far from 1970 to work with")
            }
        } ?: nowMs()

        val text = args["elevations"].orEmpty()
        val elevations = text.split(if ('\n' in text) "\n" else ",")
            .mapNotNull { it.trim().toDoubleOrNull()?.takeIf(Double::isFinite) }

        val times = TaskerSunTimes.calculate(latitude, longitude, epochMs, zone(), elevations)
        fun put(suffix: String, value: Any) = ctx.variables.set(prefix + suffix, value.toString())
        put("_sunrise", times.sunrise)
        put("_sunset", times.sunset)
        put("_solar_noon", times.solarNoon)
        put("_civil_dawn", times.civilDawn)
        put("_civil_dusk", times.civilDusk)
        put("_nautical_dawn", times.nauticalDawn)
        put("_nautical_dusk", times.nauticalDusk)
        put("_astronomical_dawn", times.astronomicalDawn)
        put("_astronomical_dusk", times.astronomicalDusk)
        put("_day_or_night", if (times.isDay) "Day" else "Night")
        put("_day_or_night_code", if (times.isDay) 1 else 0)
        put("_sunlight_duration", times.sunlightMinutes)
        ctx.variables.setArray(prefix + "_custom_morning", times.customMorning.map(Long::toString))
        ctx.variables.setArray(prefix + "_custom_evening", times.customEvening.map(Long::toString))

        // Never the coordinates: they say where the phone is.
        ctx.logger("sun.times -> %${prefix}_sunrise and the rest, ${elevations.size} custom elevation(s)")
        return ActionResult.Success
    }

    companion object {
        internal const val DEFAULT_PREFIX = "ss"
        private const val LONGEST_SUFFIX = "_astronomical_dawn"
        private const val FIX_TIMEOUT_MS = 20_000L

        private fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * A fresh fix from the first enabled provider, or the newest one the phone already has when
         * no fresh one comes in time, the way Tasker falls back to the last location.
         */
        @SuppressLint("MissingPermission")
        private suspend fun currentFix(context: Context): Location? {
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                add(LocationManager.GPS_PROVIDER)
            }
            val provider = providers.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            val fresh = provider?.let {
                withTimeoutOrNull(FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Location?> { continuation ->
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        try {
                            LocationManagerCompat.getCurrentLocation(
                                manager,
                                provider,
                                signal,
                                ContextCompat.getMainExecutor(context),
                            ) { location -> if (continuation.isActive) continuation.resume(location) }
                        } catch (_: SecurityException) {
                            continuation.resume(null)
                        } catch (_: IllegalArgumentException) {
                            continuation.resume(null)
                        }
                    }
                }
            }
            return fresh ?: (providers + LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull(Location::getTime)
        }
    }
}
