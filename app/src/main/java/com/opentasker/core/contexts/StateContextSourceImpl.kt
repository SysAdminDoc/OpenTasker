package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** The state facts a screen broadcast changes. A missing [keyguardLocked] reads as locked. */
internal fun screenStatePatch(on: Boolean, keyguardLocked: Boolean?): Map<String, String> = mapOf(
    "screen" to if (on) "on" else "off",
    "unlocked" to (keyguardLocked == false).toString(),
)

/**
 * Real StateContextSource implementation using BroadcastReceivers.
 *
 * Matches predicates like:
 *   - "battery_level>=80" (battery percentage)
 *   - "charging=true" (is device charging)
 *   - "headphones=connected" or "headphones=true" (headphones plugged in)
 *   - "screen=on" (display state)
 */
class StateContextSourceImpl : StateDemandContextSource {
    override val type = "state"

    override fun events(app: Context, requestedStateKey: String?): Flow<ContextEvent> = callbackFlow {
        // Patches arrive from two threads: the BroadcastReceiver (main) and the
        // DeviceStateEvents collector. Serialize the read-modify-write so a concurrent
        // battery broadcast cannot drop a Wi-Fi patch computed against a stale map.
        val stateLock = Any()
        var lastState: Map<String, String> = emptyMap()

        fun publishPatch(statePatch: Map<String, String>) {
            val mergedState = synchronized(stateLock) {
                val merged = mergeStatePatch(lastState, statePatch)
                if (merged == lastState) return
                lastState = merged
                merged
            }
            trySend(ContextEvent(type, true, mergedState))
        }

        publishPatch(seedInitialState(app))

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                val statePatch = mutableMapOf<String, String>()

                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                            it == BatteryManager.BATTERY_STATUS_CHARGING ||
                            it == BatteryManager.BATTERY_STATUS_FULL
                        }
                        batteryPercent(level, scale)?.let { statePatch["battery_level"] = it.toString() }
                        statePatch["charging"] = isCharging.toString()
                    }
                    Intent.ACTION_HEADSET_PLUG -> {
                        val state = intent.getIntExtra("state", 0)
                        statePatch["headphones"] = (state == 1).toString()
                    }
                    // USER_PRESENT was the only writer of "unlocked", so once the device had been
                    // unlocked the state latched true for the rest of the service's life: an
                    // unlocked=false profile could never activate again, and an unlocked=true one
                    // never deactivated. Screen-off alone isn't enough either, because with a lock
                    // delay the keyguard is still open at that moment and no broadcast marks it
                    // closing later, so both edges read it.
                    Intent.ACTION_SCREEN_ON -> statePatch +=
                        screenStatePatch(on = true, app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked)
                    Intent.ACTION_SCREEN_OFF -> statePatch +=
                        screenStatePatch(on = false, app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked)
                    Intent.ACTION_USER_PRESENT -> statePatch["unlocked"] = "true"
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val pm = app.getSystemService(PowerManager::class.java)
                        statePatch["power_save"] = (pm?.isPowerSaveMode == true).toString()
                    }
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        statePatch["airplane"] = isAirplaneModeOn(app).toString()
                    }
                }

                publishPatch(statePatch)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }

        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val deviceStateJob = launch {
            DeviceStateEvents.events.collect(::publishPatch)
        }
        // Demand-gated like the sensor sub-source. Every STATE context started this 2 s
        // MediaSessionManager poll, so a battery_level context paid for media polling it never
        // read; a null key means the Inspector wants every key.
        val mediaPlaybackJob = if (requestedStateKey == null || requestedStateKey in MEDIA_STATE_KEYS) {
            launch { MediaPlaybackStateEvents.events(app).collect(::publishPatch) }
        } else {
            null
        }
        val sensorJob = launch {
            StateSensorEvents.events(app, requestedStateKey).collect(::publishPatch)
        }

        awaitClose {
            deviceStateJob.cancel()
            mediaPlaybackJob?.cancel()
            sensorJob.cancel()
            runCatching { app.unregisterReceiver(receiver) }
        }
    }
}

/**
 * Helper to check if a state predicate matches current device state.
 *
 * Predicates:
 *   - "battery_level>=80" / "battery_level<20"
 *   - "charging=true" / "charging=false"
 *   - "headphones=connected" / "headphones=disconnected"
 *   - "screen=on" / "screen=off"
 */
fun stateMatches(predicate: String, state: Map<String, String>): Boolean {
    val (key, op, value) = parseStatePredicate(predicate) ?: return false

    val normalizedKey = normalizeStateKey(key)
    if (normalizedKey == "wifi") return wifiMatches(op, value, state)
    if (normalizedKey == BT_NEAR_STATE_KEY) return bluetoothNearMatches(op, value, state)

    val actualValue = state[normalizedKey] ?: return false
    val expectedValue = normalizeStateExpectedValue(normalizedKey, value.trim()) ?: return false

    if (normalizedKey == "media_package" && op == "=") {
        return MediaPlaybackStateEvents.packageMatches(actualValue, expectedValue)
    }

    if (normalizedKey == "orientation" && op == "=") {
        return orientationMatches(actualValue, expectedValue)
    }

    return when (op) {
        "=" -> actualValue == expectedValue
        ">=" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual >= expected }
        "<=" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual <= expected }
        ">" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual > expected }
        "<" -> numericCompare(actualValue, expectedValue) { actual, expected -> actual < expected }
        else -> false
    }
}

/**
 * Requested-key sentinel for a spec whose physical key cannot be determined.
 *
 * A null request means "the Inspector wants every physical key", so it must not double as "this
 * context is unparseable". A malformed predicate - reachable through JSON or Tasker import - would
 * otherwise start continuous GPS, telephony receivers, and every sensor for a context that can
 * never match, which is the opposite of failing closed.
 */
internal const val UNRESOLVED_STATE_KEY = "__unresolved__"

/** Extract the physical state key used by a matcher, preserving compatibility with predicate form. */
internal fun stateContextKey(spec: ContextSpec): String? {
    val rawKey = spec.config["predicate"]?.let(::parseStatePredicate)?.first
        ?: spec.config["key"]
    return rawKey?.let(::normalizeStateKey)?.takeIf(String::isNotBlank)
}

private val MEDIA_STATE_KEYS = setOf("media_active", "media_package")

internal fun seedInitialState(app: Context): Map<String, String> {
    val seed = mutableMapOf<String, String>()

    val dm = app.getSystemService(DisplayManager::class.java)
    val screenOn = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        ?.state == Display.STATE_ON
    seed["screen"] = if (screenOn) "on" else "off"

    // isInteractive answers "is the screen on", not "is the device unlocked": a locked device
    // showing the lock screen or always-on display reported unlocked=true.
    app.getSystemService(KeyguardManager::class.java)?.let { keyguard ->
        seed["unlocked"] = (!keyguard.isKeyguardLocked).toString()
    }

    val pm = app.getSystemService(PowerManager::class.java)
    if (pm != null) {
        seed["power_save"] = pm.isPowerSaveMode.toString()
    }

    val am = app.getSystemService(AudioManager::class.java)
    if (am != null) {
        @Suppress("DEPRECATION")
        seed["headphones"] = am.isWiredHeadsetOn.toString()
    }

    seed["airplane"] = isAirplaneModeOn(app).toString()

    seed += MediaPlaybackStateEvents.snapshot(app)

    val batteryIntent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    if (batteryIntent != null) {
        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val isCharging = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        batteryPercent(level, scale)?.let { seed["battery_level"] = it.toString() }
        seed["charging"] = isCharging.toString()
    }

    return seed
}

@Suppress("DEPRECATION")
private fun isAirplaneModeOn(context: Context): Boolean =
    Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

internal fun mergeStatePatch(
    currentState: Map<String, String>,
    patch: Map<String, String>,
): Map<String, String> {
    if (patch.isEmpty()) return currentState
    return currentState + patch
}

internal fun normalizeStateKey(key: String): String = when (key.trim().lowercase()) {
    "battery" -> "battery_level"
    "headset" -> "headphones"
    "ssid", "wifi_ssid" -> "wifi"
    "battery_saver", "powersave", "power_saver" -> "power_save"
    "airplane_mode", "flight_mode" -> "airplane"
    "device_unlocked" -> "unlocked"
    "device_orientation", "orientation_state" -> "orientation"
    "proximity_state" -> "proximity"
    "activity_detection", "physical_activity", "motion" -> "activity"
    "velocity", "speed_mps" -> "speed"
    "roaming_state" -> "roaming"
    "tether", "hotspot", "wifi_hotspot", "tethering_state" -> "tethering"
    "call", "phone_call", "phone_call_state" -> "call_state"
    "bluetooth_near", "bt near", "bluetooth near", "btnear" -> BT_NEAR_STATE_KEY
    else -> key.trim().lowercase()
}

private fun normalizeStateExpectedValue(key: String, value: String): String? {
    val normalized = value.trim().lowercase()
    return when (key) {
        "headphones" -> when (normalized) {
            "connected", "plugged", "plugged_in", "on", "true", "yes" -> "true"
            "disconnected", "unplugged", "off", "false", "no" -> "false"
            else -> null
        }
        "charging" -> when (normalized) {
            "charging", "plugged", "plugged_in", "on", "true", "yes" -> "true"
            "discharging", "not_charging", "unplugged", "off", "false", "no" -> "false"
            else -> null
        }
        "screen" -> when (normalized) {
            "on", "off" -> normalized
            else -> null
        }
        "unlocked" -> when (normalized) {
            "unlocked", "on", "true", "yes" -> "true"
            "locked", "off", "false", "no" -> "false"
            else -> null
        }
        "power_save" -> when (normalized) {
            "on", "enabled", "true", "yes" -> "true"
            "off", "disabled", "false", "no" -> "false"
            else -> null
        }
        "airplane" -> when (normalized) {
            "on", "enabled", "true", "yes" -> "true"
            "off", "disabled", "false", "no" -> "false"
            else -> null
        }
        "orientation" -> when (normalized) {
            "portrait", "portrait_upside_down", "landscape_left", "landscape_right", "face_up", "face_down" -> normalized
            "landscape" -> "landscape"
            else -> null
        }
        "proximity" -> when (normalized) {
            "near", "close", "covered", "on", "true", "yes" -> "near"
            "far", "away", "open", "off", "false", "no" -> "far"
            else -> null
        }
        "activity" -> when (normalized) {
            "stationary", "still", "idle" -> "stationary"
            "walking", "walk" -> "walking"
            "running", "run" -> "running"
            else -> null
        }
        "roaming", "tethering" -> when (normalized) {
            "on", "enabled", "true", "yes", "active" -> "true"
            "off", "disabled", "false", "no", "inactive" -> "false"
            else -> null
        }
        "call_state" -> when (normalized) {
            "idle", "none", "off" -> "idle"
            "ringing", "ring" -> "ringing"
            "offhook", "off_hook", "active", "in_call" -> "offhook"
            else -> null
        }
        else -> value.trim()
    }
}

private fun orientationMatches(actual: String, expected: String): Boolean = when (expected) {
    "portrait" -> actual == "portrait" || actual == "portrait_upside_down"
    "landscape" -> actual == "landscape_left" || actual == "landscape_right"
    else -> actual == expected
}

private fun parseStatePredicate(predicate: String): Triple<String, String, String>? =
    STATE_PREDICATE_PATTERN.matchEntire(predicate.trim())?.let { match ->
        Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

private fun wifiMatches(
    op: String,
    expectedRaw: String,
    state: Map<String, String>,
): Boolean {
    if (op != "=") return false
    val expected = expectedRaw.trim()
    val normalizedExpected = expected.lowercase()
    val connected = state["wifi_connected"]?.toBooleanStrictOrNull()
    val actualSsid = state["wifi"]?.takeUnless { it.equals("disconnected", ignoreCase = true) }
        ?: state["wifi_ssid"].orEmpty()

    return when (normalizedExpected) {
        in WIFI_CONNECTED_WORDS -> connected == true
        in WIFI_DISCONNECTED_WORDS -> connected == false
        else -> actualSsid == expected
    }
}

private val WIFI_CONNECTED_WORDS = setOf("connected", "on", "true", "yes")
private val WIFI_DISCONNECTED_WORDS = setOf("disconnected", "off", "false", "no")

/**
 * Whether a STATE spec compares the Wi-Fi *name*. Android reveals the name only to an app with
 * precise location access, while the connected and disconnected words need nothing, so Setup and
 * the Inspector have to tell the two apart.
 */
internal fun stateSpecReadsWifiSsid(spec: ContextSpec): Boolean {
    if (spec.type != ContextType.STATE) return false
    val (key, operator, value) = spec.config["predicate"]?.takeIf(String::isNotBlank)?.let(::parseStatePredicate)
        ?: Triple(spec.config["key"].orEmpty(), spec.config["operator"].orEmpty(), spec.config["value"].orEmpty())
    if (normalizeStateKey(key) != "wifi") return false
    // wifiMatches only compares names with "=", so any other operator never reads the name and
    // must not ask for location or blame a withheld name for a context that cannot match anyway.
    if (operator.trim().ifEmpty { "=" } != "=") return false
    val expected = value.trim().lowercase()
    return expected.isNotEmpty() && expected !in WIFI_CONNECTED_WORDS && expected !in WIFI_DISCONNECTED_WORDS
}

/** The device a STATE context waits for, or null when it isn't a nearby-device context that can match. */
internal fun bluetoothNearTarget(spec: ContextSpec): String? {
    if (spec.type != ContextType.STATE) return null
    val (key, operator, value) = spec.config["predicate"]?.takeIf(String::isNotBlank)?.let(::parseStatePredicate)
        ?: Triple(spec.config["key"].orEmpty(), spec.config["operator"].orEmpty(), spec.config["value"].orEmpty())
    if (normalizeStateKey(key) != BT_NEAR_STATE_KEY) return null
    if (operator.trim().ifEmpty { "=" } != "=") return null
    return value.trim().takeIf(String::isNotEmpty)
}

internal fun bluetoothNearTargets(specs: Iterable<ContextSpec>): Set<String> =
    specs.mapNotNullTo(linkedSetOf(), ::bluetoothNearTarget)

/** The private `_setup_*` marker a state source writes when it cannot evaluate [spec], if any. */
internal fun stateSetupMarkerKey(spec: ContextSpec): String? =
    if (stateSpecReadsWifiSsid(spec)) {
        DeviceStateEvents.WIFI_SSID_SETUP_MARKER
    } else {
        stateContextKey(spec)?.let { "_setup_$it" }
    }

/**
 * Normalize a raw battery reading to a 0-100 percentage. `ACTION_BATTERY_CHANGED` reports
 * `EXTRA_LEVEL` against `EXTRA_SCALE` (often 100, but some devices report 255), so a bare level is
 * not a percentage. Returns null for an unknown level or a non-positive scale.
 */
internal fun batteryPercent(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) return null
    return (level * 100 / scale).coerceIn(0, 100)
}

private inline fun numericCompare(
    actualValue: String,
    expectedValue: String,
    compare: (actual: Double, expected: Double) -> Boolean,
): Boolean {
    val actual = actualValue.toDoubleOrNull() ?: return false
    val expected = expectedValue.toDoubleOrNull() ?: return false
    return compare(actual, expected)
}

private val STATE_PREDICATE_PATTERN = Regex("^\\s*([^<>=]+?)\\s*(>=|<=|=|>|<)\\s*(.*?)\\s*$")
