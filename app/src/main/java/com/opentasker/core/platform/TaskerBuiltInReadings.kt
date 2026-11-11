package com.opentasker.core.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.opentasker.core.contexts.batteryPercent

/**
 * The device side of Tasker's built-ins, read the way Tasker reads them: `%BATT` from the sticky
 * battery broadcast (-1 when there's none), `%SCREEN` from whether the device is interactive, and
 * `%UPS` and `%DTOUT` in seconds. Null for a name this doesn't read or a setting that isn't there.
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
    else -> null
}
