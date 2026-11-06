package com.opentasker.core.actions

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.contexts.QuickSettingsTileStore
import com.opentasker.core.contexts.requestRefresh
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioUsageEligibility
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Toggle or set WiFi.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class WiFiToggleAction : DeclaredAction(ActionCatalog.require("wifi.toggle")) {

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ActionResult.Failure("Android 10+ blocks direct WiFi toggles; open system WiFi settings instead")
        }
        val wm = ctx.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return ActionResult.Failure("WiFi not available")

        when (state.lowercase()) {
            "toggle" -> wm.isWifiEnabled = !wm.isWifiEnabled
            "on" -> wm.isWifiEnabled = true
            "off" -> wm.isWifiEnabled = false
            else -> return ActionResult.Failure("invalid state: $state")
        }
        ctx.logger("WiFi: $state")
        return ActionResult.Success
    }
}

/**
 * Toggle or set Bluetooth.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class BluetoothToggleAction : DeclaredAction(ActionCatalog.require("bluetooth.toggle")) {

    @Suppress("DEPRECATION")
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("Bluetooth permission is not granted")
        }
        val bm = ctx.app.getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter
            ?: return ActionResult.Failure("Bluetooth not available")

        if (Build.VERSION.SDK_INT >= 33) {
            return ActionResult.Failure("Android 13+ blocks direct Bluetooth enable/disable; use system Bluetooth settings instead")
        }

        when (state.lowercase()) {
            "toggle" -> if (adapter.isEnabled) adapter.disable() else adapter.enable()
            "on" -> adapter.enable()
            "off" -> adapter.disable()
            else -> return ActionResult.Failure("invalid state: $state")
        }
        ctx.logger("Bluetooth: $state")
        return ActionResult.Success
    }
}

/**
 * Set screen brightness.
 *
 * Args:
 *   - "brightness": 0-255 (or auto)
 */
class BrightnessAction : DeclaredAction(ActionCatalog.require("brightness.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val brightness = args["brightness"] ?: args["level"] ?: return ActionResult.Failure("missing brightness")
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            val resolver = ctx.app.contentResolver
            if (brightness.lowercase() == "auto") {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            } else {
                val value = brightness.toInt().coerceIn(0, 255)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            }
            ctx.logger("Brightness: $brightness")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed to set brightness: ${e.message}")
        }
    }
}

/**
 * Set volume level for a stream.
 *
 * Args:
 *   - "stream": "music", "alarm", "ring", "notification", etc.
 *   - "level": 0-15 (or "mute", "unmute")
 */
class VolumeAction : DeclaredAction(ActionCatalog.require("volume.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val levelArg = args["level"] ?: return ActionResult.Failure("missing level")
        val streamType = streamType(args["stream"] ?: "music") ?: return ActionResult.Failure("invalid stream")
        val usage = if (streamType == AudioManager.STREAM_ALARM) {
            AudioUsageEligibility.ALARM
        } else {
            AudioUsageEligibility.GENERAL
        }
        AndroidAudioHardening.failureIfIneligible(ctx, "volume control", usage)?.let { return it }
        val audioManager = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")

        return try {
            when (levelArg.lowercase()) {
                "mute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
                "unmute" -> audioManager.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0)
                else -> {
                    val max = audioManager.getStreamMaxVolume(streamType)
                    val level = levelArg.toIntOrNull()?.coerceIn(0, max)
                        ?: return ActionResult.Failure("invalid level: $levelArg")
                    audioManager.setStreamVolume(streamType, level, 0)
                }
            }
            ctx.logger("Volume ${args["stream"] ?: "music"}: $levelArg")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("volume change blocked by DND policy: ${ex.message}", ex)
        }
    }
}

/**
 * Toggle Airplane mode.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class AirplaneModeAction : DeclaredAction(ActionCatalog.require("airplane.toggle")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"]?.lowercase() ?: "toggle"
        val target = when (state) {
            "on" -> true
            "off" -> false
            "toggle" -> {
                val enabled = runCatching { readAirplaneMode(ctx) }.getOrElse { error ->
                    return ActionResult.Failure("Airplane mode state could not be read: ${error.message ?: "unknown error"}")
                }
                !enabled
            }
            else -> return ActionResult.Failure("invalid airplane mode state: $state")
        }
        ctx.logger("Airplane mode: $state")
        val result = ctx.runShizukuAction("airplane.toggle", "Airplane mode", if (target) 0 else 1)
        if (result !is ActionResult.Success) return result

        // Android 8 has no such command, and it still exits 0, so only the setting can prove it ran.
        val applied = runCatching { readAirplaneMode(ctx) }.getOrElse { error ->
            return ActionResult.Failure("Airplane mode state could not be read: ${error.message ?: "unknown error"}")
        }
        return airplaneModeOutcome(target, applied)
    }

    private fun readAirplaneMode(ctx: ActionContext): Boolean =
        Settings.Global.getInt(ctx.app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
}

internal fun airplaneModeOutcome(target: Boolean, applied: Boolean): ActionResult =
    if (applied == target) {
        ActionResult.Success
    } else {
        ActionResult.Failure("Airplane mode is still ${if (applied) "on" else "off"}. This version of Android ignored the command.")
    }

/**
 * Toggle mobile data.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 */
class MobileDataAction : DeclaredAction(ActionCatalog.require("mobile.toggle")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"]?.lowercase() ?: "toggle"
        val variant = when (state) {
            "on" -> 0
            "off" -> 1
            "toggle" -> {
                if (ContextCompat.checkSelfPermission(ctx.app, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    return ActionResult.Failure("Mobile data state requires phone-state permission; choose on or off, or grant it in Setup")
                }
                val telephony = ctx.app.getSystemService(TelephonyManager::class.java)
                    ?: return ActionResult.Failure("Mobile data service is unavailable")
                val enabled = runCatching { telephony.isDataEnabled }.getOrElse { error ->
                    return ActionResult.Failure("Mobile data state could not be read: ${error.message ?: "unknown error"}")
                }
                if (enabled) 1 else 0
            }
            else -> return ActionResult.Failure("invalid mobile data state: $state")
        }
        ctx.logger("Mobile data: $state")
        return ctx.runShizukuAction("mobile.toggle", "Mobile data", variant)
    }
}

class DoNotDisturbAction : DeclaredAction(ActionCatalog.require("dnd.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val nm = ctx.app.getSystemService(NotificationManager::class.java)
            ?: return ActionResult.Failure("notification service not available")
        if (!nm.isNotificationPolicyAccessGranted) {
            return ActionResult.Failure("Do Not Disturb access is not granted; enable it in Setup")
        }
        val mode = args["mode"] ?: "total_silence"
        val filter = when (mode.lowercase()) {
            "off", "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority", "priority_only" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms", "alarms_only" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "total_silence", "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return ActionResult.Failure("invalid DND mode: $mode (use off/priority/alarms/total_silence)")
        }
        return try {
            nm.setInterruptionFilter(filter)
            ctx.logger("DND: $mode")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("DND change blocked: ${ex.message}", ex)
        }
    }
}

class RingerModeAction : DeclaredAction(ActionCatalog.require("ringer.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val mode = args["mode"] ?: return ActionResult.Failure("missing mode argument")
        val ringerMode = when (mode.lowercase()) {
            "normal", "ring" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent" -> AudioManager.RINGER_MODE_SILENT
            else -> return ActionResult.Failure("invalid ringer mode: $mode (use normal/vibrate/silent)")
        }
        AndroidAudioHardening.failureIfIneligible(ctx, "ringer-mode change")?.let { return it }
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        return try {
            am.ringerMode = ringerMode
            ctx.logger("Ringer: $mode")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("ringer change blocked by DND policy: ${ex.message}", ex)
        }
    }
}

class TorchAction : DeclaredAction(ActionCatalog.require("torch.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: "toggle"
        val cm = ctx.app.getSystemService(CameraManager::class.java)
            ?: return ActionResult.Failure("camera service not available")
        val cameraId = findTorchCameraId(cm)
            ?: return ActionResult.Failure("no camera with flash found")

        return try {
            val targetState = when (state.lowercase()) {
                "on" -> true
                "off" -> false
                "toggle" -> {
                    val currentState = awaitTorchState(cm, cameraId)
                        ?: return ActionResult.Failure(
                            "torch toggle state is unavailable; use explicit on/off for reliable state"
                        )
                    currentState.not()
                }
                else -> return ActionResult.Failure("invalid state: $state (use on/off/toggle)")
            }
            cm.setTorchMode(cameraId, targetState)
            ctx.logger("Torch: ${if (targetState) "on" else "off"}")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("torch blocked by camera permission or policy: ${ex.message}", ex)
        } catch (ex: IllegalArgumentException) {
            ActionResult.Failure("torch failed: ${ex.message}", ex)
        } catch (ex: CameraAccessException) {
            ActionResult.Failure("torch failed: ${ex.message}", ex)
        }
    }

    companion object {
        internal fun targetStateFor(requestedState: String, currentState: Boolean?): Boolean? =
            when (requestedState.lowercase()) {
                "on" -> true
                "off" -> false
                "toggle" -> currentState?.not()
                else -> null
            }

        internal const val TORCH_STATE_TIMEOUT_MS = 750L
    }
}

private fun findTorchCameraId(cameraManager: CameraManager): String? =
    try {
        cameraManager.cameraIdList.firstOrNull { cameraId ->
            try {
                cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (_: CameraAccessException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    } catch (_: CameraAccessException) {
        null
    }

private suspend fun awaitTorchState(cameraManager: CameraManager, cameraId: String): Boolean? =
    withTimeoutOrNull(TorchAction.TORCH_STATE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id != cameraId || !continuation.isActive) return
                    safelyUnregisterTorchCallback(cameraManager, this)
                    continuation.resume(enabled)
                }

                override fun onTorchModeUnavailable(id: String) {
                    if (id != cameraId || !continuation.isActive) return
                    safelyUnregisterTorchCallback(cameraManager, this)
                    continuation.resume(null)
                }
            }

            try {
                cameraManager.registerTorchCallback(callback, handler)
            } catch (_: RuntimeException) {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation {
                safelyUnregisterTorchCallback(cameraManager, callback)
            }
        }
    }

private fun safelyUnregisterTorchCallback(cameraManager: CameraManager, callback: CameraManager.TorchCallback) {
    try {
        cameraManager.unregisterTorchCallback(callback)
    } catch (_: RuntimeException) {
    }
}

class TileStateAction : DeclaredAction(ActionCatalog.require("tile.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"] ?: return ActionResult.Failure("missing state argument")
        val active = when (state.lowercase()) {
            "active", "on", "true" -> true
            "inactive", "off", "false" -> false
            else -> return ActionResult.Failure("invalid state: $state (use active/inactive)")
        }
        val slot = args["slot"]?.trim()?.ifBlank { "1" }?.toIntOrNull()
            ?: return ActionResult.Failure("invalid tile slot (use 1-${com.opentasker.core.contexts.QuickSettingsTileSlots.COUNT})")
        val store = QuickSettingsTileStore(ctx.app)
        val current = runCatching { store.load(slot) }
            .getOrElse { return ActionResult.Failure(it.message ?: "invalid tile slot") }
        if (current.taskId == null) return ActionResult.Failure("tile slot $slot has no bound task")
        runCatching {
            store.setState(
                slot = slot,
                active = active,
                label = args["label"],
                subtitle = args["subtitle"],
                iconKey = args["icon"],
            )
        }.getOrElse { return ActionResult.Failure(it.message ?: "tile update failed") }
        store.requestRefresh(ctx.app, slot)
        return ActionResult.Success
    }
}

private fun streamType(name: String): Int? = when (name.lowercase()) {
    "music", "media" -> AudioManager.STREAM_MUSIC
    "alarm" -> AudioManager.STREAM_ALARM
    "ring", "ringer" -> AudioManager.STREAM_RING
    "notification" -> AudioManager.STREAM_NOTIFICATION
    "system" -> AudioManager.STREAM_SYSTEM
    "voice", "call" -> AudioManager.STREAM_VOICE_CALL
    else -> null
}

/**
 * Set screen timeout (stay-on duration).
 *
 * Args:
 *   - "millis": milliseconds until screen times out (minimum 1000)
 *
 * Android has no "never" value for SCREEN_OFF_TIMEOUT: 0 turns the screen off
 * (near-)immediately on real devices, the opposite of what "never" suggests, so
 * zero and sub-second values are rejected.
 */
class ScreenTimeoutAction : DeclaredAction(ActionCatalog.require("screen.timeout")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val rawMillis = args["millis"] ?: return ActionResult.Failure("missing millis")
        val ms = rawMillis.toLongOrNull() ?: return ActionResult.Failure("invalid millis: $rawMillis")
        if (ms !in MIN_SCREEN_TIMEOUT_MS..MAX_SCREEN_TIMEOUT_MS) {
            return ActionResult.Failure(
                "screen timeout must be between $MIN_SCREEN_TIMEOUT_MS and $MAX_SCREEN_TIMEOUT_MS ms",
            )
        }
        if (!Settings.System.canWrite(ctx.app)) {
            return ActionResult.Failure("Write system settings permission is not granted")
        }
        return try {
            Settings.System.putInt(ctx.app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, ms.toInt())
            ctx.logger("Screen timeout: ${ms / 1000}s")
            ActionResult.Success
        } catch (e: Exception) {
            ActionResult.Failure("failed: ${e.message}")
        }
    }

    companion object {
        private const val MIN_SCREEN_TIMEOUT_MS = 1_000L
        private const val MAX_SCREEN_TIMEOUT_MS = 1_800_000L // 30 minutes
    }
}

/**
 * Turn the always-on display on or off.
 *
 * Args:
 *   - "state": "on", "off", or "toggle"
 *
 * `settings put secure doze_always_on` returns a zero exit code on builds that do not implement
 * the key, so writing it proves nothing. The value is read before and after the write and the
 * action fails when the device did not actually change, rather than reporting a Success the user
 * can see is false.
 */
class AlwaysOnDisplayAction : DeclaredAction(ActionCatalog.require("aod.set")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val state = args["state"]?.lowercase() ?: "toggle"
        val current = readAlwaysOnDisplay(ctx)
            ?: return ActionResult.Failure(
                "This device does not expose the always-on display setting ($ALWAYS_ON_DISPLAY_KEY), so it cannot be changed.",
            )
        val target = when (state) {
            "on" -> 1
            "off" -> 0
            "toggle" -> if (current == 1) 0 else 1
            else -> return ActionResult.Failure("invalid always-on display state: $state")
        }
        ctx.logger("Always-on display: $state")
        if (target == current) return ActionResult.Success

        val result = ctx.runShizukuAction("aod.set", "Always-on display", if (target == 1) 0 else 1)
        if (result !is ActionResult.Success) return result

        val applied = readAlwaysOnDisplay(ctx)
        return if (applied == target) {
            ActionResult.Success
        } else {
            ActionResult.Failure(
                "Always-on display is still ${if (applied == 1) "on" else "off"}; this build accepted the write and ignored it.",
            )
        }
    }

    private fun readAlwaysOnDisplay(ctx: ActionContext): Int? = runCatching {
        Settings.Secure.getInt(ctx.app.contentResolver, ALWAYS_ON_DISPLAY_KEY)
    }.getOrNull()

    companion object {
        internal const val ALWAYS_ON_DISPLAY_KEY = "doze_always_on"
    }
}

/** The Settings tables a write can target, named as the user picks them. */
internal enum class SettingsTable(val wireValue: String) {
    GLOBAL("global"),
    SECURE("secure"),
    SYSTEM("system"),
}

internal sealed interface SettingsWriteRequest {
    data class Valid(val table: SettingsTable, val key: String, val value: String) : SettingsWriteRequest

    data class Rejected(val message: String) : SettingsWriteRequest
}

internal const val MAX_SETTINGS_VALUE_CHARS = 256

private val SETTINGS_KEY_PATTERN = Regex("[a-z0-9_.]{1,64}")

/**
 * Setting names this action refuses to write, whatever the table.
 *
 * `WRITE_SECURE_SETTINGS` is granted once, for one reason, and then persists. Without this list a
 * profile imported afterwards could write `enabled_accessibility_services` and hand an arbitrary
 * package full accessibility privileges with no system dialog, or turn off the package verifier,
 * or allow installs from unknown sources. An imported profile reaches this action behind one
 * generic device-control acknowledgement, which is nowhere near consent for that.
 *
 * These are matched as fragments rather than exact names because the same control appears under
 * several spellings across versions and OEM builds. The cost is refusing a few harmless names that
 * happen to contain one; that is the right side to be wrong on, and the refusal says why.
 */
private val PROTECTED_SETTING_FRAGMENTS = listOf(
    "accessibility",
    "notification_listener",
    "notification_policy",
    "input_method",
    // The second family, and the same harm by a different route: these name the app that gets a
    // role. Becoming the default SMS application grants the SMS permissions outright, with no
    // dialog; the assistant, autofill and voice-interaction services see what the user types and
    // what is on screen; the NFC payment default receives taps.
    "default_application",
    "assistant",
    "autofill_service",
    "voice_interaction",
    "voice_recognition",
    "nfc_payment",
    "verifier",
    "adb",
    "development_settings",
    "device_admin",
    "device_provisioned",
    "user_setup_complete",
    "install_non_market",
    "unknown_sources",
    "lock_pattern",
    "lockscreen",
    "trust_agent",
    "location_providers_allowed",
    "vpn",
    // The third family: values that redirect other apps' traffic, or lift the platform's own
    // restrictions on what code may call. hidden_api_blacklist_exemptions takes package prefixes
    // that are then allowed to call blocked private APIs; the proxy and DNS names point every
    // other app's traffic wherever the value says.
    "hidden_api",
    "http_proxy",
    "private_dns",
    "captive_portal",
)

/**
 * The one command that turns the Global and Secure tables on.
 *
 * `WRITE_SECURE_SETTINGS` cannot be requested at runtime and no dialog can grant it, so this is
 * built from the installed package name rather than hard-coded: a debug build carries a different
 * one, and a command naming the wrong package fails with a message that does not say why.
 */
internal fun secureSettingsGrantCommand(packageName: String): String =
    "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

internal fun hasWriteSecureSettings(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

/** Validates the arguments before anything touches a Settings table. */
internal fun parseSettingsWrite(args: Map<String, String>): SettingsWriteRequest {
    val requestedTable = args["table"]?.trim()?.lowercase().orEmpty()
    val table = SettingsTable.entries.firstOrNull { it.wireValue == requestedTable }
        ?: return SettingsWriteRequest.Rejected(
            "Choose the table to write: global, secure, or system.",
        )
    val key = args["key"]?.trim().orEmpty()
    if (!SETTINGS_KEY_PATTERN.matches(key)) {
        return SettingsWriteRequest.Rejected(
            "A setting name is up to 64 characters of a-z, 0-9, underscore or dot.",
        )
    }
    PROTECTED_SETTING_FRAGMENTS.firstOrNull { it in key }?.let { fragment ->
        return SettingsWriteRequest.Rejected(
            "\"$key\" controls what other apps are allowed to do, so this action will not write " +
                "it. Names containing \"$fragment\" are refused.",
        )
    }
    val value = args["value"]
        ?: return SettingsWriteRequest.Rejected("This action needs a value to write.")
    if (value.length > MAX_SETTINGS_VALUE_CHARS) {
        return SettingsWriteRequest.Rejected(
            "The value is longer than $MAX_SETTINGS_VALUE_CHARS characters.",
        )
    }
    return SettingsWriteRequest.Valid(table, key, value)
}

/**
 * Writes one Android setting, the way Tasker's Custom Setting action does.
 *
 * Global and Secure need `WRITE_SECURE_SETTINGS`, which no app can ask for at runtime; it is
 * granted once over a cable and then persists. System uses the ordinary Modify system settings
 * access the brightness and screen-timeout actions already use.
 *
 * The value is read back afterwards. Android accepts a write to a name it does not know, and
 * silently normalises or discards values it does not like, so without the read-back the action
 * would report success for a setting that never changed.
 */
class SettingsWriteAction : DeclaredAction(ActionCatalog.require("settings.write")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val request = when (val parsed = parseSettingsWrite(args)) {
            is SettingsWriteRequest.Rejected -> return ActionResult.Failure(parsed.message)
            is SettingsWriteRequest.Valid -> parsed
        }
        val resolver = ctx.app.contentResolver
        val target = "${request.table.wireValue}/${request.key}"

        if (request.table == SettingsTable.SYSTEM) {
            if (!Settings.System.canWrite(ctx.app)) {
                return ActionResult.Failure(
                    "Writing $target needs Modify system settings, which you grant from Setup.",
                )
            }
        } else if (!hasWriteSecureSettings(ctx.app)) {
            return ActionResult.Failure(
                "Writing $target needs secure settings access. Grant it once from a computer: " +
                    secureSettingsGrantCommand(ctx.app.packageName),
            )
        }

        val accepted = runCatching {
            when (request.table) {
                SettingsTable.GLOBAL -> Settings.Global.putString(resolver, request.key, request.value)
                SettingsTable.SECURE -> Settings.Secure.putString(resolver, request.key, request.value)
                SettingsTable.SYSTEM -> Settings.System.putString(resolver, request.key, request.value)
            }
        }.getOrElse { error ->
            return ActionResult.Failure(
                "$target could not be written: ${error.message ?: "Android refused it"}",
            )
        }
        if (!accepted) return ActionResult.Failure("Android refused to write $target.")

        val readBack = runCatching {
            when (request.table) {
                SettingsTable.GLOBAL -> Settings.Global.getString(resolver, request.key)
                SettingsTable.SECURE -> Settings.Secure.getString(resolver, request.key)
                SettingsTable.SYSTEM -> Settings.System.getString(resolver, request.key)
            }
        }.getOrNull()
        if (readBack != request.value) {
            // Deliberately not "Android ignored it": the value may also have been accepted and then
            // normalised or clamped by whatever owns that setting. What is certain is that the
            // setting does not hold what was asked for, so report that and nothing more.
            return ActionResult.Failure(
                "$target reads ${readBack ?: "nothing"} after the write, not ${request.value}.",
            )
        }

        ctx.logger("Setting $target = ${request.value}")
        return ActionResult.Success
    }
}
