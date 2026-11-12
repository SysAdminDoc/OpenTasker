package com.opentasker.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.opentasker.core.contexts.batteryPercent

/** The audio stream each of Tasker's `%VOL` built-ins reads. */
internal val TASKER_VOLUME_STREAMS: Map<String, Int> = mapOf(
    "VOLA" to AudioManager.STREAM_ALARM,
    "VOLC" to AudioManager.STREAM_VOICE_CALL,
    "VOLD" to AudioManager.STREAM_DTMF,
    "VOLM" to AudioManager.STREAM_MUSIC,
    "VOLN" to AudioManager.STREAM_NOTIFICATION,
    "VOLR" to AudioManager.STREAM_RING,
    "VOLS" to AudioManager.STREAM_SYSTEM,
)

/** A fix as Tasker's `%LOC` and `%LOCN` show it, Java's own text for each number. */
internal fun taskerLatLon(latitude: Double, longitude: Double): String = "$latitude,$longitude"

/**
 * The device side of Tasker's built-ins, read the way Tasker reads them: `%BATT` from the sticky
 * battery broadcast (-1 when there's none), `%SCREEN` from whether the device is interactive,
 * `%UPS` and `%DTOUT` in seconds, a `%VOL` as the stream's volume step, and `%LOC` and `%LOCN`
 * from the GPS and network providers' last fix. Null for a name this doesn't read or a setting,
 * stream or fix that isn't there.
 */
internal fun taskerBuiltInReading(context: Context, name: String): String? = when (name) {
    "BATT" -> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val percent = battery?.let {
            batteryPercent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
        }
        (percent ?: -1).toString()
    }
    "SCREEN" -> context.getSystemService(PowerManager::class.java)?.let { if (it.isInteractive) "on" else "off" }
    "BRIGHT" -> Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        .takeIf { it >= 0 }?.toString()
    "DTOUT" -> Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        .takeIf { it >= 0 }?.let { (it / 1000).toString() }
    "UPS" -> (SystemClock.elapsedRealtime() / 1000).toString()
    "SDK" -> Build.VERSION.SDK_INT.toString()
    "DEVID" -> Build.ID
    "DEVMAN" -> Build.MANUFACTURER
    "DEVMOD" -> Build.MODEL
    "DEVPROD" -> Build.PRODUCT
    in TASKER_VOLUME_STREAMS -> context.getSystemService(AudioManager::class.java)?.let { audio ->
        try {
            audio.getStreamVolume(TASKER_VOLUME_STREAMS.getValue(name)).toString()
        } catch (_: IllegalArgumentException) {
            // A stream this device doesn't have, which Tasker leaves unset too.
            null
        }
    }
    "LOC" -> lastFix(context, LocationManager.GPS_PROVIDER)
    "LOCN" -> lastFix(context, LocationManager.NETWORK_PROVIDER)
    else -> null
}

// Without location permission there's no fix to read, and the variable stays unset.
@SuppressLint("MissingPermission")
private fun lastFix(context: Context, provider: String): String? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val fix = try {
        manager.getLastKnownLocation(provider)
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return fix?.let { taskerLatLon(it.latitude, it.longitude) }
}
